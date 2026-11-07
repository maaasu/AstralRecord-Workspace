"""Capture a stopped Dev world and publish a map from its isolated snapshot."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

from continent import ContinentPlanner, REASONS, _atomic_json, load_world_master, planning_terrain_cell, validate_terrain

TOOL_ROOT = Path(__file__).resolve().parent
DEFAULT_FILEBASE = TOOL_ROOT.parents[1] / "40_filebase"
ERIVA_WORLD = "eriva_supercontinent"
BOUNDS_FIELDS = ("minX", "minZ", "width", "height", "cellSize")
TERRAIN_FILENAME = "continent-terrain.json"


def world_id_arg(value: str) -> str:
    if not re.fullmatch(r"[a-z0-9_]+", value) or re.fullmatch(r"con|prn|aux|nul|com[0-9]|lpt[0-9]", value):
        raise argparse.ArgumentTypeError("World ID must be a lowercase Filebase identifier")
    return value


def capture(args: argparse.Namespace) -> None:
    world, _ = load_world_master(args.filebase, args.world_id)
    server = args.dev_server.absolute()
    source = server / (args.source_world if args.source_world is not None else world["baseWorldPath"])
    if not (source / "region").is_dir():
        raise ValueError(f"World has no region directory: {source}")
    powershell = shutil.which("pwsh")
    if not powershell:
        raise ValueError("PowerShell 7 (pwsh) is required for capture")
    subprocess.run(
        [powershell, "-NoProfile", "-File", str(TOOL_ROOT / "prepare-server.ps1"),
         "-SourceServer", str(server), "-SourceWorld", str(source), "-ServerRoot", str(args.snapshot_root.absolute()),
         "-ScannerJar", str(args.scanner_jar.resolve(strict=True)), "-WorldName", args.world_id,
         "-Port", str(args.port), "-Continent"],
        check=True,
    )
    print("Snapshot captured. Dev can restart now.", flush=True)


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def snapshot_source_server(receipt: dict) -> Path:
    if receipt.get("sourceServer"):
        return Path(receipt["sourceServer"]).resolve()
    source_world = Path(receipt["sourceWorld"])
    # Older preparation receipts recorded only the plugin's world path.
    for parent in source_world.parents:
        if parent.name.lower() == "astralrecord" and parent.parent.name.lower() == "plugins":
            return parent.parent.parent.resolve()
        if (parent / "eula.txt").is_file():
            return parent.resolve()
    raise ValueError("Cannot identify the source server from this legacy receipt; capture a new snapshot")


def completed_scan(snapshot: Path, world: Path, receipt: dict, java: str, timeout: int) -> Path:
    terrain = snapshot / "analysis" / TERRAIN_FILENAME
    lock = terrain.with_name(".scan.lock")
    if lock.exists():
        raise ValueError(f"Scan in progress or interrupted: {lock}")
    if terrain.is_file():
        return terrain
    if receipt.get("scanMode") != "continent":
        raise ValueError("Snapshot needs a completed continent scan or a new capture")
    scanner = snapshot / "plugins" / "region-terrain-scanner.jar"
    if sha256(scanner) != receipt["scannerSha256"].lower():
        raise ValueError("Scanner JAR does not match the capture receipt")
    properties = (snapshot / "server.properties").read_text(encoding="utf-8").splitlines()
    if "server-ip=127.0.0.1" not in properties or f"level-name={world.name}" not in properties:
        raise ValueError("Expected loopback configuration for the copied world")
    java_executable = shutil.which(java)
    if not java_executable:
        raise ValueError("Java 21 is required for scanning; set --java to the executable")
    try:
        with lock.open("x", encoding="ascii") as stream:
            stream.write(f"{os.getpid()}\n")
    except FileExistsError:
        raise ValueError(f"Scan in progress or interrupted: {lock}") from None
    try:
        if not terrain.is_file():
            subprocess.run(
                [java_executable, "-Xms1G", "-Xmx3G", "-Dterminal.jline=false", "-Dterminal.ansi=false",
                 "-jar", "server.jar", "nogui"],
                cwd=snapshot, check=True, timeout=timeout, stdin=subprocess.DEVNULL,
            )
        if not terrain.is_file():
            raise ValueError(f"Scanner stopped before completing {terrain}")
        return terrain
    finally:
        lock.unlink()


def export_terrain(output: Path, terrain: dict, world: dict, fingerprint: str, revision: str) -> dict:
    identity = {"schemaVersion": 2, "worldId": world["id"],
                "terrainFingerprint": fingerprint, "exportRevision": revision}
    reasons = {"terrain_only": "地形計測済み", "absent": REASONS["absent"], "failed": REASONS["failed"]}
    cells = []
    for source in terrain["cells"]:
        cell = planning_terrain_cell(source)
        reason = "terrain_only" if cell["status"] == "ok" else cell["status"]
        cell.update(regionId="", targetLevel=None, reasonCode=reason, reason=reasons[reason])
        cells.append(cell)
    _atomic_json(output / "cells.json", {**identity, "cells": cells})
    _atomic_json(output / "regions.json", {**identity, "regions": []})
    manifest = {
        **identity, "mode": "terrain-only", "previewOnly": True, "worldName": world["displayName"],
        "capturedAt": terrain["capturedAt"], "spawn": {key: world["spawnLocation"][key] for key in ("x", "y", "z")},
        "bounds": {key: terrain[key] for key in BOUNDS_FIELDS}, "cellCount": len(cells),
        "files": {"cells": "cells.json", "regions": "regions.json", "proposals": {}},
        "radiusOptions": [], "reasons": reasons, "progressionPolicy": {},
        "limitations": ["保存済みチャンクの地表を表示します。未生成・計測失敗区画は地形情報がありません。"],
    }
    _atomic_json(output / "manifest.json", manifest)
    return manifest


def publish_revision(output: Path, destination: Path, revision: str) -> None:
    final = destination / revision
    if final.resolve() != final:
        raise ValueError(f"Linked revision directory is not supported: {final}")
    try:
        if not final.exists():
            output.rename(final)
    except OSError:
        if not final.is_dir():
            raise
    if output.exists():
        if ({entry.name for entry in output.iterdir()} != {entry.name for entry in final.iterdir()} or
                any(not (final / entry.name).is_file() or sha256(entry) != sha256(final / entry.name)
                    for entry in output.iterdir())):
            raise ValueError(f"Existing revision does not match generated files: {final}") from None

    pointer = destination / "current.txt"
    with tempfile.NamedTemporaryFile(mode="w", encoding="ascii", newline="\n",
                                     dir=destination, prefix=".current-", delete=False) as stream:
        pending = Path(stream.name)
        stream.write(revision + "\n")
    try:
        # On Windows an in-progress reader can briefly prevent replacement.
        for attempt in range(10):
            try:
                os.replace(pending, pointer)
                break
            except PermissionError:
                if attempt == 9:
                    raise
                time.sleep(0.05)
    finally:
        pending.unlink(missing_ok=True)


def publish(args: argparse.Namespace) -> None:
    from terrain_detail import render

    world, world_hash = load_world_master(args.filebase, args.world_id)
    snapshot = args.snapshot_root.resolve(strict=True)
    if (snapshot / ".capture.lock").exists():
        raise ValueError("Snapshot capture is still in progress or was interrupted")
    copied_world = snapshot / args.world_id
    receipt = json.loads((snapshot / "analysis" / "snapshot-receipt.json").read_text(encoding="utf-8-sig"))
    source = snapshot_source_server(receipt)
    if (snapshot.is_relative_to(source) or source.is_relative_to(snapshot) or
            Path(receipt["targetWorld"]) != copied_world or copied_world.resolve(strict=True) != copied_world):
        raise ValueError("Snapshot receipt does not identify this isolated copy")

    destination = args.cache_root.resolve() / "worlds" / args.world_id
    if destination.resolve() != destination:
        raise ValueError("Linked directories inside the cache root are not supported")
    if destination.is_relative_to(snapshot) or destination.is_relative_to(source):
        raise ValueError("Cache must be separate from the source and snapshot servers")
    policy = args.policy
    if policy is None and args.world_id == ERIVA_WORLD and not args.terrain_only:
        policy = TOOL_ROOT / "policies" / "eriva-westroad.json"

    terrain_path = completed_scan(snapshot, copied_world, receipt, args.java, args.scan_timeout_seconds)
    planner = ContinentPlanner(terrain_path, args.filebase, policy) if policy else None
    if planner:
        terrain, fingerprint = planner.terrain, planner.terrain_fingerprint
    else:
        raw = terrain_path.read_bytes()
        terrain, fingerprint = json.loads(raw), hashlib.sha256(raw).hexdigest()
        del raw
    if (terrain.get("schemaVersion") != 2 or terrain.get("world") != args.world_id or
            not isinstance(terrain.get("capturedAt"), str) or not terrain["capturedAt"]):
        raise ValueError("Expected a completed schema 2 scan for the selected world")
    if planner is None:
        validate_terrain(terrain)
    if not any(cell["status"] == "ok" for cell in terrain["cells"]):
        raise ValueError("Scan contains no measured cells")

    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pending-", dir=destination) as temporary:
        output = Path(temporary) / "export"
        detail = render(copied_world, terrain_path, output, require_rendered_chunks=True)
        if detail["terrainFingerprint"] != fingerprint:
            raise ValueError("Terrain changed while rendering the map")
        if (output / "terrain-detail.png").stat().st_size > 128 * 1024 * 1024:
            raise ValueError("Rendered map exceeds the editor's 128 MiB image limit")
        plan_revision = planner.export_revision if planner else world_hash + sha256(TOOL_ROOT / "continent.py")
        revision = hashlib.sha256(
            (sha256(Path(__file__)) + fingerprint + detail["imageSha256"] + plan_revision).encode("ascii")
        ).hexdigest()
        if planner:
            planner.export_revision = revision
            manifest = planner.export(output)
        else:
            manifest = export_terrain(output, terrain, world, fingerprint, revision)
        publish_revision(output, destination, revision)
    print(f"Published {manifest['worldId']} {manifest['exportRevision']} to {destination}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture", help="copy an offline Dev world under its exclusive lock")
    publish_parser = commands.add_parser("publish", help="scan the copy, render and publish its map")
    for command in (capture_parser, publish_parser):
        command.add_argument("--world-id", type=world_id_arg, required=True)
        command.add_argument("--filebase", type=Path, default=DEFAULT_FILEBASE)
        command.add_argument("--snapshot-root", type=Path, required=True)
    capture_parser.add_argument("--dev-server", type=Path, required=True)
    capture_parser.add_argument("--source-world", type=Path, help="override baseWorldPath within --dev-server")
    capture_parser.add_argument("--scanner-jar", type=Path, required=True)
    capture_parser.add_argument("--port", type=int, default=25579)
    publish_parser.add_argument("--cache-root", type=Path, required=True)
    publish_parser.add_argument("--java", default="java")
    publish_parser.add_argument("--scan-timeout-seconds", type=int, default=3600)
    policy_args = publish_parser.add_mutually_exclusive_group()
    policy_args.add_argument("--policy", type=Path, help="override the spawner proposal policy")
    policy_args.add_argument("--terrain-only", action="store_true", help="publish without spawner proposals")
    args = parser.parse_args()
    try:
        (capture if args.command == "capture" else publish)(args)
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"World map {args.command} failed with exit code {error.returncode}. See the output above.\n")
    except (ImportError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, f"World map refresh failed: {error}\n")


if __name__ == "__main__":
    main()
