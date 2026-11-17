#!/usr/bin/env python3
"""Validate AstralRecord test discovery and Maven test configuration."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
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
    r"@[ \t\f\r\n]*(?:"
    + KOTLIN_IDENTIFIER
    + r"[ \t\f\r\n]*\.[ \t\f\r\n]*)*("
    + TEST_ANNOTATION_NAME
    + r")(?![\w$`])"
)
SKIP_ANNOTATION_PATTERN = re.compile(
    r"@[ \t\f\r\n]*(?:"
    + KOTLIN_IDENTIFIER
    + r"[ \t\f\r\n]*\.[ \t\f\r\n]*)*("
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
TYPE_PATTERN = re.compile(
    r"\b(?:class|interface|enum|record|object)\s+"
    r"([A-Za-z_$][\w$]*)[^\{;]*\{"
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
STANDARD_TEST_SOURCE_DIRECTORIES = frozenset(
    {
        "src/test/java",
        "${basedir}/src/test/java",
        "${project.basedir}/src/test/java",
    }
)
COMPILER_TEST_FILTER_ELEMENTS = frozenset({"testIncludes", "testExcludes"})
MOCK_SERVER_SOURCE_PATTERN = re.compile(
    r"\b(?:org\.mockbukkit|be\.seeseemelk\.mockbukkit)"
    r"(?:\.[A-Za-z_$][\w$]*)*\b|"
    r"\bMockBukkit(?:TestBase)?\b"
)
MOCK_SERVER_DEPENDENCY_MARKERS = (
    "mockbukkit",
    "bukkitmock",
    "bukkit-mock",
    "fakebukkit",
    "fake-bukkit",
    "bukkit-test-server",
    "paper-test-server",
    "spigot-test-server",
    "paper-server-mock",
    "spigot-server-mock",
)


@dataclass(frozen=True, order=True)
class ValidationIssue:
    """One stable, sortable validation failure."""

    path: str
    line: int
    code: str
    message: str


@dataclass(frozen=True)
class TypeBlock:
    name: str
    start: int
    end: int


def _translate_java_unicode_escapes(text: str) -> str:
    """Apply Java's Unicode-escape translation before lexical scanning."""

    translated: list[str] = []
    trailing_backslashes = 0
    last_backslash_from_escape = False
    index = 0
    while index < len(text):
        character = text[index]
        if character != "\\":
            translated.append(character)
            trailing_backslashes = 0
            last_backslash_from_escape = False
            index += 1
            continue

        eligible = (
            trailing_backslashes == 0
            or last_backslash_from_escape
            or trailing_backslashes % 2 == 0
        )
        u_end = index + 1
        while u_end < len(text) and text[u_end] == "u":
            u_end += 1
        digits_end = u_end + 4
        digits = text[u_end:digits_end]
        if (
            eligible
            and u_end > index + 1
            and len(digits) == 4
            and all(value in "0123456789abcdefABCDEF" for value in digits)
        ):
            decoded = chr(int(digits, 16))
            translated.append(decoded)
            if decoded == "\\":
                trailing_backslashes += 1
                last_backslash_from_escape = True
            else:
                trailing_backslashes = 0
                last_backslash_from_escape = False
            index = digits_end
            continue

        translated.append(character)
        trailing_backslashes += 1
        last_backslash_from_escape = False
        index += 1

    return "".join(translated)


def _mask_range(buffer: list[str], start: int, end: int) -> None:
    for index in range(start, end):
        if buffer[index] not in "\r\n":
            buffer[index] = " "


def _mask_comments_and_literals(text: str) -> str:
    """Mask comments and literals while preserving source offsets."""

    masked = list(text)
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

    return "".join(masked)


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


def _validate_source_file(
    repo_root: Path, source_file: Path
) -> tuple[list[ValidationIssue], int]:
    source_path = source_file.relative_to(repo_root).as_posix()
    text = source_file.read_text(encoding="utf-8-sig")
    if source_file.suffix == ".java":
        text = _translate_java_unicode_escapes(text)
    code = _mask_comments_and_literals(text)
    issues: list[ValidationIssue] = []
    type_blocks = _source_type_blocks(code)
    mock_server_match = MOCK_SERVER_SOURCE_PATTERN.search(code)
    if mock_server_match is not None:
        issues.append(
            ValidationIssue(
                source_path,
                _line_number(text, mock_server_match.start()),
                "MOCK_SERVER_SOURCE_REMAINS",
                "MockBukkit等の疑似サーバーを利用するsourceは一時診断後に削除してください",
            )
        )

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
        for match in KOTLIN_JUNIT_ALIAS_PATTERN.finditer(code):
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
        for match in KOTLIN_JUNIT_TYPEALIAS_PATTERN.finditer(code):
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

    skip_matches = tuple(SKIP_ANNOTATION_PATTERN.finditer(code))
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

    test_matches = tuple(ANNOTATION_PATTERN.finditer(code))
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


def _plugin_configurations(plugin: ET.Element) -> tuple[ET.Element, ...]:
    configurations = list(_direct_xml_children(plugin, "configuration"))
    for executions in _direct_xml_children(plugin, "executions"):
        for execution in _direct_xml_children(executions, "execution"):
            configurations.extend(
                _direct_xml_children(execution, "configuration")
            )
    return tuple(configurations)


def _project_builds(pom_root: ET.Element) -> tuple[ET.Element, ...]:
    builds = list(_direct_xml_children(pom_root, "build"))
    for profiles in _direct_xml_children(pom_root, "profiles"):
        for profile in _direct_xml_children(profiles, "profile"):
            builds.extend(_direct_xml_children(profile, "build"))
    return tuple(builds)


def _normalize_maven_path(value: str) -> str:
    return value.strip().replace("\\", "/").removeprefix("./").rstrip("/")


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

    for dependency in pom_root.iter():
        if _xml_local_name(dependency.tag) != "dependency":
            continue
        group_id = _direct_xml_child_text(dependency, "groupId")
        artifact_id = _direct_xml_child_text(dependency, "artifactId")
        coordinate = f"{group_id}:{artifact_id}".casefold()
        if not any(marker in coordinate for marker in MOCK_SERVER_DEPENDENCY_MARKERS):
            continue
        issues.append(
            ValidationIssue(
                display_path,
                1,
                "POM_MOCK_SERVER_DEPENDENCY",
                "MockBukkit等の疑似サーバー依存は一時診断後にPOMから削除してください: "
                f"{group_id}:{artifact_id}",
            )
        )

    reported_filters: set[str] = set()
    reported_compiler_filters: set[str] = set()
    for plugin in pom_root.iter():
        if _xml_local_name(plugin.tag) != "plugin":
            continue
        artifact_id = _direct_xml_child_text(plugin, "artifactId")
        if artifact_id == "maven-compiler-plugin":
            for configuration in _plugin_configurations(plugin):
                for setting in configuration:
                    setting_name = _xml_local_name(setting.tag)
                    if (
                        setting_name not in COMPILER_TEST_FILTER_ELEMENTS
                        or setting_name in reported_compiler_filters
                    ):
                        continue
                    reported_compiler_filters.add(setting_name)
                    issues.append(
                        ValidationIssue(
                            display_path,
                            1,
                            "POM_COMPILER_TEST_FILTER",
                            "maven-compiler-plugin の test compile selection は禁止です: "
                            f"<{setting_name}>",
                        )
                    )
            continue
        if artifact_id != "maven-surefire-plugin":
            continue
        for configuration in _plugin_configurations(plugin):
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

    for build in _project_builds(pom_root):
        for setting in _direct_xml_children(build, "testSourceDirectory"):
            value = _normalize_maven_path(setting.text or "")
            if value in STANDARD_TEST_SOURCE_DIRECTORIES:
                continue
            issues.append(
                ValidationIssue(
                    display_path,
                    1,
                    "POM_TEST_SOURCE_DIRECTORY",
                    "build/testSourceDirectory は Maven 既定の src/test/java から変更できません: "
                    f"{value or '<空>'}",
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
        file_issues, file_method_count = _validate_source_file(root, source_file)
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
            f"FAIL: {len(issues)} plugin test configuration issue(s) for "
            f"{method_count} test method(s), {file_count} source file(s)."
        )
        for issue in issues:
            print(
                f"{issue.path}:{issue.line}: [{issue.code}] {issue.message}"
            )
        return 1

    print(
        f"PASS: {method_count} test method(s) in {file_count} source file(s) "
        "pass plugin test configuration checks."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
