---
name: astralrecord-code-version-commit-develop
description: AstralRecord の実装作業や本番向け filebase マスタ作成を task ごとの branch / git worktree 上で進め、`$astralrecord-code` または `$astralrecord-master-data-author` による作業と `$astralrecord-git-worktree-develop` による commit・develop 反映・cleanup をつなぐ統合スキル。プラグイン版番号更新が必要な場合は、最新版 develop へ rebase 済みの finalize 時にだけ実施する。
---

# AstralRecord Code Version Commit Develop

## Core Rule

Do not redefine implementation, plugin versioning, or git workflow rules in this skill. Use the existing skills as the source of truth and connect them in order.

1. First, use `$astralrecord-git-worktree-develop` to prepare a task branch and worktree from local `develop`.
2. Run the appropriate worker skill inside that prepared worktree:
   - `$astralrecord-code` for plugin, API, web, docs-linked implementation, database docs, resourcepack, or mixed implementation tasks.
   - `$astralrecord-master-data-author` for production-oriented filebase master creation under `40_filebase`.
3. Do not run `$astralrecord-plugin-version` during the parallel implementation phase. Plugin versioning belongs to the rebased finalize step handled by `$astralrecord-git-worktree-develop`.
4. If the user wants a serial end-to-end run, use `$astralrecord-git-worktree-develop` again to finalize the task worktree back into `develop`.
5. If the user wants parallel execution, stop after implementation and hand off the prepared worktree to a later `$astralrecord-git-worktree-develop` finalize call.
6. Keep the commit scope limited to the files changed by the implementation and, when finalize later adds it, the version-update file.
7. Keep the commit message format from `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
8. If the user also wants accumulated merged `codex/*` residues cleaned after finalize, delegate that last step to `$astralrecord-prune-codex-worktrees` instead of extending `$astralrecord-git-worktree-develop` beyond the current task.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project and choose the worker skill:
   - `40_filebase` master creation -> `$astralrecord-master-data-author`
   - other implementation tasks -> `$astralrecord-code`
3. Invoke `$astralrecord-git-worktree-develop` in Prepare mode and create a task branch / worktree for the request.
4. Map the requested absolute path from `E:\AstralRecord-Workspace\...` to the returned worktree root and invoke the selected worker skill there. Finish the requested implementation or master creation, including docs sync and verification required by that skill.
5. Inspect the resulting changed files in the task worktree.
6. Determine which execution style the user requested:
   - Serial single-task flow: immediately invoke `$astralrecord-git-worktree-develop` in Finalize mode.
   - Parallel flow or delayed merge: stop here and report the branch / worktree for later finalize.
7. In either execution style, do not invoke `$astralrecord-plugin-version` from this skill before the rebase. If plugin files changed, `$astralrecord-git-worktree-develop` will decide and run the version step during finalize.
8. If Prepare mode cannot create the worktree, or Finalize mode stops because of dirty `develop`, branch collision, or rebase / merge conflicts, stop there and report that condition instead of inventing a different flow.
9. If finalize succeeds and the user requested broader cleanup of old merged task branches/worktrees, run `$astralrecord-prune-codex-worktrees` as the final optional maintenance step.

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
$astralrecord-git-worktree-develop を使って、<absolute-path> 用の task branch / worktree を作成し、branch 名と worktree パスを報告してください。
```

```text
$astralrecord-code を使って、<worktree-absolute-path> に対して <implementation task> を行い、結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、<worktree-absolute-path> の現在の task worktree を finalize し、develop へ merge して、成功時は task branch / worktree を cleanup してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の不要な codex/* branch と task worktree を dry-run 監査し、必要なら execute で掃除してください。
```

When the user did not provide a path but the project is still clear, keep the project context explicit in all steps.

For delayed merge after parallel work, use a prompt equivalent to:

```text
$astralrecord-git-worktree-develop を使って、並列実装後の <worktree-absolute-path> の task worktree を finalize し、rebase 後もプラグイン成果物に変更がある場合だけプラグイン版番号を更新して結果を報告してください。
```

## Report Format

Write the final result in Japanese and merge all executed steps into one report.

- `実装結果`: 実行した worker skill の要点
- `Branch / Worktree`: 準備した branch 名と worktree パス
- `バージョン更新結果`: finalize 実施時のみ要点。未実施なら理由を明記
- `Git結果`: `$astralrecord-git-worktree-develop` の要点
- `次のアクション`: finalize 済み / parallel 実装完了のため finalize 待ち
- `未対応事項`: ブランチ競合、未実施テスト、競合解決待ちなど

## Example

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の依頼されたプラグイン挙動を task worktree 上で実装し、finalize 時のプラグイン版番号更新を含めて develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\.codex\skills の依頼された skill 変更を task worktree 上で実装し、プラグイン未変更なら版番号更新を行わず develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\40_filebase の最初のオーバーワールド向け本番マスタを task worktree 上で作成し、develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の依頼されたプラグイン挙動を専用 task worktree で実装し、並列実行のため finalize 前で停止して、後続 merge 用の branch 名と worktree パスを報告してください。
```
