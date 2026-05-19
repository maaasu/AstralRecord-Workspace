#!/usr/bin/env python3
"""Classify git changes for safe AstralRecord develop commits."""

from __future__ import annotations

import argparse
import fnmatch
import subprocess
from dataclasses import dataclass
from pathlib import Path


EXCLUDE_PARTS = {
    "target",
    "build",
    "out",
    "bin",
    "obj",
    ".gradle",
    "node_modules",
    ".idea",
    ".vscode",
    ".vs",
    ".settings",
    ".obsidian",
    ".claude",
}

EXCLUDE_NAMES = {
    ".classpath",
    ".project",
    ".factorypath",
    ".ds_store",
    "thumbs.db",
    "local.settings.json",
    "appsettings.development.json",
    "appsettings.local.json",
}

EXCLUDE_GLOBS = [
    "*.log",
    "*.tmp",
    "*.bak",
    "*.secret.*",
    "*secrets*.json",
    ".env",
    ".env.*",
    "20_api/AstralRecordApi/AstralRecordApi/appsettings.Development.json",
]

ALLOWED_DOT_PREFIXES = (
    ".agents/",
    ".codex/skills/",
    ".github/",
)

ALLOWED_DOT_FILES = {
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
}


@dataclass
class ChangedPath:
    status: str
    path: str
    old_path: str | None = None


def run_git(root: Path, args: list[str]) -> bytes:
    return subprocess.check_output(["git", *args], cwd=root)


def parse_status(raw: bytes) -> list[ChangedPath]:
    entries = raw.decode("utf-8", errors="replace").split("\0")
    entries = [entry for entry in entries if entry]
    result: list[ChangedPath] = []
    index = 0
    while index < len(entries):
        entry = entries[index]
        status = entry[:2]
        path = entry[3:]
        if status[0] in {"R", "C"} or status[1] in {"R", "C"}:
            old_path = entries[index + 1] if index + 1 < len(entries) else None
            result.append(ChangedPath(status=status, path=path, old_path=old_path))
            index += 2
        else:
            result.append(ChangedPath(status=status, path=path))
            index += 1
    return result


def normalize(path: str) -> str:
    return path.replace("\\", "/").strip("/")


def is_allowed_dot_path(path: str) -> bool:
    normalized = normalize(path)
    first = normalized.split("/", 1)[0]
    if first in ALLOWED_DOT_FILES:
        return True
    return any(normalized.startswith(prefix) for prefix in ALLOWED_DOT_PREFIXES)


def classify(path: str) -> tuple[str, str]:
    normalized = normalize(path)
    parts = normalized.split("/")
    lower_parts = [part.lower() for part in parts]
    name = lower_parts[-1]

    if any(part in EXCLUDE_PARTS for part in lower_parts):
        return "EXCLUDE", "local/generated directory"

    if name in EXCLUDE_NAMES:
        return "EXCLUDE", "local IDE or development config"

    for pattern in EXCLUDE_GLOBS:
        if fnmatch.fnmatch(normalized, pattern) or fnmatch.fnmatch(name, pattern.lower()):
            if normalized == ".env.example" or name == ".env.example":
                break
            return "EXCLUDE", f"matches excluded pattern {pattern}"

    if parts and parts[0].startswith(".") and not is_allowed_dot_path(normalized):
        return "REVIEW", "dot-path; commit only if documented shared project config"

    if "appsettings." in name and name.endswith(".json"):
        return "REVIEW", "environment-specific appsettings; verify no secrets"

    return "REVIEW", "inspect diff and include only if related to the requested change"


def main() -> int:
    parser = argparse.ArgumentParser(description="Classify changed files before committing.")
    parser.add_argument("root", nargs="?", default=".", help="Git workspace root or any path inside it.")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    git_root = Path(run_git(root, ["rev-parse", "--show-toplevel"]).decode("utf-8").strip()).resolve()
    branch = run_git(git_root, ["branch", "--show-current"]).decode("utf-8").strip()
    changes = parse_status(run_git(git_root, ["status", "--porcelain=v1", "-uall", "-z"]))

    print(f"# commit_candidate_audit")
    print(f"Root: {git_root}")
    print(f"Branch: {branch}")
    print(f"Changed paths: {len(changes)}")
    print()

    if branch != "develop":
        print("[STOP] Current branch is not develop.")
        print()

    if not changes:
        print("No changes.")
        return 0

    for item in changes:
        decision, reason = classify(item.path)
        old = f" (from {item.old_path})" if item.old_path else ""
        print(f"{decision:7} {item.status} {item.path}{old} - {reason}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
