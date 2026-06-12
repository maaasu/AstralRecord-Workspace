---
name: astralrecord-code-version-commit-develop
description: AstralRecord の実装作業を task ごとの branch / git worktree 上で進め、`$astralrecord-code` で実装し、必要に応じて最後に `$astralrecord-git-worktree-develop` で commit・develop への merge・cleanup まで行う統合スキル。plugin 版番号更新が必要な場合は、最新版 develop へ rebase 済みの finalize 時にだけ実施する。
---

# AstralRecord Code Version Commit Develop

## Core Rule

Do not redefine implementation, plugin versioning, or git workflow rules in this skill. Use the existing skills as the source of truth and connect them in order.

1. First, use `$astralrecord-git-worktree-develop` to prepare a task branch and worktree from local `develop`.
2. Run `$astralrecord-code` inside that prepared worktree.
3. Do not run `$astralrecord-plugin-version` during the parallel implementation phase. Plugin versioning belongs to the rebased finalize step handled by `$astralrecord-git-worktree-develop`.
4. If the user wants a serial end-to-end run, use `$astralrecord-git-worktree-develop` again to finalize the task worktree back into `develop`.
5. If the user wants parallel execution, stop after implementation and hand off the prepared worktree to a later `$astralrecord-git-worktree-develop` finalize call.
6. Keep the commit scope limited to the files changed by the implementation and, when finalize later adds it, the version-update file.
7. Keep the commit message format from `E:\AstralRecord-Workspace\COMMIT_RULES.md`.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project exactly as `$astralrecord-code` would.
3. Invoke `$astralrecord-git-worktree-develop` in Prepare mode and create a task branch / worktree for the request.
4. Map the requested absolute path from `E:\AstralRecord-Workspace\...` to the returned worktree root and invoke `$astralrecord-code` there. Finish the requested implementation, including docs sync and verification required by that skill.
5. Inspect the resulting changed files in the task worktree.
6. Determine which execution style the user requested:
   - Serial single-task flow: immediately invoke `$astralrecord-git-worktree-develop` in Finalize mode.
   - Parallel flow or delayed merge: stop here and report the branch / worktree for later finalize.
7. In either execution style, do not invoke `$astralrecord-plugin-version` from this skill before the rebase. If plugin files changed, `$astralrecord-git-worktree-develop` will decide and run the version step during finalize.
8. If Prepare mode cannot create the worktree, or Finalize mode stops because of dirty `develop`, branch collision, or rebase / merge conflicts, stop there and report that condition instead of inventing a different flow.

## Version Update Decision

When the implementation materially changes the plugin deliverable, the version step must run during finalize after rebasing to latest local `develop`:

- Plugin source under `10_plugin/AstralRecord/src/`
- Plugin resources such as `plugin.yml`, `config.yml`, message resources, logger resources
- Plugin build files such as `10_plugin/AstralRecord/pom.xml`

Skip the version step when the implementation affects only:

- API, Web, docs-only, database docs, filebase, or resourcepack
- Skill files under `.codex/skills` with no plugin code/resource change
- Pure review-result bookkeeping unrelated to the plugin binary

If both plugin and non-plugin projects changed in one task, run the plugin version step once during finalize and keep the `pom.xml` update scoped to its own commit when possible.

## Delegation Prompt Pattern

Use prompts equivalent to the following:

```text
Use $astralrecord-git-worktree-develop to prepare a task branch and worktree for <absolute-path> and report the branch name and worktree path.
```

```text
Use $astralrecord-code to <implementation task> for <worktree-absolute-path> and report the result.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for <worktree-absolute-path>, merge it into develop, and clean up the task branch and worktree if successful.
```

When the user did not provide a path but the project is still clear, keep the project context explicit in all steps.

For delayed merge after parallel work, use a prompt equivalent to:

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for <worktree-absolute-path> after parallel implementation, update the plugin version only if the rebased branch still changes the plugin deliverable, and report the result.
```

## Report Format

Write the final result in Japanese and merge all executed steps into one report.

- `実装結果`: `$astralrecord-code` の要点
- `Branch / Worktree`: 準備した branch 名と worktree パス
- `バージョン更新結果`: finalize 実施時のみ要点。未実施なら理由を明記
- `Git結果`: `$astralrecord-git-worktree-develop` の要点
- `次のアクション`: finalize 済み / parallel 実装完了のため finalize 待ち
- `未対応事項`: ブランチ競合、未実施テスト、競合解決待ちなど

## Example

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord and merge the resulting files back into develop through a task worktree with late plugin versioning during finalize.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested skill change for E:\AstralRecord-Workspace\.codex\skills, skip plugin versioning if the plugin was not touched, and merge the resulting files back into develop through a task worktree.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord in a dedicated task worktree, stop before finalize for parallel execution, and report the branch name and worktree path for later merge.
```
