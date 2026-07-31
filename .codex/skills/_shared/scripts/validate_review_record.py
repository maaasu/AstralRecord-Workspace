from __future__ import annotations

import re
import sys
from datetime import datetime
from pathlib import Path


SECTIONS = [
    "## 指摘一覧",
    "## 未確認/質問",
    "## 修正スキル入力サマリ",
    "## 確認した範囲",
    "## 対象外",
]
METADATA_LABELS = [
    "フォーマット版",
    "使用スキル",
    "対象パス",
    "作成日時",
    "完了状態",
    "指摘修正数 / 指摘数",
]
FINDING_LABELS = [
    "種別",
    "対象",
    "関連箇所",
    "根拠",
    "問題",
    "影響",
    "修正方針",
    "修正対象候補",
    "修正可否",
    "確信度",
    "修正状態",
]
QUESTION_LABELS = ["関連指摘", "確認事項", "判断が必要な理由", "確認結果", "確認状態"]
SUMMARY_LABELS = ["自動修正候補", "要確認", "推奨修正順", "対象範囲"]
SCOPE_LABELS = ["対象領域", "読んだルール/設計書", "読んだソース", "実行した検査"]
TYPES = {
    "code-review": {
        "仕様不整合",
        "コーディングルール違反",
        "バグ/アルゴリズム",
        "セキュリティ",
        "パフォーマンス",
        "死コード/重複",
        "テスト不足",
        "ドキュメント不整合",
        "可読性/保守性",
    },
    "docs-review": {"矛盾", "不適切なロジック", "不足", "未確定事項", "形式/命名", "運用リスク"},
}
NAME_RE = re.compile(
    r"^(?P<prefix>\[完了\]|\((?P<fixed>\d+)／(?P<total>\d+)\)) "
    r"(?P<stamp>\d{2}-\d{2}-\d{2} \d{2}：\d{2}：\d{2}) "
    r"(?P<skill>code-review|docs-review)\.md$"
)


def fail(messages: list[str]) -> int:
    for message in messages:
        print(f"ERROR: {message}", file=sys.stderr)
    return 1


def field_label(line: str) -> str | None:
    match = re.match(r"^- ([^:]+):", line)
    return match.group(1) if match else None


def field_value(line: str) -> str:
    return line.split(":", 1)[1].strip().strip("`")


def section_slice(lines: list[str], section: str) -> list[str]:
    start = lines.index(section) + 1
    later = [lines.index(candidate) for candidate in SECTIONS if lines.index(candidate) > start]
    end = min(later) if later else len(lines)
    return [line for line in lines[start:end] if line]


def validate(path: Path) -> list[str]:
    errors: list[str] = []
    if not path.is_file():
        return [f"file not found: {path}"]

    filename_match = NAME_RE.fullmatch(path.name)
    if not filename_match:
        errors.append("filename does not match the canonical format")

    text = path.read_text(encoding="utf-8")
    if "\r\n" in text:
        text = text.replace("\r\n", "\n")
    lines = text.splitlines()
    if not lines or lines[0] != "# AstralRecord レビュー記録":
        errors.append("first heading must be '# AstralRecord レビュー記録'")
        return errors

    metadata = [line for line in lines[1:] if line.startswith("- ")][: len(METADATA_LABELS)]
    if [field_label(line) for line in metadata] != METADATA_LABELS:
        errors.append("metadata labels or order are not canonical")
        return errors
    if any(not re.fullmatch(r"^- [^:]+: `.+`$", line) for line in metadata):
        errors.append("every metadata value must be wrapped in backticks")
    values = {field_label(line): field_value(line) for line in metadata}

    if values["フォーマット版"] != "1":
        errors.append("フォーマット版 must be 1")
    skill = values["使用スキル"]
    if skill not in {"code-review", "docs-review"}:
        errors.append("使用スキル must be code-review or docs-review")
    if values["完了状態"] not in {"未完了", "完了"}:
        errors.append("完了状態 must be 未完了 or 完了")
    if re.match(r"(?i)^E:\\AstralRecord-Worktrees\\", values["対象パス"]):
        errors.append("対象パス must not point at an ephemeral task worktree")

    created_match = re.fullmatch(r"(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})\+09:00", values["作成日時"])
    if not created_match:
        errors.append("作成日時 must use yyyy-MM-ddTHH:mm:ss+09:00")
    else:
        try:
            datetime.strptime(values["作成日時"], "%Y-%m-%dT%H:%M:%S+09:00")
        except ValueError:
            errors.append("作成日時 is not a valid date/time")

    count_match = re.fullmatch(r"(\d+) / (\d+)", values["指摘修正数 / 指摘数"])
    if not count_match:
        errors.append("指摘修正数 / 指摘数 must use '<fixed> / <total>'")
        fixed_count = total_count = -1
    else:
        fixed_count, total_count = map(int, count_match.groups())

    present_sections = [line for line in lines if line.startswith("## ")]
    if present_sections != SECTIONS:
        errors.append("section headings or order are not canonical")
        return errors

    prefix = "AR-CODE" if skill == "code-review" else "AR-DOC"
    question_prefix = "Q-CODE" if skill == "code-review" else "Q-DOC"
    findings = section_slice(lines, "## 指摘一覧")
    finding_headers = [i for i, line in enumerate(findings) if line.startswith("### ")]
    finding_ids_in_record = [
        finding_match.group(1)
        for line in findings
        if (finding_match := re.match(r"^### (AR-(?:CODE|DOC)-\d{3}) ", line))
    ]
    statuses: list[str] = []
    if findings == ["指摘なし。"]:
        if total_count != 0:
            errors.append("指摘なし requires a total finding count of 0")
    else:
        if not finding_headers:
            errors.append("指摘一覧 must contain finding blocks or exactly '指摘なし。'")
        elif finding_headers[0] != 0:
            errors.append("指摘一覧 contains text before the first finding")
        for sequence, header_index in enumerate(finding_headers, start=1):
            expected_id = f"{prefix}-{sequence:03d}"
            header = findings[header_index]
            if not re.fullmatch(rf"### {re.escape(expected_id)} \[(高|中|低|情報)\] .+", header):
                errors.append(f"invalid or non-sequential finding heading: {header}")
            end = finding_headers[sequence] if sequence < len(finding_headers) else len(findings)
            block = findings[header_index + 1 : end]
            labels = [field_label(line) for line in block]
            if labels != FINDING_LABELS:
                errors.append(f"{expected_id} fields or order are not canonical")
                continue
            values_by_label = {field_label(line): field_value(line) for line in block}
            for token_index in (0, 1, 2, 7, 8, 9, 10):
                if not re.fullmatch(r"^- [^:]+: `[^`]+`$", block[token_index]):
                    errors.append(f"{expected_id} token fields must be wrapped in backticks")
            if values_by_label["種別"] not in TYPES.get(skill, set()):
                errors.append(f"{expected_id} has invalid 種別")
            if values_by_label["修正可否"] not in {"自動修正可", "要確認", "設計判断待ち"}:
                errors.append(f"{expected_id} has invalid 修正可否")
            if values_by_label["確信度"] not in {"高", "中", "低"}:
                errors.append(f"{expected_id} has invalid 確信度")
            status = field_value(block[-1])
            if status not in {"未修正", "修正済み"}:
                errors.append(f"{expected_id} has invalid 修正状態")
            statuses.append(status)
        if len(finding_headers) != total_count:
            errors.append("finding block count does not match 指摘数")

    actual_fixed = statuses.count("修正済み")
    if fixed_count >= 0 and fixed_count != actual_fixed:
        errors.append("指摘修正数 does not match 修正済み findings")

    questions = section_slice(lines, "## 未確認/質問")
    open_question_ids: list[str] = []
    if questions != ["なし。"]:
        question_headers = [i for i, line in enumerate(questions) if line.startswith("### ")]
        if not question_headers:
            errors.append("未確認/質問 must contain question blocks or exactly 'なし。'")
        elif question_headers[0] != 0:
            errors.append("未確認/質問 contains text before the first question")
        for sequence, header_index in enumerate(question_headers, start=1):
            expected_id = f"{question_prefix}-{sequence:03d}"
            if questions[header_index] != f"### {expected_id}":
                errors.append(f"invalid or non-sequential question heading: {questions[header_index]}")
            end = question_headers[sequence] if sequence < len(question_headers) else len(questions)
            labels = [field_label(line) for line in questions[header_index + 1 : end]]
            if labels != QUESTION_LABELS:
                errors.append(f"{expected_id} fields or order are not canonical")
            else:
                related = field_value(questions[header_index + 1])
                if not re.fullmatch(r"^- 関連指摘: `(?:AR-(?:CODE|DOC)-\d{3}|なし)`$", questions[header_index + 1]):
                    errors.append(f"{expected_id} 関連指摘 must be one backticked finding ID or なし")
                if related != "なし" and related not in finding_ids_in_record:
                    errors.append(f"{expected_id} references a missing finding: {related}")
                question_result = field_value(questions[header_index + 4])
                question_status_line = questions[header_index + 5]
                if not re.fullmatch(r"^- 確認状態: `(未確認|確認済み)`$", question_status_line):
                    errors.append(f"{expected_id} has invalid 確認状態")
                elif field_value(question_status_line) == "未確認":
                    if question_result != "未確認":
                        errors.append(f"{expected_id} 未確認 state requires 確認結果: 未確認")
                    open_question_ids.append(expected_id)
                elif question_result == "未確認" or not question_result:
                    errors.append(f"{expected_id} 確認済み state requires a confirmed result")

    summary = section_slice(lines, "## 修正スキル入力サマリ")
    if [field_label(line) for line in summary] != SUMMARY_LABELS:
        errors.append("修正スキル入力サマリ fields or order are not canonical")
    else:
        summary_values = {field_label(line): field_value(line) for line in summary}
        unresolved_auto: list[str] = []
        unresolved_confirmation: list[str] = []
        current_id: str | None = None
        current_fixability: str | None = None
        for line in findings:
            if header := re.match(r"^### (AR-(?:CODE|DOC)-\d{3}) ", line):
                current_id = header.group(1)
                current_fixability = None
            elif current_id and line.startswith("- 修正可否:"):
                current_fixability = field_value(line)
            elif current_id and line == "- 修正状態: `未修正`":
                target = unresolved_auto if current_fixability == "自動修正可" else unresolved_confirmation
                target.append(current_id)
        def ids(value: str) -> list[str]:
            return re.findall(r"(?:AR|Q)-(?:CODE|DOC)-\d{3}", value)

        if ids(summary_values["自動修正候補"]) != unresolved_auto:
            errors.append("自動修正候補 must list only unresolved auto-fix findings in order")
        if ids(summary_values["要確認"]) != unresolved_confirmation + open_question_ids:
            errors.append("要確認 must list unresolved confirmation findings and questions in order")
        if ids(summary_values["推奨修正順"]) != unresolved_auto:
            errors.append("推奨修正順 must list unresolved auto-fix findings in order")
    scope = section_slice(lines, "## 確認した範囲")
    if [field_label(line) for line in scope] != SCOPE_LABELS:
        errors.append("確認した範囲 fields or order are not canonical")
    out_of_scope = section_slice(lines, "## 対象外")
    if not out_of_scope or any(not line.startswith("- ") for line in out_of_scope):
        errors.append("対象外 must contain one or more bullet lines")

    if re.search(r"<[^>]*(?:path|line|finding|project|review|根拠|問題|影響|方針|確認|理由|対象|範囲)[^>]*>", text):
        errors.append("unresolved template placeholder remains")

    if filename_match:
        expected_complete = total_count == fixed_count and not open_question_ids
        if filename_match.group("skill") != skill:
            errors.append("filename skill does not match 使用スキル")
        if expected_complete:
            if filename_match.group("prefix") != "[完了]" or values["完了状態"] != "完了":
                errors.append("completed counts require [完了] and 完了状態: 完了")
        else:
            if filename_match.group("fixed") != str(fixed_count) or filename_match.group("total") != str(total_count):
                errors.append("filename counts do not match record counts")
            if values["完了状態"] != "未完了":
                errors.append("unresolved findings require 完了状態: 未完了")
        if created_match:
            expected_stamp = datetime.strptime(
                values["作成日時"], "%Y-%m-%dT%H:%M:%S+09:00"
            ).strftime("%y-%m-%d %H：%M：%S")
            if filename_match.group("stamp") != expected_stamp:
                errors.append("filename timestamp does not match 作成日時")

    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: validate_review_record.py <record-path>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1]).resolve()
    errors = validate(path)
    if errors:
        return fail(errors)
    print(f"valid review record: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
