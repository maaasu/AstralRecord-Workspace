---
name: astralrecord-code-review
description: AstralRecord モノレポのソースコード、実装データ、workspace skill をレビューし、固定書式のレビュー記録を専用 task worktree 内へ安全に保存する。コード/skillレビュー、実装監査、ルール準拠、設計整合、バグ・死コード・セキュリティ・テスト・保守性の評価、実装後の独立レビューで使う。対象成果物は編集せず、可能な場合は読み取り専用サブエージェントを活用する。
---

# AstralRecord Code Review

## Core Rule

Review implementation artifacts without editing them. This includes source code, implementation configuration/data, SQL Server schema docs, scripts, tools, and workspace skill definitions; other design-doc-only review belongs to `$astralrecord-docs-review`. The only file content this skill may create is the canonical review record. Treat findings as proposals; actual changes are the job of `$astralrecord-code-fix`.

Base every judgment on documented rules first (root guide, project README/AGENTS.md, design docs, `references/*`). Do not infer project rules from the code alone when an authoritative document exists. If documented rules are silent, fall back to general engineering practice and clearly mark the finding as a general-practice judgment rather than a rule violation.

Resolve the selected repository root, then read `<repo-root>\.codex\skills\_shared\review-record-format.md` completely before reviewing. Its storage, filename, body schema, state, and validation rules are mandatory and override examples in this skill.

## Git Preflight

Complete this before creating the review record.

1. Resolve the selected checkout with `git rev-parse --show-toplevel` and inspect `git status --short --branch`.
2. If already inside a dedicated non-`develop` task worktree, set that root as `<task-root>`.
3. If the selected checkout is the main `develop` workspace, inspect whether uncommitted changes overlap the review target. If they do, stop before writing and require those changes to be moved to a task worktree; never review a stale HEAD copy as though it contains the dirty diff. Otherwise invoke `$astralrecord-git-worktree-develop` in Prepare mode to create `codex/review-<slug>`, remap the target path into that worktree, and use the new root as `<task-root>`.
4. Never save to the literal main-workspace path from a task worktree. The only destination is `<task-root>\00_docs\99_資料\レビュー結果`.
5. If a review-only request ends in an existing task worktree, invoke `$astralrecord-commit-current-diff` and commit only the validated record. Do not finalize an existing implementation worktree unless requested.
6. If this skill created a review-only worktree, invoke `$astralrecord-git-worktree-develop` in Finalize mode directly after validation; Finalize owns staging and committing the record. If finalize is blocked, keep the branch/worktree and report it. Never pre-commit and then call Finalize, and never fall back to writing on `develop`.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project from the absolute path or technical signals:
   - `10_plugin/AstralRecord` → Minecraft Plugin (Java/Kotlin, Paper/Spigot, Maven)
   - `10_plugin/AstralArchitect` → AI-assisted Minecraft building Plugin (Java/Paper/FAWE/Python)
   - `20_api/AstralRecordApi` → REST API (ASP.NET Core, C#)
   - `30_web/AstralRecordWeb` → Web (Razor Pages)
   - `40_filebase/` → file-based master data (YAML/Markdown)
   - `50_resourcepack/` → Minecraft Resource Pack (JSON/PNG)
   - `00_docs/40_Database設計書/` → SQL Server schema docs
   - `.codex/skills/` → Workspace skills (Markdown/Python/YAML)
   - `60_tool/` → Workspace build/deploy/development tools (PowerShell/C#/TypeScript/BAT)
3. Read documented rules for the target project before judging:
   - Plugin: root `PLUGIN_GUIDE.md`, project `README.md`/`AGENTS.md`, and any `astralrecord-code/references/plugin-code.md`.
   - AstralArchitect: `10_plugin/AstralArchitect/AGENTS.md` and its linked project rules.
   - API: root `API_GUIDE.md`, project `README.md`/`AGENTS.md`, and `astralrecord-code/references/api-code.md`.
   - Web: root `README.md` "AstralRecord Web" section and `30_web/AstralRecordWeb/AGENTS.md`.
   - Filebase / Resourcepack / Database: the corresponding section of root `README.md` and the area's `AGENTS.md`/`README.md`.
   - Workspace skills: `.codex/skills/README.md`, the target `SKILL.md`, linked references/scripts, and `$skill-creator` instructions.
   - Tools: `60_tool/README.md` and any local `AGENTS.md` or linked tool documentation.
4. When the review references a design document area, read the relevant `00_docs/...` design docs to check code↔design consistency. Do not perform a docs-only review here; that belongs to `$astralrecord-docs-review`.
5. If the target project cannot be determined, stop and ask the project-selection question from the root `AGENTS.md`.

## Workflow

1. Complete Git Preflight and define `<task-root>` before any file write.
2. Define the review scope:
   - File set: explicit path(s), feature directory, recent diff range, or a named module.
   - Review depth: quick scan vs. deep review. Default is deep review when a single feature/path is given.
3. Map code to design:
   - For Plugin features under `00_docs/10_Plugin設計書`, identify the corresponding code modules from `FEATURE_CATALOG.md`, the feature overview, or naming conventions. For other design areas, follow that area's documented entry-point rules.
   - For custom-instruction scope (e.g. "ホットバー周り"), use grep/glob to enumerate the affected files.
4. Read the minimum necessary code:
   - Entry points, public APIs/commands/endpoints/events, service/repository boundaries, data models, and call sites of the changed symbols.
   - Tests, fixtures, and resource files that gate the behavior.
5. Review against the checklist (see "Review Checklist"). For each issue, distinguish:
   - Rule violation (documented rule exists and is broken).
   - Bug / algorithmic defect (incorrect behavior, missing edge case, race, leak).
   - Design mismatch (code contradicts a design doc).
   - Dead / unreachable / unused code.
   - Maintainability / readability concern (general practice).
6. Verify suspected issues:
   - Trace call sites with grep before claiming "unused" or "dead".
   - Re-read the documented rule before claiming a rule violation.
   - When a doubt remains, downgrade the finding to a question instead of inventing a defect.
7. For a non-trivial scope and when sub-agents are available, delegate at least one independent read-only pass to an agent that did not implement the change. For multi-project, security, concurrency, or data-integrity work, use a second read-only specialist with a distinct concern. Give them the target diff and authoritative context, not expected findings. The coordinating reviewer de-duplicates evidence and remains the only canonical record writer. Never allow parallel edits to the record.
8. Resolve questions that can be answered from the reviewed code, design docs, or documented rules during the review. Only leave `## 未確認/質問` entries for decisions or facts that cannot be confirmed from the allowed review sources.
9. If a finding-specific question is needed, put it under `## 未確認/質問` and reference the finding from `関連指摘`. Do not leave questions inline only inside the finding body.
10. Create exactly one canonical record using the shared format. Use the allowed code-review types below, start every new finding with `修正状態: 未修正`, and do not add extra headings.
    - Round 1 creates the record and returns its absolute path to the coordinator.
    - Round 2 must receive that canonical record path as input, update the same file, preserve existing IDs/text/timestamp/target, and append only new sequential findings. It must not create a second record.
11. Validate the saved file with `<task-root>\.codex\skills\_shared\scripts\validate_review_record.py`. Correct the record until it passes.
12. Complete the review-only commit/finalize behavior from Git Preflight. In an integrated implementation workflow, leave commit/finalize ownership to the coordinator so implementation, record, and fixes remain one scoped task.

## Review Checklist

Adjust depth to the target project, but cover these categories:

- 仕様整合性
  - 設計書（概要 / モデル / ユースケース / メソッド仕様 / 統合フロー / 例外・ログ・運用）と実装が一致しているか。
  - 設計書未決事項 (`9-未決事項`) を勝手に実装で確定していないか。
- コーディングルール遵守
  - 言語規約・パッケージ/レイヤ構成・命名 (`PLUGIN_GUIDE.md`/`API_GUIDE.md` などのルール準拠)。
  - DI、リポジトリ、サービス、DTO、Enum、ID、メッセージ、ログのカテゴリ運用が既存パターンに沿っているか。
- 正しさ / アルゴリズム / バグ
  - ロジックの破綻、論理矛盾、境界値 (空・null・最大値・負値・空白文字列・Unicode・タイムゾーン) の扱い。
  - 例外/エラー処理の握りつぶし、再スロー、ログ粒度、メッセージ ID/カテゴリ。
  - 状態遷移、冪等性、リトライ、ロールバック、トランザクション境界、並行性 (スレッド/コルーチン/非同期/メインスレッド制約)。
  - データ整合性 (DB / filebase / resourcepack / メモリ間の不一致)。
- セキュリティ / 信頼境界
  - 入力検証、認可/権限 (コマンド権限、API ロール)、SQL 注入、パス操作、外部入力のシリアライズ、機微情報のログ出力。
- パフォーマンス / リソース
  - N+1、無駄なループ・無駄なコピー、大きなコレクションへのロック、サーバ tick での重処理、ホットパスの I/O、未解放リソース、ファイナライザ依存。
- 死コード / 重複 / 過剰実装
  - 呼ばれていない public/internal、未使用 import/フィールド/パラメータ、コピペ、過剰抽象、フラグオフのまま残るパス、TODO/FIXME 放置。
- テスト / 検証容易性
  - テストの有無、シナリオ網羅 (正常/異常/境界)、固定値依存、テスト名と検証対象のズレ、フェイク/モックの妥当性。
- ドキュメント / 運用
  - public API、コマンド、設定キー、メッセージ、ログ ID の docs/README 反映状況。
  - resourcepack/filebase の整合 (ID, パス, モデル, JSON スキーマ)。
- 可読性 / 保守性
  - 関数長、責務集中、副作用の局所化、マジックナンバー、コメントが「なぜ」を説明しているか、命名と振る舞いの乖離。

不要な範囲は明示的に「対象外」と書く。範囲外を装って網羅率を水増ししない。

## Report Format

Write the review in Japanese and emit the validated saved record body without restructuring it. Use the canonical body and exact section order from the shared format. Severity is exactly `[高]`, `[中]`, `[低]`, or `[情報]`.

The allowed `種別` values for code review are:

`仕様不整合` | `コーディングルール違反` | `バグ/アルゴリズム` | `セキュリティ` | `パフォーマンス` | `死コード/重複` | `テスト不足` | `ドキュメント不整合` | `可読性/保守性`

Set `修正可否: 自動修正可` only when `$astralrecord-code-fix` can resolve the finding without a new design decision. Always include `修正対象候補`, `確信度`, and `修正状態` in the shared field order.

## Review Result File

Save exactly one Markdown record under `<task-root>\00_docs\99_資料\レビュー結果`. The shared format is the sole authority for filename, metadata, fields, empty values, state transitions, and validation. Do not use `E:\AstralRecord-Workspace` as a literal destination when `<task-root>` is another worktree.

## Out of Scope

- ソースの編集、設計書の編集、設定ファイルの書き換え。必要な場合は `$astralrecord-code-fix` に引き継ぐ。
- 設計書だけの不整合チェック。これは `$astralrecord-docs-review` の範囲。
- 大規模リファクタの提案。指摘は最小修正案にとどめ、構造的な再設計は「要確認」または別タスクとして残す。

## Extension Points

プロジェクト固有のレビュー観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-review.md` … `10_plugin/AstralRecord` 固有観点。
- `references/api-code-review.md` … `20_api/AstralRecordApi` 固有観点。
- `references/web-code-review.md` … `30_web/AstralRecordWeb` 固有観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
