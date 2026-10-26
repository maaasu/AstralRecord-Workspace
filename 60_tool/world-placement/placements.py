"""Export the selected Dev's legacy placements, then explicitly import into Dev API."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

import yaml

NAMESPACE = uuid.UUID("d583fdb9-1602-42ac-b74b-fbf01f404fef")
SOURCES = (("mob_spawners.yml", "spawners", "spawner"),
           ("npc_locations.yml", "npcs", "npc"),
           ("waystones.yml", "waystones", "waystone"))


class UniqueKeyLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"Duplicate YAML key: {key}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def read_yaml(path):
    return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueKeyLoader)


def number(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{field} must be a finite number")
    return float(value)


def text(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be nonempty text")
    return value


def export_legacy(server: Path, filebase: Path, aliases: dict[str, str]):
    worlds = {}
    world_ids = set()
    for path in sorted((filebase / "60.features.world").glob("*.yml")):
        document = read_yaml(path)
        if isinstance(document, dict) and document.get("id") and document.get("baseWorldPath"):
            world_ids.add(document["id"])
            worlds[document["baseWorldPath"]] = document["id"]
    for old_path, world_id in aliases.items():
        if world_id not in world_ids:
            raise ValueError(f"Unknown world alias target: {world_id}")
        worlds[old_path] = world_id
    result, sources, seen = [], [], set()
    root = server / "plugins" / "AstralRecord"
    for filename, collection, kind in SOURCES:
        path = root / filename
        before = path.read_bytes()
        document = read_yaml(path)
        if not isinstance(document, dict) or not isinstance(document.get(collection), list):
            raise ValueError(f"Missing placement list: {filename}")
        for row in document[collection]:
            world_name = text(row.get("worldName" if kind == "waystone" else "world"), "worldName")
            if world_name not in worlds:
                raise ValueError(f"World mapping required; add an explicit alias: {world_name}")
            template = text(row.get("id"), "id")
            coords = [number(row.get(axis), axis) for axis in ("x", "y", "z")]
            identity = json.dumps([kind, world_name, template, *coords], ensure_ascii=False, separators=(",", ":"))
            placement_id = str(uuid.uuid5(NAMESPACE, identity))
            if placement_id in seen:
                raise ValueError(f"Duplicate placement in {filename}: {identity}")
            seen.add(placement_id)
            waypoint = None
            if kind == "waystone":
                locked = row.get("lockEnabled", False)
                if not isinstance(locked, bool):
                    raise ValueError("lockEnabled must be boolean")
                gold = row.get("unlockGold", 0)
                if isinstance(gold, bool) or not isinstance(gold, int) or gold < 0:
                    raise ValueError("unlockGold must be a nonnegative integer")
                waypoint = {
                    "id": template, "name": text(row.get("name"), "name"),
                    "lockEnabled": locked, "unlockGold": gold,
                    "icon": text(row.get("icon", "ENDER_PEARL"), "icon"),
                    "createdAt": text(row.get("createdAt"), "createdAt"),
                    "createdBy": text(row.get("createdBy"), "createdBy"),
                }
            result.append({
                "placementId": placement_id, "kind": kind,
                "worldId": worlds[world_name], "worldName": world_name,
                "templateId": None if kind == "waystone" else template,
                "x": coords[0], "y": coords[1], "z": coords[2],
                "yaw": number(row.get("yaw", 0), "yaw"),
                "pitch": number(row.get("pitch", 0), "pitch"),
                "enabled": True, "waystone": waypoint,
            })
        if path.read_bytes() != before:
            raise ValueError(f"Source changed while exporting: {filename}; stop Dev and retry")
        sources.append({"file": filename, "sha256": hashlib.sha256(before).hexdigest(),
                        "count": len(document[collection])})
    return {"schemaVersion": 1, "sourceServer": str(server.resolve()),
            "sources": sources, "placements": sorted(result, key=lambda row: row["placementId"])}


def request_api(base_url, settings, method="GET", body=None):
    parsed = urllib.parse.urlparse(base_url)
    if parsed.scheme not in ("https", "http") or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Invalid API URL")
    if parsed.scheme == "http" and parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError("Plain HTTP is allowed only on loopback")
    key = text(settings.get("ApiKey", {}).get("Key"), "ApiKey.Key")
    headers = {"X-API-Key": key, "Accept": "application/json"}
    if method != "GET":
        headers["X-World-Placement-Key"] = text(settings.get("WorldPlacements", {}).get("WriteKey"), "WriteKey")
        headers["Content-Type"] = "application/json"
    url = base_url.rstrip("/") + "/api/world-placements/dev"
    data = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Do not expose credentials/request headers or blindly retry a mutation.
        detail = error.read().decode("utf-8", errors="replace")[:2000]
        raise ValueError(f"Placement API HTTP {error.code}: {detail}") from None


def import_dev(export, base_url, settings, expected_revision=None):
    current = request_api(base_url, settings)
    target = sorted(export["placements"], key=lambda row: row["placementId"])
    existing = sorted(current["placements"], key=lambda row: row["placementId"])
    if target == existing:
        return {"changed": False, "revision": current["revision"], "counts": counts(target)}
    if expected_revision is None and existing:
        raise ValueError("Dev already has placements. Re-export/review and pass --expected-revision explicitly.")
    if expected_revision is not None and str(current["revision"]) != expected_revision:
        raise ValueError("Dev revision changed; no data was written")
    updated = request_api(base_url, settings, "PUT", {"expectedRevision": str(current["revision"]), "placements": target})
    actual = sorted(updated["placements"], key=lambda row: row["placementId"])
    if actual != target:
        raise ValueError("API returned different placements; inspect before retrying")
    return {"changed": True, "revision": updated["revision"], "counts": counts(actual)}


def counts(rows):
    return {kind: sum(row["kind"] == kind for row in rows) for _, _, kind in SOURCES}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    export = sub.add_parser("export")
    export.add_argument("--source-server", required=True, type=Path)
    export.add_argument("--filebase", required=True, type=Path)
    export.add_argument("--world-aliases", type=Path)
    export.add_argument("--output", required=True, type=Path)
    load = sub.add_parser("import-dev")
    load.add_argument("--input", required=True, type=Path)
    load.add_argument("--api-url", required=True)
    load.add_argument("--settings", required=True, type=Path)
    load.add_argument("--expected-revision")
    load.add_argument("--receipt", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "export":
        aliases = json.loads(args.world_aliases.read_text(encoding="utf-8-sig")) if args.world_aliases else {}
        data = export_legacy(args.source_server, args.filebase, aliases)
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
        print(json.dumps({"output": str(args.output), "counts": counts(data["placements"])}, ensure_ascii=False))
    else:
        if args.receipt.exists():
            raise ValueError("Receipt already exists; use a new receipt path")
        data = json.loads(args.input.read_text(encoding="utf-8-sig"))
        # Refuse stale exports: initial migration is a stopped-Dev operation.
        root = Path(data["sourceServer"]) / "plugins" / "AstralRecord"
        for source in data["sources"]:
            if source["file"] not in {item[0] for item in SOURCES}:
                raise ValueError("Unexpected source filename")
            if hashlib.sha256((root / source["file"]).read_bytes()).hexdigest() != source["sha256"]:
                raise ValueError("Legacy source changed after export; export again before importing")
        settings = json.loads(args.settings.read_text(encoding="utf-8-sig"))
        result = import_dev(data, args.api_url, settings, args.expected_revision)
        result.update({"sourceServer": data["sourceServer"], "sources": data["sources"], "sharedPublished": False})
        with args.receipt.open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
