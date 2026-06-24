---
name: astralrecord-merge-codex-branches-develop
description: Git operations skill for AstralRecord workspace that audits and merges local `codex/*` branches into local `develop` one by one. Use when the user wants to batch-merge multiple Codex task branches, inspect which branches are fast-forwardable, execute only explicit safe merges, optionally delete successfully merged local branches, and avoid fetch, pull, push, remote-tracking branches, or default merge commits.
---

# AstralRecord Merge Codex Branches Develop

## Core Rule

Merge only local branches whose names start with `codex/` into local `develop`.
Do not fetch, pull, push, create manual commits, or merge remote-tracking branches in this skill.

Default to a dry-run audit. Perform an actual merge only when the user explicitly asks to execute or apply the merge after seeing the candidate list.

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
2. Inspect repository state:
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
3. Run a dry-run audit:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace
```

4. Review the audit output:
   - `MERGEABLE`: branch can be fast-forwarded into the simulated `develop` sequence.
   - `ALREADY_MERGED`: branch tip is already contained in `develop`.
   - `NON_FAST_FORWARD`: branch diverges from the current simulated `develop` tip; do not merge it in this batch by default.
5. Execute only when the user requested actual merging and the dry-run has no unacceptable `NON_FAST_FORWARD` item:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute
```

6. If merged branches should be removed, do it only when the user explicitly asked for cleanup:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --delete-merged
```

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

## Stop reason
- none / <reason>

## Next action
- none / <rebase, individual finalize, rerun, etc.>
```
