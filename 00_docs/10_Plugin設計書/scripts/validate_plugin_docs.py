#!/usr/bin/env python3
"""AstralRecord Plugin設計書の構造、所有関係、参照を検証する。"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote


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
DOC_FILE_PATTERN = re.compile(
    r"^(?P<number>\d{2})_(?P<category>\d+)-(?P<title>[^\[\]\s]+)\.md$"
)
OLD_DOC_FILE_PATTERN = re.compile(r"^\d{2}_\d+\.\d{2}-.+\.md$")
FEATURE_README_PATTERN = re.compile(r"^\d{2}_README\.md$")
CATEGORY_DIR_PATTERN = re.compile(r"^(?P<category>\d+)-(?P<title>[^\[\]\s]+)$")
ALLOWED_CATEGORIES = {"0", "1", "2", "3", "4", "5", "6", "8", "9"}
WIKI_LINK_PATTERN = re.compile(r"\[\[([^\]]+)\]\]")
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]*\]\((?!https?://|mailto:)([^)]+)\)")
PLUGIN_DOC_TARGET_PATTERN = re.compile(
    r"^\d{2}_(?:README|\d+\.\d{2}-.+|\d+-.+)$"
)
CHANGELOG_HEADING_PATTERN = re.compile(
    r"^#{2,6}\s+(?:追記|20\d{2}-\d{2}(?:-\d{2})?(?:\s|$))", re.MULTILINE
)
EMPTY_UNRESOLVED_PATTERN = re.compile(
    r"(?:現在|現時点で).{0,20}未決事項.{0,10}(?:は)?(?:ない|なし)", re.DOTALL
)
CATALOG_OVERVIEW_PATTERN = re.compile(r"\[\[(\d{2})_0-概要(?:\|[^\]]+)?\]\]")
CATALOG_PACKAGE_PATTERN = re.compile(r"`feature/([a-z0-9]+)`")
CATALOG_OWNER_HEADING_PATTERN = re.compile(
    r"^### \[\[(\d{2})_0-概要\|[^\]]+\]\]$", re.MULTILINE
)


def read_text(path: Path) -> str:
    """UTF-8 BOMの有無を許容してテキストを読む。"""

    return path.read_text(encoding="utf-8-sig")


def relative(path: Path) -> str:
    """検証結果向けにdocs rootからの相対パスを返す。"""

    try:
        return path.relative_to(DOCS_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def wiki_target(raw_target: str) -> str:
    """Wikiリンクから拡張子を除いた対象ファイル名を返す。"""

    target = raw_target.split("|", 1)[0].split("#", 1)[0].strip()
    name = Path(target.replace("\\", "/")).name
    return name[:-3] if name.endswith(".md") else name


def markdown_target(raw_target: str) -> str:
    """Markdownリンクからfragment等を除いたパス文字列を返す。"""

    target = raw_target.strip().split("#", 1)[0].split("?", 1)[0]
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    return unquote(target)


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
            errors.append(f"不正なfeatureディレクトリ名: {relative(feature_dir)}")
            continue
        number = match.group("number")
        if number in feature_numbers:
            errors.append(
                "feature採番重複: "
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
                errors.append(f"feature採番欠番: {number}")

    files_by_stem: dict[str, list[Path]] = defaultdict(list)
    for markdown_file in markdown_files:
        files_by_stem[markdown_file.stem].append(markdown_file)
    for stem, paths in sorted(files_by_stem.items()):
        if len(paths) > 1:
            errors.append(
                f"Markdownファイル名重複: {stem} "
                f"({', '.join(relative(path) for path in paths)})"
            )

    for feature_dir, number in parsed_features:
        direct_readmes = [
            path for path in feature_dir.glob("*_README.md") if FEATURE_README_PATTERN.fullmatch(path.name)
        ]
        for readme in direct_readmes:
            errors.append(f"廃止済みfeature README残存: {relative(readme)}")

        feature_markdown = sorted(feature_dir.rglob("*.md"))
        overviews = [path for path in feature_markdown if path.name == f"{number}_0-概要.md"]
        if len(overviews) != 1:
            errors.append(
                f"feature概要は1件必須: {relative(feature_dir)} -> {len(overviews)}件"
            )
        elif overviews[0].parent != feature_dir:
            errors.append(f"feature概要はfeature直下へ配置: {relative(overviews[0])}")

        categories_at_root: set[str] = set()
        categories_in_directories: set[str] = set()

        for child in sorted(feature_dir.iterdir()):
            if not child.is_dir():
                continue
            category_match = CATEGORY_DIR_PATTERN.fullmatch(child.name)
            if category_match is None:
                errors.append(f"カテゴリディレクトリ名不正: {relative(child)}")
                continue
            if category_match.group("category") not in ALLOWED_CATEGORIES:
                errors.append(f"未定義カテゴリディレクトリ: {relative(child)}")

            direct_markdown = sorted(child.glob("*.md"))
            nested_markdown = sorted(path for path in child.rglob("*.md") if path.parent != child)
            if nested_markdown:
                errors.append(f"カテゴリディレクトリの多段化: {relative(child)}")
            if len(direct_markdown) < 2:
                errors.append(
                    f"単一ファイルカテゴリはfeature直下へ配置: {relative(child)} -> "
                    f"{len(direct_markdown)}件"
                )
            if not direct_markdown and not nested_markdown:
                errors.append(f"空カテゴリディレクトリ: {relative(child)}")
            categories_in_directories.add(category_match.group("category"))

        for markdown_file in feature_markdown:
            content = read_text(markdown_file)
            if FEATURE_README_PATTERN.fullmatch(markdown_file.name):
                continue

            if OLD_DOC_FILE_PATTERN.fullmatch(markdown_file.name):
                errors.append(f"旧詳細番号ファイル名残存: {relative(markdown_file)}")

            name_match = DOC_FILE_PATTERN.fullmatch(markdown_file.name)
            if name_match is None:
                errors.append(f"設計書ファイル名形式不正: {relative(markdown_file)}")
                continue
            if name_match.group("number") != number:
                errors.append(f"feature番号とファイル名不一致: {relative(markdown_file)}")

            category = name_match.group("category")
            if category not in ALLOWED_CATEGORIES:
                errors.append(f"未定義カテゴリ番号: {relative(markdown_file)}")
            if markdown_file.parent == feature_dir:
                categories_at_root.add(category)
            else:
                if markdown_file.parent.parent != feature_dir:
                    errors.append(f"設計書配置が深すぎる: {relative(markdown_file)}")
                directory_match = CATEGORY_DIR_PATTERN.fullmatch(markdown_file.parent.name)
                if directory_match is None or directory_match.group("category") != category:
                    errors.append(f"カテゴリ番号とディレクトリ不一致: {relative(markdown_file)}")

            first_line = content.splitlines()[:1]
            if first_line != [f"# {markdown_file.stem}"]:
                errors.append(f"H1とファイル名不一致: {relative(markdown_file)}")

            if CHANGELOG_HEADING_PATTERN.search(content) is not None:
                errors.append(f"時系列追記見出し: {relative(markdown_file)}")

            if category == "9" and EMPTY_UNRESOLVED_PATTERN.search(content):
                errors.append(f"空の未決事項文書: {relative(markdown_file)}")

        for mixed_category in sorted(categories_at_root & categories_in_directories):
            errors.append(
                f"同一カテゴリが直下とディレクトリに分散: "
                f"{relative(feature_dir)} -> {mixed_category}"
            )

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

        for raw_target in MARKDOWN_LINK_PATTERN.findall(content):
            target = markdown_target(raw_target)
            if not target or not target.lower().endswith(".md"):
                continue
            target_path = Path(target)
            if target_path.is_absolute():
                resolved = target_path
            else:
                resolved = (markdown_file.parent / target_path).resolve()
            if not resolved.is_file():
                errors.append(
                    f"Markdownリンク参照先不存在: {relative(markdown_file)} -> {target}"
                )

    source_packages: set[str] = set()
    if PLUGIN_FEATURE_ROOT.is_dir():
        source_packages = {
            path.name for path in PLUGIN_FEATURE_ROOT.iterdir() if path.is_dir()
        }
    else:
        errors.append(
            "Plugin feature source root不存在: "
            f"{PLUGIN_FEATURE_ROOT.relative_to(REPOSITORY_ROOT).as_posix()}"
        )

    if not FEATURE_CATALOG.is_file():
        errors.append(f"featureカタログ不足: {relative(FEATURE_CATALOG)}")
    else:
        catalog_content = read_text(FEATURE_CATALOG)
        primary_catalog = catalog_content.split("## 更新規則", 1)[0]
        catalog_numbers = CATALOG_OVERVIEW_PATTERN.findall(primary_catalog)
        expected_numbers = set(feature_numbers)

        for number in sorted(expected_numbers - set(catalog_numbers)):
            errors.append(f"featureカタログの概要漏れ: {number}_0-概要")
        for number in sorted(set(catalog_numbers) - expected_numbers):
            errors.append(f"featureカタログの不存在概要: {number}_0-概要")
        for number in sorted(
            {value for value in catalog_numbers if catalog_numbers.count(value) > 1}
        ):
            errors.append(f"featureカタログの概要重複: {number}_0-概要")

        catalog_packages = CATALOG_PACKAGE_PATTERN.findall(primary_catalog)
        for package in sorted(source_packages - set(catalog_packages)):
            errors.append(f"featureカタログのsource package漏れ: {package}")
        for package in sorted(set(catalog_packages) - source_packages):
            errors.append(f"featureカタログの不存在source package: {package}")
        for package in sorted(
            {value for value in catalog_packages if catalog_packages.count(value) > 1}
        ):
            errors.append(f"featureカタログのsource package重複: {package}")

        owner_numbers = CATALOG_OWNER_HEADING_PATTERN.findall(catalog_content)
        for number in sorted(expected_numbers - set(owner_numbers)):
            errors.append(f"実装所有パスのfeature漏れ: {number}_0-概要")
        for number in sorted(set(owner_numbers) - expected_numbers):
            errors.append(f"実装所有パスの不存在feature: {number}_0-概要")
        for number in sorted(
            {value for value in owner_numbers if owner_numbers.count(value) > 1}
        ):
            errors.append(f"実装所有パスのfeature重複: {number}_0-概要")

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
