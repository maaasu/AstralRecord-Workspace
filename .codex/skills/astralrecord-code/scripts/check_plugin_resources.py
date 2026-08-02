#!/usr/bin/env python3
"""Validate AstralRecord plugin log/message resources and direct-output guardrails.

Skill GUI master-display policy
-------------------------------
The strict scope is ``feature/skill/gui`` and ``feature/skill/view`` production sources.
Within that scope, passing ``getName()``, ``getLore()``, or ``getDescription()`` directly
to a display sink (Component construction, item meta display/lore, legacy deserialization,
or the local GUI item factory) is prohibited.  GUI/View code must use the dedicated
``SkillPresentationUtil`` component API, which delegates all legacy-color normalization to
``ColorCodeUtil``.  The intentionally allowed exceptions are non-display business logic,
the common presentation API itself, and sources outside the strict skill GUI/View scope.
"""

from __future__ import annotations

import argparse
from collections import Counter
import re
import subprocess
import sys
from pathlib import Path


ID_PATTERN = re.compile(r"^\s*([A-Z]_[0-9]+)\s*\(\s*[0-9]+\s*\)", re.MULTILINE)
ID_VALUE_PATTERN = re.compile(r"^\s*([A-Z]_([0-9]+))\s*\(\s*([0-9]+)\s*\)", re.MULTILINE)
PROPERTY_PATTERN = re.compile(
    r"^[ \t]*([A-Z]_[0-9]+)(?:[ \t]*[=:][ \t]*|[ \t]+)(?=\S)",
    re.MULTILINE,
)
DIRECT_PLATFORM_LOG_PATTERN = re.compile(
    r"\bgetLogger\s*\(\s*\)\s*\.\s*(?:severe|warning|info|config|fine|finer|finest|log)\s*\(",
    re.DOTALL,
)
DIRECT_LOGGER_VARIABLE_PATTERN = re.compile(
    r"\b(?:logger|log|LOGGER|pluginLogger|utilLogger)\s*\.\s*"
    r"(?:trace|debug|info|warn|warning|error|severe|log)\s*\(",
    re.DOTALL,
)
DIRECT_LOGGER_WITHOUT_ID_PATTERN = re.compile(
    r"\bLogger\s*\.\s*(?:info|warn|error|debug)\s*\("
    r"(?!\s*(?:[A-Za-z_]\w*\s*\.\s*)*LogId\s*\.)",
    re.DOTALL,
)
DIRECT_STD_STREAM_PATTERN = re.compile(
    r"\bSystem\s*\.\s*(?:out|err)\s*\.\s*(?:print|println|printf)\s*\(",
    re.DOTALL,
)
DIRECT_MESSAGE_PATTERN = re.compile(r"\.sendMessage\s*\(", re.DOTALL)
DIRECT_RAW_MESSAGE_LITERAL_PATTERN = re.compile(
    r"\bsendRaw\s*\([^,;]{0,240},"
    r"(?!\s*(?:[A-Za-z_]\w*\.)*PlayerMsgResource\s*\.\s*(?:getMessage|format)\s*\()"
    r"\s*(?=[^;]{0,500}\")[^;]{0,500}(?:\)|;)",
    re.DOTALL,
)
DIRECT_COMMAND_MESSAGE_LITERAL_PATTERN = re.compile(
    r"\b(?:sendInfo|sendSuccess|sendWarning|sendError)\s*\([^,;]{0,240},"
    r"(?!\s*(?:[A-Za-z_]\w*\.)*PlayerMsgResource\s*\.\s*(?:getMessage|format)\s*\()"
    r"\s*(?=[^;]{0,500}\")[^;]{0,500}(?:\)|;)",
    re.DOTALL,
)
LOGGER_WITH_ID_CALL_PATTERN = re.compile(
    r"\bLogger\s*\.\s*(?P<method>log|info|warn|error|debug)\s*\("
    r"(?P<arguments>[^;]{0,2000}?)\)\s*(?:;|(?=\r?$))",
    re.DOTALL | re.MULTILINE,
)
STRING_LITERAL_PATTERN = re.compile(r'"((?:\\.|[^"\\])*)"')
STRUCTURED_LOG_LITERAL_PATTERN = re.compile(r"^[a-z0-9_.:-]+$")
DIFF_HUNK_PATTERN = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
FORMAT_PLACEHOLDER_PATTERN = re.compile(
    r"(?<!%)%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[tT]?[a-zA-Z]"
)
THROWABLE_ARGUMENT_PATTERN = re.compile(
    r"^(?:null|e|ex|t|throwable|exception|cause|failure|error|"
    r"\w*(?:Exception|Throwable|Failure|Error)|new\s+\w*(?:Exception|Throwable|Error)\b)$"
)
MASTER_DISPLAY_GETTER_PATTERN = re.compile(r"\.get(?:Name|Lore|Description)\s*\(")
MASTER_DISPLAY_SINK_PATTERN = re.compile(
    r"(?:Component\s*\.\s*text|(?:\w+\s*\.\s*)?(?:displayName|lore)|"
    r"LegacyComponentSerializer\s*\.\s*\w+\s*\(\s*\)\s*\.\s*deserialize|createItem)\s*\("
)


def read_ids(path: Path, pattern: re.Pattern[str]) -> set[str]:
    return set(pattern.findall(path.read_text(encoding="utf-8")))


def read_id_occurrences(path: Path, pattern: re.Pattern[str]) -> list[str]:
    return pattern.findall(path.read_text(encoding="utf-8"))


def report_difference(label: str, left: set[str], right: set[str]) -> list[str]:
    return [f"{label}: {value}" for value in sorted(left - right)]


def report_duplicates(label: str, values: list[str]) -> list[str]:
    return [
        f"{label}: {value} ({count} definitions)"
        for value, count in sorted(Counter(values).items())
        if count > 1
    ]


def report_id_number_mismatches(label: str, path: Path) -> list[str]:
    errors: list[str] = []
    for id_name, name_number, constructor_number in ID_VALUE_PATTERN.findall(
        path.read_text(encoding="utf-8")
    ):
        if name_number != constructor_number:
            errors.append(
                f"{label}: {id_name} declares constructor value {constructor_number}"
            )
    return errors


def report_matches(
    label: str,
    relative: Path,
    text: str,
    pattern: re.Pattern[str],
) -> list[str]:
    return [
        f"{label}: {relative}:{text.count(chr(10), 0, match.start()) + 1}"
        for match in pattern.finditer(text)
    ]


def balanced_invocation_arguments(text: str, opening_parenthesis: int) -> str:
    """Return text inside a Java/Kotlin invocation's balanced parentheses."""
    depth = 0
    quote: str | None = None
    escaped = False
    for index in range(opening_parenthesis, len(text)):
        character = text[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {'"', "'"}:
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                return text[opening_parenthesis + 1:index]
    return text[opening_parenthesis + 1:]


def is_skill_gui_or_view(relative: Path) -> bool:
    """Return whether the relative production source belongs to the strict GUI/View scope."""
    parts = relative.as_posix().split("/")
    return any(
        parts[index:index + 3] == ["feature", "skill", directory]
        for index in range(len(parts) - 2)
        for directory in ("gui", "view")
    )


def report_raw_skill_master_display(relative: Path, text: str) -> list[str]:
    """Reject raw skill/item master text passed directly to a skill GUI/View display sink."""
    if not is_skill_gui_or_view(relative):
        return []
    violations: list[str] = []
    reported_lines: set[int] = set()
    for sink in MASTER_DISPLAY_SINK_PATTERN.finditer(text):
        opening_parenthesis = sink.end() - 1
        arguments = balanced_invocation_arguments(text, opening_parenthesis)
        if MASTER_DISPLAY_GETTER_PATTERN.search(arguments) is None:
            continue
        line = text.count("\n", 0, sink.start()) + 1
        if line in reported_lines:
            continue
        reported_lines.add(line)
        violations.append(f"raw skill master display: {relative.as_posix()}:{line}")
    return violations


def changed_source_lines(repo_root: Path, source_root: Path) -> dict[Path, set[int]]:
    """Return added/modified production line numbers compared with HEAD."""
    relative_source = source_root.relative_to(repo_root).as_posix()
    result = subprocess.run(
        [
            "git",
            "diff",
            "--no-ext-diff",
            "--unified=0",
            "HEAD",
            "--",
            relative_source,
        ],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    changed: dict[Path, set[int]] = {}
    current: Path | None = None
    new_line = 0
    for raw_line in result.stdout.splitlines():
        if raw_line.startswith("+++ b/"):
            repository_path = Path(raw_line[6:])
            try:
                current = repository_path.relative_to(relative_source)
            except ValueError:
                current = None
            continue
        hunk = DIFF_HUNK_PATTERN.match(raw_line)
        if hunk is not None:
            new_line = int(hunk.group(1))
            continue
        if current is None or raw_line.startswith("---"):
            continue
        if raw_line.startswith("+"):
            changed.setdefault(current, set()).add(new_line)
            new_line += 1
        elif not raw_line.startswith("-"):
            new_line += 1

    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", relative_source],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    for value in untracked.stdout.splitlines():
        repository_path = Path(value)
        path = repo_root / repository_path
        if path.suffix not in {".java", ".kt"}:
            continue
        relative = repository_path.relative_to(relative_source)
        line_count = len(path.read_text(encoding="utf-8").splitlines())
        changed[relative] = set(range(1, line_count + 1))
    return changed


def production_source_roots(plugin_root: Path) -> tuple[Path, Path]:
    """Return every supported production source root for the Plugin."""
    main_root = plugin_root / "src" / "main"
    return main_root / "java", main_root / "kotlin"


def report_inline_log_text(
    relative: Path,
    text: str,
    changed_lines: set[int],
) -> list[str]:
    """Reject new human-readable log fragments hidden in LogId arguments."""
    violations: list[str] = []
    for match in LOGGER_WITH_ID_CALL_PATTERN.finditer(text):
        arguments = match.group("arguments")
        if "LogId." not in arguments:
            continue
        start_line = text.count("\n", 0, match.start()) + 1
        end_line = text.count("\n", 0, match.end()) + 1
        if not any(start_line <= line <= end_line for line in changed_lines):
            continue
        literals = STRING_LITERAL_PATTERN.findall(arguments)
        if any(
            literal
            and STRUCTURED_LOG_LITERAL_PATTERN.fullmatch(literal) is None
            for literal in literals
        ):
            violations.append(f"direct log text argument: {relative}:{start_line}")
    return violations


def read_property_values(path: Path) -> dict[str, str]:
    """Read the simple one-line ID properties used by plugin resources."""
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^[ \t]*([A-Z]_[0-9]+)[ \t]*[=:][ \t]*(.*)$", line)
        if match is not None:
            values[match.group(1)] = match.group(2)
    return values


def split_top_level_arguments(arguments: str) -> list[str]:
    """Split a Java/Kotlin argument list without splitting nested calls."""
    values: list[str] = []
    start = 0
    depth = 0
    quote: str | None = None
    escaped = False
    for index, character in enumerate(arguments):
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {'"', "'"}:
            quote = character
        elif character in "([{":
            depth += 1
        elif character in ")]}" and depth > 0:
            depth -= 1
        elif character == "," and depth == 0:
            values.append(arguments[start:index].strip())
            start = index + 1
    tail = arguments[start:].strip()
    if tail:
        values.append(tail)
    return values


def looks_like_throwable(argument: str) -> bool:
    normalized = re.sub(r"\([^()]*\)$", "", argument.strip())
    return THROWABLE_ARGUMENT_PATTERN.fullmatch(normalized) is not None


def report_log_placeholder_mismatches(
    relative: Path,
    text: str,
    changed_lines: set[int],
    templates: dict[str, str],
) -> list[str]:
    """Check changed Logger calls against the selected properties template arity."""
    violations: list[str] = []
    for match in LOGGER_WITH_ID_CALL_PATTERN.finditer(text):
        start_line = text.count("\n", 0, match.start()) + 1
        end_line = text.count("\n", 0, match.end()) + 1
        if not any(start_line <= line <= end_line for line in changed_lines):
            continue
        arguments = split_top_level_arguments(match.group("arguments"))
        id_index = next(
            (index for index, value in enumerate(arguments) if "LogId." in value),
            None,
        )
        if id_index is None:
            continue
        id_match = re.search(r"LogId\.([A-Z]_[0-9]+)", arguments[id_index])
        if id_match is None or id_match.group(1) not in templates:
            continue
        log_id = id_match.group(1)
        values = arguments[id_index + 1:]
        method = match.group("method")
        if values and (
            method == "error"
            or (method == "log" and looks_like_throwable(values[0]))
        ):
            values = values[1:]
        expected = len(FORMAT_PLACEHOLDER_PATTERN.findall(templates[log_id]))
        if len(values) != expected:
            violations.append(
                f"log placeholder mismatch: {relative}:{start_line} "
                f"{log_id} expects {expected}, got {len(values)}"
            )
    return violations


def scan_direct_calls(
    source_root: Path,
    changed_lines: dict[Path, set[int]],
    log_templates: dict[str, str],
) -> list[str]:
    violations: list[str] = []
    allowed_message_file = Path(
        "io/github/maaasu/astralRecord/feature/player/service/PlayerMessageService.java"
    )
    allowed_logger_file = Path(
        "io/github/maaasu/astralRecord/infrastructure/logging/Logger.java"
    )
    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".java", ".kt"}:
            continue
        relative = path.relative_to(source_root)
        text = path.read_text(encoding="utf-8")
        if relative != allowed_logger_file:
            for pattern in (
                DIRECT_PLATFORM_LOG_PATTERN,
                DIRECT_LOGGER_VARIABLE_PATTERN,
                DIRECT_LOGGER_WITHOUT_ID_PATTERN,
                DIRECT_STD_STREAM_PATTERN,
            ):
                violations.extend(report_matches("direct logger call", relative, text, pattern))
            violations.extend(report_inline_log_text(
                relative,
                text,
                changed_lines.get(relative, set()),
            ))
            violations.extend(report_log_placeholder_mismatches(
                relative,
                text,
                set(range(1, len(text.splitlines()) + 1)),
                log_templates,
            ))
        if relative != allowed_message_file:
            violations.extend(report_matches(
                "direct player message call",
                relative,
                text,
                DIRECT_MESSAGE_PATTERN,
            ))
            violations.extend(report_matches(
                "direct raw player message literal",
                relative,
                text,
                DIRECT_RAW_MESSAGE_LITERAL_PATTERN,
            ))
            violations.extend(report_matches(
                "direct command message literal",
                relative,
                text,
                DIRECT_COMMAND_MESSAGE_LITERAL_PATTERN,
            ))
        violations.extend(report_raw_skill_master_display(relative, text))
    return violations


def scan_source_roots(
    source_roots: tuple[Path, ...],
    changed_lines_by_root: dict[Path, dict[Path, set[int]]],
    log_templates: dict[str, str],
) -> list[str]:
    """Scan Java and Kotlin production roots without assuming either one exists."""
    violations: list[str] = []
    for source_root in source_roots:
        if not source_root.is_dir():
            continue
        violations.extend(scan_direct_calls(
            source_root,
            changed_lines_by_root.get(source_root, {}),
            log_templates,
        ))
    return violations


def main() -> int:
    default_repo = Path(__file__).resolve().parents[4]
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=default_repo)
    args = parser.parse_args()

    plugin_root = args.repo_root.resolve() / "10_plugin" / "AstralRecord"
    source_roots = production_source_roots(plugin_root)
    java_root = source_roots[0]
    resources = plugin_root / "src" / "main" / "resources"

    log_id_file = java_root / "io/github/maaasu/astralRecord/infrastructure/logging/LogId.java"
    logger_file = resources / "logger.properties"
    player_id_file = java_root / "io/github/maaasu/astralRecord/feature/player/PlayerMsgId.java"
    player_file = resources / "player.properties"

    log_id_occurrences = read_id_occurrences(log_id_file, ID_PATTERN)
    logger_key_occurrences = read_id_occurrences(logger_file, PROPERTY_PATTERN)
    player_id_occurrences = read_id_occurrences(player_id_file, ID_PATTERN)
    player_key_occurrences = read_id_occurrences(player_file, PROPERTY_PATTERN)
    log_ids = set(log_id_occurrences)
    logger_keys = set(logger_key_occurrences)
    player_ids = set(player_id_occurrences)
    player_keys = set(player_key_occurrences)
    player_setting_id_file = (
        java_root / "io/github/maaasu/astralRecord/feature/playersetting/PlayerSettingMsgId.java"
    )
    player_setting_id_occurrences = read_id_occurrences(player_setting_id_file, ID_PATTERN)
    player_setting_ids = set(player_setting_id_occurrences)

    errors: list[str] = []
    errors.extend(report_duplicates("duplicate LogId", log_id_occurrences))
    errors.extend(report_duplicates("duplicate logger.properties key", logger_key_occurrences))
    errors.extend(report_duplicates("duplicate PlayerMsgId", player_id_occurrences))
    errors.extend(report_duplicates("duplicate player.properties key", player_key_occurrences))
    errors.extend(report_duplicates("duplicate PlayerSettingMsgId", player_setting_id_occurrences))
    errors.extend(report_id_number_mismatches("LogId number mismatch", log_id_file))
    errors.extend(report_id_number_mismatches("PlayerMsgId number mismatch", player_id_file))
    errors.extend(report_id_number_mismatches(
        "PlayerSettingMsgId number mismatch",
        player_setting_id_file,
    ))
    errors.extend(report_difference("LogId only", log_ids, logger_keys))
    errors.extend(report_difference("logger.properties only", logger_keys, log_ids))
    errors.extend(report_difference("PlayerMsgId only", player_ids, player_keys))
    errors.extend(report_difference("player.properties only", player_keys, player_ids))
    errors.extend(report_difference("PlayerSettingMsgId not in PlayerMsgId", player_setting_ids, player_ids))
    changed_lines_by_root = {
        source_root: changed_source_lines(args.repo_root.resolve(), source_root)
        for source_root in source_roots
        if source_root.is_dir()
    }
    errors.extend(scan_source_roots(
        source_roots,
        changed_lines_by_root,
        read_property_values(logger_file),
    ))

    if errors:
        print("Plugin resource validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(
        "Plugin resource validation passed: "
        f"{len(log_ids)} log IDs, {len(player_ids)} player message IDs."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
