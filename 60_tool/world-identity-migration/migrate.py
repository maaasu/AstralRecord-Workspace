"""One-time, offline Eriva world and Xaero data migration.

Dry-run is the default. This script never deploys Filebase or seeds the API.
"""

from __future__ import annotations

import argparse
import ctypes
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone


OLD = "vanaheim_supercontinent"
NEW = "eriva_supercontinent"
SERVER_KEY = "Multiplayer_mc.astralrecord.com"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def safe_path(path: Path) -> Path:
    absolute = path.absolute()
    require(absolute.exists(), f"Missing path: {absolute}")
    require(not absolute.is_symlink(), f"Symlink is not allowed: {absolute}")
    return absolute.resolve(strict=True)


def require_tree_without_links(root: Path) -> None:
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            candidate = Path(directory) / name
            require(not candidate.is_symlink() and not candidate.is_junction(),
                    f"Link or junction is not allowed: {candidate}")


def level_name_payload(path: Path) -> tuple[bytes, bytes]:
    data = gzip.decompress(path.read_bytes())
    tag = b"\x08\x00\x09LevelName"
    require(data.count(tag) == 1, f"Unexpected LevelName tag count in {path}")
    start = data.index(tag) + len(tag)
    size = int.from_bytes(data[start : start + 2], "big")
    name = data[start + 2 : start + 2 + size].decode("utf-8")
    expected = f"plugins/AstralRecord/worlds/overworld/{OLD}"
    require(name == expected, f"Unexpected LevelName in {path}: {name}")
    replacement = f"plugins/AstralRecord/worlds/overworld/{NEW}".encode("utf-8")
    changed = data[:start] + len(replacement).to_bytes(2, "big") + replacement + data[start + 2 + size :]
    return data, changed


def write_level_name(path: Path) -> None:
    original, changed = level_name_payload(path)
    require(original != changed, f"No LevelName change in {path}")
    temporary = path.with_name(path.name + ".eriva-tmp")
    require(not temporary.exists(), f"Temporary file already exists: {temporary}")
    try:
        with temporary.open("wb") as stream:
            with gzip.GzipFile(fileobj=stream, mode="wb", mtime=0) as packed:
                packed.write(changed)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def process_check(server_root: Path, client_root: Path) -> None:
    """Fail closed if the Dev port or matching Java process is observed."""
    script = r'''
$ErrorActionPreference = 'Stop'
$port = @(Get-NetTCPConnection -LocalPort 25567 -State Listen -ErrorAction SilentlyContinue).Count
$java = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java(w)?\.exe$' } | ForEach-Object { [pscustomobject]@{ Id=$_.ProcessId; CommandLine=$_.CommandLine } })
[pscustomobject]@{ PortListening=($port -gt 0); Java=$java } | ConvertTo-Json -Compress -Depth 5
'''
    result = subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, check=True,
    )
    state = json.loads(result.stdout)
    require(not state["PortListening"], "Dev port 25567 is listening")
    java = state["Java"]
    if isinstance(java, dict):
        java = [java]
    server_hint = str(server_root).casefold()
    client_hint = str(client_root).casefold()
    for process in java or []:
        command = process.get("CommandLine")
        require(command, f"Cannot inspect Java process {process.get('Id')}; stop or identify it first")
        lowered = command.casefold()
        require(server_hint not in lowered and client_hint not in lowered,
                f"Matching Java process is still running: PID {process.get('Id')}")


def lock_probe(path: Path) -> None:
    """Request an exclusive Windows file handle and release it immediately."""
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32,
                                   ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32,
                                   ctypes.c_void_p]
    kernel.CreateFileW.restype = ctypes.c_void_p
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel.CloseHandle.restype = ctypes.c_int
    handle = kernel.CreateFileW(str(path), 0x80000000, 0, None, 3, 0x80, None)
    require(handle != ctypes.c_void_p(-1).value,
            f"File is locked or inaccessible: {path} (Win32 {ctypes.get_last_error()})")
    kernel.CloseHandle(ctypes.c_void_p(handle))


def tree_size(root: Path) -> int:
    return sum((Path(directory) / filename).stat().st_size
               for directory, _, files in os.walk(root) for filename in files)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-root", required=True, type=Path)
    parser.add_argument("--client-root", required=True, type=Path)
    parser.add_argument("--backup-root", required=True, type=Path)
    parser.add_argument("--apply", action="store_true", help="perform the migration after all checks")
    args = parser.parse_args()

    server = safe_path(args.server_root)
    client = safe_path(args.client_root)
    require(server.name == "10_Dev", "Server root must be the 10_Dev directory")
    require(client.name == "instance" and (client / "mods").is_dir(),
            "Client root must be a Minecraft instance directory")
    backup_parent = args.backup_root.absolute()
    require(backup_parent.exists() and backup_parent.is_dir(), "Backup root must already exist")
    backup_parent = safe_path(backup_parent)
    require(backup_parent != server and backup_parent != client, "Backup root overlaps a live root")
    require(not backup_parent.is_relative_to(server) and not backup_parent.is_relative_to(client),
            "Backup root must be outside live roots")

    worlds = server / "plugins" / "AstralRecord" / "worlds" / "overworld"
    old_world, new_world = worlds / OLD, worlds / NEW
    xaero = client / "xaero"
    minimap_server = xaero / "minimap" / SERVER_KEY
    worldmap_server = xaero / "world-map" / SERVER_KEY
    old_mini = minimap_server / f"dim%minecraft$plugins%astralrecord%worlds%overworld%{OLD}"
    new_mini = minimap_server / f"dim%minecraft$plugins%astralrecord%worlds%overworld%{NEW}"
    old_map = worldmap_server / f"minecraft$plugins%astralrecord%worlds%overworld%{OLD}"
    new_map = worldmap_server / f"minecraft$plugins%astralrecord%worlds%overworld%{NEW}"
    config = minimap_server / "config.txt"
    old_line = f"dimensionType:minecraft$plugins/astralrecord/worlds/overworld/{OLD}:minecraft$overworld"
    new_line = f"dimensionType:minecraft$plugins/astralrecord/worlds/overworld/{NEW}:minecraft$overworld"

    for source in (old_world, old_mini, old_map, config):
        require(source.exists(), f"Missing source: {source}")
        require(not source.is_symlink() and not source.is_junction(),
                f"Link or junction is not allowed: {source}")
    for target in (new_world, new_mini, new_map):
        require(not target.exists(), f"Destination collision: {target}")
    for tree in (old_world, old_mini, old_map):
        require_tree_without_links(tree)
    for filename in ("level.dat", "level.dat_old", "uid.dat", "xaeromap.txt"):
        require((old_world / filename).is_file(), f"Missing world identity file: {filename}")
    for filename in ("level.dat", "level.dat_old"):
        level_name_payload(old_world / filename)
    xaero_id = (old_world / "xaeromap.txt").read_text(encoding="utf-8").strip()
    require(xaero_id.startswith("id:") and xaero_id[3:].isdigit(), "Unexpected xaeromap.txt")
    config_bytes = config.read_bytes()
    old_bytes, new_bytes = old_line.encode(), new_line.encode()
    require(config_bytes.count(old_bytes) == 1 and new_bytes not in config_bytes,
            "Expected exactly one old Xaero dimensionType and no new one")
    require((old_map / "mw$default").is_dir(), "Expected Xaero map content is absent")
    sample_map = next((old_map / "mw$default").rglob("*.zip"), None)
    require(sample_map is not None, "Expected Xaero map ZIP is absent")

    print("Mode:", "apply" if args.apply else "dry-run")
    for before, after in ((old_world, new_world), (old_mini, new_mini), (old_map, new_map)):
        print("Rename:", before, "->", after)
    print("Update: level.dat and level.dat_old LevelName; Xaero minimap config dimensionType")
    print("Preserve: uid.dat, xaeromap.txt ID, Xaero map ZIP contents")
    print("Backup parent:", backup_parent)
    if not args.apply:
        return 0

    process_check(server, client)
    for path in (old_world / "session.lock", old_world / "level.dat", old_world / "level.dat_old", config, sample_map):
        lock_probe(path)
    free = shutil.disk_usage(backup_parent).free
    needed = sum(tree_size(tree) for tree in (old_world, old_mini, old_map)) + config.stat().st_size
    require(free > needed * 1.1, f"Insufficient backup space: need at least {needed * 1.1:.0f} bytes")
    backup = backup_parent / ("eriva-world-identity-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    require(not backup.exists(), f"Backup collision: {backup}")
    backup.mkdir()
    moved: list[tuple[Path, Path]] = []
    try:
        shutil.copytree(old_world, backup / "world")
        shutil.copytree(old_mini, backup / "minimap")
        shutil.copytree(old_map, backup / "world-map")
        shutil.copy2(config, backup / "minimap-config.txt")
        require(tree_size(backup / "world") == tree_size(old_world), "World backup size mismatch")
        require(tree_size(backup / "minimap") == tree_size(old_mini), "Minimap backup size mismatch")
        require(tree_size(backup / "world-map") == tree_size(old_map), "World map backup size mismatch")
        manifest = {"old": OLD, "new": NEW, "server": str(server), "client": str(client),
                    "xaeromapId": xaero_id, "status": "backed-up"}
        (backup / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        process_check(server, client)
        for before, after in ((old_world, new_world), (old_mini, new_mini), (old_map, new_map)):
            before.rename(after)
            moved.append((before, after))
        for filename in ("level.dat", "level.dat_old"):
            write_level_name(new_world / filename)
        config_temp = config.with_name("config.eriva-tmp")
        require(not config_temp.exists(), f"Temporary file already exists: {config_temp}")
        with config_temp.open("wb") as stream:
            stream.write(config_bytes.replace(old_bytes, new_bytes))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(config_temp, config)
        require((new_world / "uid.dat").read_bytes() == (backup / "world" / "uid.dat").read_bytes(),
                "World UID changed unexpectedly")
        require((new_world / "xaeromap.txt").read_bytes() == (backup / "world" / "xaeromap.txt").read_bytes(),
                "Xaero world ID changed unexpectedly")
        manifest["status"] = "complete"
        (backup / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print("Migration complete. Backup:", backup)
        return 0
    except Exception:
        print("Migration interrupted; retained backup:", backup, file=sys.stderr)
        raise


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
