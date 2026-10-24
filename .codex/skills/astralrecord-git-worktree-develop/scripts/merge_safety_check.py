#!/usr/bin/env python3
"""Reject checkout/merge paths that overlap existing uncommitted changes."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path


def git_paths(repo: Path, args: list[str]) -> list[str]:
    result = subprocess.run(
        ["git", *args], cwd=repo, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    return [os.fsdecode(path) for path in result.stdout.split(b"\0") if path]


def local_change_paths(repo: Path) -> set[str]:
    entries = iter(git_paths(repo, ["status", "--porcelain=v1", "-z", "-uall", "--ignored=traditional"]))
    paths: set[str] = set()
    for entry in entries:
        state, path = entry[:2], entry[3:]
        if "U" in state or state in {"AA", "DD"}:
            raise RuntimeError(f"unresolved conflict: {path}")
        paths.add(path)
        if "R" in state or "C" in state:
            paths.add(next(entries))
    return paths


def overlap(left: str, right: str) -> bool:
    left = os.path.normcase(left).replace("\\", "/").rstrip("/")
    right = os.path.normcase(right).replace("\\", "/").rstrip("/")
    return left == right or left.startswith(right + "/") or right.startswith(left + "/")


def ensure_merge_safe(repo: Path, target: str) -> None:
    local = local_change_paths(repo)
    changed = git_paths(
        repo, ["diff", "--no-ext-diff", "--no-renames", "--name-only", "-z", "HEAD", target, "--"]
    )
    conflicts = sorted(path for path in local if any(overlap(path, other) for other in changed))
    if conflicts:
        raise RuntimeError("uncommitted changes overlap target paths: " + ", ".join(conflicts))


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: merge_safety_check.py <repo> <target>", file=sys.stderr)
        return 2
    try:
        ensure_merge_safe(Path(sys.argv[1]).resolve(), sys.argv[2])
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print("merge path check: successful (unrelated changes may remain)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
