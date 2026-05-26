---
name: astralrecord-code-fix
description: astralrecord-code-review のレビュー結果に基づき AstralRecord モノレポのソースコードを修正する。code レビュー指摘の修正、レビュー結果の実装への反映、AR-CODE 指摘 ID からのコード更新、plugin/API/Web/database/filebase/resourcepack の指摘解決を、レビュー結果を権威として最小修正で行いたい場合に使う。
---

# AstralRecord Code Fix

## Core Rule

Fix source code (and implementation-adjacent data such as filebase / resourcepack assets) based on a review result. Do not invent new design intent. If a finding is marked `要確認` or `設計判断待ち`, leave it unresolved unless the user explicitly supplies the missing decision in the request.

Use the review result as the authority for what to change. Follow the target project's documented coding rules (root guide, project `README.md` / `AGENTS.md`, `astralrecord-code/references/*`) when making the edits. Keep edits minimal — do not bundle unrelated refactors.

This skill is the code counterpart of `$astralrecord-docs-fix`. Design document edits remain the job of `$astralrecord-docs-fix`; new implementation work without a review result belongs to `$astralrecord-code`.

## Inputs

Accept either:

- A code target path (file, feature directory, or project) plus a review result path or pasted review text.
- A request that references review finding IDs already present in the conversation, such as `AR-CODE-001`.

If no review result or finding detail is available, ask for the review result before editing.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project from the review result `対象範囲` / `確認した範囲` or from the absolute paths in each finding:
   - `10_plugin/AstralRecord` → Minecraft Plugin (Java/Kotlin, Paper/Spigot, Maven)
   - `20_api/AstralRecordApi` → REST API (ASP.NET Core, C#)
   - `30_web/AstralRecordWeb` → Web (Razor Pages)
   - `40_filebase/` → file-based master data (YAML/Markdown)
   - `50_resourcepack/` → Minecraft Resource Pack (JSON/PNG)
   - `00_docs/40_Database設計書/` → SQL Server schema docs
3. Read documented rules for the target project before editing:
   - Plugin: root `PLUGIN_GUIDE.md`, project `README.md` / `AGENTS.md`, and `astralrecord-code/references/plugin-code.md`.
   - API: root `API_GUIDE.md`, project `README.md` / `AGENTS.md`, and `astralrecord-code/references/api-code.md`.
   - Web: root `README.md` "AstralRecord Web" section and `30_web/AstralRecordWeb/AGENTS.md`.
   - Filebase / Resourcepack / Database: the corresponding section of root `README.md` and the area's `AGENTS.md` / `README.md`.
4. When a finding references a design document, read that design doc to understand the contract before editing the code. Do not edit the design doc from this skill; if the implementation change would also change documented behavior, record that in `残事項` so `$astralrecord-docs-fix` or `$astralrecord-code` can follow up.
5. If the target project cannot be determined, stop and ask the project-selection question from the root `AGENTS.md`.

## Workflow

1. Identify the code target path(s) and the review source.
2. Parse the review result using the `astralrecord-code-review` report format:
   - `AR-CODE-*` finding IDs.
   - `種別`, `対象`, `関連箇所`, `根拠`, `問題`, `影響`, `修正方針`, `修正可否`, `確信度`.
   - `修正スキル入力サマリ` (自動修正候補 / 要確認 / 推奨修正順 / 対象範囲) when present.
3. Select findings to fix:
   - Fix all `修正可否: 自動修正可` findings by default.
   - If the user names specific IDs, fix only those IDs.
   - Do not fix `要確認` or `設計判断待ち` findings unless the user provides the required decision.
   - Honor `推奨修正順` when present so dependent fixes land in a coherent order.
4. Read the minimum necessary code for each fix:
   - The file at `対象` and its `関連箇所`.
   - Call sites, tests, fixtures, and resource files that gate the behavior.
   - Existing enums, IDs, repositories, DTOs, services, helpers, messages, and resource conventions to match local patterns.
5. Apply the smallest code change that resolves the finding while preserving:
   - Surrounding language, naming, package/layer structure, DI style, error handling, and test patterns.
   - The project's documented coding rules.
   - Unrelated behavior — no opportunistic refactors.
6. After editing, re-read changed snippets and verify that each fixed finding is addressed.
7. Verify:
   - Run the narrowest meaningful build / test / static-analysis check for the touched project.
   - If a full build is too expensive or blocked, run targeted compile / test / lint checks and report what was not run.
8. If the review source is a saved review result under `E:\AstralRecord-Workspace\99_work`, update that file after fixes:
   - set each fixed finding's `修正状態` to `修正済み`.
   - update `指摘修正数 / 指摘数`.
   - set `完了状態: 完了` when all findings are fixed.
   - rename the file to `yyyy-MM-dd HH：mm：ss <skill-name> (<fixed-count>／<finding-count>).md`, prefixing `[完了] ` when all findings are fixed.
   - use fullwidth `：` and `／` in the filename because Windows file names cannot contain `:` or `/`.

## Editing Guardrails

- Keep changes limited to code (and implementation-adjacent data) under the requested target unless a finding explicitly points elsewhere.
- Do not modify design documents (`00_docs/...` Markdown). If a fix would change documented behavior, record it under `残事項` for `$astralrecord-docs-fix`.
- Prefer fixing the authoritative location over duplicating fixes across multiple files.
- Do not introduce new abstractions, helpers, or configuration toggles beyond what the finding requires.
- Do not silently resolve `未確認/質問` (`Q-CODE-*`). Leave them unresolved and list them in the report.
- Do not change public APIs, command names, message IDs, log categories, table names, item IDs, or resource keys unless the finding explicitly requires it.
- Preserve Japanese terminology and message wording already used in the project.

## Out of Scope

- Design document edits → `$astralrecord-docs-fix`.
- New feature implementation without a review result → `$astralrecord-code`.
- Large-scale refactors. Keep each fix minimal; defer structural redesign to a separate task.

## Report Format

Write the result in Japanese.

```markdown
## 修正結果
- `AR-CODE-001`: 修正済み - <何を変えたか>
- `AR-CODE-002`: 未対応（要確認） - <必要な確認>

## 変更ファイル
- `<path>`: <変更概要>

## 未対応
- `AR-CODE-002`: <理由>
- `Q-CODE-001`: <理由>

## 検証
- `<command>`: 成功 / 失敗 / 未実行（理由）
- 変更箇所の再確認: 実施
- 設計書参照: <読んだ docs / なし>
- 設計書編集: していません

## 残事項
- なし / <docs 反映が必要な項目（$astralrecord-docs-fix へ引き継ぐ内容）>
```

## Extension Points

プロジェクト固有の修正観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-fix.md` … `10_plugin/AstralRecord` 固有の修正観点。
- `references/api-code-fix.md` … `20_api/AstralRecordApi` 固有の修正観点。
- `references/web-code-fix.md` … `30_web/AstralRecordWeb` 固有の修正観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
