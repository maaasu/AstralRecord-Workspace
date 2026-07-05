---
name: astralrecord-git-worktree-develop
description: AstralRecord workspace で task ごとに専用 branch と git worktree を作成し、そこでの差分を選別して commit し、develop へ rebase / fast-forward merge して、成功時に branch と worktree を片付ける git 運用スキル。作成・finalize・保持時は worktree 管理コンテンツを更新し、残った worktree が merged 済みの消し忘れか未完了作業か分かるようにする。プラグイン変更がある場合の `pom.xml` 版番号更新は、finalize 時に最新 develop へ rebase した後でだけ行う。
---

# AstralRecord Git Worktree Develop

## Core Rule

Never implement a task directly on `develop`. Create a task branch and a dedicated git worktree for each task, do the work there, and merge back to `develop` only after a scoped commit and a clean rebase.

When a task changes the plugin deliverable under `10_plugin/AstralRecord`, do not update `pom.xml` during the parallel implementation phase. Rebase the task branch onto the latest local `develop` first, then run `$astralrecord-plugin-version` only inside that rebased task worktree immediately before the final merge.

Use `E:\AstralRecord-Workspace\COMMIT_RULES.md` as the source of truth for commit message format.

worktree 管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## Supported Modes

This skill supports two modes.

- Prepare mode: create a task branch and a dedicated worktree from local `develop`, then report the branch name and worktree path for follow-up work.
- Finalize mode: from an existing task worktree, inspect the diff, commit the implementation changes, rebase onto `develop`, run plugin versioning only when needed on the rebased worktree, fast-forward merge into `develop`, and remove the task worktree and branch when safe.

This skill manages one requested task branch/worktree at a time. For historical cleanup of already merged `codex/*` branches, stale worktree metadata, or leftover task worktrees outside the current finalize target, hand off to `$astralrecord-prune-codex-worktrees`.

If the request is ambiguous, infer the mode from the wording:

- `prepare`, `start`, `create branch`, `create worktree` -> Prepare mode
- `commit`, `merge`, `finalize`, `close task`, `cleanup` -> Finalize mode

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
3. Read `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md`.
4. Inspect repository state:
   - `git status --short --branch`
   - `git worktree list`
5. Decide the task slug:
   - Prefer a stable slug derived from the requested task.
   - Use lowercase ASCII, digits, and hyphens only.
   - Default branch format: `codex/<task-slug>`.
6. In Prepare mode:
   - The main workspace branch must be `develop`.
   - Base the task branch on the current local `develop` HEAD. Do not silently pull, fetch, or switch to another base branch.
   - Default worktree root: `E:\AstralRecord-Worktrees\<task-slug>`.
   - If the branch or worktree already exists, stop and report it unless the user explicitly asked to reuse it.
   - Create the branch and worktree.
   - Regenerate `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` with `--write-management`, then report the exact branch name and worktree path.
7. In Finalize mode:
   - Confirm the current worktree is on a dedicated task branch, not `develop`.
   - Run:
     - `git status --porcelain=v1 -uall`
     - `git diff --stat`
     - targeted `git diff -- <path>`
   - Run the bundled classifier:
     - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <worktree-root>`
   - Stage only requested implementation files with explicit paths. Never use `git add .` or `git add -A`.
   - Run:
     - `git diff --cached --stat`
     - `git diff --cached --check`
     - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <worktree-root>`
   - Commit the implementation diff with a Japanese summary that follows `COMMIT_RULES.md`.
   - Rebase the task branch onto local `develop`.
   - After the rebase, determine whether the branch materially changes the plugin deliverable:
     - Plugin source under `10_plugin/AstralRecord/src/`
     - Plugin resources such as `plugin.yml`, `config.yml`, message resources, logger resources
     - Plugin build files under `10_plugin/AstralRecord/`
   - If the rebased branch still contains plugin deliverable changes, invoke `$astralrecord-plugin-version` in that rebased worktree and create a separate scoped commit for `10_plugin/AstralRecord/pom.xml`.
   - If rebase succeeds and any required version-bump commit completes, fast-forward merge the task branch into `develop`.
   - After a successful fast-forward merge, always remove the task worktree and delete the task branch before reporting completion, unless the user explicitly requested retention. Do not leave completed task worktrees for later cleanup.
   - Regenerate `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` with `--write-management` after success, failure, or intentional retention.
8. If any merge or rebase conflict occurs:
   - Stop immediately.
   - Do not delete the branch or worktree.
   - Regenerate the worktree management file so the retained worktree is visible as unmerged, dirty, or detached.
   - Report the blocking files and the current state.
9. If the plugin version update or its commit fails:
   - Stop immediately.
   - Do not delete the branch or worktree.
   - Regenerate the worktree management file before reporting.
   - Report the failure and keep the rebased worktree for follow-up.
10. After a successful finalize, if the user also asked to prune older merged `codex/*` branches or leftover task worktrees, run `$astralrecord-prune-codex-worktrees` as a separate follow-up cleanup step.

## Safety Checks

Stop before mutating git state if:

- The main workspace is not on `develop` when preparing a task worktree.
- Finalize mode is invoked from `develop` instead of a task branch worktree.
- The target task branch or worktree path already exists and reuse was not explicitly requested.
- `develop` has uncommitted changes before the merge step.
- The selected files mix unrelated work that cannot be separated safely.
- The commit would be empty.
- Rebase or merge produces conflicts.
- A plugin version update is required but cannot be completed cleanly after the rebase.

## Worktree Conventions

- Main workspace: `E:\AstralRecord-Workspace`
- Task branch prefix: `codex/`
- Default task worktree root: `E:\AstralRecord-Worktrees\<task-slug>`
- Worktree management file: `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md`

When another skill needs to operate inside the task worktree, remap paths by replacing the workspace root prefix:

```text
E:\AstralRecord-Workspace\<relative-path>
-> E:\AstralRecord-Worktrees\<task-slug>\<relative-path>
```

## Commit Scope Rules

- Commit only files that belong to the requested task.
- Exclude local build outputs, IDE settings, machine-local config, secrets, logs, temp files, and unrelated user changes.
- `.codex/skills/` is commit-eligible when the requested task is a skill creation or skill update.
- Use `git restore --staged -- <path>` when an unrelated file was staged by mistake.
- When plugin versioning is required, keep the implementation commit and the `pom.xml` version-bump commit scoped separately.

## Cleanup Rules

Successful finalize includes cleanup. Remove the task worktree and delete the task branch in the same turn as the successful `develop` merge when all of the following are true:

- The task branch was committed successfully.
- Rebase onto `develop` succeeded.
- `develop` fast-forward merge succeeded.
- The user did not explicitly request to keep the branch or worktree.

Do not report a finalize as fully complete while the completed task worktree or merged task branch still exists. If cleanup fails after a successful merge, report the merge as successful but the finalize as cleanup-blocked, including the exact worktree and branch that still need removal.

Keep the branch and worktree when:

- Rebase or merge conflicts occurred.
- Verification failed and follow-up edits are expected.
- The user asked to keep the task workspace for review or later edits.
- The post-rebase plugin version update could not be completed cleanly.

This skill's own cleanup scope ends at the current task branch/worktree. Use `$astralrecord-prune-codex-worktrees` for accumulated cross-task cleanup.

## Worktree Management Content

Create or refresh `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` during Prepare and Finalize flows by running:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

finalize が途中停止した場合も更新する。管理ファイルの目的は、worktree が残っている理由を説明できるようにすること。生成ファイルの `## 手動メモ` セクションは人間の判断欄として保持する。

If the management file shows `DIRTY_WORKTREE`, `UNMERGED_WORKTREE`, `UNMERGED_BRANCH`, `DETACHED_WORKTREE`, `UNREGISTERED_PATH`, or `NON_GIT_DIRECTORY`, include those items in the final report instead of saying cleanup is complete.

## Example Prompts

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の変更用 task branch / worktree を作成し、branch 名と worktree パスを報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の現在の task worktree を finalize し、develop へ merge して、成功時は task branch / worktree を cleanup してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の task worktree を finalize し、merge 失敗時は branch / worktree を保持して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、並列実装後の E:\AstralRecord-Workspace\10_plugin\AstralRecord の task worktree を finalize し、develop へ rebase した後にだけプラグイン版番号を更新して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の task worktree を finalize し、その後に不要な codex/* branch / task worktree の掃除が必要なら $astralrecord-prune-codex-worktrees に引き継いでください。
```

## Report Format

Write the result in Japanese.

```markdown
## Git結果
- <prepare / finalize の実施内容>

## Branch / Worktree
- `branch`: <branch-name>
- `worktree`: <absolute-path>

## Commit結果
- `<commit-hash>`: <要点> / 未実施

## バージョン更新結果
- 実施: はい / いいえ
- 内容: <旧版 -> 新版> / 不要 / 失敗

## Merge結果
- `rebase`: 成功 / 失敗 / 未実施
- `develop merge`: 成功 / 失敗 / 未実施

## Cleanup
- `worktree`: 削除 / 保持
- `branch`: 削除 / 保持

## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>

## 残事項
- なし / <停止理由や手動対応事項>
```
