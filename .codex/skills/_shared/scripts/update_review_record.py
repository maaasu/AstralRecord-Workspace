from __future__ import annotations

import argparse
import re
from pathlib import Path

from validate_review_record import validate


def replace_field(lines: list[str], label: str, value: str) -> None:
    prefix = f"- {label}:"
    matches = [i for i, line in enumerate(lines) if line.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"expected one field '{label}', found {len(matches)}")
    lines[matches[0]] = f"{prefix} `{value}`"


def replace_raw_field(lines: list[str], label: str, value: str) -> None:
    prefix = f"- {label}:"
    matches = [i for i, line in enumerate(lines) if line.startswith(prefix)]
    if len(matches) != 1:
        raise ValueError(f"expected one field '{label}', found {len(matches)}")
    lines[matches[0]] = f"{prefix} {value}"


def id_list(ids: list[str], separator: str = ", ") -> str:
    return separator.join(f"`{finding_id}`" for finding_id in ids) if ids else "`なし`"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--fixed", nargs="*", default=[])
    parser.add_argument("--resolve-question", action="append", default=[], metavar="ID=RESULT")
    args = parser.parse_args()
    path = args.path.resolve()
    if not args.fixed and not args.resolve_question:
        parser.error("provide at least one --fixed or --resolve-question value")

    question_results: dict[str, str] = {}
    for value in args.resolve_question:
        if "=" not in value:
            parser.error(f"invalid --resolve-question value: {value}")
        question_id, result = value.split("=", 1)
        if not re.fullmatch(r"Q-(?:CODE|DOC)-\d{3}", question_id) or not result.strip():
            parser.error(f"invalid --resolve-question value: {value}")
        question_results[question_id] = result.strip()

    errors = validate(path)
    if errors:
        raise SystemExit("record is invalid before update:\n" + "\n".join(errors))

    original_text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    lines = original_text.splitlines()
    finding_start = lines.index("## 指摘一覧") + 1
    finding_end = lines.index("## 未確認/質問")
    question_start = finding_end + 1
    question_end = lines.index("## 修正スキル入力サマリ")
    fixed_ids = set(args.fixed)
    seen: set[str] = set()
    current_id: str | None = None
    for index in range(finding_start, finding_end):
        line = lines[index]
        header = re.match(r"^### (AR-(?:CODE|DOC)-\d{3}) ", line)
        if header:
            current_id = header.group(1)
            continue
        if current_id in fixed_ids and line == "- 修正状態: `未修正`":
            lines[index] = "- 修正状態: `修正済み`"
            seen.add(current_id)

    missing = fixed_ids - seen
    if missing:
        raise SystemExit(f"IDs not found or already fixed: {', '.join(sorted(missing))}")

    resolved_question_ids = set(question_results)
    resolved_result_seen: set[str] = set()
    resolved_status_seen: set[str] = set()
    current_question_id: str | None = None
    for index in range(question_start, question_end):
        line = lines[index]
        question_header = re.match(r"^### (Q-(?:CODE|DOC)-\d{3})$", line)
        if question_header:
            current_question_id = question_header.group(1)
            continue
        if current_question_id in resolved_question_ids and line == "- 確認結果: `未確認`":
            lines[index] = f"- 確認結果: {question_results[current_question_id]}"
            resolved_result_seen.add(current_question_id)
        if current_question_id in resolved_question_ids and line == "- 確認状態: `未確認`":
            lines[index] = "- 確認状態: `確認済み`"
            resolved_status_seen.add(current_question_id)

    missing_questions = resolved_question_ids - (resolved_result_seen & resolved_status_seen)
    if missing_questions:
        raise SystemExit(f"question IDs not found or already resolved: {', '.join(sorted(missing_questions))}")

    finding_ids: list[str] = []
    fixability: dict[str, str] = {}
    status_by_id: dict[str, str] = {}
    current_id = None
    for line in lines[finding_start:finding_end]:
        if match := re.match(r"^### (AR-(?:CODE|DOC)-\d{3}) ", line):
            current_id = match.group(1)
            finding_ids.append(current_id)
        elif current_id and line.startswith("- 修正可否:"):
            fixability[current_id] = line.split(":", 1)[1].strip().strip("`")
        elif current_id and line.startswith("- 修正状態:"):
            status_by_id[current_id] = line.split(":", 1)[1].strip().strip("`")

    fixed_count = sum(status == "修正済み" for status in status_by_id.values())
    total_count = len(finding_ids)
    replace_field(lines, "指摘修正数 / 指摘数", f"{fixed_count} / {total_count}")

    unresolved = [finding_id for finding_id in finding_ids if status_by_id[finding_id] == "未修正"]
    auto_fix = [finding_id for finding_id in unresolved if fixability[finding_id] == "自動修正可"]
    needs_confirmation = [finding_id for finding_id in unresolved if fixability[finding_id] != "自動修正可"]
    open_question_ids: list[str] = []
    current_question_id = None
    for line in lines[question_start:question_end]:
        if question_match := re.match(r"^### (Q-(?:CODE|DOC)-\d{3})$", line):
            current_question_id = question_match.group(1)
        elif current_question_id and line == "- 確認状態: `未確認`":
            open_question_ids.append(current_question_id)
    replace_raw_field(lines, "自動修正候補", id_list(auto_fix))
    replace_raw_field(lines, "要確認", id_list(needs_confirmation + open_question_ids))
    replace_raw_field(lines, "推奨修正順", id_list(auto_fix, " -> "))

    is_complete = fixed_count == total_count and not open_question_ids
    replace_field(lines, "完了状態", "完了" if is_complete else "未完了")

    base_match = re.fullmatch(
        r"(?:\[完了\]|\(\d+／\d+\)) (?P<base>\d{2}-\d{2}-\d{2} \d{2}：\d{2}：\d{2} (?:code-review|docs-review)\.md)",
        path.name,
    )
    if not base_match:
        raise SystemExit("cannot derive canonical base filename")
    prefix = "[完了]" if is_complete else f"({fixed_count}／{total_count})"
    destination = path.with_name(f"{prefix} {base_match.group('base')}")
    if destination != path:
        if destination.exists():
            raise SystemExit(f"destination already exists: {destination}")

    text = "\n".join(lines) + "\n"
    if destination == path:
        path.write_text(text, encoding="utf-8", newline="\n")
        errors = validate(path)
        if errors:
            path.write_text(original_text, encoding="utf-8", newline="\n")
            raise SystemExit("record is invalid after update:\n" + "\n".join(errors))
    else:
        destination.write_text(text, encoding="utf-8", newline="\n")
        errors = validate(destination)
        if errors:
            destination.unlink()
            raise SystemExit("record is invalid after update:\n" + "\n".join(errors))
        path.unlink()
    print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
