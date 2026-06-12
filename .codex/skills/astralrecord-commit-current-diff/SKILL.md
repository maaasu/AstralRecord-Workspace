---
name: astralrecord-commit-current-diff
description: AstralRecord workspace の現在の branch / worktree にある未コミット差分を確認し、今回の作業に関係するファイルだけを stage して commit する skill。すでに作業場所が決まっていて branch 作成・worktree 作成・develop への merge ではなく、今いる場所の差分整理とコミットだけを安全に行いたいときに使う。`.codex/skills`、plugin、API、docs、filebase などの差分から不要ファイルやローカル設定を除外して commit したい場合に使う。`develop` 直コミットは明示指示がない限り停止する。
---

# AstralRecord Commit Current Diff

## Core Rule

Commit only the files that belong to the requested change in the current branch or worktree. Do not create, switch, or merge branches in this skill. Never use `git add .` or `git add -A`.

Use `E:\AstralRecord-Workspace\COMMIT_RULES.md` as the source of truth for commit message format. Preserve unrelated user changes by leaving them unstaged or stopping when they cannot be separated safely.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
3. Inspect the current repository state:
   - `git status --short --branch`
   - `git status --porcelain=v1 -uall`
   - `git diff --stat`
   - targeted `git diff -- <path>`
4. Confirm branch context:
   - Prefer a dedicated task branch or task worktree.
   - If the current branch is `develop` and the user did not explicitly request a direct `develop` commit, stop and recommend `$astralrecord-git-worktree-develop`.
   - If the repository is in detached HEAD, stop unless the user explicitly requested committing there.
5. Run the shared classifier:
   - `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <current-worktree-root>`
   - Treat `EXCLUDE` as not stageable unless the user explicitly overrides.
   - Treat `REVIEW` as requiring judgment from the actual diff and the user request.
6. Select files:
   - Include only files that belong to the requested change.
   - For untracked text files, read only enough to judge whether they belong to the task.
   - Exclude generated files, local settings, secrets, logs, temp files, and unrelated changes.
   - If the current diff mixes multiple independent tasks, split the commit if it can be done safely; otherwise stop and report the conflicting paths.
7. Stage safely:
   - Use explicit paths only: `git add -- <path1> <path2> ...`
   - Run `git diff --cached --stat`
   - Run `git diff --cached --check`
   - Run `python E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <current-worktree-root>`
   - If an unrelated file was staged by mistake, remove it with `git restore --staged -- <path>`
8. Compose a commit message:
   - Follow `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
   - Use the dominant type: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, or `chore`.
   - Write the summary in Japanese.
   - Describe the actual diff, not the process.
9. Commit:
   - `git commit -m "<type>: <summary>"`
   - Report the commit hash, staged files, excluded files, and any remaining unstaged paths.

## Safety Checks

Stop before committing if any of the following is true:

- The current branch is `develop` without an explicit direct-commit instruction.
- The repository is in detached HEAD without explicit user approval.
- The selected files mix unrelated purposes and cannot be separated safely.
- A selected file appears to contain secrets or machine-local configuration.
- The staged diff is empty.
- `git diff --cached --check` fails.
- The mojibake check fails.

## Relationship To Other Skills

- Use `$astralrecord-git-worktree-develop` when the user wants branch creation, worktree creation, rebase, merge into `develop`, or cleanup.
- Use `$astralrecord-code-version-commit-develop` when the user wants implementation and commit flow in one request.
- Use this skill when the branch or worktree already exists and only the current uncommitted diff needs to be sorted and committed.

## Example Prompts

```text
Use $astralrecord-commit-current-diff to commit the current skill changes for E:\AstralRecord-Workspace\.codex\skills and report the result.
```

```text
Use $astralrecord-commit-current-diff to commit the current plugin changes for E:\AstralRecord-Workspace\10_plugin\AstralRecord from the current task branch and report the result.
```

```text
Use $astralrecord-commit-current-diff to commit the current review-fix changes for E:\AstralRecord-Workspace\00_docs\99_資料\レビュー結果 and report the result.
```

## Report Format

Write the result in Japanese.

```markdown
## Commit結果
- `branch`: <current-branch>
- `worktree`: <current-worktree-root>
- `commit`: <commit-hash> / 未実行

## Stage対象
- `included`: <stage した主なファイル>
- `excluded`: <除外した主なファイル>
- `remaining`: <未 stage のまま残したファイル> / なし

## 注意事項
- <停止理由 or follow-up があれば記載>
```
