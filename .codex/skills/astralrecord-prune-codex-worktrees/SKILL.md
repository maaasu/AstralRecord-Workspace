---
name: astralrecord-prune-codex-worktrees
description: AstralRecord の Codex・Claude のローカル作業ツリーとブランチを監査し、develop 取り込み済みで整理可能な項目をドライラン既定で確認する。管理ファイルの更新、古い作業ツリー・メタデータの整理、Windows での日次実行の設定に使う。
---

# AstralRecord 作業ツリーの整理

## 対象範囲

ローカルの `codex/*` と `claude/*` が既定の対象ブランチです。`--prefix` で接頭辞を変更でき、複数回指定できます。

`--repo` の既定はスクリプトを置いたリポジトリです。リンクされた作業ツリーからも、`git worktree list --porcelain` の先頭にあるメインワークスペースを基準に既定の走査先を決めます。

| 種類 | 既定の場所 | 引数 |
|:--|:--|:--|
| 所有ルート | `C:\AstralRecord-Worktrees`（メインワークスペースの隣） | `--worktree-root`（複数指定可） |
| Claude アプリ | `C:\AstralRecord-Workspace\.claude\worktrees` | `--claude-root` |
| Codex アプリ | `%USERPROFILE%\.codex\worktrees` | `--codex-app-root` |

所有ルートと Claude ルートの未登録ディレクトリは監査に表示します。Codex アプリのルートでは登録済みの作業ツリーを扱います。

## 監査と削除

試行では候補と保持理由を表示します。手動で削除する場合は、候補を確認し、ユーザーが実行を指定したら `--execute` を使います。

```powershell
python 'C:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py'
```

分類と管理ファイルの詳細は [作業ツリー管理](../astralrecord-git-worktree-develop/references/worktree-management.md#状態分類) を参照してください。

- メインワークスペース、`--repo` が指す作業ツリー、現在のディレクトリや別の作業ツリーを含む作業ツリーを保護します。
- 分類では Git のロック、Claude アプリの予備、未コミット変更、develop への取り込み、無活動日数の順に確認します。Claude ルートの detached HEAD はアプリが管理します。削除可能な detached HEAD の配置先は所有ルートと Codex アプリルートです。
- `REMOVABLE_DETACHED_WORKTREE` は実行モードで削除します。Codex アプリの作業ツリーを削除したときは、空になった 4 桁の 16 進数名の親ディレクトリも削除します。
- `RECENT_WORKTREE` は無活動日数が不足している作業ツリー、`LOCKED_WORKTREE` は Git のロック中、`APP_POOL_WORKTREE` は Claude アプリの予備、`NON_MANAGED_WORKTREE` は対象の接頭辞に一致しないブランチの作業ツリーです。いずれも保持します。
- 最終活動は、作業ツリー自身と Git 管理ディレクトリ内の `HEAD`、`index`、`logs/HEAD`、`ORIG_HEAD`、`FETCH_HEAD` のうち、存在する項目の最新更新時刻です。`--min-idle-days` の既定は 7 日で、小数も指定できます。
- 作業ツリーを削除した後、取り込み済みで作業ツリーに接続されておらず、先端を detached HEAD から参照されていない対象ブランチを `git branch -d` で削除します。
- ローカル `develop` がなければ停止します。メインワークスペースに変更がある場合や現在のブランチが `develop` 以外の場合は警告を出します。
- 終了コードは成功が `0`、削除の一部失敗が `1`、前提条件や監査・管理ファイル更新での停止が `2` です。削除に失敗した項目は記録して次に進みます。

`--write-management` を付けると、試行・実行ともに最初の所有ルート直下の `WORKTREE_MANAGEMENT.md` を更新します。`--management-file` で出力先を上書きできます。実行時の削除・失敗結果と各作業ツリーの最終活動を記録し、`## 手動メモ` を保持します。

## 日次実行

メインワークスペースの `60_tool\18-worktree-cleanup.bat` は `--execute --write-management` を指定して実行します。追加引数は Python スクリプトへ渡します。実行ログは `C:\AstralRecord-Worktrees\_logs\worktree-cleanup-latest.log` に毎回上書きします。

メインワークスペースで次のいずれかを実行します。登録先は `\AstralRecord\WorktreeCleanup` です。既定で毎日 12:30 に、登録したユーザーのログオン中に実行します。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\60_tool\18-worktree-cleanup\Register-WorktreeCleanupTask.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\60_tool\18-worktree-cleanup\Register-WorktreeCleanupTask.ps1 -Time '13:00'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\60_tool\18-worktree-cleanup\Register-WorktreeCleanupTask.ps1 -Unregister
```

## 他スキルとの関係

個別の作業ツリーの準備・完了処理には `$astralrecord-git-worktree-develop` を使います。リモートブランチやリベース前の取り込み判定は [取り込み済み枝の安全な整理](../astralrecord-git-worktree-develop/references/integrated-branch-cleanup.md) を参照してください。

報告には、試行か実行か、削除候補または実際の削除・失敗、保持理由、管理ファイルの更新状況を含めます。
