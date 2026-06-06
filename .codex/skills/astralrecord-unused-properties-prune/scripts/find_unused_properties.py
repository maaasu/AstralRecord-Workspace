#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


KEY_PATTERN = re.compile(r"([A-Z]+_\d+)\s*\(")
PROPERTY_LINE_PATTERN = re.compile(r"^\s*([^#!\s][^=:\s]*)\s*[:=]")
SOURCE_FILE_SUFFIXES = {".java", ".kt"}


@dataclass(frozen=True)
class TargetSpec:
    name: str
    properties_path: Path
    enum_path: Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "player.properties / logger.properties と対応 enum を比較し、"
            "削除候補のメッセージ定義を一覧化します。"
        )
    )
    parser.add_argument(
        "--root",
        type=Path,
        required=True,
        help="10_plugin/AstralRecord のルートパス",
    )
    parser.add_argument(
        "--target",
        choices=["all", "player", "logger"],
        default="all",
        help="解析対象を絞り込みます",
    )
    parser.add_argument(
        "--format",
        choices=["markdown", "json"],
        default="markdown",
        help="出力形式",
    )
    parser.add_argument(
        "--write",
        type=Path,
        help="結果をファイルにも保存します",
    )
    return parser.parse_args()


def build_specs(root: Path) -> list[TargetSpec]:
    return [
        TargetSpec(
            name="player",
            properties_path=root / "src/main/resources/player.properties",
            enum_path=(
                root
                / "src/main/java/io/github/maaasu/astralRecord/feature/player/PlayerMsgId.java"
            ),
        ),
        TargetSpec(
            name="logger",
            properties_path=root / "src/main/resources/logger.properties",
            enum_path=(
                root
                / "src/main/java/io/github/maaasu/astralRecord/infrastructure/logging/LogId.java"
            ),
        ),
    ]


def parse_properties_keys(path: Path) -> list[str]:
    keys: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = PROPERTY_LINE_PATTERN.match(line)
        if match:
            keys.append(match.group(1).strip())
    return keys


def parse_enum_keys(path: Path) -> list[str]:
    keys: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = KEY_PATTERN.search(line)
        if match:
            keys.append(match.group(1))
    return keys


def collect_source_files(root: Path, excluded_files: set[Path]) -> list[Path]:
    source_dirs = [root / "src/main/java", root / "src/main/kotlin"]
    files: list[Path] = []
    for source_dir in source_dirs:
        if not source_dir.exists():
            continue
        for path in source_dir.rglob("*"):
            if path.suffix not in SOURCE_FILE_SUFFIXES:
                continue
            if path in excluded_files:
                continue
            files.append(path)
    return files


def index_source_usages(files: list[Path], root: Path) -> dict[str, list[str]]:
    usage_map: dict[str, list[str]] = {}
    for path in files:
        text = path.read_text(encoding="utf-8")
        keys = set(re.findall(r"(?<![A-Za-z0-9_])([A-Z]+_\d+)(?![A-Za-z0-9_])", text))
        if not keys:
            continue
        relative_path = path.relative_to(root).as_posix()
        for key in keys:
            usage_map.setdefault(key, []).append(relative_path)
    return usage_map


def analyze_target(root: Path, spec: TargetSpec) -> dict:
    if not spec.properties_path.exists():
        raise FileNotFoundError(f"properties file not found: {spec.properties_path}")
    if not spec.enum_path.exists():
        raise FileNotFoundError(f"enum file not found: {spec.enum_path}")

    property_keys = parse_properties_keys(spec.properties_path)
    enum_keys = parse_enum_keys(spec.enum_path)

    property_key_set = set(property_keys)
    enum_key_set = set(enum_keys)

    properties_only = sorted(property_key_set - enum_key_set)
    enum_only = sorted(enum_key_set - property_key_set)

    source_files = collect_source_files(root, excluded_files={spec.enum_path})
    usage_map = index_source_usages(source_files, root)
    paired_unused: list[dict] = []
    for key in sorted(property_key_set & enum_key_set):
        if not usage_map.get(key):
            paired_unused.append(
                {
                    "key": key,
                    "reason": "enum と properties の両方にあるが、対応 enum ファイル以外から参照されていません。",
                }
            )

    return {
        "target": spec.name,
        "properties_path": spec.properties_path.relative_to(root).as_posix(),
        "enum_path": spec.enum_path.relative_to(root).as_posix(),
        "property_key_count": len(property_keys),
        "enum_key_count": len(enum_keys),
        "properties_only": properties_only,
        "enum_only": enum_only,
        "paired_unused": paired_unused,
        "removable_count": len(properties_only) + len(enum_only) + len(paired_unused),
    }


def format_markdown(root: Path, analyses: list[dict]) -> str:
    lines = [
        "# Unused Properties Report",
        "",
        f"- root: `{root}`",
        "",
    ]

    total = sum(item["removable_count"] for item in analyses)
    lines.append(f"- removable_count: `{total}`")
    lines.append("")

    for item in analyses:
        lines.append(f"## {item['target']}")
        lines.append("")
        lines.append(f"- properties: `{item['properties_path']}`")
        lines.append(f"- enum: `{item['enum_path']}`")
        lines.append(f"- property_key_count: `{item['property_key_count']}`")
        lines.append(f"- enum_key_count: `{item['enum_key_count']}`")
        lines.append(f"- removable_count: `{item['removable_count']}`")
        lines.append("")

        lines.append("### properties のみに存在")
        if item["properties_only"]:
            lines.extend(f"- `{key}`" for key in item["properties_only"])
        else:
            lines.append("- なし")
        lines.append("")

        lines.append("### enum のみに存在")
        if item["enum_only"]:
            lines.extend(f"- `{key}`" for key in item["enum_only"])
        else:
            lines.append("- なし")
        lines.append("")

        lines.append("### enum だけが接続点になっている定義")
        if item["paired_unused"]:
            for entry in item["paired_unused"]:
                lines.append(f"- `{entry['key']}`: {entry['reason']}")
        else:
            lines.append("- なし")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    specs = build_specs(root)
    if args.target != "all":
        specs = [spec for spec in specs if spec.name == args.target]

    try:
        analyses = [analyze_target(root, spec) for spec in specs]
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    payload = {
        "root": str(root),
        "analyses": analyses,
    }

    if args.format == "json":
        rendered = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    else:
        rendered = format_markdown(root, analyses)

    if args.write:
        args.write.parent.mkdir(parents=True, exist_ok=True)
        args.write.write_text(rendered, encoding="utf-8")

    sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
