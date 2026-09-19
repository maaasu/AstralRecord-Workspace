---
name: astralrecord-prune-codex-worktrees
description: AstralRecord ワークスペースのローカル `codex/*` ブランチと Git 作業ツリーを監査し、ローカル `develop` へ取り込み済み・作業不要になった候補だけをドライラン既定で整理する。作業ツリー管理コンテンツを作成・更新し、残った作業ツリーがマージ済みの消し忘れ、未コミット変更あり、未マージ、HEAD がブランチから分離した状態、未登録ディレクトリのどれか分かるようにする。`$astralrecord-git-worktree-develop` で完了処理を進めた後に不要なブランチ／作業ツリーを整理したい場合、欠損した作業ツリーのメタデータを整理したい場合、削除前に安全な候補一覧だけを確認したい場合に使う。
---

# AstralRecord Codex 作業ツリーの整理

## 基本ルール

まず監査する。後片付けの適用は、ユーザーが明示的に要求した場合だけ実行する。

明らかに不要な項目だけを削除する。

- ローカル `develop` にすでにマージ済みのローカル `codex/*` ブランチ
- 接続された `codex/*` ブランチがローカル `develop` にすでにマージ済みの作業ツリー
- ディスク上にパスが存在しない古い作業ツリーメタデータ

まだ危険または曖昧なものは保持する。

- 未コミット変更がある作業ツリー
- `develop` にまだマージされていないブランチ
- HEAD がブランチから分離した状態、または `codex/*` 以外のブランチを使用しており、手動レビューが必要な作業ツリー
- `E:\AstralRecord-Worktrees` 配下の未登録ディレクトリ

このスキルはフェッチ、プル、プッシュ、リベース、マージ、コミット作成を行わない。

このスキルはローカル管理スナップショット `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成・更新する。このファイルは運用コンテンツであり、通常はコミット対象ではない。

## 対象範囲

対象リポジトリ:

```text
E:\AstralRecord-Workspace
```

既定の作業ツリールート:

```text
E:\AstralRecord-Worktrees
```

既定の管理ファイル:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

後片付け候補:

- `refs/heads/codex/*` に一致するローカルブランチ
- そのブランチに接続された登録済み git 作業ツリー
- `git worktree prune` で整理できる古い作業ツリーメタデータ
- 既定の作業ツリールート配下にあり、登録されていない Git 管理ディレクトリ

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
3. リポジトリ状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
   - `git branch --list "codex/*"`
4. 最初に試行監査を実行し、管理スナップショットを書き込む。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

5. 監査出力を確認する。
   - `REMOVABLE_WORKTREE`: 安全に削除できる、マージ済みで未コミット変更のない作業ツリー。
   - `REMOVABLE_BRANCH`: 残っている作業ツリーに接続されていない、マージ済みの `codex/*` ブランチ。
   - `STALE_METADATA`: `git worktree prune` で整理できる、存在しない作業ツリーのパス。
   - `UNREGISTERED_PATH`: 作業ツリールート配下にあるが未登録で、手動レビューが必要な Git らしいディレクトリ。
   - `NON_GIT_DIRECTORY`: 作業ツリールート配下にある、Git 作業ツリーではなく手動レビューが必要なディレクトリ。
   - `DIRTY_WORKTREE`: ローカル変更があるマージ済み作業ツリー。保持する。
   - `UNMERGED_WORKTREE`: ブランチが `develop` にまだマージされていない作業ツリー。保持する。
   - `UNMERGED_BRANCH`: `develop` にまだマージされていない `codex/*` ブランチ。保持する。
   - `DETACHED_WORKTREE`: ブランチのない作業ツリー。手動でレビューする。
   - `DETACHED_HEAD_BRANCH`: マージ済み `codex/*` ブランチの先端を HEAD がブランチから分離した状態の作業ツリーがまだチェックアウトしている。手動レビューのため保持する。
   - `NON_CODEX_WORKTREE`: 別のブランチ名前空間上にある登録済み作業ツリー。触らない。
6. ユーザーが削除適用を明示した場合だけ後片付けを実行する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --execute --write-management
```

7. 実行モードではスクリプトが次を満たすこと。
   - メインワークスペースが変更のない状態であることを要求する。
   - メインワークスペースの現在ブランチが `develop` であることを要求する。
   - 古いメタデータが存在する場合だけ `git worktree prune --verbose` を実行する。
   - マージ済みで未コミット変更のない作業ツリーだけを削除する。
   - どこにも接続されていないマージ済み `codex/*` ブランチだけを削除する。
8. 未コミット変更がある作業ツリー、未マージブランチ、HEAD がブランチから分離した状態の作業ツリー、未登録ディレクトリ、非 Git ディレクトリが残る場合は、強制削除せず手動確認項目として報告する。

## 安全確認

次のいずれかに該当する場合は Git 状態を変更する前に停止する。

- リポジトリにステージ済みまたは未ステージの変更がある。
- メインワークスペースの現在ブランチが `develop` ではない。
- ローカル `develop` が存在しない。
- 候補作業ツリーに未コミット変更がある。
- 候補ブランチが `develop` にマージされていない。
- 削除コマンドが失敗する。
- 削除対象パスがメインワークスペースルートである。

次の場合は候補を手動レビュー用に保持する。

- `E:\AstralRecord-Worktrees` 配下にディレクトリがあるが、git に登録されていない。
- `E:\AstralRecord-Worktrees` 配下にディレクトリがあるが、git 作業ツリーではない。
- 作業ツリーの HEAD がブランチから分離した状態である。
- ブランチ先端が HEAD がブランチから分離した状態の作業ツリーにまだチェックアウトされている。
- 作業ツリーが `codex/*` 以外のブランチに属している。
- 作業ツリーまたはブランチに未マージの作業が残っている。

## 他スキルとの関係

- 1つの作業の準備/完了処理の流れには `$astralrecord-git-worktree-develop` を使う。
- 1つの依頼で準備 → 実装 → 完了処理を行う場合は `$astralrecord-code-version-commit-develop` を使う。
- まだ存在する複数の `codex/*` ブランチを `develop` にマージすることが目的なら `$astralrecord-merge-codex-branches-develop` を使う。
- これらの処理後、古いマージ済み作業ブランチ/作業ツリーが蓄積して安全に整理したい場合にこのスキルを使う。

## 作業ツリー管理ファイルの内容

試行監査でも、後片付けを適用しない場合でも `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を更新する。このファイルは「なぜこの作業ツリーが残っているのか」に答えるための台帳である。

実行モードのみで後片付け可能な分類: `REMOVABLE_WORKTREE`, `REMOVABLE_BRANCH`, `STALE_METADATA`。

手動確認項目として扱う分類: `DIRTY_WORKTREE`, `UNMERGED_WORKTREE`, `UNMERGED_BRANCH`, `DETACHED_WORKTREE`, `DETACHED_HEAD_BRANCH`, `UNREGISTERED_PATH`, `NON_GIT_DIRECTORY`, `NON_CODEX_WORKTREE`。

## 使用例

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の不要な codex/* ブランチと作業ツリーを試行監査し、結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace のマージ済み codex/* ブランチ / 作業ツリーの削除を実行し、保持した項目も含めて結果を報告してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の古い作業ツリーメタデータを整理し、削除できなかった未コミット変更がある作業ツリーがあれば残事項として報告してください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## 整理監査の結果
- `repo`: E:\AstralRecord-Workspace
- `mode`: 試行 / 実行
- `develop`: <コミット>

## 削除候補
- `REMOVABLE_WORKTREE`: <ブランチ + パス>
- `REMOVABLE_BRANCH`: <ブランチ>
- `STALE_METADATA`: <パス>
- `UNREGISTERED_PATH`: <パス>
- `NON_GIT_DIRECTORY`: <パス>

## 保持項目
- `DIRTY_WORKTREE`: <ブランチ + パス>
- `UNMERGED_WORKTREE`: <ブランチ + パス>
- `UNMERGED_BRANCH`: <ブランチ>
- `DETACHED_WORKTREE`: <パス>
- `DETACHED_HEAD_BRANCH`: <ブランチ>
- `NON_CODEX_WORKTREE`: <ブランチ + パス>

## 作業ツリー管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <カテゴリ + ブランチ/パス>

## 実行結果
- `worktree prune`: 実施 / 未実施 / 失敗
- `worktree removed`: <paths> / なし
- `branch deleted`: <branches> / なし

## 残りの対応
- なし / <手動確認が必要な項目>
```
