---
name: astralrecord-code
description: AstralRecord モノレポ全体のコード変更を実装し、関連する設計書を実装内容と同期する。設計書パスからの実装、docs/spec の実装への移行、plugin/API/Web/database/filebase/resourcepack の挙動変更、実装変更の docs への反映、アイテム ID 変更などの個別実装指示を、ルートガイド・プロジェクト README/AGENTS.md・skill references に従って行いたい場合に使う。
---

# AstralRecord Code

## Core Rule

Implement code or implementation-adjacent data only after identifying the target project and reading its documented rules. Reflect the implemented behavior back into the relevant design document when the change clarifies, narrows, or changes the documented specification. Do not infer operating rules from source alone when the root guide, project `README.md`, project `AGENTS.md`, or skill references cover the work.

This skill handles two input modes:

- Design-driven implementation: a design document path, spec path, or docs feature path is provided. Read the docs as the implementation source of truth, then implement the smallest coherent code change.
- Custom implementation instruction: the user gives a direct change such as `表示アイテムを apple から iron_ingot に変更`. Locate the affected project and code/data, then implement according to local coding rules.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project from explicit paths, technical terms, or affected files.
3. Read the target project's rule file before editing:
   - `PLUGIN_GUIDE.md` and `references/plugin-code.md` for Minecraft plugin code.
   - `API_GUIDE.md` and `references/api-code.md` for REST API code.
   - Root `README.md` "AstralRecord Web" section for Razor Pages web code.
   - `00_docs/40_Database設計書/README.md` for SQL Server schema or table docs.
   - Root `README.md` "AstralRecord Filebase" section for file-based master data.
   - Root `README.md` "AstralRecord Resource Pack" section for resource pack assets or JSON.
4. If the request crosses project boundaries, split the work by project and read each project's rules.

If target project cannot be determined, stop and ask the project-selection question from the root `AGENTS.md`.

## Workflow

1. Classify the request:
   - Design path under `00_docs/`: use `references/design-driven-implementation.md`.
   - Plugin implementation under `10_plugin/AstralRecord`: use `PLUGIN_GUIDE.md` and `references/plugin-code.md`.
   - Plugin test / MockBukkit / dev-server scaffolding under `10_plugin/AstralRecord`: prefer `$astralrecord-plugin-test` when the main work is verification harness rather than feature behavior.
   - API implementation: use `API_GUIDE.md` and `references/api-code.md`, then apply the general workflow here.
   - Web implementation: use the root `README.md` "AstralRecord Web" section, then apply the general workflow here.
   - Database, filebase, or resourcepack changes: use `00_docs/40_Database設計書/README.md` or the root `README.md` "AstralRecord Filebase" / "AstralRecord Resource Pack" sections; treat generated/runtime outputs as out of scope unless the project rules say otherwise.
2. Build the minimum context:
   - For design-driven work, read the specified design docs, feature README, linked contract docs, and unresolved-decision notes.
   - For custom instructions, search for the named symbols, item IDs, routes, messages, tables, or resource keys.
   - For plugin logs/messages/DB/filebase dependencies, use the specialized rules embedded in `references/plugin-code.md`.
3. Plan the edit boundary:
   - Name which project(s) and file groups are in scope.
   - Keep docs, source, database, filebase, and resourcepack concerns separate unless the user requested a cross-project implementation.
4. Implement with existing local patterns:
   - Match surrounding language, naming, package, layer, DI, error handling, and tests.
   - Prefer existing enums, IDs, repositories, DTOs, services, helpers, and resource conventions over new ad hoc strings or abstractions.
   - Keep unrelated refactors out of scope.
5. Sync design documents:
   - For design-driven work, update the source design document(s) so they describe the final implemented behavior, names, IDs, routes, commands, tables, files, messages, and unresolved items accurately.
   - For custom implementation work, locate the related design document when a stable docs path, feature name, ID, route, table, or project rule makes it reasonably discoverable; update it if the implementation changes documented behavior.
   - Keep docs edits narrow and factual. Do not rewrite unrelated sections, invent future behavior, or resolve open decisions without user input.
   - If no related design document can be identified with reasonable search, report that explicitly instead of creating a new speculative document.
6. Verify:
   - Run the narrowest meaningful tests or build checks for the touched project.
   - If a full build is too expensive or blocked, run targeted compile/test/lint checks and report what was not run.
   - Re-read changed source and docs snippets for rule compliance before final reporting.

## Design-Driven Implementation

When the user gives a design document path, the design is input for implementation and must be kept aligned with the completed implementation. Do not rewrite it broadly by default; update only the parts whose documented behavior, contracts, names, or status changed because of the implementation.

- Extract required behavior, data contracts, lifecycle/state rules, commands/routes, messages/logs, error behavior, and unresolved decisions.
- Do not implement behavior that the docs mark as unresolved unless the user provides the missing decision.
- If docs contradict current code, prefer the explicit design for new implementation, but report the mismatch if it creates risk.
- If implementation reveals a documentation gap, add the smallest necessary note or status update in the relevant design document. If the gap requires broader design remediation, keep this skill focused on coding and note the remaining docs work separately.

See `references/design-driven-implementation.md` for the detailed checklist.

## Plugin-Specific Rule

For `10_plugin/AstralRecord`, always read `PLUGIN_GUIDE.md` before code changes, then use `references/plugin-code.md`. The old `/code` prompt and old helper prompts are treated as migrated into that reference.

## Report Format

Write the result in Japanese.

```markdown
## 実装結果
- <変更概要>

## 変更ファイル
- `<path>`: <変更内容>

## 設計書反映
- `<path>`: <反映内容> / 未反映（理由）

## 検証
- `<command>`: 成功 / 失敗 / 未実行（理由）

## 残事項
- なし / <未対応・要確認事項>
```
