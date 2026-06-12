---
name: astralrecord-code-version-commit-develop
description: AstralRecord の実装作業を task ごとの branch / git worktree 上で進め、`$astralrecord-code` で実装し、必要な場合だけ `$astralrecord-plugin-version` でプラグイン版番号を更新し、最後に `$astralrecord-git-worktree-develop` で commit・develop への merge・cleanup まで行う統合スキル。プラグインに触れない実装ではバージョン更新を自動で省略する。
---

# AstralRecord Code Version Commit Develop

## Core Rule

Do not redefine implementation, plugin versioning, or git workflow rules in this skill. Use the existing skills as the source of truth and connect them in order.

1. First, use `$astralrecord-git-worktree-develop` to prepare a task branch and worktree from local `develop`.
2. Run `$astralrecord-code` inside that prepared worktree.
3. If that implementation changed `10_plugin/AstralRecord`, use `$astralrecord-plugin-version` in the same worktree.
4. After implementation and any required version update complete, use `$astralrecord-git-worktree-develop` again to finalize the task worktree back into `develop`.
5. Keep the commit scope limited to the files changed by the implementation and the version update.
6. Keep the commit message format from `E:\AstralRecord-Workspace\COMMIT_RULES.md`.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project exactly as `$astralrecord-code` would.
3. Invoke `$astralrecord-git-worktree-develop` in Prepare mode and create a task branch / worktree for the request.
4. Map the requested absolute path from `E:\AstralRecord-Workspace\...` to the returned worktree root and invoke `$astralrecord-code` there. Finish the requested implementation, including docs sync and verification required by that skill.
5. Inspect the resulting changed files in the task worktree.
6. If plugin files under `10_plugin/AstralRecord` changed, invoke `$astralrecord-plugin-version` in the same worktree:
   - Default to a `dev` version unless the user explicitly asked for a release or release-candidate style version.
   - Choose `major` / `minor` / `patch` based on the implementation scope.
7. Invoke `$astralrecord-git-worktree-develop` in Finalize mode and commit only the implementation files plus the version update file when step 6 ran.
8. If Prepare mode cannot create the worktree, or Finalize mode stops because of dirty `develop`, branch collision, or rebase / merge conflicts, stop there and report that condition instead of inventing a different flow.

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
Use $astralrecord-git-worktree-develop to prepare a task branch and worktree for <absolute-path> and report the branch name and worktree path.
```

```text
Use $astralrecord-code to <implementation task> for <worktree-absolute-path> and report the result.
```

```text
Use $astralrecord-plugin-version to update the plugin version for <worktree-plugin-path> based on the implementation just completed and report the result.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for <worktree-absolute-path>, merge it into develop, and clean up the task branch and worktree if successful.
```

When the user did not provide a path but the project is still clear, keep the project context explicit in all steps.

## Report Format

Write the final result in Japanese and merge all executed steps into one report.

- `実装結果`: `$astralrecord-code` の要点
- `Branch / Worktree`: 準備した branch 名と worktree パス
- `バージョン更新結果`: 実施した場合のみ要点。未実施なら理由を明記
- `Git結果`: `$astralrecord-git-worktree-develop` の要点
- `未対応事項`: ブランチ競合、未実施テスト、競合解決待ちなど

## Example

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord, update the plugin version, and merge the resulting files back into develop through a task worktree.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested skill change for E:\AstralRecord-Workspace\.codex\skills, skip plugin versioning if the plugin was not touched, and merge the resulting files back into develop through a task worktree.
```
