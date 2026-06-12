---
name: astralrecord-git-worktree-develop
description: AstralRecord workspace で task ごとに専用 branch と git worktree を作成し、そこでの差分を選別して commit し、develop へ rebase / fast-forward merge して、成功時に branch と worktree を片付ける git 運用 skill。develop 直作業は行わず、conflict や dirty develop では停止して作業場所を残す。
---

# AstralRecord Git Worktree Develop

## Core Rule

Never implement a task directly on `develop`. Create a task branch and a dedicated git worktree for each task, do the work there, and merge back to `develop` only after a scoped commit and a clean rebase.

Use `E:\AstralRecord-Workspace\COMMIT_RULES.md` as the source of truth for commit message format.

## Supported Modes

This skill supports two modes.

- Prepare mode: create a task branch and a dedicated worktree from local `develop`, then report the branch name and worktree path for follow-up work.
- Finalize mode: from an existing task worktree, inspect the diff, stage only relevant files, commit, rebase onto `develop`, fast-forward merge into `develop`, and remove the task worktree and branch when safe.

If the request is ambiguous, infer the mode from the wording:

- `prepare`, `start`, `create branch`, `create worktree` -> Prepare mode
- `commit`, `merge`, `finalize`, `close task`, `cleanup` -> Finalize mode

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
3. Inspect repository state:
   - `git status --short --branch`
   - `git worktree list`
4. Decide the task slug:
   - Prefer a stable slug derived from the requested task.
   - Use lowercase ASCII, digits, and hyphens only.
   - Default branch format: `codex/<task-slug>`.
5. In Prepare mode:
   - The main workspace branch must be `develop`.
   - Base the task branch on the current local `develop` HEAD. Do not silently pull, fetch, or switch to another base branch.
   - Default worktree root: `E:\AstralRecord-Worktrees\<task-slug>`.
   - If the branch or worktree already exists, stop and report it unless the user explicitly asked to reuse it.
   - Create the branch and worktree, then report the exact branch name and worktree path.
6. In Finalize mode:
   - Confirm the current worktree is on a dedicated task branch, not `develop`.
   - Run:
     - `git status --porcelain=v1 -uall`
     - `git diff --stat`
     - targeted `git diff -- <path>`
   - Run the bundled classifier:
     - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <worktree-root>`
   - Stage only requested files with explicit paths. Never use `git add .` or `git add -A`.
   - Run:
     - `git diff --cached --stat`
     - `git diff --cached --check`
     - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <worktree-root>`
   - Commit with a Japanese summary that follows `COMMIT_RULES.md`.
   - Rebase the task branch onto local `develop`.
   - If rebase succeeds, fast-forward merge the task branch into `develop`.
   - Remove the task worktree and delete the task branch only after merge success and only when the user did not request retention.
7. If any merge or rebase conflict occurs:
   - Stop immediately.
   - Do not delete the branch or worktree.
   - Report the blocking files and the current state.

## Safety Checks

Stop before mutating git state if:

- The main workspace is not on `develop` when preparing a task worktree.
- Finalize mode is invoked from `develop` instead of a task branch worktree.
- The target task branch or worktree path already exists and reuse was not explicitly requested.
- `develop` has uncommitted changes before the merge step.
- The selected files mix unrelated work that cannot be separated safely.
- The commit would be empty.
- Rebase or merge produces conflicts.

## Worktree Conventions

- Main workspace: `E:\AstralRecord-Workspace`
- Task branch prefix: `codex/`
- Default task worktree root: `E:\AstralRecord-Worktrees\<task-slug>`

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

## Cleanup Rules

Remove the task worktree and delete the task branch only when all of the following are true:

- The task branch was committed successfully.
- Rebase onto `develop` succeeded.
- `develop` fast-forward merge succeeded.
- The user did not request to keep the branch or worktree.

Keep the branch and worktree when:

- Rebase or merge conflicts occurred.
- Verification failed and follow-up edits are expected.
- The user asked to keep the task workspace for review or later edits.

## Example Prompts

```text
Use $astralrecord-git-worktree-develop to prepare a task branch and worktree for skill changes under E:\AstralRecord-Workspace\.codex\skills and report the branch name and worktree path.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for E:\AstralRecord-Workspace\10_plugin\AstralRecord, merge it into develop, and clean up the task branch and worktree if successful.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for E:\AstralRecord-Workspace\.codex\skills, keep the branch and worktree if merge fails, and report the result.
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

## Merge結果
- `rebase`: 成功 / 失敗 / 未実施
- `develop merge`: 成功 / 失敗 / 未実施

## Cleanup
- `worktree`: 削除 / 保持
- `branch`: 削除 / 保持

## 残事項
- なし / <停止理由や手動対応事項>
```
