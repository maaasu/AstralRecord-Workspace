---
name: astralrecord-code-version-commit-develop
description: AstralRecord の実装作業を `$astralrecord-code` で行い、その結果として `10_plugin/AstralRecord` を変更した場合に `$astralrecord-plugin-version` でプラグイン版番号を更新し、最後に `$astralrecord-commit-develop` で `develop` ブランチへ安全にコミットする統合スキル。実装・版番号更新・コミットを 1 回で通したいとき、特にプラグイン実装や `.codex/skills` 変更を `develop` ブランチに反映したいときに使う。プラグインに触れない実装ではバージョン更新を自動で省略する。
---

# AstralRecord Code Version Commit Develop

## Core Rule

Do not redefine implementation, plugin versioning, or commit rules in this skill. Use the existing skills as the source of truth and connect them in order.

1. First, use `$astralrecord-code` for the user's requested implementation.
2. If that implementation changed `E:\AstralRecord-Workspace\10_plugin\AstralRecord`, use `$astralrecord-plugin-version`.
3. After implementation and any required version update complete, use `$astralrecord-commit-develop`.
4. Keep the commit scope limited to the files changed by the implementation and the version update.
5. Keep the commit message format from `$astralrecord-commit-develop`.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project exactly as `$astralrecord-code` would.
3. Invoke `$astralrecord-code` and finish the requested implementation, including docs sync and verification required by that skill.
4. Inspect the resulting changed files.
5. If plugin files under `10_plugin/AstralRecord` changed, invoke `$astralrecord-plugin-version`:
   - Default to a `dev` version unless the user explicitly asked for a release or release-candidate style version.
   - Choose `major` / `minor` / `patch` based on the implementation scope.
6. Invoke `$astralrecord-commit-develop` and commit only the implementation files plus the version update file when step 5 ran.
7. If `$astralrecord-commit-develop` stops because the current branch is not `develop`, stop there and report that condition instead of inventing a different flow.

## Version Update Decision

Run the version step only when the implementation materially changes the plugin deliverable:

- Plugin source under `10_plugin/AstralRecord/src/`
- Plugin resources such as `plugin.yml`, `config.yml`, message resources, logger resources
- Plugin build files such as `10_plugin/AstralRecord/pom.xml`

Skip the version step when the implementation affects only:

- API, Web, docs-only, database docs, filebase, or resourcepack
- Skill files under `.codex/skills` with no plugin code/resource change
- Pure review-result bookkeeping unrelated to the plugin binary

If both plugin and non-plugin projects changed in one task, run the plugin version step once and include it in the same commit only if the commit scope is still coherent.

## Delegation Prompt Pattern

Use prompts equivalent to the following:

```text
Use $astralrecord-code to <implementation task> for <absolute-path> and report the result.
```

```text
Use $astralrecord-plugin-version to update the plugin version for E:\AstralRecord-Workspace\10_plugin\AstralRecord based on the implementation just completed and report the result.
```

```text
Use $astralrecord-commit-develop to commit only the files changed for <absolute-path> including the plugin version update when present, and report the result.
```

When the user did not provide a path but the project is still clear, keep the project context explicit in all steps.

## Report Format

Write the final result in Japanese and merge all executed steps into one report.

- `実装結果`: `$astralrecord-code` の要点
- `バージョン更新結果`: 実行した場合のみ要点。未実行なら理由を明記
- `コミット結果`: `$astralrecord-commit-develop` の要点
- `未完了事項`: ブランチ条件、未実行検証、保留判断のみ

## Example

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord, update the plugin version, and commit the resulting files to develop.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested skill change for E:\AstralRecord-Workspace\.codex\skills, skip plugin versioning if the plugin was not touched, and commit the resulting files to develop.
```
