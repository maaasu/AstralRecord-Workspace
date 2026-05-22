---
name: astralrecord-docs-fix
description: Apply fixes to AstralRecord design documents based on an astralrecord-docs-review result. Use when asked to fix docs review findings, apply review results to Markdown design docs, update docs from AR-DOC finding IDs, or resolve review指摘 in 00_docs without changing source code.
---

# AstralRecord Docs Fix

## Core Rule

Fix design documents only. Do not open or edit source code, implementation files, database schema files, generated assets, runtime outputs, or deployment files. Treat implementation paths written in docs as scope labels only.

Use the review result as the authority for what to change. Do not invent missing design intent. If a finding is marked `要確認` or `設計判断待ち`, leave it unresolved unless the user explicitly provides the missing decision in the request.

## Inputs

Accept either:

- A docs target path plus a review result path or pasted review text.
- A request that references review finding IDs already present in the conversation, such as `AR-DOC-001`.

If no review result or finding detail is available, ask for the review result before editing.

## Workflow

1. Identify the docs target path and the review source.
2. Parse the review result using the `astralrecord-docs-review` report format:
   - `AR-DOC-*` finding IDs.
   - `対象`, `関連箇所`, `修正方針`, `修正対象候補`, and `修正可否`.
   - `修正スキル入力サマリ` when present.
3. Select findings to fix:
   - Fix all `修正可否: 自動修正可` findings by default.
   - If the user names specific IDs, fix only those IDs.
   - Do not fix `要確認` or `設計判断待ち` findings unless the user provides the required decision.
4. Read the target docs and nearby docs needed to make a coherent minimal edit. Prefer the files listed in `修正対象候補`; if it says `複数`, read each referenced target before editing.
5. Apply the smallest Markdown/design change that resolves the finding while preserving the local structure, headings, terminology, Wiki link style, and table format.
6. After editing, re-read changed snippets and verify that each fixed finding is addressed.
7. If the review source is a saved review result under `E:\AstralRecord-Workspace\99_work`, update that file after fixes:
   - set each fixed finding's `修正状態` to `修正済み`.
   - update `指摘修正数 / 指摘数`.
   - set `完了状態: 完了` when all findings are fixed.
   - rename the file to `yyyy-MM-dd HH：mm：ss <skill-name> (<fixed-count>／<finding-count>).md`, prefixing `[完了] ` when all findings are fixed.
   - use fullwidth `：` and `／` in the filename because Windows file names cannot contain `:` or `/`.
8. For plugin design docs, run:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-docs-review\scripts\docs_structure_audit.py <absolute-docs-path>
```

Use the audit as a format check. If the script reports unrelated pre-existing issues, list them separately instead of expanding the edit scope.

## Editing Guardrails

- Keep changes limited to docs under the requested target unless a finding explicitly points elsewhere.
- Preserve Japanese terminology already used in the feature docs.
- Prefer correcting the authoritative doc over duplicating explanations across multiple files.
- Keep unresolved design decisions in `9-未決事項` when the review result or user instruction says to record them instead of deciding them.
- Do not add implementation details that are not stated in docs or by the user.
- Do not remove ambiguity by choosing one behavior when the review marked it as requiring confirmation.

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
- ソースコード参照: していません
```
