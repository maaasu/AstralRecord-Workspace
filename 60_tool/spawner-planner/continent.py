"""Whole-continent, read-only field-spawner proposal from measured terrain.

The export is a preview. It never reads existing placements and cannot publish
masters or write a server's mob_spawners.yml.
"""
from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import math
import os
import re
import tempfile
from collections import Counter, defaultdict, deque
from pathlib import Path

import yaml


VERSION = "continent-1"
REASONS = {
    "absent": "ワールドデータに未生成の区画",
    "failed": "割当済みだが生成・読込・計測できない区画",
    "water": "水面または水中のため地表案の対象外",
    "unsafe": "安全な地表列が不足",
    "steep": "区画内の高低差が大きい",
    "protected": "開始地点の48m保護範囲",
    "reserved_future": "道路沿いの今回の配置範囲外（後続地域として保留）",
    "unsupported_habitat": "既存の野外スポナーがない環境",
    "unsupported_level": "この環境・レベル帯に合うモブ定義・スポナーマスタがない",
    "eligible": "配置候補の計算対象",
    "uncovered": "半径・高度・既知区画・地域境界の制約で未カバー",
    "covered": "新しい配置案でカバー",
}
DIRECTIONS = (
    ("e", "東"), ("ne", "北東"), ("n", "北"), ("nw", "北西"),
    ("w", "西"), ("sw", "南西"), ("s", "南"), ("se", "南東"),
)
HABITAT_NAMES = {
    "grass": "草原", "forest": "森林", "water": "水域", "snow": "雪原",
    "dry": "乾燥地", "other": "その他", "unknown": "未解析",
}


def _yaml(path: Path) -> dict:
    value = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"YAML root must be a mapping: {path}")
    return value


def _plain(value: str) -> str:
    return re.sub(r"[&§][0-9a-fk-or]", "", value, flags=re.I)


def _mob_name(mob: dict, level: int) -> str:
    variant = next((entry for entry in mob.get("levels", []) if int(entry["level"]) == level), None)
    return _plain((variant or {}).get("name") or mob.get("name") or mob["id"])


def _habitat(cell: dict) -> str:
    if cell["status"] != "ok":
        return "unknown"
    biome = str(cell.get("biome") or "").lower()
    ground = str(cell.get("ground") or "").lower().split(":")[-1]
    surface = str(cell.get("surface") or "").lower()
    if cell.get("waterCount", 0) >= 128 or ground in {"water", "ice", "packed_ice"}:
        return "water"
    if any(word in biome + ground + surface for word in ("snow", "frozen", "ice", "grove")):
        return "snow"
    if any(word in biome for word in ("desert", "badlands", "savanna")) or ground in {"sand", "red_sand", "terracotta"}:
        return "dry"
    natural = {"grass_block", "dirt", "coarse_dirt", "podzol", "moss_block", "rooted_dirt", "mud", "mycelium"}
    if ground not in natural:
        return "other"
    if any(word in biome for word in ("forest", "jungle", "taiga")) or cell.get("treeCount", 0) >= 48:
        return "forest"
    return "grass"


def _atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent,
                                     prefix=f".{path.name}.", suffix=".tmp", delete=False) as stream:
        temp = Path(stream.name)
        try:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
            stream.write("\n")
        except BaseException:
            temp.unlink(missing_ok=True)
            raise
    try:
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


class ContinentPlanner:
    def __init__(self, terrain_path: Path, filebase: Path, policy_path: Path):
        terrain_raw = terrain_path.read_bytes()
        policy_raw = policy_path.read_bytes()
        self.terrain = json.loads(terrain_raw)
        self.policy = json.loads(policy_raw)
        if self.terrain.get("schemaVersion") != 2 or self.policy.get("schemaVersion") != 1:
            raise ValueError("Expected terrain schema 2 and continent policy schema 1")
        if not isinstance(self.terrain.get("capturedAt"), str):
            raise ValueError("Terrain capturedAt is required")
        self.terrain_fingerprint = hashlib.sha256(terrain_raw).hexdigest()
        self.filebase = filebase
        self.world = self._world()
        if str(self.terrain.get("world", "")).split("/")[-1] != self.world["id"]:
            raise ValueError("Terrain world does not match world master")
        self.spawn = self.world["spawnLocation"]
        self.minimum_level = int(self.world["adventureGuide"]["recommendedLevelMin"])
        self.step = self.terrain["cellSize"]
        self._validate_terrain()
        self.columns = self.terrain["width"] // self.step
        self.rows = self.terrain["height"] // self.step
        if self.policy.get("progressionMode") not in (None, "road"):
            raise ValueError("Unsupported progression mode")
        self.road_mode = self.policy.get("progressionMode") == "road"
        if self.road_mode:
            self._build_road_network()
        self.spawners, self.catalog, master_hash = self._load_catalog()
        planner_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        self.export_revision = hashlib.sha256(
            (VERSION + planner_hash + self.terrain_fingerprint + hashlib.sha256(policy_raw).hexdigest() +
             self.world_hash + master_hash).encode()
        ).hexdigest()
        self.cells = [self._classify(c) for c in self.terrain["cells"]]
        self.land_components = self._assign_land_components()
        self.regions = self._regions()
        self._select_spawners()

    def _build_road_network(self) -> None:
        road = self.policy["road"]
        levels = road["levels"]
        if self.policy.get("radii") != [64]:
            raise ValueError("Road progression supports radius 64m only")
        if (not isinstance(levels, list) or not levels or
                any(type(level) is not int for level in levels) or
                levels != sorted(set(levels)) or levels[0] != self.minimum_level):
            raise ValueError("Road levels must be sorted unique integers starting at world minimum")
        if (road.get("material") != "minecraft:dirt_path" or
                type(road.get("corridorMeters")) is not int or road["corridorMeters"] <= 0 or
                type(road.get("candidateCenterMeters")) is not int or
                not 0 < road["candidateCenterMeters"] <= road["corridorMeters"]):
            raise ValueError("Invalid road material or corridor")
        grid = {(c["x"], c["z"]): c for c in self.terrain["cells"]}
        roads = {p for p, c in grid.items() if c["status"] == "ok" and
                 c.get("waterCount", 0) < 128 and (c.get("safeCount") or 0) > 0 and
                 c.get("groundCounts", {}).get(road["material"], 0) > 0}
        spawn_x = self.terrain["minX"] + math.floor((self.spawn["x"] - self.terrain["minX"]) / 16) * 16
        spawn_z = self.terrain["minZ"] + math.floor((self.spawn["z"] - self.terrain["minZ"]) / 16) * 16
        start = (spawn_x, spawn_z)
        if start not in roads:
            raise ValueError("Spawn cell has no measured dirt_path; road progression cannot be inferred")
        distances = {start: 0}
        queue = deque([start])
        while queue:
            x, z = queue.popleft()
            for neighbor in ((x - 16, z), (x + 16, z), (x, z - 16), (x, z + 16)):
                if neighbor in roads and neighbor not in distances:
                    distances[neighbor] = distances[(x, z)] + 16
                    queue.append(neighbor)
        self.road_distances = distances
        self.max_road_distance = max(distances.values())
        if self.max_road_distance == 0:
            raise ValueError("Spawn-connected road has no progression length")
        positions = sorted(distances, key=lambda p: (p[1], p[0]))
        index = {p: i for i, p in enumerate(positions)}
        self.road_nodes = [{"x": x + 8, "z": z + 8, "distanceMeters": distances[(x, z)]}
                           for x, z in positions]
        self.road_edges = [[i, index[q]] for i, (x, z) in enumerate(positions)
                           for q in ((x + 16, z), (x, z + 16)) if q in index]
        # Assign cells by the nearest centre of a measured road-containing chunk.
        # The histogram does not retain the road's actual columns inside that chunk.
        self.road_nearest = {}
        limit = road["corridorMeters"]
        for x, z in positions:
            route_distance = distances[(x, z)]
            for dz in range(-limit - 16, limit + 17, 16):
                for dx in range(-limit - 16, limit + 17, 16):
                    p = (x + dx, z + dz)
                    if p not in grid:
                        continue
                    lateral = math.hypot(dx, dz)
                    if lateral > limit:
                        continue
                    previous = self.road_nearest.get(p)
                    choice = (lateral, route_distance, z, x)
                    if previous is None or choice < previous:
                        self.road_nearest[p] = choice

    def _world(self) -> dict:
        matches = [(path, data) for path in sorted((self.filebase / "60.features.world").glob("*.yml"))
                   if (data := _yaml(path)).get("id") == self.policy["worldId"]]
        if len(matches) != 1:
            raise ValueError("World master must resolve exactly once")
        self.world_hash = hashlib.sha256(matches[0][0].read_bytes()).hexdigest()
        return matches[0][1]

    def _validate_terrain(self) -> None:
        t = self.terrain
        if self.step != 16 or any(type(t.get(k)) is not int for k in ("minX", "minZ", "width", "height")):
            raise ValueError("Expected 16m cell grid and integer terrain bounds")
        if t["width"] <= 0 or t["height"] <= 0 or t["width"] % 16 or t["height"] % 16:
            raise ValueError("Invalid terrain bounds")
        expected = t["width"] // 16 * (t["height"] // 16)
        if len(t.get("cells", [])) != expected:
            raise ValueError("Terrain must include every cell, including absent/failed cells")
        seen = set()
        for cell in t["cells"]:
            x, z, status = cell.get("x"), cell.get("z"), cell.get("status")
            key = (x, z)
            if (type(x) is not int or type(z) is not int or
                    x < t["minX"] or x >= t["minX"] + t["width"] or
                    z < t["minZ"] or z >= t["minZ"] + t["height"] or
                    (x - t["minX"]) % 16 or (z - t["minZ"]) % 16 or key in seen):
                raise ValueError("Duplicate, missing or out-of-bounds terrain cell")
            seen.add(key)
            if status not in {"ok", "absent", "failed"}:
                raise ValueError("Unsupported cell status")
            if status == "ok":
                count = cell.get("safeCount")
                if type(count) is not int or not 0 <= count <= 256:
                    raise ValueError("Invalid safeCount")
                if count:
                    for k in ("candidateX", "candidateY", "candidateZ", "minY", "maxY"):
                        if not isinstance(cell.get(k), (int, float)) or not math.isfinite(cell[k]):
                            raise ValueError(f"Missing finite {k} in safe cell")
                    if not x <= cell["candidateX"] < x + 16 or not z <= cell["candidateZ"] < z + 16:
                        raise ValueError("Candidate outside measured cell")
                    if not cell["minY"] <= cell["candidateY"] <= cell["maxY"]:
                        raise ValueError("Candidate outside measured elevation bounds")
        # Index every cell by its location. Input order is not an implicit contract.
        grid = { (c["x"], c["z"]): c for c in t["cells"] }
        t["cells"] = [grid[(x, z)] for z in range(t["minZ"], t["minZ"] + t["height"], 16)
                      for x in range(t["minX"], t["minX"] + t["width"], 16)]

    def _load_catalog(self) -> tuple[dict, dict, str]:
        hash_input = hashlib.sha256()
        mobs = {}
        for path in sorted((self.filebase / "40.features.mob" / "enemy").glob("*.yml")):
            raw = path.read_bytes()
            hash_input.update(path.name.encode() + b"\0" + raw)
            data = _yaml(path)
            if "id" in data:
                if data["id"] in mobs:
                    raise ValueError(f"Duplicate enemy id: {data['id']}")
                mobs[data["id"]] = data
        definitions = {}
        for path in sorted((self.filebase / "41.features.mob.spawner").glob("*.yml")):
            raw = path.read_bytes()
            hash_input.update(path.name.encode() + b"\0" + raw)
            data = _yaml(path)
            if "id" in data:
                if data["id"] in definitions:
                    raise ValueError(f"Duplicate spawner id: {data['id']}")
                definitions[data["id"]] = data
        field_ids = self.policy["fieldSpawnerIds"]
        if set(field_ids) != {"grass", "forest"}:
            raise ValueError("Policy field habitats must be grass and forest")
        if self.road_mode:
            expected = {habitat: [f"eriva_westroad_{habitat}_lv{level}_spawner"
                                  for level in self.policy["road"]["levels"]]
                        for habitat in ("grass", "forest")}
            if field_ids != expected:
                raise ValueError("Road mode requires exact west-road spawner IDs for every level and habitat")
        selected = {}
        for habitat, ids in field_ids.items():
            for key in ids:
                if key in selected or key not in definitions:
                    raise ValueError(f"Duplicate or unknown field spawner: {key}")
                data = definitions[key]
                entries = []
                for entry in data.get("spawnMobs", []):
                    mob_id = str(entry["mobId"]).removeprefix("mob:")
                    if mob_id not in mobs:
                        raise ValueError(f"Unknown mob in {key}: {mob_id}")
                    mob = mobs[mob_id]
                    level = int(entry["level"])
                    levels = {int(mob["level"])} | {int(v["level"]) for v in mob.get("levels", [])}
                    if level not in levels or level < self.minimum_level or mob.get("category") != "ENEMY":
                        raise ValueError(f"Unsupported field enemy level: {key}/{mob_id}/{level}")
                    entries.append({"mobId": entry["mobId"], "name": _mob_name(mob, level),
                                    "level": level, "weight": int(entry["weight"])})
                if not entries or len({entry["level"] for entry in entries}) != 1:
                    raise ValueError(f"Field spawner needs a single defined level: {key}")
                if self.road_mode and entries[0]["level"] != int(key.split("_lv")[-1].removesuffix("_spawner")):
                    raise ValueError(f"Road spawner level differs from its ID: {key}")
                selected[key] = {"id": key, "definitionStatus": "existing", "habitat": habitat, "level": entries[0]["level"],
                                 "region": data.get("region"), "radiusMeters": int(data["radiusMeters"]),
                                 "spawnMobs": entries,
                                 "maxAlivePerSpawner": int(data.get("spawnLimit", {}).get("maxAlivePerSpawner", 0))}
        draft_ids = self.policy.get("draftEnemyIds", {})
        if self.road_mode and draft_ids:
            raise ValueError("Road mode cannot use draft Enemy fallbacks")
        if not self.road_mode and set(draft_ids) != {"grass", "forest", "snow", "dry"}:
            raise ValueError("Draft enemy habitats must be grass, forest, snow and dry")
        for habitat, ids in draft_ids.items():
            for mob_id in ids:
                mob = mobs.get(mob_id)
                if mob is None or mob.get("category") != "ENEMY" or not mob_id.startswith("field_"):
                    raise ValueError(f"Draft enemy must be an existing field ENEMY: {mob_id}")
                levels = {int(mob["level"])} | {int(v["level"]) for v in mob.get("levels", [])}
                for level in sorted(levels):
                    if level < self.minimum_level:
                        continue
                    key = f"{mob_id}_lv{level}_spawner"
                    if key in selected or key in definitions:
                        raise ValueError(f"Draft spawner id conflicts with master: {key}")
                    selected[key] = {"id": key, "definitionStatus": "draft", "habitat": habitat,
                                     "level": level, "region": f"エリヴァ{HABITAT_NAMES[habitat]}",
                                     "radiusMeters": None,
                                     "spawnMobs": [{"mobId": f"mob:{mob_id}",
                                                    "name": _mob_name(mob, level),
                                                    "level": level, "weight": 100}],
                                     "maxAlivePerSpawner": None}
        available = []
        for key, data in sorted(definitions.items()):
            available.append({"id": key, "selectedForField": key in selected,
                              "reason": "地域と環境を明示して選定" if key in selected else
                              "エリヴァ野外用と確認できないため自動配置しない",
                              "region": data.get("region"), "radiusMeters": data.get("radiusMeters"),
                              "spawnMobs": data.get("spawnMobs", [])})
        enemy_catalog = []
        draft_mobs = {mob_id for ids in draft_ids.values() for mob_id in ids}
        existing_mobs = {entry["mobId"].removeprefix("mob:") for definition in selected.values()
                         if definition["definitionStatus"] == "existing" for entry in definition["spawnMobs"]}
        for mob_id, mob in sorted(mobs.items()):
            enemy_catalog.append({"id": mob_id, "name": _plain(mob.get("name", mob_id)),
                                  "levels": sorted({int(mob["level"])} | {int(v["level"]) for v in mob.get("levels", [])}),
                                  "tags": mob.get("tags", []),
                                  "fieldUse": "existing" if mob_id in existing_mobs else
                                              "draft" if mob_id in draft_mobs else "excluded",
                                  "reason": "野外用の選定済みマスタ" if mob_id in existing_mobs else
                                            "既存Enemyの実在レベルを使う未登録案" if mob_id in draft_mobs else
                                            "エリヴァ野外への適合を確認できない"})
        return selected, {"fieldSpawners": sorted(selected.values(), key=lambda v: v["id"]),
                          "availableSpawnerMasters": available,
                          "enemyMasters": enemy_catalog,
                          "enemyMasterCount": len(mobs)}, hash_input.hexdigest()

    def _region_key(self, x: float, z: float, habitat: str) -> tuple[str, str, int]:
        if self.road_mode:
            nearest = self.road_nearest.get((int(x - 8), int(z - 8)))
            if nearest is None:
                return "eriva-reserved-future", "エリヴァ・後続地域（未配置）", None
            distance = nearest[1]
            levels = self.policy["road"]["levels"]
            stage = min(len(levels) - 1, int(distance * len(levels) / (self.max_road_distance + 1)))
            level = levels[stage]
            return (f"eriva-westroad-stage{stage + 1:02d}-lv{level}-{habitat}",
                    f"エリヴァ・西側道路第{stage + 1}段階・{HABITAT_NAMES[habitat]}（仮称）", level)
        dx, dz = x - self.spawn["x"], z - self.spawn["z"]
        distance = math.hypot(dx, dz)
        band = next(b for b in self.policy["distanceBands"]
                    if b["maxDistance"] is None or distance < b["maxDistance"])
        direction_index = round(math.atan2(-dz, dx) / (math.pi / 4)) % 8
        direction, direction_name = DIRECTIONS[direction_index]
        level = int(band["level"])
        region_id = f"eriva-{direction}-lv{level}-{habitat}"
        name = f"エリヴァ{direction_name}・{band['name']}・{HABITAT_NAMES[habitat]}（仮称）"
        return region_id, name, level

    def _classify(self, source: dict) -> dict:
        c = {k: source.get(k) for k in ("x", "z", "status", "y", "candidateX", "candidateY", "candidateZ",
              "surface", "ground", "biome", "safeCount", "waterCount", "treeCount", "minY", "maxY")}
        if source.get("reason"):
            c["terrainReason"] = source["reason"]
        c["habitat"] = _habitat(source)
        if self.road_mode:
            nearest = self.road_nearest.get((c["x"], c["z"])) if c["status"] == "ok" else None
            c["distanceToRoadMeters"] = round(nearest[0], 2) if nearest else None
            c["roadDistanceMeters"] = nearest[1] if nearest else None
        c["regionId"], c["regionName"], c["targetLevel"] = self._region_key(
            c["x"] + self.step / 2, c["z"] + self.step / 2, c["habitat"])
        if self.road_mode and c["status"] != "ok":
            c["regionId"], c["regionName"], c["targetLevel"] = (
                "eriva-road-unmeasured", "エリヴァ・未計測区画", None)
        if c["status"] != "ok":
            reason = c["status"]
        elif c["habitat"] == "water":
            reason = "water"
        elif self.road_mode and c["distanceToRoadMeters"] is None:
            reason = "reserved_future"
        elif c["safeCount"] < self.policy["minSafeColumns"]:
            reason = "unsafe"
        elif c["maxY"] - c["minY"] > self.policy["maxLocalSlope"]:
            reason = "steep"
        elif math.hypot(c["x"] + 8 - self.spawn["x"], c["z"] + 8 - self.spawn["z"]) <= self.policy["spawnSafeRadius"] + math.sqrt(128):
            reason = "protected"
        elif c["habitat"] not in (set(self.policy["fieldSpawnerIds"]) | set(self.policy.get("draftEnemyIds", {}))):
            reason = "unsupported_habitat"
        else:
            reason = "eligible"
        c["reasonCode"] = reason
        c["reason"] = REASONS[reason]
        return c

    def _regions(self) -> dict:
        regions = {}
        region_components = defaultdict(set)
        for c in self.cells:
            key = c["regionId"]
            if key not in regions:
                regions[key] = {"id": key, "name": c["regionName"], "habitat": c["habitat"],
                                "targetLevel": c["targetLevel"], "levelMin": None, "levelMax": None,
                                "cellCount": 0, "eligibleCells": 0, "coveredCellsByRadius": {},
                                "reasonCounts": {},
                                "landComponentIds": [],
                                "bounds": {"minX": c["x"], "minZ": c["z"],
                                           "maxX": c["x"] + self.step, "maxZ": c["z"] + self.step},
                                "spawnerIds": [], "reason": "道路区画の距離による仮の進行区分" if self.road_mode else
                                "スポーンからの平面距離と方角による仮の進行区分"}
            regions[key]["cellCount"] += 1
            if c["landComponentId"] is not None:
                region_components[key].add(c["landComponentId"])
            bounds = regions[key]["bounds"]
            bounds["minX"] = min(bounds["minX"], c["x"])
            bounds["minZ"] = min(bounds["minZ"], c["z"])
            bounds["maxX"] = max(bounds["maxX"], c["x"] + self.step)
            bounds["maxZ"] = max(bounds["maxZ"], c["z"] + self.step)
        for key, region in regions.items():
            region["landComponentIds"] = sorted(region_components[key])
        return regions

    def _assign_land_components(self) -> list[dict]:
        """Coarse landmass grouping; this does not establish walkable paths."""
        components = []
        visited = set()
        for first, cell in enumerate(self.cells):
            if first in visited or not self._is_land(cell):
                continue
            component_id = f"land-{len(components) + 1:04d}"
            queue = deque([first])
            visited.add(first)
            count = 0
            bounds = {"minX": cell["x"], "minZ": cell["z"],
                      "maxX": cell["x"] + self.step, "maxZ": cell["z"] + self.step}
            while queue:
                i = queue.popleft()
                c = self.cells[i]
                c["landComponentId"] = component_id
                count += 1
                bounds["minX"] = min(bounds["minX"], c["x"])
                bounds["minZ"] = min(bounds["minZ"], c["z"])
                bounds["maxX"] = max(bounds["maxX"], c["x"] + self.step)
                bounds["maxZ"] = max(bounds["maxZ"], c["z"] + self.step)
                row, col = divmod(i, self.columns)
                neighbors = ((row - 1, col), (row + 1, col), (row, col - 1), (row, col + 1))
                for nr, nc in neighbors:
                    if not 0 <= nr < self.rows or not 0 <= nc < self.columns:
                        continue
                    j = nr * self.columns + nc
                    if j not in visited and self._is_land(self.cells[j]):
                        visited.add(j)
                        queue.append(j)
            components.append({"id": component_id, "cellCount": count, "bounds": bounds})
        for c in self.cells:
            c.setdefault("landComponentId", None)
        return components

    @staticmethod
    def _is_land(cell: dict) -> bool:
        return (cell["status"] == "ok" and cell["habitat"] != "water" and
                (cell.get("safeCount") or 0) > 0 and (cell.get("waterCount") or 0) < 128)

    def _select_spawners(self) -> None:
        by_habitat_level = defaultdict(list)
        for definition in self.spawners.values():
            by_habitat_level[(definition["habitat"], definition["level"])].append(definition["id"])
        for ids in by_habitat_level.values():
            ids.sort()
        available_by_habitat = {habitat: sorted(level for h, level in by_habitat_level if h == habitat)
                                for habitat in (self.policy["fieldSpawnerIds"] if self.road_mode else
                                                self.policy["draftEnemyIds"])}
        for c in self.cells:
            if c["reasonCode"] != "eligible":
                continue
            habitat, level = c["habitat"], c["targetLevel"]
            available = available_by_habitat.get(habitat, [])
            if self.road_mode:
                candidate_level = level if level in available else None
            else:
                lower = [v for v in available if v <= level and level - v <= 2]
                upper = [v for v in available if v > level and v - level <= 2]
                candidate_level = max(lower) if lower else (min(upper) if upper else None)
            ids = by_habitat_level.get((habitat, candidate_level), [])
            if not ids:
                c["reasonCode"] = "unsupported_level"
                c["reason"] = REASONS["unsupported_level"]
                continue
            # Spread same-level master variants deterministically over nearby cells.
            choice = ((c["x"] // 64) + (c["z"] // 64)) % len(ids)
            c["spawnerId"] = ids[choice]
            region = self.regions[c["regionId"]]
            region["eligibleCells"] += 1
            region["spawnerIds"] = sorted(set(region["spawnerIds"]) | set(ids))
            region["levelMin"] = candidate_level if region["levelMin"] is None else min(region["levelMin"], candidate_level)
            region["levelMax"] = candidate_level if region["levelMax"] is None else max(region["levelMax"], candidate_level)
        region_reasons = defaultdict(Counter)
        for c in self.cells:
            region_reasons[c["regionId"]][c["reasonCode"]] += 1
        for key, region in self.regions.items():
            region["reasonCounts"] = dict(region_reasons[key])

    def _index_range(self, coordinate: float, radius: int, minimum: int, count: int) -> range:
        start = max(0, math.floor((coordinate - radius - minimum) / self.step))
        end = min(count, math.ceil((coordinate + radius - minimum) / self.step))
        return range(start, end)

    def _circle_known(self, x: float, z: float, radius: int) -> bool:
        t = self.terrain
        if (x - radius < t["minX"] or x + radius > t["minX"] + t["width"] or
                z - radius < t["minZ"] or z + radius > t["minZ"] + t["height"]):
            return False
        # Any unknown chunk inside the full spawn circle rejects the centre.
        for rz in self._index_range(z, radius, t["minZ"], self.rows):
            for rx in self._index_range(x, radius, t["minX"], self.columns):
                c = self.cells[rz * self.columns + rx]
                if c["status"] != "ok":
                    nearest_x = min(max(x, c["x"]), c["x"] + self.step)
                    nearest_z = min(max(z, c["z"]), c["z"] + self.step)
                    if (nearest_x - x) ** 2 + (nearest_z - z) ** 2 <= radius ** 2:
                        return False
        return True

    def _circle_within_road_scope(self, x: float, z: float, radius: int) -> bool:
        """Reject circles touching an explicitly reserved future land cell."""
        if not self.road_mode:
            return True
        t = self.terrain
        for rz in self._index_range(z, radius, t["minZ"], self.rows):
            for rx in self._index_range(x, radius, t["minX"], self.columns):
                c = self.cells[rz * self.columns + rx]
                if c["reasonCode"] != "reserved_future":
                    continue
                nearest_x = min(max(x, c["x"]), c["x"] + self.step)
                nearest_z = min(max(z, c["z"]), c["z"] + self.step)
                if (nearest_x - x) ** 2 + (nearest_z - z) ** 2 <= radius ** 2:
                    return False
        return True

    def _covered(self, i: int, radius: int) -> tuple[int, ...]:
        center = self.cells[i]
        x, y, z = center["candidateX"] + .5, center["candidateY"], center["candidateZ"] + .5
        t = self.terrain
        result = []
        for rz in self._index_range(z, radius, t["minZ"], self.rows):
            for rx in self._index_range(x, radius, t["minX"], self.columns):
                j = rz * self.columns + rx
                c = self.cells[j]
                if (c["reasonCode"] != "eligible" or c["regionId"] != center["regionId"] or
                        c["landComponentId"] != center["landComponentId"] or
                        c["minY"] < y - 3 or c["maxY"] > y + radius):
                    continue
                if max((vx - x) ** 2 + (vz - z) ** 2 for vx in (c["x"], c["x"] + self.step)
                       for vz in (c["z"], c["z"] + self.step)) <= radius ** 2:
                    result.append(j)
        return tuple(result)

    def plan(self, radius: int) -> dict:
        if radius not in self.policy["radii"]:
            raise ValueError("Unsupported radius")
        if self.road_mode and radius > self.policy["road"]["corridorMeters"]:
            raise ValueError("Road corridor is narrower than spawn radius")
        target = {i for i, c in enumerate(self.cells) if c["reasonCode"] == "eligible"}
        remaining = set(target)
        candidates = []
        heap = []
        stride = self.policy["candidateStrideCells"]
        for i in sorted(target):
            c = self.cells[i]
            if (c["x"] - self.terrain["minX"]) // self.step % stride or (c["z"] - self.terrain["minZ"]) // self.step % stride:
                continue
            x, z = c["candidateX"] + .5, c["candidateZ"] + .5
            if math.hypot(x - self.spawn["x"], z - self.spawn["z"]) < radius + self.policy["spawnSafeRadius"]:
                continue
            if self.road_mode:
                lateral = min(math.hypot(x - node["x"], z - node["z"]) for node in self.road_nodes)
                if lateral > min(self.policy["road"]["candidateCenterMeters"],
                                 self.policy["road"]["corridorMeters"] - radius):
                    continue
            if not self._circle_known(x, z, radius):
                continue
            if not self._circle_within_road_scope(x, z, radius):
                continue
            hit = self._covered(i, radius)
            if hit:
                heapq.heappush(heap, (-len(hit), i, len(candidates)))
                candidates.append((i, hit))
        proposals = []
        covered_by = {}
        reason_codes = [c["reasonCode"] for c in self.cells]
        while heap and remaining:
            old_score, _, n = heapq.heappop(heap)
            i, hit = candidates[n]
            fresh = tuple(j for j in hit if j in remaining)
            if not fresh:
                continue
            if -old_score != len(fresh):
                heapq.heappush(heap, (-len(fresh), i, n))
                continue
            c = self.cells[i]
            definition = self.spawners[c["spawnerId"]]
            proposal_id = hashlib.sha256(f"{self.export_revision}:{radius}:{i}:{definition['id']}".encode()).hexdigest()[:16]
            proposal = {"proposalId": proposal_id, "spawnerId": definition["id"],
                        "world": self.world["baseWorldPath"], "regionId": c["regionId"],
                        "regionName": c["regionName"], "x": c["candidateX"], "y": c["candidateY"],
                        "z": c["candidateZ"], "radiusMeters": radius,
                        "landComponentId": c["landComponentId"],
                        "originalRadiusMeters": definition["radiusMeters"],
                        "masterUpdateRequired": definition["definitionStatus"] == "draft" or radius != definition["radiusMeters"],
                        "definitionStatus": definition["definitionStatus"],
                        "habitat": c["habitat"], "level": definition["level"],
                        "targetLevel": c["targetLevel"], "spawnMobs": definition["spawnMobs"],
                        "reason": f"{c['regionName']}、Lv.{definition['level']}の定義済み野外マスタ、実測地表を使用",
                        "newCoveredCells": len(fresh), "coveredCellIndices": fresh}
            if self.road_mode:
                proposal["roadDistanceMeters"] = c["roadDistanceMeters"]
                proposal["distanceToRoadMeters"] = c["distanceToRoadMeters"]
            if definition["definitionStatus"] == "draft":
                proposal["draftDefinition"] = {"id": definition["id"], "type": "MOB_SPAWNER",
                                               "region": definition["region"], "radiusMeters": radius,
                                               "spawnMobs": [{"mobId": v["mobId"], "level": v["level"],
                                                              "weight": v["weight"]} for v in definition["spawnMobs"]],
                                               "registrationRequired": True,
                                               "note": "既存Enemyの実在レベルを参照する未登録案。出現間隔・上限等は未設計。"}
                proposal["reason"] = f"{c['regionName']}、既存Enemy Lv.{definition['level']}からの未登録スポナー案、実測地表を使用"
            proposals.append(proposal)
            for j in fresh:
                reason_codes[j] = "covered"
                covered_by[str(j)] = proposal_id
            remaining.difference_update(fresh)
        for i in remaining:
            reason_codes[i] = "uncovered"
        counts = dict(Counter(reason_codes))
        region_covered = Counter(self.cells[i]["regionId"] for i in target - remaining)
        for key, region in self.regions.items():
            region["coveredCellsByRadius"][str(radius)] = region_covered[key]
        known_alive_total = sum(self.spawners[p["spawnerId"]]["maxAlivePerSpawner"]
                                for p in proposals if self.spawners[p["spawnerId"]]["maxAlivePerSpawner"] is not None)
        unknown_limit_count = sum(self.spawners[p["spawnerId"]]["maxAlivePerSpawner"] is None
                                  for p in proposals)
        return {"schemaVersion": 2, "exportRevision": self.export_revision,
                "worldId": self.world["id"], "terrainFingerprint": self.terrain_fingerprint,
                "previewOnly": True, "radiusMeters": radius, "proposals": proposals,
                "reasonCodes": reason_codes, "coveredBy": covered_by, "counts": counts,
                "targetCells": len(target), "coveredCells": len(target) - len(remaining),
                "uncoveredCells": len(remaining),
                "coveragePercent": round(100 * (len(target) - len(remaining)) / len(target), 2) if target else 0,
                "maxAliveUpperBound": known_alive_total if unknown_limit_count == 0 else None,
                "knownMaxAliveTotal": known_alive_total,
                "unknownLimitProposalCount": unknown_limit_count}

    def export(self, output: Path) -> dict:
        outputs = {}
        for radius in self.policy["radii"]:
            name = f"proposals-{radius}.json"
            outputs[str(radius)] = name
            _atomic_json(output / name, self.plan(radius))
        _atomic_json(output / "cells.json", {"schemaVersion": 2, "exportRevision": self.export_revision,
                                                "worldId": self.world["id"],
                                                "terrainFingerprint": self.terrain_fingerprint,
                                                "cells": self.cells})
        _atomic_json(output / "regions.json", {"schemaVersion": 2, "exportRevision": self.export_revision,
                                                  "worldId": self.world["id"],
                                                  "terrainFingerprint": self.terrain_fingerprint,
                                                  "regions": sorted(self.regions.values(), key=lambda r: r["id"]),
                                                  "landComponents": self.land_components,
                                                  "catalog": self.catalog})
        manifest = {"schemaVersion": 2, "exportRevision": self.export_revision,
                    "mode": "continent-redesign", "previewOnly": True,
                    "worldId": self.world["id"], "worldName": self.world["displayName"],
                    "terrainFingerprint": self.terrain_fingerprint,
                    "capturedAt": self.terrain["capturedAt"],
                    "spawn": {k: self.spawn[k] for k in ("x", "y", "z")},
                    "bounds": {k: self.terrain[k] for k in ("minX", "minZ", "width", "height", "cellSize")},
                    "cellCount": len(self.cells),
                    "files": {"cells": "cells.json", "regions": "regions.json", "proposals": outputs},
                    "radiusOptions": self.policy["radii"], "reasons": REASONS,
                    "progressionPolicy": {} if self.road_mode else
                    {"basis": "開始地点からの平面距離。地形経路・到達性は未証明",
                     "minimumWorldLevel": self.minimum_level,
                     "distanceBands": self.policy["distanceBands"]},
                    "counts": dict(Counter(c["reasonCode"] for c in self.cells)),
                    "notes": ["地域名は方角・距離帯・地表環境から生成した提案上の仮称。既存の正式地名ではない。",
                              "既存の配置は読まず、カバー率に加算しない。"],
                    "limitations": ["地表の16m区画のみ。洞窟、室内、水中、未生成/失敗区画は対象外。",
                                    "全大陸の通行可能性と各Mobの経路探索は確認していない。島や峡谷を別個に実地確認する必要がある。",
                                    "landComponentIdは地表16m区画の4近傍連結推定で、橋・段差・断崖の歩行可能性を証明しない。",
                                    "スポーン円内の異なる環境・地域へ実際のMobが出現する可能性は未検証。",
                                    "半径を変更すると同じスポナーマスタIDの全配置へ影響し得る。実配置・公開は別工程。",
                                    "同時存在上限の合計は性能測定値ではない。"]}
        if self.road_mode:
            road = self.policy["road"]
            manifest["progressionPolicy"] = {
                "mode": "road", "basis": "開始地点につながるdirt_path含有16m区画の4近傍最短距離",
                "minimumWorldLevel": self.minimum_level, "maxLevel": road["levels"][-1],
                "levels": road["levels"],
                "road": {"nodes": self.road_nodes, "edges": self.road_edges,
                         "maxDistanceMeters": self.max_road_distance,
                         "corridorMeters": road["corridorMeters"],
                         "candidateCenterMeters": road["candidateCenterMeters"],
                         "material": road["material"]}}
            manifest["notes"] = ["西側道路沿いLv.15～45だけを候補とし、他の陸地は後続地域として保留。北東などのLv.100以上の将来設計を先取りしない。",
                                 "地域名は道路距離と地表環境から生成した提案上の仮称。正式地名ではない。",
                                 "既存の配置は読まず、カバー率に加算しない。"]
            manifest["limitations"].append(
                "groundCountsは16m区画ごとの材質数で道の柱位置を持たない。4近傍道路グラフは歩行可能性、橋、段差を証明しない。")
        # Publish the manifest last. Readers reject mismatched exportRevision.
        _atomic_json(output / "manifest.json", manifest)
        return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--terrain", type=Path, required=True)
    parser.add_argument("--filebase", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=Path(__file__).parent / "policies" / "eriva-continent.json")
    args = parser.parse_args()
    manifest = ContinentPlanner(args.terrain, args.filebase, args.policy).export(args.output)
    print(json.dumps({"exportRevision": manifest["exportRevision"], "cellCount": manifest["cellCount"],
                      "counts": manifest["counts"], "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
