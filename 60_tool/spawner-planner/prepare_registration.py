"""Validate a reviewed road plan and prepare local registration files without deployment."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path, PurePosixPath
import shutil

import yaml

from continent import terrain_distances


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def catalog(directory: Path) -> dict[str, tuple[dict, Path]]:
    result = {}
    for path in sorted(directory.glob("*.yml")):
        value = yaml.safe_load(path.read_text(encoding="utf-8-sig"))
        if not isinstance(value, dict) or not value.get("id"):
            raise ValueError(f"Invalid master: {path.name}")
        if value["id"] in result:
            raise ValueError(f"Duplicate master ID: {value['id']}")
        result[value["id"]] = (value, path)
    return result


def validate_plan(manifest: dict, plan: dict, cells: list[dict],
                  spawners: dict[str, tuple[dict, Path]], mobs: dict[str, tuple[dict, Path]],
                  world_name: str, source_world_name: str | None = None) -> tuple[list[dict], set[Path]]:
    """Return explicitly named-world placements only after master and geometry checks."""
    world = PurePosixPath(world_name.replace("\\", "/"))
    if (not world_name or not world.parts or world.is_absolute() or any(p in (".", "..") for p in world.parts)
            or ":" in world_name or str(world) != world_name or world_name.startswith("/")):
        raise ValueError("world-name must be the exact server-relative Bukkit world name")
    for key in ("schemaVersion", "worldId", "terrainFingerprint", "exportRevision"):
        if manifest.get(key) != plan.get(key):
            raise ValueError(f"Plan identity mismatch: {key}")
    if manifest.get("schemaVersion") != 2 or plan.get("previewOnly") is not True:
        raise ValueError("Expected a schema 2 reviewed preview")
    policy = manifest.get("progressionPolicy", {})
    if policy.get("mode") != "road" or policy.get("maxLevel") != 45 or plan.get("radiusMeters") != 64:
        raise ValueError("Only the road progression plan up to Lv45 with radius64 is supported")
    source_world_name = source_world_name or world_name
    declared_levels = policy.get("levels", [])
    if declared_levels != list(range(15, 46, 3)):
        raise ValueError("Registration requires the declared Lv15 to Lv45 progression catalog")
    level_step = policy.get("levelStepMeters")
    if type(level_step) is not int or level_step <= 0:
        raise ValueError("Registration requires a fixed positive levelStepMeters")
    if len(cells) != manifest.get("cellCount") or len(plan.get("reasonCodes", [])) != len(cells):
        raise ValueError("Incomplete cell set")
    road = policy["road"]
    nodes = road["nodes"]
    if not nodes:
        raise ValueError("No measured road network")
    bounds, spawn = manifest["bounds"], manifest["spawn"]
    min_x, min_z = bounds["minX"], bounds["minZ"]
    max_x, max_z = min_x + bounds["width"], min_z + bounds["height"]
    if bounds.get("cellSize") != 16:
        raise ValueError("Expected measured 16m cells")
    cell_by_position = {(c["x"], c["z"]): c for c in cells}
    if len(cell_by_position) != len(cells):
        raise ValueError("Duplicate measured cell")
    extension = road.get("terrainExtension")
    terrain_progress = None
    if extension:
        seeds = {(n["x"] - 8, n["z"] - 8): n["distanceMeters"]
                 for n in nodes if n.get("kind") == "road"}
        if not seeds or any(p not in cell_by_position for p in seeds):
            raise ValueError("Terrain extension requires measured road seeds")
        terrain_progress, _ = terrain_distances(cell_by_position, seeds, extension)
        for n in nodes:
            if terrain_progress.get((n["x"] - 8, n["z"] - 8)) != n["distanceMeters"]:
                raise ValueError("Terrain route node differs from recalculated terrain distance")
        if 45 not in {p["level"] for p in plan["proposals"]}:
            raise ValueError("The extended terrain plan must include actual Lv45 placements")
    placements, files, occupied, proposal_ids = [], set(), set(), set()
    for proposal in plan["proposals"]:
        distance = proposal.get("roadDistanceMeters")
        maximum_distance = extension["maxDistanceMeters"] if extension else road["maxDistanceMeters"]
        if type(distance) is not int or not 0 <= distance <= maximum_distance:
            raise ValueError("Missing or invalid measured road distance")
        expected_level = declared_levels[min(len(declared_levels) - 1, distance // level_step)]
        if proposal.get("level") != expected_level:
            raise ValueError("Proposal level differs from fixed road-distance progression")
        if proposal.get("world") != source_world_name:
            raise ValueError("Proposal world differs from the explicitly selected source world")
        if proposal.get("definitionStatus") != "existing" or proposal.get("masterUpdateRequired") is not False:
            raise ValueError("Draft or modified master radius cannot be registered")
        if proposal["proposalId"] in proposal_ids:
            raise ValueError("Duplicate proposal ID")
        proposal_ids.add(proposal["proposalId"])
        x, y, z = (proposal[k] for k in ("x", "y", "z"))
        if any(type(v) is not int for v in (x, y, z)) or (x, y, z) in occupied:
            raise ValueError("Coordinates must be unique integer block locations")
        occupied.add((x, y, z))
        cell_x = min_x + (x - min_x) // 16 * 16
        cell_z = min_z + (z - min_z) // 16 * 16
        cell = cell_by_position.get((cell_x, cell_z))
        if (cell is None or cell.get("status") != "ok" or cell.get("reasonCode") != "eligible" or
                tuple(cell.get(k) for k in ("candidateX", "candidateY", "candidateZ")) != (x, y, z) or
                cell.get("roadDistanceMeters") != distance or cell.get("targetLevel") != expected_level):
            raise ValueError("Proposal does not match its measured candidate cell")
        if terrain_progress is not None:
            if terrain_progress.get((cell_x, cell_z)) != distance:
                raise ValueError("Proposal distance differs from recalculated terrain distance")
        else:
            nearest = min(nodes, key=lambda n: (math.hypot(cell_x + 8 - n["x"], cell_z + 8 - n["z"]),
                                               n["distanceMeters"], n["z"], n["x"]))
            if nearest["distanceMeters"] != distance:
                raise ValueError("Proposal distance differs from measured road nodes")
        cx, cz = x + .5, z + .5
        if not (min_x + 64 <= cx <= max_x - 64 and min_z + 64 <= cz <= max_z - 64):
            raise ValueError("Spawn circle exceeds scanned bounds")
        if math.hypot(cx - spawn["x"], cz - spawn["z"]) <= 112:
            raise ValueError("Spawn circle overlaps the 48m protected start")
        road_distance = min(math.hypot(cx - n["x"], cz - n["z"]) for n in nodes)
        if road_distance > min(road["candidateCenterMeters"], road["corridorMeters"] - 64) + 1e-7:
            raise ValueError("Spawn circle exceeds the registered road corridor")
        master, master_path = spawners[proposal["spawnerId"]]
        if master.get("radiusMeters") != 64 or proposal.get("radiusMeters") != 64:
            raise ValueError("Master radius differs from reviewed radius")
        if master.get("spawnIntervalTicks", 0) <= 0 or not 0 < master.get("spawnChancePercent", 0) <= 100:
            raise ValueError("Spawn timing must be explicitly defined")
        limits = master.get("spawnLimit", {})
        if any(limits.get(k, 0) <= 0 for k in ("maxAlivePerSpawner", "maxNearbyMobs", "spawnPerPlayer")):
            raise ValueError("Spawn limits must be explicitly defined")
        expected, actual = [], []
        for entry in master["spawnMobs"]:
            mob_id, level = entry["mobId"].removeprefix("mob:"), entry["level"]
            mob, mob_path = mobs[mob_id]
            levels = {p["level"] for p in mob["levels"]} if mob.get("levels") else {mob["level"]}
            if (mob.get("category") != "ENEMY" or type(level) is not int or
                    not 15 <= level <= 45 or level not in levels or level != proposal["level"] or entry["weight"] <= 0):
                raise ValueError(f"Unresolved or inappropriate level: {mob_id} Lv{level}")
            expected.append((entry["mobId"], level, entry["weight"]))
            files.add(mob_path)
        actual = [(e["mobId"], e["level"], e["weight"]) for e in proposal["spawnMobs"]]
        if sorted(actual) != sorted(expected) or not actual:
            raise ValueError("Preview mob composition no longer matches its master")
        for index in proposal["coveredCellIndices"]:
            if type(index) is not int or not 0 <= index < len(cells) or cells[index].get("reasonCode") == "reserved_future":
                raise ValueError("Proposal covers a future or invalid cell")
        files.add(master_path)
        placements.append({"id": master["id"], "world": world_name, "x": x, "y": y, "z": z})
    if not placements:
        raise ValueError("Empty registration is not permitted")
    return placements, files


def prepare(plan_directory: Path, filebase: Path, output: Path, world_name: str,
            source_world_name: str | None = None) -> dict:
    """Write a new staging directory; never mutate servers or shared master services."""
    manifest = read_json(plan_directory / "manifest.json")
    plan = read_json(plan_directory / "proposals-64.json")
    cell_document = read_json(plan_directory / "cells.json")
    if any(cell_document.get(k) != manifest.get(k) for k in
           ("schemaVersion", "worldId", "terrainFingerprint", "exportRevision")):
        raise ValueError("Cell identity mismatch")
    spawners = catalog(filebase / "41.features.mob.spawner")
    mobs = catalog(filebase / "40.features.mob" / "enemy")
    placements, sources = validate_plan(manifest, plan, cell_document["cells"], spawners, mobs, world_name, source_world_name)
    if output.exists():
        raise ValueError("Use a new output directory; existing registrations are never overwritten")
    output.mkdir(parents=True)
    placement_path = output / "mob_spawners.yml"
    placement_path.write_text(yaml.safe_dump({"spawners": placements}, allow_unicode=True, sort_keys=False), encoding="utf-8")
    masters = []
    for source in sorted(sources):
        relative = source.relative_to(filebase)
        destination = output / "filebase" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        masters.append({"path": relative.as_posix(), "sha256": digest(destination)})
    (output / "filebase" / "manifest.yml").write_text(
        yaml.safe_dump({"schemaVersion": 1, "files": masters}, allow_unicode=True, sort_keys=False), encoding="utf-8")
    receipt = {"schemaVersion": 1, "runtimeApplied": False, "sharedMasterPublished": False,
               "worldId": manifest["worldId"], "worldName": world_name,
               "sourceWorldName": source_world_name or world_name,
               "exportRevision": manifest["exportRevision"], "terrainFingerprint": manifest["terrainFingerprint"],
               "radiusMeters": 64, "maxLevel": max(p["level"] for p in plan["proposals"]),
               "levelCap": 45, "levelStepMeters": manifest["progressionPolicy"]["levelStepMeters"],
               "placementCount": len(placements),
               "placementsSha256": digest(placement_path), "masters": masters}
    (output / "registration.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan-directory", type=Path, required=True)
    parser.add_argument("--filebase", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--world-name", required=True, help="Exact Bukkit world name; no server destination is inferred")
    parser.add_argument("--source-world-name", help="Explicit preview world alias when the runtime rename is held; verify world identity before deployment")
    args = parser.parse_args()
    result = prepare(args.plan_directory.resolve(), args.filebase.resolve(), args.output.resolve(), args.world_name, args.source_world_name)
    print(json.dumps({"prepared": result["placementCount"], "runtimeApplied": False}))
