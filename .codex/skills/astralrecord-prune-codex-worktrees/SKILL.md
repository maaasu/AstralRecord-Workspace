---
name: astralrecord-prune-codex-worktrees
description: AstralRecord workspace の local `codex/*` branch と task git worktree を監査し、local `develop` へ取り込み済み・作業不要になった候補だけを dry-run 既定で整理する。worktree 管理コンテンツを作成・更新し、残った worktree が merged 済みの消し忘れ、dirty、未 merge、detached、未登録ディレクトリのどれか分かるようにする。`$astralrecord-git-worktree-develop` で finalize を進めた後に不要 branch / worktree を掃除したい場合、欠損した worktree メタデータを prune したい場合、削除前に安全な候補一覧だけを確認したい場合に使う。
---

# AstralRecord Codex Worktree の整理

## 基本ルール

まず audit する。cleanup の適用は、ユーザーが明示的に要求した場合だけ実行する。

明らかに不要な項目だけを削除する。

- local `develop` にすでに merge 済みの local `codex/*` branch
- 接続された `codex/*` branch が local `develop` にすでに merge 済みの task worktree
- disk 上に path が存在しない古い worktree metadata

まだ危険または曖昧なものは保持する。

- 未コミット変更がある worktree
- `develop` にまだ merge されていない branch
- 手動 review が必要な detached または `codex/*` 以外の worktree
- `E:\AstralRecord-Worktrees` 配下の未登録ディレクトリ

この skill は fetch、pull、push、rebase、merge、commit 作成を行わない。

この skill はローカル管理スナップショット `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成・更新する。このファイルは運用コンテンツであり、通常はコミット対象ではない。

## 対象範囲

対象 repository:

```text
E:\AstralRecord-Workspace
```

既定の task worktree root:

```text
E:\AstralRecord-Worktrees
```

既定の管理ファイル:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

Cleanup 候補:

- `refs/heads/codex/*` に一致する local branch
- その branch に接続された登録済み git worktree
- `git worktree prune` で整理できる古い worktree metadata
- 既定の worktree root 配下にあり、登録されていない Git 管理ディレクトリ

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
3. repository 状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. 最初に dry-run audit を実行し、管理 snapshot を書き込む。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

5. audit 出力を確認する。
   - `REMOVABLE_WORKTREE`: 安全に削除できる、merge 済みで clean な task worktree。
   - `REMOVABLE_BRANCH`: 残っている worktree に接続されていない、merge 済みの `codex/*` branch。
   - `STALE_METADATA`: `git worktree prune` で整理できる、存在しない worktree path。
   - `UNREGISTERED_PATH`: worktree root 配下にあるが未登録で、手動 review が必要な Git らしいディレクトリ。
   - `NON_GIT_DIRECTORY`: worktree root 配下にある、Git worktree ではなく手動 review が必要なディレクトリ。
   - `DIRTY_WORKTREE`: local 変更がある merge 済み task worktree。保持する。
   - `UNMERGED_WORKTREE`: branch が `develop` にまだ merge されていない task worktree。保持する。
   - `UNMERGED_BRANCH`: `develop` にまだ merge されていない `codex/*` branch。保持する。
   - `DETACHED_WORKTREE`: branch のない worktree。手動で review する。
   - `DETACHED_HEAD_BRANCH`: merge 済み `codex/*` branch の tip を detached worktree がまだ checkout している。手動 review のため保持する。
   - `NON_CODEX_WORKTREE`: 別の branch namespace 上にある登録済み worktree。触らない。
6. ユーザーが削除適用を明示した場合だけ cleanup を execute する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --execute --write-management
```

7. execute mode では script が次を満たすこと。
   - メイン workspace が clean であることを要求する。
   - メイン workspace の現在 branch が `develop` であることを要求する。
   - stale metadata が存在する場合だけ `git worktree prune --verbose` を実行する。
   - merge 済みで clean な task worktree だけを削除する。
   - どこにも接続されていない merge 済み `codex/*` branch だけを削除する。
8. dirty worktree、未 merge branch、detached worktree、未登録ディレクトリ、非 Git ディレクトリが残る場合は、強制削除せず手動確認項目として報告する。

## 安全確認

次のいずれかに該当する場合は Git state を変更する前に停止する。

- repository に staged または unstaged の変更がある。
- メイン workspace の現在 branch が `develop` ではない。
- local `develop` が存在しない。
- 候補 worktree が dirty である。
- 候補 branch が `develop` に merge されていない。
- 削除 command が失敗する。
- 削除対象 path がメイン workspace root である。

次の場合は候補を手動 review 用に保持する。

- `E:\AstralRecord-Worktrees` 配下にディレクトリがあるが、git に登録されていない。
- `E:\AstralRecord-Worktrees` 配下にディレクトリがあるが、git worktree ではない。
- worktree が detached である。
- branch tip が detached worktree にまだ checkout されている。
- worktree が `codex/*` 以外の branch に属している。
- worktree または branch に未 merge の作業が残っている。

## 他スキルとの関係

- 1つの task の prepare/finalize flow には `$astralrecord-git-worktree-develop` を使う。
- 1つの依頼で prepare → implementation → finalize を行う場合は `$astralrecord-code-version-commit-develop` を使う。
- まだ存在する複数の `codex/*` branch を `develop` に merge することが目的なら `$astralrecord-merge-codex-branches-develop` を使う。
- これらの flow 後、古い merge 済み task branch/worktree が蓄積して安全に prune したい場合にこの skill を使う。

## Worktree 管理ファイルの内容

dry-run 監査でも、cleanup を適用しない場合でも `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を更新する。このファイルは「なぜこの worktree が残っているのか」に答えるための台帳である。

execute mode のみで cleanup 可能な分類: `REMOVABLE_WORKTREE`, `REMOVABLE_BRANCH`, `STALE_METADATA`。

手動確認項目として扱う分類: `DIRTY_WORKTREE`, `UNMERGED_WORKTREE`, `UNMERGED_BRANCH`, `DETACHED_WORKTREE`, `DETACHED_HEAD_BRANCH`, `UNREGISTERED_PATH`, `NON_GIT_DIRECTORY`, `NON_CODEX_WORKTREE`。

## 使用例

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の不要な codex/* branch と task worktree を dry-run 監査し、結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の merged 済み codex/* branch / task worktree を execute で掃除し、保持した項目も含めて結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の stale worktree metadata を prune し、削除できなかった dirty worktree があれば残事項として報告してください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## 整理監査の結果
- `repo`: E:\AstralRecord-Workspace
- `mode`: dry-run / execute
- `develop`: <commit>

## 削除候補
- `REMOVABLE_WORKTREE`: <branch + path>
- `REMOVABLE_BRANCH`: <branch>
- `STALE_METADATA`: <path>
- `UNREGISTERED_PATH`: <path>
- `NON_GIT_DIRECTORY`: <path>

## 保持項目
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

## 実行結果
- `worktree prune`: 実施 / 未実施 / 失敗
- `worktree removed`: <paths> / なし
- `branch deleted`: <branches> / なし

## 残りの対応
- なし / <手動確認が必要な項目>
```
