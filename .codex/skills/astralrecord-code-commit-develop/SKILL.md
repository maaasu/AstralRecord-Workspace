---
name: astralrecord-code-commit-develop
description: AstralRecord の実装作業を `$astralrecord-code` で行った直後に、変更内容を `$astralrecord-commit-develop` で `develop` へ安全にコミットする統合 skill。実装からコミットまでを 1 回の依頼で続けて進めたいとき、特に `.codex/skills`・plugin・API・Web・docs 連動実装の完了後にそのままコミットまで済ませたいときに使う。
---

# AstralRecord Code Commit Develop

## Core Rule

Do not redefine the implementation or commit rules in this skill. Use the existing skills as the source of truth and only connect them in order.

1. First, use `$astralrecord-code` for the user's requested implementation.
2. After the implementation completes, immediately use `$astralrecord-commit-develop` for the resulting changes.
3. Pass the same target path or target project context into both steps whenever the request includes it.
4. Keep the commit scope limited to the files that were changed for the implementation just completed.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project exactly as `$astralrecord-code` would.
3. Invoke `$astralrecord-code` and complete the implementation, including any required docs sync and verification.
4. Once implementation is done, invoke `$astralrecord-commit-develop` and commit only the files belonging to that implementation.
5. If `$astralrecord-commit-develop` stops because the current branch is not `develop`, stop there and report that condition instead of inventing a different commit flow.

## Delegation Prompt Pattern

Use prompts equivalent to the following:

```text
Use $astralrecord-code to <implementation task> for <absolute-path> and report the result.
```

```text
Use $astralrecord-commit-develop to commit the files changed for <absolute-path> and report the result.
```

When the user did not provide a path but the target project is still clear from the request, keep the project context explicit in both steps.

## Report Format

Write the final result in Japanese and merge both step results into one report.

- `実装結果`: `$astralrecord-code` の要点
- `コミット結果`: `$astralrecord-commit-develop` の要点
- `未完了事項`: ブランチ条件や未実行検証など、後続対応が必要なものだけ

## Example

```text
Use $astralrecord-code-commit-develop to implement the requested API behavior for E:\AstralRecord-Workspace\20_api\AstralRecordApi and commit the resulting files to develop.
```
