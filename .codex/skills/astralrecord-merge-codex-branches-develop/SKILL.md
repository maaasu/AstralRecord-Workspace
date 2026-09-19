---
name: astralrecord-merge-codex-branches-develop
description: AstralRecord ワークスペースのローカル `codex/*` ブランチを監査し、早送り可能なブランチだけをローカル `develop` へ順次マージする。マージの監査・実行後に作業ツリー管理コンテンツを更新し、残ったブランチ／作業ツリーがマージ済みの整理待ちか未マージの対応待ちか分かるようにする。複数の Codex 作業ブランチをまとめて確認・取り込みしたい場合、実行前にマージ可能性を確認したい場合、成功したローカルブランチだけを任意で削除したい場合に使う。フェッチ、プル、プッシュ、リモート追跡ブランチ、既定のマージコミットは扱わない。
---

# AstralRecord Codex ブランチの develop 反映

## 基本ルール

名前が `codex/` で始まるローカルブランチだけをローカル `develop` にマージする。
このスキルではフェッチ、プル、プッシュ、手動コミット、リモート追跡ブランチのマージを行わない。

既定では試行監査とする。候補一覧を確認した後、ユーザーが実行またはマージの適用を明示した場合だけ実際のマージを行う。

作業ツリー管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## 対象範囲

対象リポジトリ:

```text
E:\AstralRecord-Workspace
```

候補ブランチ:

```text
refs/heads/codex/*
```

既定で除外するもの:

- `origin/codex/*` などのリモート追跡ブランチ
- `codex/` 外のブランチ
- すでに `develop` にマージ済みのブランチ
- 現在の `develop` 先端に早送りできないブランチ

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
3. リポジトリ状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. 試行監査を実行する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace
```

5. マージ監査後に管理スナップショットを更新する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

6. 監査の出力を確認する。
   - `MERGEABLE`: 模擬実行した `develop` 順序に早送りできるブランチ。
   - `ALREADY_MERGED`: ブランチ先端がすでに `develop` に含まれている。
   - `NON_FAST_FORWARD`: 現在の模擬実行した `develop` 先端からブランチが分岐している。既定ではこの一括処理でマージしない。
7. ユーザーが実際のマージを要求し、試行に受け入れられない `NON_FAST_FORWARD` 項目がない場合だけ実行する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute
```

8. マージ済みブランチを削除する場合は、ユーザーが後片付けを明示した場合だけ行う。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --delete-merged
```

9. 実行または早期停止の後に `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再度更新し、残ったブランチ/作業ツリー状態を見えるようにする。

## 安全確認

次のいずれかに該当する場合は Git 状態を変更する前に停止する。

- リポジトリに未コミットまたはステージ済みの変更がある。
- ローカル `develop` が存在しない。
- 現在のブランチを `develop` に変更のない状態に切り替えできない。
- 対象ブランチに早送りできないマージが必要で、ユーザーが早送りできないブランチのスキップを明示していない。
- マージコマンドが失敗する。

次の場合はすべてのブランチを保持する。

- 試行だけが依頼された。
- ブランチが `NON_FAST_FORWARD` である。
- ユーザーが `--delete-merged` を明示していない。

## 早送りできない場合の扱い

既定ではマージコミットを作成しない。

`NON_FAST_FORWARD` ブランチについてはブランチ名を報告し、次の後続対応のいずれかを案内する。

- `$astralrecord-git-worktree-develop` でそのブランチを個別に完了処理またはリベースする。
- 残りの早送り可能なブランチをマージしてよい場合は、早送りできないブランチをスキップしてこのスキルを再実行するよう依頼する。

部分的なマージをユーザーが明示的に受け入れた場合だけ `--skip-non-ff` を使う。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-merge-codex-branches-develop\scripts\merge_codex_branches.py --repo E:\AstralRecord-Workspace --execute --skip-non-ff
```

## 作業ツリー管理ファイルの内容

このスキルは作業ツリーを削除しない。試行または実行の後は、`--write-management` を付けて `$astralrecord-prune-codex-worktrees` のスクリプト経由で `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再生成する。

`NON_FAST_FORWARD` ブランチは、`UNMERGED_BRANCH`、`UNMERGED_WORKTREE`、`DIRTY_WORKTREE`、`REMOVABLE_WORKTREE` など管理ファイルにある項目と合わせて報告する。ブランチをマージした後も作業ツリーが残る場合は、明示的な後片付けのため `$astralrecord-prune-codex-worktrees` を案内する。

## 報告形式

結果は日本語で記載する。

```markdown
## マージ監査結果
- `repo`: E:\AstralRecord-Workspace
- `mode`: 試行 / 実行
- `develop`: <コミット>

## ブランチ結果
- `MERGEABLE`: <branches>
- `ALREADY_MERGED`: <branches>
- `NON_FAST_FORWARD`: <branches>
- `MERGED`: <branches, 実行 only>
- `DELETED`: <branches, 後片付け only>

## 作業ツリー管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <カテゴリ + ブランチ/パス>

## 停止理由
- none / <理由>

## 次の対応
- なし / <リベース、個別完了処理、再実行など>
```
