#!/usr/bin/env python3
"""Validate design traceability for AstralRecord Java and Kotlin tests."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
import re
import sys
from typing import Iterable, Sequence
import xml.etree.ElementTree as ET


TEST_ANNOTATIONS = (
    "Test",
    "ParameterizedTest",
    "RepeatedTest",
    "TestFactory",
    "TestTemplate",
)
SKIP_ANNOTATIONS = (
    "Disabled",
    "Ignore",
    "DisabledOnOs",
    "EnabledOnOs",
    "DisabledOnJre",
    "EnabledOnJre",
    "DisabledForJreRange",
    "EnabledForJreRange",
    "DisabledIfSystemProperty",
    "EnabledIfSystemProperty",
    "DisabledIfEnvironmentVariable",
    "EnabledIfEnvironmentVariable",
    "DisabledInNativeImage",
    "EnabledInNativeImage",
    "DisabledIf",
    "EnabledIf",
)
KOTLIN_IDENTIFIER = r"(?:`[^`\r\n]+`|(?:[^\W\d]|[$])[\w$]*)"
TEST_ANNOTATION_NAME = (
    r"(?:" + "|".join(TEST_ANNOTATIONS) + r"|`(?:"
    + "|".join(TEST_ANNOTATIONS)
    + r")`)"
)
SKIP_ANNOTATION_NAME = (
    r"(?:" + "|".join(SKIP_ANNOTATIONS) + r"|`(?:"
    + "|".join(SKIP_ANNOTATIONS)
    + r")`)"
)
ANNOTATION_PATTERN = re.compile(
    r"@(?:"
    + KOTLIN_IDENTIFIER
    + r"[ \t]*\.[ \t]*)*("
    + TEST_ANNOTATION_NAME
    + r")(?![\w$`])"
)
SKIP_ANNOTATION_PATTERN = re.compile(
    r"@(?:"
    + KOTLIN_IDENTIFIER
    + r"[ \t]*\.[ \t]*)*("
    + SKIP_ANNOTATION_NAME
    + r")(?![\w$`])"
)
KOTLIN_JUNIT_ALIAS_PATTERN = re.compile(
    r"^[ \t]*import[ \t]+(?P<target>[^;\r\n]+?)[ \t]+as[ \t]+"
    r"(?P<alias>[^;\r\n]+?)[ \t]*(?:;[ \t]*)?$",
    re.MULTILINE,
)
KOTLIN_JUNIT_TYPEALIAS_PATTERN = re.compile(
    r"^[ \t]*(?:(?:public|private|internal|protected|expect|actual)[ \t]+)*"
    r"typealias[ \t]+(?P<alias>"
    r"(?:`[^`\r\n]+`|[^\s=;]+)"
    r")[ \t]*=[ \t]*(?P<target>[^;\r\n]+?)[ \t]*(?:;[ \t]*)?$",
    re.MULTILINE,
)
FIELD_PATTERN = re.compile(r"^(設計入力|章・見出し|検証契約):\s*(.*?)\s*$")
HEADING_VALUE_PATTERN = re.compile(r"^(#{1,6})[ \t]+(.+?)\s*$")
MARKDOWN_HEADING_PATTERN = re.compile(r"^[ \t]{0,3}(#{1,6})[ \t]+(.+?)\s*$")
FENCE_PATTERN = re.compile(r"^[ \t]{0,3}(`{3,}|~{3,})")
TODO_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_])TODO(?![A-Za-z0-9_])", re.IGNORECASE
)
NON_ADOPTED_HEADING_PATTERN = re.compile(
    r"(?:実装予定|未決事項|判断待ち|要検討)"
)
NON_ADOPTED_BODY_PATTERN = re.compile(
    r"(?:未決事項|判断待ち|設計判断待ち|要検討|検討中|未実装|別途設計|TBD|FIXME)",
    re.IGNORECASE,
)
TYPE_PATTERN = re.compile(
    r"\b(?:class|interface|enum|record|object)\s+"
    r"([A-Za-z_$][\w$]*)[^\{;]*\{"
)
GENERIC_CONTRACTS = {
    "動作を確認する",
    "動作を確認する。",
    "正しく動作する",
    "正しく動作する。",
    "テストする",
    "テストする。",
    "仕様どおりである",
    "仕様どおりである。",
}
NON_CONCRETE_CONTRACT_FRAGMENTS = (
    "テスト名が示す",
    "設計節の記載どおり",
    "入力条件・実行結果・禁止される副作用",
    "期待どおり",
    "期待通り",
    "仕様どおり",
    "仕様通り",
    "設計どおり",
    "設計通り",
    "source正本",
    "ソース正本",
    "明記要",
    "要明記",
    "設計書へ明記",
    "設計書に未記載",
)
GENERIC_META_CONTRACT_TERMS = tuple(
    sorted(
        {
            "入力条件",
            "実行結果",
            "処理結果",
            "対象処理",
            "処理内容",
            "禁止される副作用",
            "問題がない",
            "問題なく",
            "期待どおり",
            "期待通り",
            "想定どおり",
            "想定通り",
            "仕様どおり",
            "仕様通り",
            "設計どおり",
            "設計通り",
            "確認する",
            "検証する",
            "テストする",
            "保証する",
            "確かめる",
            "正しく",
            "適切に",
            "正常に",
            "妥当に",
            "である",
            "される",
            "できる",
            "対象",
            "処理",
            "機能",
            "実装",
            "動作",
            "挙動",
            "結果",
            "内容",
            "状態",
            "条件",
            "入力",
            "出力",
            "副作用",
            "テスト",
            "仕様",
            "設計",
            "正しい",
            "適切",
            "正常",
            "妥当",
            "期待",
            "想定",
            "確認",
            "検証",
            "保証",
            "こと",
            "もの",
            "なる",
            "する",
            "行う",
        },
        key=len,
        reverse=True,
    )
)
GENERIC_VERIFICATION_PREDICATE_SUFFIX_PATTERN = re.compile(
    r"(?:(?:正しい|正常な?|適切な?|妥当な?))?"
    r"(?:動作|挙動|処理(?:内容)?|機能)を"
    r"(?:確認|検証)する(?:こと)?$",
    re.IGNORECASE,
)
CONCRETE_OUTCOME_PATTERN = re.compile(
    r"(?:"
    r"(?:null|true|false|例外|空(?:配列|集合|文字列)?|上限値|下限値|既定値)"
    r"(?:を|が)(?:返(?:す|さない)|送出(?:する|しない)|維持(?:する|しない))"
    r"|(?:を|へ|に)(?:返(?:す|さない)|送出(?:する|しない)|投げる|拒否する|"
    r"遷移する|登録(?:する|しない)|解除(?:する|しない)|削除(?:する|しない)|"
    r"追加(?:する|しない)|更新(?:する|しない)|保存(?:する|しない)|"
    r"送信(?:する|しない)|公開(?:する|しない)|生成(?:する|しない)|"
    r"破棄(?:する|しない)|消費(?:する|しない)|付与(?:する|しない)|"
    r"復元する|保持する|維持する|再開する|継続する|停止する|"
    r"一致する|変換する|解決する|適用する|通知する|記録する)"
    r"|(?:を|が)(?:変更|更新|保存|送信|通知|登録|解除|削除|追加)"
    r"(?:しない|されない)"
    r")",
    re.IGNORECASE,
)
CONCRETE_NUMERIC_CONSTRAINT_PATTERN = re.compile(
    r"(?:"
    r"(?:[0-9０-９]+(?:\.[0-9０-９]+)?|一|二|三|四|五|六|七|八|九|十)"
    r"(?:件|回|度|秒|ミリ秒|ms|tick|個|枠|段|文字|%|％|倍|以上|以下|未満|以内|超)"
    r"|(?:=|==|≠|!=|>=|<=|>|<|は|が|を)"
    r"[0-9０-９]+(?:\.[0-9０-９]+)?(?:である|になる|へ|を|$)"
    r")",
    re.IGNORECASE,
)
SUREFIRE_SCALAR_FILTER_ELEMENTS = frozenset(
    {
        "includesFile",
        "excludesFile",
        "groups",
        "excludedGroups",
        "includeJUnit5Engines",
        "excludeJUnit5Engines",
        "test",
        "skip",
        "skipTests",
    }
)
SUREFIRE_COLLECTION_FILTER_ELEMENTS = frozenset(
    {"includes", "excludes", "suiteXmlFiles"}
)
POM_TEST_CONTROL_PROPERTIES = frozenset(
    {
        "maven.test.skip",
        "skipTests",
        "test",
        "groups",
        "excludedGroups",
        "includeJUnit5Engines",
        "excludeJUnit5Engines",
        "surefire.includes",
        "surefire.excludes",
        "surefire.includesFile",
        "surefire.excludesFile",
    }
)


@dataclass(frozen=True, order=True)
class ValidationIssue:
    """One stable, sortable validation failure."""

    path: str
    line: int
    code: str
    message: str


@dataclass(frozen=True)
class JavadocBlock:
    start: int
    end: int
    body: str


@dataclass(frozen=True)
class SourceScan:
    code: str
    javadocs: tuple[JavadocBlock, ...]


@dataclass(frozen=True)
class TypeBlock:
    name: str
    start: int
    end: int


@dataclass(frozen=True)
class MarkdownDocument:
    hierarchies: frozenset[tuple[str, ...]]
    section_bodies: dict[tuple[str, ...], str]


def _mask_range(buffer: list[str], start: int, end: int) -> None:
    for index in range(start, end):
        if buffer[index] not in "\r\n":
            buffer[index] = " "


def _scan_source(text: str) -> SourceScan:
    """Mask comments/literals while recording real Javadoc blocks."""

    masked = list(text)
    javadocs: list[JavadocBlock] = []
    index = 0
    length = len(text)

    while index < length:
        if text.startswith("//", index):
            end = text.find("\n", index + 2)
            if end < 0:
                end = length
            _mask_range(masked, index, end)
            index = end
            continue

        if text.startswith("/*", index):
            end_marker = text.find("*/", index + 2)
            end = length if end_marker < 0 else end_marker + 2
            if text.startswith("/**", index) and end_marker >= 0:
                javadocs.append(JavadocBlock(index, end, text[index + 3 : end_marker]))
            _mask_range(masked, index, end)
            index = end
            continue

        if text.startswith('\"\"\"', index):
            end_marker = text.find('\"\"\"', index + 3)
            end = length if end_marker < 0 else end_marker + 3
            _mask_range(masked, index, end)
            index = end
            continue

        if text[index] in {'\"', "'"}:
            quote = text[index]
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                end += 1
                if text[end - 1] == quote:
                    break
            _mask_range(masked, index, min(end, length))
            index = min(end, length)
            continue

        index += 1

    return SourceScan("".join(masked), tuple(javadocs))


def _line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _unquote_kotlin_identifier(identifier: str) -> str:
    value = identifier.strip()
    if len(value) >= 2 and value.startswith("`") and value.endswith("`"):
        return value[1:-1]
    return value


def _kotlin_qualified_terminal_identifier(target: str) -> str:
    terminal = re.split(r"[ \t]*\.[ \t]*", target.strip())[-1]
    return _unquote_kotlin_identifier(terminal)


def _consume_annotation_arguments(text: str, start: int, end: int) -> int | None:
    """Consume one balanced annotation argument list, including quoted values."""

    depth = 0
    index = start
    quote: str | None = None
    while index < end:
        character = text[index]
        if quote is not None:
            if character == "\\":
                index += 2
                continue
            if character == quote:
                quote = None
            index += 1
            continue
        if character in {'"', "'"}:
            quote = character
            index += 1
            continue
        if text.startswith("//", index):
            newline = text.find("\n", index + 2, end)
            index = end if newline < 0 else newline + 1
            continue
        if text.startswith("/*", index):
            comment_end = text.find("*/", index + 2, end)
            if comment_end < 0:
                return None
            index = comment_end + 2
            continue
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return None


def _is_annotation_stack(text: str) -> bool:
    """Return true when text contains only whitespace and Java/Kotlin annotations."""

    identifier = re.compile(KOTLIN_IDENTIFIER)
    index = 0
    length = len(text)
    while True:
        while index < length and text[index].isspace():
            index += 1
        if index == length:
            return True
        if text[index] != "@":
            return False
        index += 1
        name_match = identifier.match(text, index)
        if name_match is None:
            return False
        index = name_match.end()
        if index < length and text[index] == ":":
            index += 1
            use_site_match = identifier.match(text, index)
            if use_site_match is None:
                return False
            index = use_site_match.end()
        while index < length and text[index] == ".":
            index += 1
            part_match = identifier.match(text, index)
            if part_match is None:
                return False
            index = part_match.end()
        while index < length and text[index].isspace():
            index += 1
        if index < length and text[index] == "(":
            consumed = _consume_annotation_arguments(text, index, length)
            if consumed is None:
                return False
            index = consumed


def _nearest_direct_javadoc(
    text: str, javadocs: Sequence[JavadocBlock], annotation_start: int
) -> JavadocBlock | None:
    candidate: JavadocBlock | None = None
    for block in javadocs:
        if block.end > annotation_start:
            break
        candidate = block
    if candidate is None or not _is_annotation_stack(
        text[candidate.end : annotation_start]
    ):
        return None
    return candidate


def _parse_javadoc_fields(
    block: JavadocBlock,
) -> tuple[dict[str, list[str]], list[str]]:
    fields: dict[str, list[str]] = {}
    field_order: list[str] = []
    meaningful_lines: list[tuple[str, str | None]] = []
    errors: list[str] = []
    for raw_line in block.body.splitlines():
        line = raw_line.strip()
        if line.startswith("*"):
            line = line[1:].lstrip()
        if not line:
            continue
        match = FIELD_PATTERN.fullmatch(line)
        meaningful_lines.append((line, match.group(1) if match else None))
        if not match:
            continue
        name, value = match.groups()
        fields.setdefault(name, []).append(value)
        field_order.append(name)

    for index, (_, name) in enumerate(meaningful_lines):
        if name != "設計入力":
            continue
        next_name = (
            meaningful_lines[index + 1][1]
            if index + 1 < len(meaningful_lines)
            else None
        )
        if next_name != "章・見出し":
            errors.append(
                "設計入力: と対応する 章・見出し: の間には空行以外を置けません"
            )
            break

    for name in ("設計入力", "章・見出し", "検証契約"):
        values = fields.get(name, [])
        if not values:
            errors.append(f"{name}: がありません")
        elif any(not value for value in values):
            errors.append(f"{name}: が空です")

    design_paths = fields.get("設計入力", [])
    headings = fields.get("章・見出し", [])
    contracts = fields.get("検証契約", [])
    if design_paths and headings and len(design_paths) != len(headings):
        errors.append(
            "設計入力: と 章・見出し: は同じ件数で対にしてください"
        )
    elif design_paths and headings:
        expected_order = [
            name
            for _ in design_paths
            for name in ("設計入力", "章・見出し")
        ] + ["検証契約"]
        if field_order != expected_order:
            errors.append(
                "設計入力: の直後に対応する 章・見出し: を置き、"
                "最後に検証契約: を一つ記載してください"
            )
    if len(contracts) > 1:
        errors.append("検証契約: は一つの具体的な文にまとめてください")
    return fields, errors


def _is_surefire_name(stem: str) -> bool:
    return (
        stem.startswith("Test")
        or stem.endswith("Test")
        or stem.endswith("Tests")
        or stem.endswith("TestCase")
    )


def _is_ad_hoc_test_name(name: str) -> bool:
    logical_name = name
    while True:
        previous_name = logical_name
        while logical_name.startswith("Test"):
            logical_name = logical_name[len("Test") :]
        for suffix in ("TestCase", "Tests", "Test"):
            if logical_name.endswith(suffix):
                logical_name = logical_name[: -len(suffix)]
                break
        if logical_name == previous_name:
            break
    return logical_name.startswith("AdHoc") or logical_name.endswith("OneShot")


def _source_type_blocks(code: str) -> tuple[TypeBlock, ...]:
    blocks: list[TypeBlock] = []
    for match in TYPE_PATTERN.finditer(code):
        opening_brace = match.end() - 1
        depth = 0
        closing_brace = len(code)
        for index in range(opening_brace, len(code)):
            if code[index] == "{":
                depth += 1
            elif code[index] == "}":
                depth -= 1
                if depth == 0:
                    closing_brace = index
                    break
        blocks.append(TypeBlock(match.group(1), opening_brace, closing_brace))
    return tuple(blocks)


def _outermost_type_at(
    blocks: Sequence[TypeBlock], offset: int
) -> TypeBlock | None:
    containing = [block for block in blocks if block.start < offset < block.end]
    return min(containing, key=lambda block: block.start) if containing else None


def _is_allowed_design_path(path: str) -> bool:
    return path == "PLUGIN_GUIDE.md" or (
        path.startswith("00_docs/10_Plugin設計書/") and path.endswith(".md")
    )


def _is_non_adopted_design_path(path: str) -> bool:
    parts = PurePosixPath(path).parts
    for part in parts:
        review_name = PurePosixPath(part).stem.casefold()
        if re.search(r"(?:^|[-_.])reviews?(?:$|[-_.])", review_name):
            return True
        if "レビュー" in review_name:
            return True
        if re.match(r"^[89](?:-|_)", part):
            return True
        if re.match(r"^\d+_[89]-", part):
            return True
    return False


def _parse_markdown_document(text: str) -> MarkdownDocument:
    lines = text.splitlines()
    visible_lines = list(lines)
    headings: list[tuple[int, int, tuple[str, ...]]] = []
    stack: list[tuple[int, str]] = []
    fence_character: str | None = None
    fence_length = 0

    for line_index, raw_line in enumerate(lines):
        fence_match = FENCE_PATTERN.match(raw_line)
        if fence_match:
            visible_lines[line_index] = ""
            marker = fence_match.group(1)
            if fence_character is None:
                fence_character = marker[0]
                fence_length = len(marker)
                continue
            if marker[0] == fence_character and len(marker) >= fence_length:
                fence_character = None
                fence_length = 0
                continue
        if fence_character is not None:
            visible_lines[line_index] = ""
            continue

        heading_match = MARKDOWN_HEADING_PATTERN.match(raw_line)
        if not heading_match:
            continue
        hashes, heading_text = heading_match.groups()
        heading_text = re.sub(r"[ \t]+#+[ \t]*$", "", heading_text).strip()
        level = len(hashes)
        while stack and stack[-1][0] >= level:
            stack.pop()
        stack.append((level, f"{'#' * level} {heading_text}"))
        headings.append(
            (line_index, level, tuple(value for _, value in stack))
        )

    section_bodies: dict[tuple[str, ...], str] = {}
    for heading_index, (line_index, level, hierarchy) in enumerate(headings):
        end_line = len(lines)
        for next_line, next_level, _ in headings[heading_index + 1 :]:
            if next_level <= level:
                end_line = next_line
                break
        body = "\n".join(visible_lines[line_index + 1 : end_line])
        if hierarchy in section_bodies:
            section_bodies[hierarchy] += "\n" + body
        else:
            section_bodies[hierarchy] = body

    return MarkdownDocument(frozenset(section_bodies), section_bodies)


def _parse_markdown_hierarchies(text: str) -> set[tuple[str, ...]]:
    return set(_parse_markdown_document(text).hierarchies)


def _has_non_adopted_heading(
    document: MarkdownDocument, hierarchy: tuple[str, ...]
) -> bool:
    for segment in hierarchy:
        if NON_ADOPTED_HEADING_PATTERN.search(segment.lstrip("# ")):
            return True
    for candidate in document.hierarchies:
        if candidate[: len(hierarchy)] != hierarchy:
            continue
        for segment in candidate[len(hierarchy) :]:
            heading_text = segment.lstrip("# ")
            if NON_ADOPTED_HEADING_PATTERN.search(heading_text):
                return True
    return False


def _is_generic_meta_contract(contract: str) -> bool:
    """Detect contracts composed only of generic test/verification meta language."""

    remainder = contract.casefold()
    for term in GENERIC_META_CONTRACT_TERMS:
        remainder = remainder.replace(term.casefold(), "")
    remainder = re.sub(
        r"[\s\u3000、。・,.;:：!！?？()（）\[\]［］{}「」『』“”\"'`]+",
        "",
        remainder,
    )
    remainder = re.sub(r"(?:および|及び|かつ)", "", remainder)
    remainder = re.sub(r"[のがはをにへでともやか]", "", remainder)
    return not remainder


def _normalize_generic_verification_predicate(contract: str) -> str:
    """Normalize equivalent Japanese verification predicates for structure checks."""

    normalized = contract.casefold()
    normalized = re.sub(r"[\s\u3000]+", "", normalized)
    normalized = re.sub(r"[。．.!！]+$", "", normalized)
    normalized = re.sub(
        r"(確認|検証)(?:を)?(?:行う|実施する|する|できる|される)",
        r"\1する",
        normalized,
    )
    normalized = normalized.replace("確かめる", "確認する")
    normalized = re.sub(
        r"(?:正しく|正常に|適切に|妥当に)(?:動く|動作する)(?:こと|か)を"
        r"(?:確認|検証)する",
        "正しい動作を確認する",
        normalized,
    )
    normalized = re.sub(
        r"(動作|挙動|処理(?:内容)?|機能)が"
        r"(正しい|正常|適切|妥当)(?:である)?ことを(確認|検証)する",
        lambda match: (
            f"{match.group(2)}な{match.group(1)}を{match.group(3)}する"
            if match.group(2) != "正しい"
            else f"正しい{match.group(1)}を{match.group(3)}する"
        ),
        normalized,
    )
    normalized = re.sub(
        r"(動作|挙動|処理(?:内容)?|機能)(?:の)?(確認|検証)する",
        r"\1を\2する",
        normalized,
    )
    return normalized


def _has_concrete_contract_evidence(subject_or_detail: str) -> bool:
    """Return true for a result, invariant, numeric bound, or forbidden side effect."""

    return bool(
        CONCRETE_OUTCOME_PATTERN.search(subject_or_detail)
        or CONCRETE_NUMERIC_CONSTRAINT_PATTERN.search(subject_or_detail)
        or re.search(
            r"(?:副作用|変更|更新|保存|送信|通知|登録|解除|削除|追加|呼び出し?)"
            r".{0,24}(?:禁止|発生しない|行わない|しない|されない)",
            subject_or_detail,
            re.IGNORECASE,
        )
    )


def _is_generic_named_subject_contract(contract: str) -> bool:
    """Reject a named subject followed only by a generic verification predicate."""

    normalized = _normalize_generic_verification_predicate(contract)
    predicate_match = GENERIC_VERIFICATION_PREDICATE_SUFFIX_PATTERN.search(
        normalized
    )
    if predicate_match is None:
        return False

    subject_or_detail = normalized[: predicate_match.start()]
    subject_or_detail = re.sub(
        r"(?:について|に対して|の|が|は|を)$", "", subject_or_detail
    )
    return not _has_concrete_contract_evidence(subject_or_detail)


def _parse_heading_value(value: str) -> tuple[tuple[str, ...] | None, str | None]:
    segments = tuple(segment.strip() for segment in value.split(" > "))
    if len(segments) < 2:
        return None, "H1 から子見出しまでを ` > ` で指定してください"

    previous_level = 0
    for index, segment in enumerate(segments):
        match = HEADING_VALUE_PATTERN.fullmatch(segment)
        if not match:
            return None, f"Markdown 見出し形式ではありません: {segment}"
        level = len(match.group(1))
        if index == 0 and level != 1:
            return None, "見出し階層は H1 から開始してください"
        if level <= previous_level:
            return None, "見出しレベルは親から子へ昇順で指定してください"
        previous_level = level
    return segments, None


def _validate_reference_pair(
    repo_root: Path,
    source_path: str,
    line: int,
    design_path: str,
    heading_value: str,
    markdown_cache: dict[Path, MarkdownDocument],
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    if (
        design_path.startswith(("/", "//", "./"))
        or re.match(r"^[A-Za-z]:[/\\]", design_path)
        or "\\" in design_path
        or any(part in {".", ".."} for part in PurePosixPath(design_path).parts)
    ):
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "DESIGN_PATH_FORMAT",
                "設計入力は `/` 区切りの正規化済みリポジトリ相対パスにしてください",
            )
        )
        return issues

    if not _is_allowed_design_path(design_path):
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "DESIGN_PATH_NOT_ALLOWED",
                "設計入力は PLUGIN_GUIDE.md または 00_docs/10_Plugin設計書/**/*.md に限定されます",
            )
        )
        return issues

    if _is_non_adopted_design_path(design_path):
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "NON_ADOPTED_DESIGN",
                "8-実装予定、9-未決事項、review は恒久テストの入力にできません",
            )
        )
        return issues

    candidate = repo_root.joinpath(*PurePosixPath(design_path).parts)
    try:
        resolved_candidate = candidate.resolve(strict=True)
        resolved_candidate.relative_to(repo_root.resolve(strict=True))
    except (FileNotFoundError, OSError, ValueError):
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "DESIGN_PATH_MISSING",
                f"設計入力が存在しないかリポジトリ外です: {design_path}",
            )
        )
        return issues
    if not resolved_candidate.is_file():
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "DESIGN_PATH_NOT_FILE",
                f"設計入力がファイルではありません: {design_path}",
            )
        )
        return issues

    hierarchy, heading_error = _parse_heading_value(heading_value)
    if heading_error:
        issues.append(
            ValidationIssue(source_path, line, "HEADING_FORMAT", heading_error)
        )
    else:
        if resolved_candidate not in markdown_cache:
            markdown = resolved_candidate.read_text(encoding="utf-8-sig")
            markdown_cache[resolved_candidate] = _parse_markdown_document(markdown)
        document = markdown_cache[resolved_candidate]
        if hierarchy not in document.hierarchies:
            issues.append(
                ValidationIssue(
                    source_path,
                    line,
                    "HEADING_NOT_FOUND",
                    f"指定した見出し階層が設計入力に存在しません: {heading_value}",
                )
            )
        else:
            if _has_non_adopted_heading(document, hierarchy):
                issues.append(
                    ValidationIssue(
                        source_path,
                        line,
                        "NON_ADOPTED_DESIGN_SECTION",
                        "実装予定・未決事項・判断待ちの見出しは恒久テストの入力にできません",
                    )
                )
            if TODO_PATTERN.search(document.section_bodies.get(hierarchy, "")):
                issues.append(
                    ValidationIssue(
                        source_path,
                        line,
                        "DESIGN_BODY_TODO",
                        "参照見出し配下の TODO は恒久テストの期待値にできません",
                    )
                )
            if NON_ADOPTED_BODY_PATTERN.search(
                document.section_bodies.get(hierarchy, "")
            ):
                issues.append(
                    ValidationIssue(
                        source_path,
                        line,
                        "DESIGN_BODY_UNRESOLVED",
                        "参照見出し配下の未決・判断待ち・要検討等は恒久テストの期待値にできません",
                    )
                )

    return issues


def _validate_design_reference(
    repo_root: Path,
    source_path: str,
    line: int,
    fields: dict[str, list[str]],
    markdown_cache: dict[Path, MarkdownDocument],
) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []

    for name, values in fields.items():
        for value in values:
            if TODO_PATTERN.search(value):
                issues.append(
                    ValidationIssue(
                        source_path,
                        line,
                        "TODO_VALUE",
                        f"{name}: に TODO を残せません",
                    )
                )

    for design_path, heading_value in zip(
        fields["設計入力"], fields["章・見出し"], strict=True
    ):
        issues.extend(
            _validate_reference_pair(
                repo_root,
                source_path,
                line,
                design_path,
                heading_value,
                markdown_cache,
            )
        )

    normalized_contract = fields["検証契約"][0].strip()
    normalized_contract_casefold = normalized_contract.casefold()
    if (
        len(normalized_contract) < 8
        or normalized_contract in GENERIC_CONTRACTS
        or any(
            fragment.casefold() in normalized_contract_casefold
            for fragment in NON_CONCRETE_CONTRACT_FRAGMENTS
        )
        or _is_generic_meta_contract(normalized_contract)
        or _is_generic_named_subject_contract(normalized_contract)
    ):
        issues.append(
            ValidationIssue(
                source_path,
                line,
                "CONTRACT_NOT_CONCRETE",
                "検証契約は条件と期待結果が分かる具体的な一文にしてください",
            )
        )
    return issues


def _validate_source_file(
    repo_root: Path,
    source_file: Path,
    markdown_cache: dict[Path, MarkdownDocument],
) -> tuple[list[ValidationIssue], int]:
    source_path = source_file.relative_to(repo_root).as_posix()
    text = source_file.read_text(encoding="utf-8-sig")
    scan = _scan_source(text)
    issues: list[ValidationIssue] = []
    type_blocks = _source_type_blocks(scan.code)

    ad_hoc_file = _is_ad_hoc_test_name(source_file.stem)
    if ad_hoc_file:
        issues.append(
            ValidationIssue(
                source_path,
                1,
                "AD_HOC_TEST_REMAINS",
                "AdHoc*Test / *OneShotTest は対象実行後に削除してください",
            )
        )
    for block in type_blocks:
        if _is_ad_hoc_test_name(block.name) and not (
            ad_hoc_file and block.name == source_file.stem
        ):
            issues.append(
                ValidationIssue(
                    source_path,
                    _line_number(text, block.start),
                    "AD_HOC_TEST_REMAINS",
                    f"ad-hoc test class が残っています: {block.name}",
                )
            )

    if source_file.suffix == ".kt":
        for match in KOTLIN_JUNIT_ALIAS_PATTERN.finditer(scan.code):
            target_name = _kotlin_qualified_terminal_identifier(
                match.group("target")
            )
            if target_name not in TEST_ANNOTATIONS + SKIP_ANNOTATIONS:
                continue
            issues.append(
                ValidationIssue(
                    source_path,
                    _line_number(text, match.start()),
                    "JUNIT_ANNOTATION_ALIAS",
                    f"JUnit annotation @{target_name} に Kotlin import alias "
                    f"{match.group('alias').strip()} を付けないでください",
                )
            )
        for match in KOTLIN_JUNIT_TYPEALIAS_PATTERN.finditer(scan.code):
            target_name = _kotlin_qualified_terminal_identifier(
                match.group("target")
            )
            if target_name not in TEST_ANNOTATIONS + SKIP_ANNOTATIONS:
                continue
            alias_name = _unquote_kotlin_identifier(match.group("alias"))
            issues.append(
                ValidationIssue(
                    source_path,
                    _line_number(text, match.start()),
                    "JUNIT_ANNOTATION_TYPEALIAS",
                    f"JUnit annotation @{target_name} に Kotlin typealias "
                    f"{alias_name} を付けないでください",
                )
            )

    skip_matches = tuple(SKIP_ANNOTATION_PATTERN.finditer(scan.code))
    for match in skip_matches:
        annotation_name = _unquote_kotlin_identifier(match.group(1))
        issues.append(
            ValidationIssue(
                source_path,
                _line_number(text, match.start()),
                "DISABLED_TEST",
                f"@{annotation_name} でテストを無効化・条件付き実行できません",
            )
        )

    test_matches = tuple(ANNOTATION_PATTERN.finditer(scan.code))
    if test_matches and not _is_surefire_name(source_file.stem):
        issues.append(
            ValidationIssue(
                source_path,
                1,
                "SUREFIRE_NAME_MISMATCH",
                "test annotation を含むファイル名が Surefire の既定命名規則に一致しません",
            )
        )

    for match in test_matches:
        annotation_name = _unquote_kotlin_identifier(match.group(1))
        line = _line_number(text, match.start())
        outermost_type = _outermost_type_at(type_blocks, match.start())
        if outermost_type is None or not _is_surefire_name(outermost_type.name):
            actual_name = outermost_type.name if outermost_type else "<typeなし>"
            issues.append(
                ValidationIssue(
                    source_path,
                    line,
                    "SUREFIRE_TYPE_MISMATCH",
                    "test method を含む最外 class が Surefire の既定命名規則に"
                    f"一致しません: {actual_name}",
                )
            )
        javadoc = _nearest_direct_javadoc(text, scan.javadocs, match.start())
        if javadoc is None:
            issues.append(
                ValidationIssue(
                    source_path,
                    line,
                    "JAVADOC_MISSING",
                    f"@{annotation_name} と同一methodの連続annotation stack直前に"
                    "設計トレーサビリティ Javadoc がありません",
                )
            )
            continue

        fields, field_errors = _parse_javadoc_fields(javadoc)
        for error in field_errors:
            issues.append(
                ValidationIssue(source_path, line, "JAVADOC_FIELD", error)
            )
        if field_errors:
            continue
        issues.extend(
            _validate_design_reference(
                repo_root, source_path, line, fields, markdown_cache
            )
        )

    return issues, len(test_matches)


def _xml_local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _direct_xml_child_text(element: ET.Element, name: str) -> str:
    for child in element:
        if _xml_local_name(child.tag) == name:
            return (child.text or "").strip()
    return ""


def _direct_xml_children(
    element: ET.Element, name: str
) -> tuple[ET.Element, ...]:
    return tuple(
        child for child in element if _xml_local_name(child.tag) == name
    )


def _surefire_configurations(plugin: ET.Element) -> tuple[ET.Element, ...]:
    configurations = list(_direct_xml_children(plugin, "configuration"))
    for executions in _direct_xml_children(plugin, "executions"):
        for execution in _direct_xml_children(executions, "execution"):
            configurations.extend(
                _direct_xml_children(execution, "configuration")
            )
    return tuple(configurations)


def _project_property_containers(pom_root: ET.Element) -> tuple[ET.Element, ...]:
    containers = list(_direct_xml_children(pom_root, "properties"))
    for profiles in _direct_xml_children(pom_root, "profiles"):
        for profile in _direct_xml_children(profiles, "profile"):
            containers.extend(_direct_xml_children(profile, "properties"))
    return tuple(containers)


def _validate_pom_configuration(
    repo_root: Path, pom_path: Path
) -> list[ValidationIssue]:
    """Reject POM settings that can silently filter the permanent test suite."""

    issues: list[ValidationIssue] = []
    display_path = (
        pom_path.relative_to(repo_root).as_posix()
        if pom_path.is_relative_to(repo_root)
        else str(pom_path)
    )
    try:
        resolved_pom = pom_path.resolve(strict=True)
        resolved_pom.relative_to(repo_root.resolve(strict=True))
    except (FileNotFoundError, OSError, ValueError):
        return [
            ValidationIssue(
                display_path,
                1,
                "POM_MISSING",
                "Plugin POM が存在しないかリポジトリ外です",
            )
        ]

    try:
        pom_root = ET.parse(resolved_pom).getroot()
    except (ET.ParseError, OSError) as error:
        return [
            ValidationIssue(
                display_path,
                1,
                "POM_INVALID",
                f"Plugin POM を解析できません: {error}",
            )
        ]

    reported_filters: set[str] = set()
    for plugin in pom_root.iter():
        if _xml_local_name(plugin.tag) != "plugin":
            continue
        if _direct_xml_child_text(plugin, "artifactId") != "maven-surefire-plugin":
            continue
        for configuration in _surefire_configurations(plugin):
            for setting in configuration:
                setting_name = _xml_local_name(setting.tag)
                if setting_name not in (
                    SUREFIRE_SCALAR_FILTER_ELEMENTS
                    | SUREFIRE_COLLECTION_FILTER_ELEMENTS
                ):
                    continue
                if setting_name in {"skip", "skipTests"} and (
                    setting.text or ""
                ).strip().casefold() in {"", "false"}:
                    continue
                if setting_name in reported_filters:
                    continue
                reported_filters.add(setting_name)
                issues.append(
                    ValidationIssue(
                        display_path,
                        1,
                        "POM_SUREFIRE_FILTER",
                        "maven-surefire-plugin の custom test selection は禁止です: "
                        f"<{setting_name}>",
                    )
                )

    reported_properties: set[str] = set()
    for properties in _project_property_containers(pom_root):
        for element in properties:
            property_name = _xml_local_name(element.tag)
            if property_name not in POM_TEST_CONTROL_PROPERTIES:
                continue
            value = (element.text or "").strip()
            if property_name in {"maven.test.skip", "skipTests"} and (
                value.casefold() in {"", "false"}
            ):
                continue
            if property_name in reported_properties:
                continue
            reported_properties.add(property_name)
            issues.append(
                ValidationIssue(
                    display_path,
                    1,
                    "POM_TEST_CONTROL_PROPERTY",
                    "POM property で test selection/skip を変更できません: "
                    f"<{property_name}>",
                )
            )
    return issues


def validate_repository(
    repo_root: Path,
    test_roots: Iterable[Path] | None = None,
    pom_path: Path | None = None,
) -> tuple[list[ValidationIssue], int, int]:
    """Return sorted issues, test method count, and scanned source file count."""

    root = repo_root.resolve()
    roots = list(test_roots or [root / "10_plugin" / "AstralRecord" / "src" / "test"])
    issues: list[ValidationIssue] = []
    files: set[Path] = set()
    markdown_cache: dict[Path, MarkdownDocument] = {}
    resolved_pom_path = pom_path or (
        root / "10_plugin" / "AstralRecord" / "pom.xml"
    )
    if not resolved_pom_path.is_absolute():
        resolved_pom_path = root / resolved_pom_path
    issues.extend(_validate_pom_configuration(root, resolved_pom_path))

    for test_root in roots:
        resolved_test_root = test_root if test_root.is_absolute() else root / test_root
        if not resolved_test_root.is_dir():
            display = (
                resolved_test_root.relative_to(root).as_posix()
                if resolved_test_root.is_relative_to(root)
                else str(resolved_test_root)
            )
            issues.append(
                ValidationIssue(
                    display,
                    1,
                    "TEST_ROOT_MISSING",
                    "テストソースルートが存在しません",
                )
            )
            continue
        files.update(resolved_test_root.rglob("*.java"))
        files.update(resolved_test_root.rglob("*.kt"))

    method_count = 0
    for source_file in sorted(files, key=lambda path: path.as_posix()):
        try:
            source_file.resolve().relative_to(root)
        except ValueError:
            issues.append(
                ValidationIssue(
                    str(source_file),
                    1,
                    "TEST_SOURCE_OUTSIDE_REPO",
                    "テストソースはリポジトリ内に置いてください",
                )
            )
            continue
        file_issues, file_method_count = _validate_source_file(
            root, source_file, markdown_cache
        )
        issues.extend(file_issues)
        method_count += file_method_count

    return sorted(issues), method_count, len(files)


def _default_repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=_default_repo_root(),
        help="repository root (default: inferred from this script)",
    )
    parser.add_argument(
        "--test-root",
        action="append",
        type=Path,
        dest="test_roots",
        help="test source root relative to the repository; repeatable",
    )
    parser.add_argument(
        "--pom",
        type=Path,
        help=(
            "Plugin POM relative to the repository "
            "(default: 10_plugin/AstralRecord/pom.xml)"
        ),
    )
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    issues, method_count, file_count = validate_repository(
        args.repo_root, args.test_roots, args.pom
    )
    if issues:
        print(
            f"FAIL: {len(issues)} traceability issue(s) in "
            f"{method_count} test method(s), {file_count} source file(s)."
        )
        for issue in issues:
            print(
                f"{issue.path}:{issue.line}: [{issue.code}] {issue.message}"
            )
        return 1

    print(
        f"PASS: {method_count} test method(s) in {file_count} source file(s) "
        "have valid design traceability."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
