---
name: astralrecord-merge-codex-branches-develop
description: AstralRecord workspace の local `codex/*` branch を監査し、fast-forward 可能な branch だけを local `develop` へ順次 merge する。merge 監査・実行後に worktree 管理コンテンツを更新し、残った branch/worktree が merge 済み掃除待ちか未 merge 対応待ちか分かるようにする。複数の Codex task branch をまとめて確認・取り込みしたい場合、実行前に merge 可能性を確認したい場合、成功した local branch だけを任意で削除したい場合に使う。fetch / pull / push / remote-tracking branch / 既定の merge commit は扱わない。
---

# AstralRecord Codex Branch の develop 反映

## 基本ルール

名前が `codex/` で始まる local branch だけを local `develop` に merge する。
この skill では fetch、pull、push、手動 commit、remote-tracking branch の merge を行わない。

既定では dry-run audit とする。候補一覧を確認した後、ユーザーが execute または merge の適用を明示した場合だけ実際の merge を行う。

worktree 管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## 対象範囲

対象 repository:

```text
E:\AstralRecord-Workspace
```

候補 branch:

```text
refs/heads/codex/*
```

既定で除外するもの:

- `origin/codex/*` などの remote-tracking branch
- `codex/` 外の branch
- すでに `develop` に merge 済みの branch
- 現在の `develop` tip に fast-forward できない branch

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
3. repository 状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. dry-run audit を実行する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace
```

5. merge audit 後に管理 snapshot を更新する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

6. audit の出力を確認する。
   - `MERGEABLE`: simulated `develop` sequence に fast-forward できる branch。
   - `ALREADY_MERGED`: branch tip がすでに `develop` に含まれている。
   - `NON_FAST_FORWARD`: 現在の simulated `develop` tip から branch が分岐している。既定ではこの batch で merge しない。
7. ユーザーが実際の merge を要求し、dry-run に受け入れられない `NON_FAST_FORWARD` 項目がない場合だけ execute する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute
```

8. merge 済み branch を削除する場合は、ユーザーが cleanup を明示した場合だけ行う。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --delete-merged
```

9. execute または早期停止の後に `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再度更新し、残った branch/worktree 状態を見えるようにする。

## 安全確認

次のいずれかに該当する場合は Git state を変更する前に停止する。

- repository に未 commit または staged の変更がある。
- local `develop` が存在しない。
- 現在の branch を `develop` に clean に switch できない。
- 対象 branch に non-fast-forward merge が必要で、ユーザーが non-fast-forward branch の skip を明示していない。
- merge command が失敗する。

次の場合はすべての branch を保持する。

- dry-run だけが依頼された。
- branch が `NON_FAST_FORWARD` である。
- ユーザーが `--delete-merged` を明示していない。

## Non-Fast-Forward の扱い

既定では merge commit を作成しない。

`NON_FAST_FORWARD` branch については branch 名を報告し、次の follow-up のいずれかを案内する。

- `$astralrecord-git-worktree-develop` でその branch を個別に finalize または rebase する。
- 残りの fast-forward 可能な branch を merge してよい場合は、non-fast-forward branch を skip してこの skill を再実行するよう依頼する。

部分的な merge をユーザーが明示的に受け入れた場合だけ `--skip-non-ff` を使う。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --skip-non-ff
```

## Worktree 管理ファイルの内容

この skill は worktree を削除しない。dry-run または execute の後は、`--write-management` を付けて `$astralrecord-prune-codex-worktrees` の script 経由で `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再生成する。

`NON_FAST_FORWARD` branch は、`UNMERGED_BRANCH`、`UNMERGED_WORKTREE`、`DIRTY_WORKTREE`、`REMOVABLE_WORKTREE` など管理ファイルにある項目と合わせて報告する。branch を merge した後も worktree が残る場合は、明示的な cleanup のため `$astralrecord-prune-codex-worktrees` を案内する。

## 報告形式

結果は日本語で記載する。

```markdown
## Merge audit 結果
- `repo`: E:\AstralRecord-Workspace
- `mode`: dry-run / execute
- `develop`: <commit>

## Branch 結果
- `MERGEABLE`: <branches>
- `ALREADY_MERGED`: <branches>
- `NON_FAST_FORWARD`: <branches>
- `MERGED`: <branches, execute only>
- `DELETED`: <branches, cleanup only>

## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>

## 停止理由
- none / <reason>

## 次の対応
- なし / <rebase、個別 finalize、再実行など>
```
