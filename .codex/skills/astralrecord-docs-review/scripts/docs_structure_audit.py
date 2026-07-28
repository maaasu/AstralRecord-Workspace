#!/usr/bin/env python3
"""Audit AstralRecord Plugin design-doc structure without reading source code."""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


PLUGIN_DOC_ROOT_NAME = "10_Plugin設計書"
FEATURE_PATTERN = re.compile(r"^(?P<number>\d{2})-[a-z0-9-]+$")
DOC_PATTERN = re.compile(
    r"^(?P<number>\d{2})_(?P<category>\d+)-(?P<title>[^\[\]\s]+)\.md$"
)
CATEGORY_PATTERN = re.compile(r"^(?P<category>\d+)-[^\[\]\s]+$")
ALLOWED_CATEGORIES = {"0", "1", "2", "3", "4", "5", "6", "8", "9"}
OLD_DOC_PATTERN = re.compile(r"^\d{2}_\d+\.\d{2}-.+\.md$")
README_PATTERN = re.compile(r"^\d{2}_README\.md$")
EMPTY_UNRESOLVED_PATTERN = re.compile(
    r"(?:現在|現時点で).{0,20}未決事項.{0,10}(?:は)?(?:ない|なし)", re.DOTALL
)


@dataclass
class Finding:
    severity: str
    path: Path
    line: int | None
    message: str


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def rel(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def find_plugin_root(path: Path) -> Path | None:
    current = path if path.is_dir() else path.parent
    for candidate in [current, *current.parents]:
        if candidate.name == PLUGIN_DOC_ROOT_NAME:
            return candidate
    return None


def find_target_feature(path: Path, plugin_root: Path) -> Path | None:
    current = path if path.is_dir() else path.parent
    feature_root = plugin_root / "feature"
    for candidate in [current, *current.parents]:
        if candidate.parent == feature_root:
            return candidate
        if candidate in {feature_root, plugin_root}:
            return None
    return None


def add(
    findings: list[Finding],
    severity: str,
    path: Path,
    line: int | None,
    message: str,
) -> None:
    findings.append(Finding(severity, path, line, message))


def audit_document(
    path: Path,
    feature_dir: Path,
    feature_number: str,
    findings: list[Finding],
) -> None:
    text = read_text(path)
    if README_PATTERN.fullmatch(path.name):
        add(findings, "ERROR", path, None, "feature READMEは廃止されています。概要へ統合してください。")
        return
    if OLD_DOC_PATTERN.fullmatch(path.name):
        add(findings, "ERROR", path, None, "旧詳細番号形式のファイル名です。詳細番号を除去してください。")

    match = DOC_PATTERN.fullmatch(path.name)
    if match is None:
        add(
            findings,
            "ERROR",
            path,
            None,
            f"ファイル名を `{feature_number}_<カテゴリ番号>-<名称>.md` 形式にしてください。",
        )
        return
    if match.group("number") != feature_number:
        add(findings, "ERROR", path, None, "feature番号とファイル名の番号が一致しません。")

    if text.splitlines()[:1] != [f"# {path.stem}"]:
        add(findings, "ERROR", path, 1, "H1がファイル名と一致しません。")

    category = match.group("category")
    if category not in ALLOWED_CATEGORIES:
        add(findings, "ERROR", path, None, "未定義のカテゴリ番号です。")
    if path.parent != feature_dir:
        if path.parent.parent != feature_dir:
            add(findings, "ERROR", path, None, "カテゴリディレクトリが多段化されています。")
        category_match = CATEGORY_PATTERN.fullmatch(path.parent.name)
        if category_match is None or category_match.group("category") != category:
            add(findings, "ERROR", path, None, "カテゴリ番号と配置ディレクトリが一致しません。")

    if category == "9" and EMPTY_UNRESOLVED_PATTERN.search(text):
        add(findings, "WARN", path, None, "未決事項がない文書は削除してください。")


def audit_plugin_docs(target: Path, findings: list[Finding]) -> Path:
    plugin_root = find_plugin_root(target)
    if plugin_root is None:
        raise SystemExit(f"Target is not under {PLUGIN_DOC_ROOT_NAME}: {target}")

    root_readme = plugin_root / "README.md"
    if not root_readme.exists():
        add(findings, "ERROR", root_readme, None, "プラグイン設計書ルートのREADME.mdがありません。")

    feature_root = plugin_root / "feature"
    if not feature_root.exists():
        add(findings, "ERROR", feature_root, None, "`feature`ディレクトリがありません。")
        return plugin_root

    target_feature = find_target_feature(target, plugin_root)
    feature_dirs = (
        [target_feature]
        if target_feature
        else sorted(path for path in feature_root.iterdir() if path.is_dir())
    )

    files_by_stem: dict[str, list[Path]] = defaultdict(list)
    for markdown in plugin_root.rglob("*.md"):
        files_by_stem[markdown.stem].append(markdown)
    for stem, paths in sorted(files_by_stem.items()):
        if len(paths) > 1:
            add(
                findings,
                "ERROR",
                paths[0],
                None,
                f"設計書ツリー内でファイル名 `{stem}` が重複しています。",
            )

    for feature_dir in feature_dirs:
        if feature_dir is None:
            continue
        feature_match = FEATURE_PATTERN.fullmatch(feature_dir.name)
        if feature_match is None:
            add(findings, "ERROR", feature_dir, None, "featureディレクトリ名が不正です。")
            continue

        feature_number = feature_match.group("number")
        markdown_files = sorted(feature_dir.rglob("*.md"))
        overviews = [
            path for path in markdown_files if path.name == f"{feature_number}_0-概要.md"
        ]
        if len(overviews) != 1:
            add(
                findings,
                "ERROR",
                feature_dir,
                None,
                f"入口概要 `{feature_number}_0-概要.md` は1件必須です。",
            )
        elif overviews[0].parent != feature_dir:
            add(findings, "ERROR", overviews[0], None, "入口概要はfeature直下へ配置してください。")

        for child in sorted(feature_dir.iterdir()):
            if not child.is_dir():
                continue
            category_match = CATEGORY_PATTERN.fullmatch(child.name)
            if category_match is None:
                add(findings, "ERROR", child, None, "カテゴリディレクトリ名が不正です。")
                continue
            if category_match.group("category") not in ALLOWED_CATEGORIES:
                add(findings, "ERROR", child, None, "未定義のカテゴリディレクトリです。")
            direct_markdown = list(child.glob("*.md"))
            if len(direct_markdown) < 2:
                add(
                    findings,
                    "ERROR",
                    child,
                    None,
                    "Markdownが1件以下のカテゴリはfeature直下へ平坦化してください。",
                )
            if any(path.parent != child for path in child.rglob("*.md")):
                add(findings, "ERROR", child, None, "カテゴリディレクトリを多段化しないでください。")

        for markdown in markdown_files:
            audit_document(markdown, feature_dir, feature_number, findings)

    return plugin_root


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit AstralRecord design-doc structure.")
    parser.add_argument(
        "target", help="Path to a docs root, feature directory, or Markdown file."
    )
    args = parser.parse_args()

    target = Path(args.target).resolve()
    findings: list[Finding] = []

    if not target.exists():
        raise SystemExit(f"Target does not exist: {target}")

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    root = audit_plugin_docs(target, findings)
    findings.sort(
        key=lambda item: (
            {"ERROR": 0, "WARN": 1, "INFO": 2}.get(item.severity, 9),
            str(item.path),
            item.line or 0,
        )
    )

    print(f"# docs_structure_audit: {target}")
    print(f"Scope root: {root}")
    print(f"Findings: {len(findings)}")
    print()

    if not findings:
        print("No structural findings.")
        return 0

    for item in findings:
        location = rel(item.path, root)
        if item.line:
            location += f":{item.line}"
        print(f"- [{item.severity}] {location} - {item.message}")

    return 1 if any(item.severity == "ERROR" for item in findings) else 0


if __name__ == "__main__":
    raise SystemExit(main())
