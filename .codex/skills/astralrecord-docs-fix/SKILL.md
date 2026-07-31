---
name: astralrecord-docs-fix
description: astralrecord-docs-review のレビュー結果に基づき AstralRecord 設計書を修正する。設計書レビュー指摘の反映、AR-DOC 指摘 ID からの Markdown 更新、00_docs 配下の指摘解決を、ソースコードを変更せずに行いたい場合に使う。
---

# AstralRecord Docs Fix

## Core Rule

Fix design documents only. Do not open or edit source code, implementation files, database schema files, generated assets, runtime outputs, or deployment files. Treat implementation paths written in docs as scope labels only.

Use the review result as the authority for what to change. Do not invent missing design intent. If a finding is marked `要確認` or `設計判断待ち`, leave it unresolved unless the user explicitly provides the missing decision in the request.

Read `<task-root>\.codex\skills\_shared\review-record-format.md` completely before parsing or updating a saved review record. The canonical schema and updater are mandatory.

## Inputs

Accept either:

- A docs target path plus a review result path or pasted review text.
- A request that references review finding IDs already present in the conversation, such as `AR-DOC-001`.

If no review result or finding detail is available, ask for the review result before editing.

## Workflow

1. Identify the docs target path and the review source.
   - Resolve `<task-root>` with `git rev-parse --show-toplevel`.
   - Require a dedicated non-`develop` task branch/worktree before editing docs or the review record. If absent, use the integrated worktree flow instead of writing on `develop`.
   - When the supplied record path is under `E:\AstralRecord-Workspace`, remap it to the same relative path under `<task-root>` and update only that worktree copy.
   - If the supplied record is inside a different task worktree, stop and require the integrated review-fix entry to reuse that record's worktree. Never split fixes and the canonical record across branches.
2. Parse the review result using the `astralrecord-docs-review` report format:
   - `AR-DOC-*` finding IDs.
   - `対象`, `関連箇所`, `修正方針`, `修正対象候補`, `修正可否`, `確信度`, and `修正状態`.
   - `修正スキル入力サマリ` when present.
3. Select findings to fix:
   - Fix all `修正可否: 自動修正可` findings by default.
   - If the user names specific IDs, fix only those IDs.
   - Do not fix `要確認` or `設計判断待ち` findings unless the user provides the required decision.
4. Read the target docs and nearby docs needed to make a coherent minimal edit. Prefer the files listed in `修正対象候補`; if it says `複数`, read each referenced target before editing.
5. Apply the smallest Markdown/design change that resolves the finding while preserving the local structure, headings, terminology, Wiki link style, and table format.
6. After editing, re-read changed snippets and verify that each fixed finding is addressed.
7. If the review source is a saved record under `<task-root>\00_docs\99_資料\レビュー結果`, update only fixed states and derived metadata with:

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed <AR-DOC-IDs>
```

   - Do not manually rename the record, rewrite metadata, delete finding fields, summarize finding text, reorder findings, or renumber IDs.
   - Preserve its original timestamp, target path, and `docs-review` skill name.
   - Add `--resolve-question '<Q-DOC-ID>=<confirmed answer>'` once per question only when the answer was supplied or unambiguously confirmed; otherwise omit it and keep the question `未確認`.
   - Validate the returned path again with `validate_review_record.py` and do not report the record update complete on failure.
8. For plugin design docs, run:

```powershell
python <task-root>\.codex\skills\astralrecord-docs-review\scripts\docs_structure_audit.py <absolute-docs-path>
```

Use the audit as a format check. If the script reports unrelated pre-existing issues, list them separately instead of expanding the edit scope.
9. When the changed docs are under `00_docs/10_Plugin設計書`, also run the following black-box consistency gate from `<task-root>`, even though this skill does not open or interpret Plugin source. This checks whether existing permanent-test references still resolve after docs-only heading/path edits.

```powershell
python <task-root>\.codex\skills\astralrecord-plugin-test\scripts\validate_test_traceability.py --repo-root <task-root>
```

Do not substitute `mvn verify`; its Plugin shade output targets the main workspace distribution path.

## Editing Guardrails

- Keep changes limited to docs under the requested target unless a finding explicitly points elsewhere.
- Preserve Japanese terminology already used in the feature docs.
- Prefer correcting the authoritative doc over duplicating explanations across multiple files.
- Keep unresolved design decisions in category `9` when the review result or user instruction says to record them instead of deciding them. Keep accepted but unimplemented specifications in category `8`; do not mix the two states.
- Do not add implementation details that are not stated in docs or by the user.
- Do not remove ambiguity by choosing one behavior when the review marked it as requiring confirmation.
- Resolve `未確認/質問` (`Q-DOC-*`) only when the answer is already present in the review result, supplied by the user, or unambiguously confirmed from the allowed docs context. Otherwise leave them unresolved and list them in the report.

## Report Format

Write the result in Japanese.

```markdown
## 修正結果
- `AR-DOC-001`: 修正済み - <何を変えたか>
- `AR-DOC-002`: 未対応（要確認） - <必要な確認>

## 変更ファイル
- `<path>`: <変更概要>

## 未対応
- `AR-DOC-002`: <理由>

## 検証
- 変更箇所の再確認: 実施
- docs_structure_audit.py: 実行 / 未実行（理由: ...）
- validate_test_traceability.py: 実行 / 未実行（理由: Plugin設計書以外）
- ソースコード参照: していません
```
