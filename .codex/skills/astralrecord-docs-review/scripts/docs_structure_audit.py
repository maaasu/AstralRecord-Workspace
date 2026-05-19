#!/usr/bin/env python3
"""Audit AstralRecord Markdown design docs without reading source code."""

from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path


PLUGIN_DOC_ROOT_NAME = "10_プラグイン設計書"


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
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def line_of(text: str, pattern: str) -> int | None:
    regex = re.compile(pattern)
    for index, line in enumerate(text.splitlines(), start=1):
        if regex.search(line):
            return index
    return None


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
        if candidate == feature_root or candidate == plugin_root:
            return None
    return None


def is_method_spec(path: Path) -> bool:
    return "3-メソッド仕様" in path.parts


def add(findings: list[Finding], severity: str, path: Path, line: int | None, message: str) -> None:
    findings.append(Finding(severity, path, line, message))


def audit_feature_readme(path: Path, root: Path, findings: list[Finding]) -> None:
    text = read_text(path)
    required = [
        "対象実装パス",
        "ドキュメント一覧",
        "依存 feature",
        "更新ルール",
    ]
    for heading in required:
        if heading not in text:
            add(findings, "WARN", path, None, f"feature README に必須項目 `{heading}` がありません。")

    markdown_doc_link = re.search(r"\[[^\]]+\]\((?!https?://|#)([^)]+\.md(?:#[^)]+)?)\)", text)
    if markdown_doc_link:
        add(
            findings,
            "WARN",
            path,
            line_of(text, r"\[[^\]]+\]\((?!https?://|#)([^)]+\.md(?:#[^)]+)?)\)"),
            "別ファイル参照に Markdown リンクが使われています。設計書間参照は Wiki リンクを優先してください。",
        )


def audit_markdown_file(path: Path, root: Path, findings: list[Finding]) -> None:
    text = read_text(path)
    if "[" in path.name or "]" in path.name:
        add(findings, "ERROR", path, None, "ファイル名に `[` または `]` が含まれています。")
    if " " in path.name:
        add(findings, "WARN", path, None, "ファイル名に空白が含まれています。区切りは `_` を使用してください。")

    wiki_with_path = re.search(r"\[\[[^\]]*[\\/][^\]]*\]\]", text)
    if wiki_with_path:
        add(
            findings,
            "WARN",
            path,
            line_of(text, r"\[\[[^\]]*[\\/][^\]]*\]\]"),
            "Wiki リンクにパスが含まれています。原則としてファイル名のみで参照してください。",
        )

    markdown_doc_link = re.search(r"\[[^\]]+\]\((?!https?://|#)([^)]+\.md(?:#[^)]+)?)\)", text)
    if markdown_doc_link:
        add(
            findings,
            "WARN",
            path,
            line_of(text, r"\[[^\]]+\]\((?!https?://|#)([^)]+\.md(?:#[^)]+)?)\)"),
            "別ファイル参照に Markdown リンクが使われています。設計書間参照は Wiki リンクを優先してください。",
        )

    if re.search(r"^#+\s*ログ\s*/\s*メッセージ", text, re.MULTILINE):
        add(
            findings,
            "WARN",
            path,
            line_of(text, r"^#+\s*ログ\s*/\s*メッセージ"),
            "`ログ/メッセージ` の独立見出しがあります。ログ・メッセージは出力処理番号の直下に記載してください。",
        )

    if is_method_spec(path) and "LogId." in text and "テンプレート" not in text:
        add(findings, "WARN", path, line_of(text, r"LogId\."), "LogId の記載がありますが、メッセージテンプレートが見当たりません。")


def audit_method_spec(path: Path, root: Path, findings: list[Finding]) -> None:
    text = read_text(path)
    if re.search(r"_3\.00-", path.name):
        return

    if "クラス名" not in text:
        add(findings, "ERROR", path, None, "メソッド仕様に `クラス名` が見当たりません。")
    if "物理名" not in text:
        add(findings, "ERROR", path, None, "メソッド仕様に `物理名` が見当たりません。")

    for index, line in enumerate(text.splitlines(), start=1):
        match = re.match(r"^###\s+(.+)$", line)
        if not match:
            continue
        title = match.group(1).strip()
        if re.search(r"(する|します|した|される|できる|を行う)$", title):
            add(findings, "WARN", path, index, f"メソッド論理名 `{title}` が文章形に見えます。名詞句で記載してください。")


def audit_plugin_docs(target: Path, findings: list[Finding]) -> Path:
    plugin_root = find_plugin_root(target)
    if plugin_root is None:
        raise SystemExit(f"Target is not under {PLUGIN_DOC_ROOT_NAME}: {target}")

    root_readme = plugin_root / "README.md"
    if not root_readme.exists():
        add(findings, "ERROR", root_readme, None, "プラグイン設計書ルートの README.md がありません。")

    feature_root = plugin_root / "feature"
    if not feature_root.exists():
        add(findings, "ERROR", feature_root, None, "`feature` ディレクトリがありません。")
        return plugin_root

    feature_pattern = re.compile(r"^\d{2}-[A-Za-z0-9][A-Za-z0-9-]*$")
    doc_pattern_template = r"^{num}_(\d+)\.(\d{{2}})-[^\[\]\s]+\.md$"

    target_feature = find_target_feature(target, plugin_root)
    feature_dirs = [target_feature] if target_feature else sorted(p for p in feature_root.iterdir() if p.is_dir())

    for feature_dir in feature_dirs:
        if not feature_pattern.match(feature_dir.name):
            add(findings, "WARN", feature_dir, None, "feature ディレクトリ名は `2桁採番-機能名` 形式にしてください。")
            continue

        feature_num = feature_dir.name[:2]
        readme = feature_dir / f"{feature_num}_README.md"
        if not readme.exists():
            add(findings, "ERROR", readme, None, f"feature README `{feature_num}_README.md` がありません。")
        else:
            audit_feature_readme(readme, plugin_root, findings)

        doc_pattern = re.compile(doc_pattern_template.format(num=re.escape(feature_num)))
        for child in sorted(feature_dir.iterdir()):
            if child.is_dir() and not re.match(r"^\d+-", child.name):
                add(findings, "WARN", child, None, "カテゴリディレクトリ名は `<カテゴリ番号>-<名称>` 形式にしてください。")

        for md in sorted(feature_dir.rglob("*.md")):
            if md == readme:
                continue
            audit_markdown_file(md, plugin_root, findings)
            if not doc_pattern.match(md.name):
                add(findings, "WARN", md, None, f"Markdown ファイル名が `{feature_num}_<カテゴリ番号>.<詳細番号>-<名称>.md` 形式ではありません。")
            if is_method_spec(md):
                audit_method_spec(md, plugin_root, findings)

    return plugin_root


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit AstralRecord design-doc structure.")
    parser.add_argument("target", help="Absolute path to a docs root, feature directory, or Markdown file.")
    args = parser.parse_args()

    target = Path(args.target).resolve()
    findings: list[Finding] = []

    if not target.exists():
        raise SystemExit(f"Target does not exist: {target}")

    root = audit_plugin_docs(target, findings)
    findings.sort(key=lambda item: ({"ERROR": 0, "WARN": 1, "INFO": 2}.get(item.severity, 9), str(item.path), item.line or 0))

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
