---
name: astralrecord-prune-codex-worktrees
description: AstralRecord workspace の local `codex/*` branch と task git worktree を監査し、local `develop` へ取り込み済み・作業不要になった候補だけを dry-run 既定で整理する。worktree 管理コンテンツを作成・更新し、残った worktree が merged 済みの消し忘れ、dirty、未 merge、detached、未登録ディレクトリのどれか分かるようにする。`$astralrecord-git-worktree-develop` で finalize を進めた後に不要 branch / worktree を掃除したい場合、欠損した worktree メタデータを prune したい場合、削除前に安全な候補一覧だけを確認したい場合に使う。
---

# AstralRecord Prune Codex Worktrees

## Core Rule

Audit first. Execute cleanup only when the user explicitly asks to apply it.

Delete only items that are clearly unnecessary:

- local `codex/*` branches already merged into local `develop`
- task worktrees whose attached `codex/*` branch is already merged into local `develop`
- stale worktree metadata whose path no longer exists on disk

Keep anything that is still risky or ambiguous:

- dirty worktrees
- branches not yet merged into `develop`
- detached or non-`codex/*` worktrees that need manual review
- unregistered directories under `E:\AstralRecord-Worktrees`

This skill does not fetch, pull, push, rebase, merge, or create commits.

この skill はローカル管理スナップショット `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成・更新する。このファイルは運用コンテンツであり、通常はコミット対象ではない。

## Scope

Target repository:

```text
E:\AstralRecord-Workspace
```

Default task worktree root:

```text
E:\AstralRecord-Worktrees
```

Default management file:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

Cleanup candidates:

- local branches matching `refs/heads/codex/*`
- registered git worktrees attached to those branches
- stale worktree metadata that `git worktree prune` can clean
- Git-managed directories under the default worktree root that are no longer registered

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md`.
3. Inspect repository state:
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. Run the dry-run audit first and write the management snapshot:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

5. Review the audit output:
   - `REMOVABLE_WORKTREE`: merged and clean task worktree that can be removed safely
   - `REMOVABLE_BRANCH`: merged `codex/*` branch not attached to any remaining worktree
   - `STALE_METADATA`: missing worktree path that can be cleaned by `git worktree prune`
   - `UNREGISTERED_PATH`: Git-looking directory under the worktree root that is not registered and needs manual review
   - `NON_GIT_DIRECTORY`: directory under the worktree root that is not a Git worktree and needs manual review
   - `DIRTY_WORKTREE`: merged task worktree with local changes; keep it
   - `UNMERGED_WORKTREE`: task worktree whose branch is not yet merged into `develop`; keep it
   - `UNMERGED_BRANCH`: `codex/*` branch not yet merged into `develop`; keep it
   - `DETACHED_WORKTREE`: worktree without a branch; review manually
   - `DETACHED_HEAD_BRANCH`: merged `codex/*` branch whose tip is still checked out by a detached worktree; keep it for manual review
   - `NON_CODEX_WORKTREE`: registered worktree on another branch namespace; leave it alone
6. Execute cleanup only when the user explicitly asks to apply the removals:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --execute --write-management
```

7. In execute mode, the script must:
   - require the main workspace to be clean
   - require the main workspace current branch to be `develop`
   - run `git worktree prune --verbose` only when stale metadata exists
   - remove only merged and clean task worktrees
   - delete only merged `codex/*` branches that are no longer attached anywhere
8. dirty worktree、未 merge branch、detached worktree、未登録ディレクトリ、非 Git ディレクトリが残る場合は、強制削除せず手動確認項目として報告する。

## Safety Checks

Stop before mutating git state if:

- the repository has staged or unstaged changes
- the main workspace current branch is not `develop`
- local `develop` does not exist
- a candidate worktree is dirty
- a candidate branch is not merged into `develop`
- a removal command fails
- the path to remove is the main workspace root

Keep candidates for manual review when:

- the directory exists under `E:\AstralRecord-Worktrees` but is not registered by git
- the directory exists under `E:\AstralRecord-Worktrees` but is not a git worktree
- the worktree is detached
- the branch tip is still checked out by a detached worktree
- the worktree belongs to a non-`codex/*` branch
- the worktree or branch still contains unmerged work

## Relationship To Other Skills

- Use `$astralrecord-git-worktree-develop` for one task's prepare/finalize flow.
- Use `$astralrecord-code-version-commit-develop` for prepare -> implementation -> finalize in one request.
- Use `$astralrecord-merge-codex-branches-develop` when the goal is to merge several still-existing `codex/*` branches into `develop`.
- Use this skill after those flows when old merged task branches/worktrees have accumulated and should be pruned safely.

## Worktree Management Content

dry-run 監査でも、cleanup を適用しない場合でも `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を更新する。このファイルは「なぜこの worktree が残っているのか」に答えるための台帳である。

execute mode のみで cleanup 可能な分類: `REMOVABLE_WORKTREE`, `REMOVABLE_BRANCH`, `STALE_METADATA`。

手動確認項目として扱う分類: `DIRTY_WORKTREE`, `UNMERGED_WORKTREE`, `UNMERGED_BRANCH`, `DETACHED_WORKTREE`, `DETACHED_HEAD_BRANCH`, `UNREGISTERED_PATH`, `NON_GIT_DIRECTORY`, `NON_CODEX_WORKTREE`。

## Example Prompts

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の不要な codex/* branch と task worktree を dry-run 監査し、結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の merged 済み codex/* branch / task worktree を execute で掃除し、保持した項目も含めて結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の stale worktree metadata を prune し、削除できなかった dirty worktree があれば残事項として報告してください。
```

## Report Format

Write the result in Japanese.

```markdown
## Cleanup audit
- `repo`: E:\AstralRecord-Workspace
- `mode`: dry-run / execute
- `develop`: <commit>

## Removal candidates
- `REMOVABLE_WORKTREE`: <branch + path>
- `REMOVABLE_BRANCH`: <branch>
- `STALE_METADATA`: <path>
- `UNREGISTERED_PATH`: <path>
- `NON_GIT_DIRECTORY`: <path>

## Kept items
- `DIRTY_WORKTREE`: <branch + path>
- `UNMERGED_WORKTREE`: <branch + path>
- `UNMERGED_BRANCH`: <branch>
- `DETACHED_WORKTREE`: <path>
- `DETACHED_HEAD_BRANCH`: <branch>
- `NON_CODEX_WORKTREE`: <branch + path>

## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>

## Execution result
- `worktree prune`: 実施 / 未実施 / 失敗
- `worktree removed`: <paths> / なし
- `branch deleted`: <branches> / なし

## Remaining action
- なし / <手動確認が必要な項目>
```
