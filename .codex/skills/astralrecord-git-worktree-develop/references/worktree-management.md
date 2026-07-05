# Worktree Management Content

Use this reference when a skill creates, finalizes, merges, audits, or prunes AstralRecord task worktrees.

## Management File

Default local management file:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

This file is local operational content, not a source artifact to commit. It records the current state of task worktrees so a leftover directory can be understood as one of these cases:

- already merged and safe to remove
- already merged but dirty and needing manual inspection
- unmerged and still active
- unmerged and blocked
- detached because a rebase, conflict, or manual checkout stopped midway
- unregistered or non-git directory that needs manual review

## Required Update Points

Update or regenerate the management file whenever a worktree-related skill performs one of these actions:

- Prepare creates a new `codex/*` branch and task worktree.
- Finalize succeeds, fails, or intentionally retains a task worktree.
- A batch merge audit or execute changes the status of one or more `codex/*` branches.
- A prune audit or execute discovers, removes, or intentionally keeps worktrees/branches.

Use the prune script as the default generator:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

In execute cleanup mode, include `--execute --write-management`.

## Status Categories

Use these category meanings in reports and follow-up decisions:

| Category | Meaning | Default action |
|:--|:--|:--|
| `REMOVABLE_WORKTREE` | Branch is already in `develop` and the worktree is clean. | Remove during explicit execute cleanup. |
| `REMOVABLE_BRANCH` | Branch is already in `develop` and no worktree is attached. | Delete during explicit execute cleanup. |
| `STALE_METADATA` | Git has worktree metadata for a missing path. | Run `git worktree prune` during execute cleanup. |
| `UNREGISTERED_PATH` | Directory under worktree root looks like a Git checkout but is not registered. | Inspect manually before deletion. |
| `NON_GIT_DIRECTORY` | Directory under worktree root is not a Git worktree. | Inspect manually before deletion. |
| `DIRTY_WORKTREE` | Branch is already in `develop`, but local changes remain in the worktree. | Inspect, preserve, or discard local changes manually. |
| `UNMERGED_WORKTREE` | Branch tip is not in `develop` and a registered worktree exists. | Finalize/rebase individually or explicitly abandon. |
| `UNMERGED_BRANCH` | Branch tip is not in `develop` and no registered worktree is attached. | Locate/recreate worktree, finalize/rebase, or abandon. |
| `DETACHED_WORKTREE` | Registered worktree is detached. | Inspect for rebase/conflict/manual checkout state. |
| `DETACHED_HEAD_BRANCH` | Merged branch tip is still held by a detached worktree. | Inspect detached worktree before branch deletion. |
| `NON_CODEX_WORKTREE` | Registered worktree uses a branch outside `codex/*`. | Leave it to its owning workflow. |

## Manual Notes

The generator preserves the `## Manual Notes` section when rewriting the management file. Put human decisions there, such as:

```markdown
- `codex/example-task`: keep until visual QA is complete. Owner: Codex. Next review: 2026-07-06.
- `E:\AstralRecord-Worktrees\old-task`: confirmed disposable after backup; delete on next cleanup.
```

Do not rely on branch names alone when a worktree is `DIRTY_WORKTREE`, `DETACHED_WORKTREE`, `UNREGISTERED_PATH`, or `NON_GIT_DIRECTORY`. Inspect the path and report the concrete reason it is kept.

## Reporting

When a worktree-related skill reports results, include a short management line:

```markdown
## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>
```
