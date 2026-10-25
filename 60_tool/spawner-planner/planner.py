"""実測地形から既存スポナーの配置候補を作る。サーバーへの適用機能は持たない。"""
from __future__ import annotations

import hashlib
import heapq
import json
import math
import re
from collections import Counter, deque
from pathlib import Path

import yaml


REASONS = {
    "unknown": "未解析・読込失敗",
    "water": "水面・水中（今回の対象外）",
    "unsafe": "安全な足場が不足",
    "steep": "区画内の高低差が大きい",
    "protected": "開始地点の保護範囲",
    "unsupported": "この環境に対応する既存スポナーを未選定",
    "disconnected": "開始地点からの接続を確認できない（地表グリッド推定）",
    "eligible": "配置候補を計算中",
    "uncovered": "半径・高度・保護範囲・解析境界の制約で未カバー",
    "budget": "配置数の上限により未カバー",
    "covered": "新しい配置案でカバー",
    "existing": "既存スポナーでカバー",
}


def read_yaml(path: Path) -> dict:
    """UTF-8 YAMLのルートmappingを読む。"""
    value = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"YAML mapping required: {path}")
    return value


def strip_color(value: str) -> str:
    return re.sub(r"[&§][0-9a-fk-or]", "", value, flags=re.I)


def habitat(cell: dict) -> str:
    """分類は説明可能な初期ルール。未知の人工地形は自動配置対象にしない。"""
    if cell["status"] != "ok":
        return "unknown"
    biome = cell.get("biome") or ""
    ground = cell.get("ground") or ""
    surface = cell.get("surface") or ""
    if cell.get("waterCount", 0) >= 8:
        return "water"
    if any(key in biome + ground + surface for key in ("snow", "frozen", "ice", "grove")):
        return "snow"
    if any(key in biome for key in ("desert", "badlands")) or ground.endswith(":sand"):
        return "dry"
    natural = {"grass_block", "dirt", "coarse_dirt", "podzol", "moss_block", "rooted_dirt", "mud", "mycelium"}
    if ground.split(":")[-1] not in natural:
        return "other"
    if any(key in biome for key in ("forest", "jungle", "taiga")) or cell.get("treeCount", 0) >= 3:
        return "forest"
    if ground.split(":")[-1] in {"grass_block", "dirt", "coarse_dirt", "podzol", "moss_block", "rooted_dirt", "mud"}:
        return "grass"
    return "other"


class Planner:
    """読み取り専用の地形・マスタスナップショットと、決定的な配置計算。"""

    def __init__(self, terrain_path: Path, filebase: Path, policy_path: Path,
                 existing_path: Path | None = None):
        raw = terrain_path.read_bytes()
        self.fingerprint = hashlib.sha256(raw).hexdigest()
        self.terrain = json.loads(raw)
        self.policy = json.loads(policy_path.read_text(encoding="utf-8"))
        if self.terrain.get("schemaVersion") != 1 or self.policy.get("schemaVersion") != 1:
            raise ValueError("Unsupported terrain/policy schema")
        self.world = self._world(filebase, self.policy["worldId"])
        if self.terrain["world"].split("/")[-1] != self.world["id"]:
            raise ValueError("Terrain and world master identities do not match")
        self.spawn = self.world["spawnLocation"]
        self.min_level = int(self.world.get("adventureGuide", {}).get("recommendedLevelMin", 1))
        self.catalog = self._catalog(filebase)
        self.step = self.terrain["cellSize"]
        self._validate_grid()
        self.cells = [dict(cell, habitat=habitat(cell)) for cell in self.terrain["cells"]]
        self.grid = {(c["x"], c["z"]): i for i, c in enumerate(self.cells)}
        self._classify()
        self.existing, self.inventory_status = self._existing(existing_path)

    @staticmethod
    def _world(filebase: Path, world_id: str) -> dict:
        matches = [data for path in sorted((filebase / "60.features.world").glob("*.yml"))
                   if (data := read_yaml(path)).get("id") == world_id]
        if len(matches) != 1:
            raise ValueError(f"World master must resolve exactly once: {world_id}")
        return matches[0]

    def _catalog(self, filebase: Path) -> dict:
        definitions = {}
        for path in sorted((filebase / "41.features.mob.spawner").glob("*.yml")):
            data = read_yaml(path)
            key = data.get("id")
            if key in definitions:
                raise ValueError(f"Duplicate spawner id: {key}")
            definitions[key] = data
        mobs = {}
        for path in sorted((filebase / "40.features.mob" / "enemy").glob("*.yml")):
            data = read_yaml(path)
            mobs[data["id"]] = data
        result = {}
        for environment, ids in self.policy["habitats"].items():
            for key in ids:
                if key in result:
                    raise ValueError(f"Ambiguous habitat for {key}")
                data = definitions[key]
                entries = []
                for entry in data["spawnMobs"]:
                    mob = mobs[entry["mobId"].removeprefix("mob:")]
                    level = int(entry["level"])
                    levels = {int(mob["level"])} | {int(v["level"]) for v in mob.get("levels", [])}
                    if level not in levels or level < self.min_level:
                        raise ValueError(f"Unavailable/too low mob level: {key} / {level}")
                    entries.append(dict(entry, name=strip_color(mob.get("name", mob["id"]))))
                result[key] = dict(data, habitat=environment, spawnMobs=entries)
        self.definitions = definitions
        return result

    def _validate_grid(self):
        t = self.terrain
        if t["cellSize"] != 4 or not 0 < t["width"] <= 1024 or not 0 < t["height"] <= 1024:
            raise ValueError("Expected bounded terrain with 4m cells")
        if t["width"] % self.step or t["height"] % self.step:
            raise ValueError("Terrain bounds must align with cells")
        expected = {(x, z) for z in range(t["minZ"], t["minZ"] + t["height"], self.step)
                    for x in range(t["minX"], t["minX"] + t["width"], self.step)}
        actual = [(c["x"], c["z"]) for c in t["cells"]]
        if len(actual) != len(set(actual)) or set(actual) != expected:
            raise ValueError("Terrain grid contains missing, duplicate or out-of-bounds cells")
        for c in t["cells"]:
            if c["status"] not in {"ok", "unknown"}:
                raise ValueError("Unknown cell status")
            if c["status"] == "ok":
                columns = c.get("safeColumnY")
                if not isinstance(columns, list) or len(columns) != 16 or any(v is not None and type(v) is not int for v in columns):
                    raise ValueError("Expected 16 measured safeColumnY values; rerun scanner")
                heights = [v for v in columns if v is not None]
                if len(heights) != c.get("safeCount"):
                    raise ValueError("Column count does not match safeCount")
                below = c.get("walkableBelowSurface")
                if not isinstance(below, list):
                    raise ValueError("Expected measured walkableBelowSurface; rerun scanner")
                seen = set()
                for floor in below:
                    if not isinstance(floor, dict) or any(type(floor.get(k)) is not int for k in ("x", "y", "z")):
                        raise ValueError("Invalid measured lower floor")
                    dx, dz = floor["x"] - c["x"], floor["z"] - c["z"]
                    point = (floor["x"], floor["y"], floor["z"])
                    if not 0 <= dx < 4 or not 0 <= dz < 4 or point in seen:
                        raise ValueError("Duplicate or out-of-cell lower floor")
                    upper = columns[dz * 4 + dx]
                    if upper is not None and not upper - 32 <= floor["y"] < upper:
                        raise ValueError("Lower floor outside surface scan depth")
                    seen.add(point)
            if c["status"] == "ok" and c.get("safeCount", 0) > 0:
                if any(not isinstance(c.get(k), (int, float)) or not math.isfinite(c[k])
                       for k in ("candidateX", "candidateY", "candidateZ", "minY", "maxY")):
                    raise ValueError("Safe cell lacks finite measured candidate coordinates")
                dx, dz = c["candidateX"] - c["x"], c["candidateZ"] - c["z"]
                if type(dx) is not int or type(dz) is not int or not 0 <= dx < 4 or not 0 <= dz < 4:
                    raise ValueError("Candidate outside measured cell")
                if columns[dz * 4 + dx] != c["candidateY"] or min(heights) != c["minY"] or max(heights) != c["maxY"]:
                    raise ValueError("Candidate or elevation bounds do not match measured columns")

    def _classify(self):
        p = self.policy
        floors = set()
        for i, c in enumerate(self.cells):
            # 接続は1ブロック列を使い、同区画にある欄干や崖の高さと混同しない。
            if c["status"] == "ok":
                for n, y in enumerate(c["safeColumnY"]):
                    if y is not None:
                        floors.add((c["x"] + n % 4, y, c["z"] + n // 4))
                floors.update((floor["x"], floor["y"], floor["z"]) for floor in c["walkableBelowSurface"])
            if c["status"] != "ok":
                reason = "unknown"
            elif c["habitat"] == "water":
                reason = "water"
            elif c["safeCount"] < p["minSafeColumns"]:
                reason = "unsafe"
            elif c["maxY"] - c["minY"] > p["maxLocalSlope"]:
                reason = "steep"
            else:
                reason = "eligible" if c["habitat"] in p["habitats"] else "unsupported"
            if reason != "unknown" and self._spawn_distance(c["x"] + 2, c["z"] + 2) <= p["spawnSafeRadius"] + math.sqrt(8):
                reason = "protected"
            c["reasonCode"] = reason
        # 同じ柱の実測床だけを起点にする。屋根から地面や近傍の別成分へ飛ばさない。
        spawn_x, spawn_z = math.floor(self.spawn["x"]), math.floor(self.spawn["z"])
        at_spawn = [point for point in floors if point[0] == spawn_x and point[2] == spawn_z
                    and abs(point[1] - self.spawn["y"]) <= 1]
        start = min(at_spawn, key=lambda pt: (abs(pt[1] - self.spawn["y"]), pt), default=None)
        self.connection_start = dict(zip(("x", "y", "z"), start)) if start else None
        reached = {start} if start is not None else set()
        queue = deque(reached)
        while queue:
            x, y, z = queue.popleft()
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                for dy in (0, -1, 1):
                    point = (x + dx, y + dy, z + dz)
                    if point in floors and point not in reached:
                        reached.add(point)
                        queue.append(point)
        for i, c in enumerate(self.cells):
            c["connected"] = (c.get("candidateX"), c.get("candidateY"), c.get("candidateZ")) in reached
            if c["reasonCode"] == "eligible" and not c["connected"]:
                c["reasonCode"] = "disconnected"

    def _spawn_distance(self, x, z):
        return math.hypot(x - self.spawn["x"], z - self.spawn["z"])

    def _existing(self, path: Path | None):
        if path is None:
            return [], "unavailable"
        data = read_yaml(path)
        entries = data.get("spawners", [])
        if not isinstance(entries, list):
            raise ValueError("Expected spawners list in runtime inventory")
        names = {self.world["id"], self.world["baseWorldPath"], *self.policy.get("sourceWorldAliases", [])}
        found = []
        for entry in entries:
            if entry.get("world", "").replace("\\", "/") not in names:
                continue
            definition = self.definitions.get(entry["id"])
            radius = float(definition["radiusMeters"]) if definition else 0
            t = self.terrain
            if not (t["minX"] - radius <= entry["x"] <= t["minX"] + t["width"] + radius
                    and t["minZ"] - radius <= entry["z"] <= t["minZ"] + t["height"] + radius):
                continue
            found.append(dict(entry, radiusMeters=definition["radiusMeters"] if definition else 0,
                              knownDefinition=definition is not None))
        return found, "loaded"

    def public_data(self):
        return {"schemaVersion": 1, "world": self.world, "regionName": self.policy["regionName"],
                "terrain": {k: v for k, v in self.terrain.items() if k != "cells"},
                "cells": self.cells, "catalog": self.catalog, "existing": self.existing,
                "inventoryStatus": self.inventory_status, "reasons": REASONS,
                "notes": self.policy["notes"], "fingerprint": self.fingerprint,
                "safeRadius": self.policy["spawnSafeRadius"], "connectionStart": self.connection_start}

    def _coverage(self, x, y, z, radius, environment, target):
        covered = set()
        t = self.terrain
        # 四隅すべてが円内の場合だけ区画をカバー扱いにする。
        low_x = max(t["minX"], math.floor((x - radius - t["minX"]) / self.step) * self.step + t["minX"])
        low_z = max(t["minZ"], math.floor((z - radius - t["minZ"]) / self.step) * self.step + t["minZ"])
        for cz in range(low_z, min(math.ceil(z + radius), t["minZ"] + t["height"]), self.step):
            for cx in range(low_x, min(math.ceil(x + radius), t["minX"] + t["width"]), self.step):
                i = self.grid.get((cx, cz))
                if i not in target:
                    continue
                c = self.cells[i]
                if c["habitat"] != environment or c["minY"] < y - 3 or c["maxY"] > y + radius:
                    continue
                if max((vx - x) ** 2 + (vz - z) ** 2 for vx in (cx, cx + self.step)
                       for vz in (cz, cz + self.step)) <= radius ** 2:
                    covered.add(i)
        return covered

    def plan(self, radius=48, max_spawners=200):
        if isinstance(radius, bool) or radius not in (32, 48, 64):
            raise ValueError("Radius must be 32, 48 or 64 metres")
        if isinstance(max_spawners, bool) or not isinstance(max_spawners, int) or not 1 <= max_spawners <= 500:
            raise ValueError("maxSpawners must be an integer between 1 and 500")
        target = {i for i, c in enumerate(self.cells) if c["reasonCode"] == "eligible"}
        remaining = set(target)
        reasons = [c["reasonCode"] for c in self.cells]
        # 既存配置は比較用に保存。新案へそのまま加算すると配置数最小化を評価できない。
        # 出力は再配置のプレビューであり、既存配置への追加・削除の実行指示ではない。
        candidates = []
        t = self.terrain
        for i in sorted(remaining):
            c = self.cells[i]
            # 12m間隔の候補で大域的な被覆を作り、未カバーは理由付きで残す。
            if (c["x"] - t["minX"]) % 12 or (c["z"] - t["minZ"]) % 12:
                continue
            x, y, z = c["candidateX"] + .5, c["candidateY"], c["candidateZ"] + .5
            if self._spawn_distance(x, z) < radius + self.policy["spawnSafeRadius"]:
                continue
            if x - radius < t["minX"] or x + radius > t["minX"] + t["width"] or z - radius < t["minZ"] or z + radius > t["minZ"] + t["height"]:
                continue
            hit = self._coverage(x, y, z, radius, c["habitat"], remaining)
            if hit:
                candidates.append((i, hit))
        heap = [(-len(hit), n) for n, (_, hit) in enumerate(candidates)]
        heapq.heapify(heap)
        proposals = []
        covered_by = {}
        while heap and remaining and len(proposals) < max_spawners:
            old_score, n = heapq.heappop(heap)
            i, hit = candidates[n]
            fresh = hit & remaining
            if not fresh:
                continue
            if -old_score != len(fresh):
                heapq.heappush(heap, (-len(fresh), n))
                continue
            c = self.cells[i]
            spawner_id = self.policy["habitats"][c["habitat"]][0]
            definition = self.catalog[spawner_id]
            proposal_id = hashlib.sha256(f"{self.fingerprint}:{spawner_id}:{i}:{radius}".encode()).hexdigest()[:16]
            proposal = {"proposalId": proposal_id, "id": spawner_id,
                        "world": self.world["baseWorldPath"], "x": math.floor(c["candidateX"]),
                        "y": math.floor(c["candidateY"]), "z": math.floor(c["candidateZ"]),
                        "radiusMeters": radius, "originalRadiusMeters": definition["radiusMeters"],
                        "masterUpdateRequired": radius != definition["radiusMeters"],
                        "habitat": c["habitat"], "newCoveredCells": len(fresh),
                        "reason": f"{c['habitat']}適性、Lv.{self.min_level}以上の既存マスタ、実測足場と高度条件を使用",
                        "spawnMobs": definition["spawnMobs"]}
            proposals.append(proposal)
            for j in fresh:
                reasons[j] = "covered"
                covered_by[str(j)] = proposal_id
            remaining -= fresh
        for i in remaining:
            reasons[i] = "budget" if len(proposals) == max_spawners and any(i in hit for _, hit in candidates) else "uncovered"
        counts = dict(Counter(reasons))
        return {"schemaVersion": 1, "previewOnly": True, "mode": "redesign-comparison", "worldId": self.world["id"],
                "terrainFingerprint": self.fingerprint, "policy": self.policy,
                "parameters": {"radiusMeters": radius, "maxSpawners": max_spawners},
                "proposals": proposals, "reasonCodes": reasons, "coveredBy": covered_by,
                "counts": counts, "targetCells": len(target), "uncoveredCells": len(remaining),
                "coveredCells": len(target) - len(remaining),
                "coveragePercent": round(100 * (len(target) - len(remaining)) / len(target), 1) if target else 0,
                "inventoryStatus": self.inventory_status,
                "existingCount": len(self.existing), "existingPlacements": self.existing,
                "maxAliveUpperBound": sum(self.catalog[p["id"]].get("spawnLimit", {}).get("maxAlivePerSpawner", 8) for p in proposals),
                "limitations": ["既存配置は比較用です。この案を既存配置へ追加すると過密になるため、そのまま追記しないでください。",
                                "被覆は地表の推定戦闘区域が分母。全ワールド・全床の網羅ではありません。",
                                "半径円内の他の地形にも現行スポーン探索は到達し得ます。適用前に地域境界を再検証してください。",
                                "同時存在上限の合計は性能測定値ではありません。半径拡大で実負荷が下がる保証はありません。"]}
