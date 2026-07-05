---
name: astralrecord-merge-codex-branches-develop
description: AstralRecord workspace の local `codex/*` branch を監査し、fast-forward 可能な branch だけを local `develop` へ順次 merge する。merge 監査・実行後に worktree 管理コンテンツを更新し、残った branch/worktree が merge 済み掃除待ちか未 merge 対応待ちか分かるようにする。複数の Codex task branch をまとめて確認・取り込みしたい場合、実行前に merge 可能性を確認したい場合、成功した local branch だけを任意で削除したい場合に使う。fetch / pull / push / remote-tracking branch / 既定の merge commit は扱わない。
---

# AstralRecord Merge Codex Branches Develop

## Core Rule

Merge only local branches whose names start with `codex/` into local `develop`.
Do not fetch, pull, push, create manual commits, or merge remote-tracking branches in this skill.

Default to a dry-run audit. Perform an actual merge only when the user explicitly asks to execute or apply the merge after seeing the candidate list.

worktree 管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## Scope

Target repository:

```text
E:\AstralRecord-Workspace
```

Candidate branches:

```text
refs/heads/codex/*
```

Excluded by default:

- `origin/codex/*` and other remote-tracking branches
- Branches outside `codex/`
- Any branch already merged into `develop`
- Any branch that cannot be fast-forwarded onto the current `develop` tip

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md`.
3. Inspect repository state:
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. Run a dry-run audit:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace
```

5. Refresh the management snapshot after the merge audit:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

6. Review the audit output:
   - `MERGEABLE`: branch can be fast-forwarded into the simulated `develop` sequence.
   - `ALREADY_MERGED`: branch tip is already contained in `develop`.
   - `NON_FAST_FORWARD`: branch diverges from the current simulated `develop` tip; do not merge it in this batch by default.
7. Execute only when the user requested actual merging and the dry-run has no unacceptable `NON_FAST_FORWARD` item:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute
```

8. If merged branches should be removed, do it only when the user explicitly asked for cleanup:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --delete-merged
```

9. Refresh `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` again after execute or early stop so leftover branch/worktree state is visible.

## Safety Checks

Stop before mutating git state if:

- The repository has uncommitted or staged changes.
- Local `develop` does not exist.
- The current branch cannot be switched to `develop` cleanly.
- Any target branch requires a non-fast-forward merge and the user did not explicitly ask to skip non-fast-forward branches.
- A merge command fails.

Keep all branches when:

- Dry-run only was requested.
- A branch is `NON_FAST_FORWARD`.
- The user did not explicitly request `--delete-merged`.

## Non-Fast-Forward Handling

Do not create merge commits by default.

For `NON_FAST_FORWARD` branches, report the branch name and advise one of these follow-ups:

- Finalize or rebase that branch individually with `$astralrecord-git-worktree-develop`.
- Ask to rerun this skill with non-fast-forward branches skipped, if merging the remaining fast-forwardable branches is acceptable.

Use `--skip-non-ff` only when the user explicitly accepts partial merging:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --skip-non-ff
```

## Worktree Management Content

This skill does not remove worktrees. After any dry-run or execute, regenerate `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` through `$astralrecord-prune-codex-worktrees`' script with `--write-management`.

Report `NON_FAST_FORWARD` branches together with any management-file items such as `UNMERGED_BRANCH`, `UNMERGED_WORKTREE`, `DIRTY_WORKTREE`, or `REMOVABLE_WORKTREE`. If branches were merged but worktrees still exist, point the user to `$astralrecord-prune-codex-worktrees` for explicit cleanup.

## Report Format

Write the result in Japanese.

```markdown
## Merge audit
- `repo`: E:\AstralRecord-Workspace
- `mode`: dry-run / execute
- `develop`: <commit>

## Branch results
- `MERGEABLE`: <branches>
- `ALREADY_MERGED`: <branches>
- `NON_FAST_FORWARD`: <branches>
- `MERGED`: <branches, execute only>
- `DELETED`: <branches, cleanup only>

## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>

## Stop reason
- none / <reason>

## Next action
- none / <rebase, individual finalize, rerun, etc.>
```
