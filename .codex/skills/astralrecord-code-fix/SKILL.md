---
name: astralrecord-code-fix
description: astralrecord-code-review のレビュー結果に基づき AstralRecord モノレポのソースコード、実装データ、workspace skill を修正する。AR-CODE 指摘 ID からプラグイン/API/Web/DB/filebase/resourcepack/.codex skills の指摘を、レビュー結果を正として最小変更で解決したい場合に使う。
---

# AstralRecord Code Fix

## Core Rule

Fix source code, implementation-adjacent data such as filebase/resourcepack assets, or workspace skill definitions based on a review result, then update any design documents in `00_docs/` whose described behavior is changed by those fixes. Do not invent new design intent. If a finding is marked `要確認` or `設計判断待ち`, leave it unresolved unless the user explicitly supplies the missing decision in the request.

Use the review result as the authority for what to change. Follow the target project's documented coding rules (root guide, project `README.md` / `AGENTS.md`, `astralrecord-code/references/*`) when making the edits. Keep edits minimal — do not bundle unrelated refactors.

Read `<task-root>\.codex\skills\_shared\review-record-format.md` completely before parsing or updating a saved review record. The canonical schema and updater are mandatory.

This skill handles both implementation fixes and corresponding docs sync in one pass. Use `$astralrecord-docs-fix` only when the change is design-doc-only. New work without a review result belongs to `$astralrecord-code`, or `$skill-creator` for `.codex/skills`.

## Inputs

Accept either:

- A code target path (file, feature directory, or project) plus a review result path or pasted review text.
- A request that references review finding IDs already present in the conversation, such as `AR-CODE-001`.

If no review result or finding detail is available, ask for the review result before editing.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project from the review result `対象範囲` / `確認した範囲` or from the absolute paths in each finding:
   - `10_plugin/AstralRecord` → Minecraft Plugin (Java/Kotlin, Paper/Spigot, Maven)
   - `10_plugin/AstralArchitect` → AI-assisted Minecraft building Plugin (Java/Paper/FAWE/Python)
   - `20_api/AstralRecordApi` → REST API (ASP.NET Core, C#)
   - `30_web/AstralRecordWeb` → Web (Razor Pages)
   - `40_filebase/` → file-based master data (YAML/Markdown)
   - `50_resourcepack/` → Minecraft Resource Pack (JSON/PNG)
   - `00_docs/40_Database設計書/` → SQL Server schema docs
   - `.codex/skills/` → Workspace skills (Markdown/Python/YAML)
   - `60_tool/` → Workspace build/deploy/development tools (PowerShell/C#/TypeScript/BAT)
3. Read documented rules for the target project before editing:
   - Plugin: root `PLUGIN_GUIDE.md`, project `README.md` / `AGENTS.md`, and `astralrecord-code/references/plugin-code.md`.
   - AstralArchitect: `10_plugin/AstralArchitect/AGENTS.md` and its linked project rules.
   - API: root `API_GUIDE.md`, project `README.md` / `AGENTS.md`, and `astralrecord-code/references/api-code.md`.
   - Web: root `README.md` "AstralRecord Web" section and `30_web/AstralRecordWeb/AGENTS.md`.
   - Filebase / Resourcepack / Database: the corresponding section of root `README.md` and the area's `AGENTS.md` / `README.md`.
   - Workspace skills: `.codex/skills/README.md`, the target `SKILL.md`, linked references/scripts, and `$skill-creator` instructions.
   - Tools: `60_tool/README.md` and any local `AGENTS.md` or linked tool documentation.
4. When a finding references a design document, read that design doc to understand the contract before editing the code.
5. If the target project cannot be determined, stop and ask the project-selection question from the root `AGENTS.md`.

## Workflow

1. Identify the code target path(s) and the review source.
   - Resolve `<task-root>` with `git rev-parse --show-toplevel`.
   - Require a dedicated non-`develop` task branch/worktree before editing code or the review record. If absent, use the integrated worktree flow instead of writing on `develop`.
   - When the supplied record path is under `E:\AstralRecord-Workspace`, remap it to the same relative path under `<task-root>` and update only that worktree copy.
   - If the supplied record is inside a different task worktree, stop and require the integrated review-fix entry to reuse that record's worktree. Never split fixes and the canonical record across branches.
2. Parse the review result using the `astralrecord-code-review` report format:
   - `AR-CODE-*` finding IDs.
   - `種別`, `対象`, `関連箇所`, `根拠`, `問題`, `影響`, `修正方針`, `修正対象候補`, `修正可否`, `確信度`, `修正状態`.
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
6. After editing code, identify design documents that describe the changed behavior:
   - Start from paths named in each finding's `関連箇所` / `根拠`.
   - Also check `00_docs/10_Plugin設計書/feature/` (plugin), `00_docs/20_API設計書/` (API), and the relevant area docs for the target project.
   - For each affected doc, apply the minimal edit that keeps it consistent with the fixed code: update method signatures, behavior descriptions, field definitions, or state diagrams as needed.
   - Do not restructure documents beyond what the fix requires.
7. After editing, re-read changed snippets and verify that each fixed finding is addressed.
8. Verify:
    - Run the narrowest meaningful build / test / static-analysis check for the touched project.
    - For feature/behavior fixes, executable scripts, schemas/data contracts, workspace skill logic, multi-file fixes, or security/concurrency/data-integrity fixes, capture complete verification output including standard error and inspect warnings as well as the exit status. Resolve warnings introduced by the fix and rerun the same check. For a remaining warning, classify it as pre-existing (verify against current local `develop` when practical) or external/toolchain-originated, and report the command, warning summary, classification, and reason. Do not mark a finding fixed while a new unexplained warning or a fix-originated warning remains, unless the user explicitly approved its deferral.
    - For Plugin source/resource fixes, run `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` and resolve ID/property drift, duplicate keys, log placeholder mismatches, direct logger/message calls, and string literals passed to command message helpers before marking any finding fixed. Verify manually that reused IDs describe the actual operation.
   - If a full build is too expensive or blocked, run targeted compile / test / lint checks and report what was not run.
9. If the review source is a saved record under `<task-root>\00_docs\99_資料\レビュー結果`, update only fixed states and derived metadata with:

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed <AR-CODE-IDs>
```

   - Do not manually rename the record, rewrite metadata, delete finding fields, summarize finding text, reorder findings, or renumber IDs.
   - Preserve its original timestamp, target path, and `code-review` skill name.
   - Add `--resolve-question '<Q-CODE-ID>=<confirmed answer>'` once per question only when the answer was supplied or unambiguously confirmed; otherwise omit it and keep the question `未確認`.
   - Validate the returned path again with `validate_review_record.py` and do not report the record update complete on failure.

## Editing Guardrails

- Keep changes limited to code, implementation-adjacent data, workspace skill definitions, and directly-affected design documents under `00_docs/` unless a finding explicitly points elsewhere.
- Design doc edits must be minimal and traceable to a fixed finding. Do not restructure, rewrite, or extend beyond what the code change requires.
- Prefer fixing the authoritative location over duplicating fixes across multiple files.
- Do not introduce new abstractions, helpers, or configuration toggles beyond what the finding requires.
- Resolve `未確認/質問` (`Q-CODE-*`) only when the answer is already present in the review result, supplied by the user, or unambiguously confirmed from the required context. Otherwise leave them unresolved and list them in the report.
- Do not change public APIs, command names, message IDs, log categories, table names, item IDs, or resource keys unless the finding explicitly requires it.
- Preserve Japanese terminology and message wording already used in the project.

## Out of Scope

- Design-doc-only changes outside `00_docs/40_Database設計書` → `$astralrecord-docs-fix`. SQL Server schema docs and workspace skill Markdown remain in this skill's implementation-artifact scope.
- New work without a review result → `$astralrecord-code`, or `$skill-creator` for `.codex/skills`.
- Large-scale refactors. Keep each fix minimal; defer structural redesign to a separate task.

## Report Format

Write the result in Japanese.

```markdown
## 修正結果
- `AR-CODE-001`: 修正済み - <何を変えたか>
- `AR-CODE-002`: 未対応（要確認） - <必要な確認>

## 変更ファイル

### コード
- `<path>`: <変更概要>

### 設計書
- `<path>`: <変更概要> / なし

## 未対応
- `AR-CODE-002`: <理由>
- `Q-CODE-001`: <理由>

## 検証
- `<command>`: 成功 / 失敗 / 未実行（理由）
- 変更箇所の再確認: 実施
- 設計書参照: <読んだ docs / なし>
- 設計書編集: <編集したdocsパス一覧 / なし>

## 残事項
- なし / <追加のdocs整備や設計判断が必要な項目>
```

## Extension Points

プロジェクト固有の修正観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-fix.md` … `10_plugin/AstralRecord` 固有の修正観点。
- `references/api-code-fix.md` … `20_api/AstralRecordApi` 固有の修正観点。
- `references/web-code-fix.md` … `30_web/AstralRecordWeb` 固有の修正観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
