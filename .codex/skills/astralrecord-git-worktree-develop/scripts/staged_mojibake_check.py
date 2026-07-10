#!/usr/bin/env python3
"""Detect likely mojibake in staged text files."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


SUSPICIOUS_TOKENS = (
    "\ufffd",
    "\u7e67\uff7b\u30fb\uff7d",
    "\u00e3\u0081",
)

SUSPICIOUS_PATTERN = re.compile(r"[\u0080-\u009f\uff61-\uff9f]|\?{4,}")

TEXT_EXTENSIONS = {
    ".md",
    ".txt",
    ".java",
    ".kt",
    ".kts",
    ".yml",
    ".yaml",
    ".json",
    ".properties",
    ".xml",
    ".cs",
    ".ts",
    ".js",
    ".html",
    ".css",
    ".sql",
}


def run_git(root: Path, args: list[str]) -> str:
    return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8")


def staged_paths(root: Path) -> list[str]:
    out = run_git(root, ["diff", "--cached", "--name-only"])
    return [line.strip() for line in out.splitlines() if line.strip()]


def is_text_target(path: str) -> bool:
    return Path(path).suffix.lower() in TEXT_EXTENSIONS


def has_mojibake(path: Path) -> bool:
    try:
        content = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return True
    return any(token in content for token in SUSPICIOUS_TOKENS) or bool(SUSPICIOUS_PATTERN.search(content))


def main() -> int:
    parser = argparse.ArgumentParser(description="Check staged files for mojibake.")
    parser.add_argument("root", nargs="?", default=".", help="Git workspace root")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    git_root = Path(run_git(root, ["rev-parse", "--show-toplevel"]).strip()).resolve()
    targets = [p for p in staged_paths(git_root) if is_text_target(p)]

    problems: list[str] = []
    for rel in targets:
        abs_path = git_root / rel
        if abs_path.exists() and has_mojibake(abs_path):
            problems.append(rel)

    if problems:
        print("Mojibake check failed:")
        for rel in problems:
            print(f"- {rel}")
        return 1

    print("Mojibake check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
