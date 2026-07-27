#!/usr/bin/env python3
"""AstralRecord Plugin 設計書の構造と参照を検証する。"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path


DOCS_ROOT = Path(__file__).resolve().parents[1]
FEATURE_ROOT = DOCS_ROOT / "feature"
FEATURE_CATALOG = DOCS_ROOT / "FEATURE_CATALOG.md"
REPOSITORY_ROOT = DOCS_ROOT.parents[1]
PLUGIN_FEATURE_ROOT = (
    REPOSITORY_ROOT
    / "10_plugin"
    / "AstralRecord"
    / "src"
    / "main"
    / "java"
    / "io"
    / "github"
    / "maaasu"
    / "astralRecord"
    / "feature"
)
FEATURE_DIR_PATTERN = re.compile(r"^(?P<number>\d{2})-(?P<slug>[a-z0-9-]+)$")
DETAIL_FILE_PATTERN = re.compile(
    r"^(?P<number>\d{2})_(?P<category>\d+)\.(?P<detail>\d{2})-(?P<title>.+)\.md$"
)
WIKI_LINK_PATTERN = re.compile(r"\[\[([^\]]+)\]\]")
PLUGIN_DOC_TARGET_PATTERN = re.compile(r"^\d{2}_(?:README|\d+\.\d{2}-.+)$")
FLOW_HEADING_PATTERN = re.compile(r"^## \d+\. .+$", re.MULTILINE)
CHANGELOG_HEADING_PATTERN = re.compile(
    r"^#{2,6}\s+(?:追記|20\d{2}-\d{2}(?:-\d{2})?(?:\s|$))", re.MULTILINE
)
REQUIRED_README_HEADINGS = (
    "## 対象実装パス",
    "## ドキュメント一覧（推奨順）",
    "## 依存 feature",
    "## 更新ルール（変更時に必ず更新する章）",
)
TARGET_PATH_PREFIX = "10_plugin/AstralRecord/"
TARGET_PATH_PATTERN = re.compile(r"`([^`\r\n]+)`")
FEATURE_PACKAGE_PATTERN = re.compile(
    r"^10_plugin/AstralRecord/src/main/(?:java|kotlin)/"
    r"io/github/maaasu/astralRecord/feature/(?P<package>[a-z0-9]+)(?:/|$)"
)
CATALOG_README_PATTERN = re.compile(r"\[\[(\d{2})_README(?:\|[^\]]+)?\]\]")
CATALOG_PACKAGE_PATTERN = re.compile(r"`feature/([a-z0-9]+)`")


def read_text(path: Path) -> str:
    """UTF-8 BOM の有無を許容してテキストを読む。"""

    return path.read_text(encoding="utf-8-sig")


def relative(path: Path) -> str:
    """検証結果向けに docs root からの相対パスを返す。"""

    return path.relative_to(DOCS_ROOT).as_posix()


def wiki_target(raw_target: str) -> str:
    """Wikiリンクからファイル名部分だけを取り出す。"""

    target = raw_target.split("|", 1)[0].split("#", 1)[0].strip()
    return Path(target).name


def validate() -> list[str]:
    """設計書を検証し、エラー一覧を返す。"""

    errors: list[str] = []
    markdown_files = sorted(DOCS_ROOT.rglob("*.md"))
    feature_dirs = sorted(path for path in FEATURE_ROOT.iterdir() if path.is_dir())

    feature_numbers: dict[str, Path] = {}
    parsed_features: list[tuple[Path, str]] = []
    for feature_dir in feature_dirs:
        match = FEATURE_DIR_PATTERN.fullmatch(feature_dir.name)
        if match is None:
            errors.append(f"不正な feature ディレクトリ名: {relative(feature_dir)}")
            continue
        number = match.group("number")
        if number in feature_numbers:
            errors.append(
                "feature 採番重複: "
                f"{number} ({relative(feature_numbers[number])}, {relative(feature_dir)})"
            )
        else:
            feature_numbers[number] = feature_dir
        parsed_features.append((feature_dir, number))

    if feature_numbers:
        highest_number = max(int(number) for number in feature_numbers)
        for value in range(1, highest_number + 1):
            number = f"{value:02d}"
            if number not in feature_numbers:
                errors.append(f"feature 採番欠番: {number}")

    files_by_stem: dict[str, list[Path]] = defaultdict(list)
    for markdown_file in markdown_files:
        files_by_stem[markdown_file.stem].append(markdown_file)
    for stem, paths in sorted(files_by_stem.items()):
        if len(paths) > 1:
            errors.append(
                f"Markdown ファイル名重複: {stem} "
                f"({', '.join(relative(path) for path in paths)})"
            )

    covered_source_packages: set[str] = set()
    for feature_dir, number in parsed_features:
        readme = feature_dir / f"{number}_README.md"
        if not readme.is_file():
            errors.append(f"feature README 不足: {relative(readme)}")
            continue

        readme_content = read_text(readme)
        if readme_content.splitlines()[:1] != [f"# {number}_README"]:
            errors.append(f"README H1 不一致: {relative(readme)}")

        previous_index = -1
        for heading in REQUIRED_README_HEADINGS:
            index = readme_content.find(heading)
            if index < 0:
                errors.append(f"README 必須章不足: {relative(readme)} -> {heading}")
            elif index <= previous_index:
                errors.append(f"README 必須章順序不正: {relative(readme)} -> {heading}")
            else:
                previous_index = index

        target_section_start = readme_content.find("## 対象実装パス")
        target_section_end = readme_content.find("\n## ", target_section_start + 1)
        if target_section_start >= 0:
            if target_section_end < 0:
                target_section_end = len(readme_content)
            target_section = readme_content[target_section_start:target_section_end]
            target_paths = [
                target
                for target in TARGET_PATH_PATTERN.findall(target_section)
                if "/" in target
            ]
            if not target_paths:
                errors.append(f"README 対象実装パス不足: {relative(readme)}")
            for target_path in target_paths:
                normalized_target = target_path.replace("\\", "/")
                if not normalized_target.startswith(TARGET_PATH_PREFIX):
                    errors.append(
                        f"README 対象実装パスがリポジトリ相対でない: "
                        f"{relative(readme)} -> {target_path}"
                    )
                    continue
                path_without_glob = normalized_target.split("*", 1)[0].rstrip("/")
                if not (REPOSITORY_ROOT / path_without_glob).exists():
                    errors.append(
                        f"README 対象実装パス不存在: "
                        f"{relative(readme)} -> {target_path}"
                    )
                package_match = FEATURE_PACKAGE_PATTERN.match(normalized_target)
                if package_match is not None:
                    covered_source_packages.add(package_match.group("package"))

        feature_markdown = sorted(feature_dir.rglob("*.md"))
        expected_stems: set[str] = set()
        for markdown_file in feature_markdown:
            content = read_text(markdown_file)
            if markdown_file == readme:
                continue

            expected_stems.add(markdown_file.stem)
            if not markdown_file.name.startswith(f"{number}_"):
                errors.append(f"feature 番号とファイル名不一致: {relative(markdown_file)}")
            if content.splitlines()[:1] != [f"# {markdown_file.stem}"]:
                errors.append(f"H1 とファイル名不一致: {relative(markdown_file)}")

            detail_match = DETAIL_FILE_PATTERN.fullmatch(markdown_file.name)
            if detail_match is None:
                errors.append(f"詳細ファイル名形式不正: {relative(markdown_file)}")
            else:
                category_match = re.match(r"^(\d+)-", markdown_file.parent.name)
                if category_match is None:
                    errors.append(f"カテゴリディレクトリ名不正: {relative(markdown_file.parent)}")
                elif category_match.group(1) != detail_match.group("category"):
                    errors.append(f"カテゴリ番号不一致: {relative(markdown_file)}")

            if markdown_file.parent.name.startswith("4-"):
                if "```mermaid" not in content:
                    errors.append(f"統合フロー Mermaid 不足: {relative(markdown_file)}")
                if FLOW_HEADING_PATTERN.search(content) is None:
                    errors.append(f"統合フロー連番見出し不足: {relative(markdown_file)}")

            if CHANGELOG_HEADING_PATTERN.search(content) is not None:
                errors.append(f"時系列追記見出し: {relative(markdown_file)}")

        listed_stems = {
            wiki_target(raw_target)
            for raw_target in WIKI_LINK_PATTERN.findall(readme_content)
            if wiki_target(raw_target).startswith(f"{number}_")
            and wiki_target(raw_target) != f"{number}_README"
        }
        for missing in sorted(expected_stems - listed_stems):
            errors.append(f"README 目次漏れ: {relative(readme)} -> {missing}")
        for unknown in sorted(listed_stems - expected_stems):
            errors.append(f"README 目次の参照先不存在: {relative(readme)} -> {unknown}")

    for markdown_file in markdown_files:
        content = read_text(markdown_file)
        for raw_target in WIKI_LINK_PATTERN.findall(content):
            target = wiki_target(raw_target)
            if not PLUGIN_DOC_TARGET_PATTERN.fullmatch(target):
                continue
            matches = files_by_stem.get(target, [])
            if len(matches) == 0:
                errors.append(f"Wikiリンク参照先不存在: {relative(markdown_file)} -> {target}")
            elif len(matches) > 1:
                errors.append(f"Wikiリンク参照先重複: {relative(markdown_file)} -> {target}")

    source_packages: set[str] = set()
    if PLUGIN_FEATURE_ROOT.is_dir():
        source_packages = {
            path.name for path in PLUGIN_FEATURE_ROOT.iterdir() if path.is_dir()
        }
        for package in sorted(source_packages - covered_source_packages):
            errors.append(f"source feature package の設計書所有者不足: {package}")
    else:
        errors.append(
            "Plugin feature source root 不存在: "
            f"{PLUGIN_FEATURE_ROOT.relative_to(REPOSITORY_ROOT).as_posix()}"
        )

    if not FEATURE_CATALOG.is_file():
        errors.append(f"feature カタログ不足: {relative(FEATURE_CATALOG)}")
    else:
        catalog_content = read_text(FEATURE_CATALOG)
        catalog_numbers = CATALOG_README_PATTERN.findall(catalog_content)
        expected_numbers = set(feature_numbers)
        for number in sorted(expected_numbers - set(catalog_numbers)):
            errors.append(f"feature カタログの README 漏れ: {number}_README")
        for number in sorted(set(catalog_numbers) - expected_numbers):
            errors.append(f"feature カタログの不存在 README: {number}_README")
        for number in sorted({value for value in catalog_numbers if catalog_numbers.count(value) > 1}):
            errors.append(f"feature カタログの README 重複: {number}_README")

        catalog_packages = CATALOG_PACKAGE_PATTERN.findall(catalog_content)
        for package in sorted(source_packages - set(catalog_packages)):
            errors.append(f"feature カタログの source package 漏れ: {package}")
        for package in sorted(set(catalog_packages) - source_packages):
            errors.append(f"feature カタログの不存在 source package: {package}")
        for package in sorted({value for value in catalog_packages if catalog_packages.count(value) > 1}):
            errors.append(f"feature カタログの source package 重複: {package}")

    return errors


def main() -> int:
    """検証結果を標準出力へ表示する。"""

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    errors = validate()
    if errors:
        print(f"Plugin docs validation failed: {len(errors)} error(s)")
        for error in errors:
            print(f"- {error}")
        return 1

    feature_count = sum(1 for path in FEATURE_ROOT.iterdir() if path.is_dir())
    document_count = sum(1 for _ in DOCS_ROOT.rglob("*.md"))
    print(
        "Plugin docs validation passed: "
        f"{feature_count} feature(s), {document_count} Markdown document(s)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
