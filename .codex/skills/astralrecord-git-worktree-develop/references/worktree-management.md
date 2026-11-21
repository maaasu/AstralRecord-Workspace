# 作業ツリー管理コンテンツ

AstralRecord の作業ツリーを作成、完了処理、マージ、監査、削除するときは、この参照を使う。

## 管理ファイル

- `<repo-root>` は `git worktree list --porcelain` で確認したメインワークスペースの絶対パス。
- `<worktrees-root>` は所有する作業ツリーの配置先。監査スクリプトでは複数指定でき、既定はメインワークスペースと同じ階層の `AstralRecord-Worktrees`。
- `<management-file>` は実行環境で指定された管理ファイル。既定は `<worktrees-root>/WORKTREE_MANAGEMENT.md`。

このファイルはローカル運用コンテンツであり、通常はコミット対象ではない。残っている作業ツリーを次のどれとして扱うべきか判断できるようにする。

- develop 取り込み済みで削除してよい
- develop 取り込み済みだが未コミット変更ありなので手動確認が必要
- develop 未取り込みで作業中
- develop 未取り込みで停止中
- リベース、競合、手動チェックアウトなどで HEAD がブランチから分離した状態 になっている
- Git 作業ツリー未登録、または Git 作業ツリーではないため手動確認が必要

## 更新タイミング

作業ツリー関連スキルが次の操作を行ったら、管理ファイルを更新または再生成する。

- 準備で新しい `codex/*` ブランチと作業ツリーを作成した。
- 完了処理が成功、失敗、または意図的な保持で終わった。
- 一括マージの試行または実行で `codex/*` ブランチの状態が変わった。
- 整理の試行または実行で作業ツリー / ブランチの検出、削除、保持が発生した。

確定した絶対パスを指定して実行する。

```powershell
python "<repo-root>/.codex/skills/astralrecord-prune-codex-worktrees/scripts/prune_codex_worktrees.py" --repo "<repo-root>" --worktree-root "<worktrees-root>" --management-file "<management-file>" --write-management
```

実削除する場合は `--execute --write-management` を使う。

## 状態分類

レポートと後続判断では次の分類を使う。

| 分類 | 意味 | 既定の扱い |
|:--|:--|:--|
| `REMOVABLE_WORKTREE` | 対象ブランチ付き。develop 取り込み済みで変更がなく、最終活動から指定日数が経過している。 | 実行モードで削除する。 |
| `REMOVABLE_DETACHED_WORKTREE` | 所有ルートまたは Codex アプリルートの detached HEAD。develop 取り込み済みで変更がなく、最終活動から指定日数が経過している。 | 実行モードで削除する。 |
| `REMOVABLE_BRANCH` | ブランチは develop 取り込み済みで、作業ツリーからの接続や detached HEAD からの参照がない。 | 実行モードで削除する。 |
| `RECENT_WORKTREE` | ほかの削除条件を満たすが、最終活動から指定日数が経過していない。 | 保持する。 |
| `LOCKED_WORKTREE` | `git worktree lock` でロックされている。 | 保持する。 |
| `APP_POOL_WORKTREE` | Claude アプリルートの detached HEAD。 | アプリが管理する。 |
| `STALE_METADATA` | Git に存在しないパスの作業ツリーメタデータが残っている。 | 実行後片付けで `git worktree prune` する。 |
| `UNREGISTERED_PATH` | 所有ルートまたは Claude アプリルートに Git チェックアウトらしい未登録ディレクトリがある。 | 報告し、手動で確認する。 |
| `NON_GIT_DIRECTORY` | 所有ルートまたは Claude アプリルートに別用途のディレクトリがある。 | 報告し、手動で確認する。 |
| `DIRTY_WORKTREE` | ブランチ付きの作業ツリーに未コミット差分が残っている。 | 内容を確認して保存、破棄、別コミットを判断する。 |
| `UNMERGED_WORKTREE` | ブランチ先端が develop 未取り込みで、登録作業ツリーがある。 | 個別完了処理 / リベースするか、明示的に破棄判断する。 |
| `UNMERGED_BRANCH` | 対象ブランチの先端が develop 未取り込み。 | 個別完了処理、破棄を判断する。 |
| `DETACHED_WORKTREE` | 未コミット変更、develop 未取り込み、対象外の配置先のいずれかに該当する detached HEAD。 | 作業ツリーの状態を手動で確認する。 |
| `DETACHED_HEAD_BRANCH` | マージ済みブランチの先端を HEAD がブランチから分離した状態の作業ツリーが保持している。 | ブランチ削除前に HEAD がブランチから分離した状態の作業ツリーを確認する。 |
| `NON_MANAGED_WORKTREE` | 指定された接頭辞（既定は `codex/` と `claude/`）に一致しないブランチの作業ツリー。 | 保持する。 |

状態一覧の「最終活動」はローカル時刻の `YYYY-MM-DD HH:MM` で表示する。スナップショットには走査ルート、対象の接頭辞、無活動日数のしきい値を記録する。実行モードでは削除した作業ツリーとブランチ、失敗した項目も記録する。

## 手動メモ

生成処理は `## 手動メモ` セクションを保持して書き換える。人間の判断はここに残す。

```markdown
- `codex/example-task`: 視覚的な QA 完了まで保持。担当: Codex。次回確認: 2026-07-06。
- `<worktrees-root>/old-task`: バックアップ後に削除してよいことを確認済み。次回後片付けで削除。
```

`DIRTY_WORKTREE`、`DETACHED_WORKTREE`、`UNREGISTERED_PATH`、`NON_GIT_DIRECTORY` はブランチ名だけで判断しない。パスを確認し、保持理由を具体的に報告する。

## 報告

作業ツリー関連スキルの結果には、短い管理行を含める。

```markdown
## 作業ツリー管理
- `management_file`: <management-file>
- 更新: はい / いいえ
- 残った確認項目: なし / <カテゴリ + ブランチ/パス>
```
