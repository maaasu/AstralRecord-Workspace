# 作業ツリー管理コンテンツ

AstralRecord の作業ツリーを作成、完了処理、マージ、監査、削除するときは、この参照を使う。

## 管理ファイル

既定のローカル管理ファイル:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

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

既定の生成コマンド:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

実削除する場合は `--execute --write-management` を使う。

## 状態分類

レポートと後続判断では次の分類を使う。

| 分類 | 意味 | 既定の扱い |
|:--|:--|:--|
| `REMOVABLE_WORKTREE` | ブランチは develop 取り込み済みで作業ツリーは変更のない状態。 | 明示的な実行後片付けで削除する。 |
| `REMOVABLE_BRANCH` | ブランチは develop 取り込み済みで作業ツリー未接続。 | 明示的な実行後片付けでブランチを削除する。 |
| `STALE_METADATA` | Git に存在しないパスの作業ツリーメタデータが残っている。 | 実行後片付けで `git worktree prune` する。 |
| `UNREGISTERED_PATH` | 作業ツリールート配下に Git チェックアウトらしい未登録ディレクトリがある。 | 削除前に手動確認する。 |
| `NON_GIT_DIRECTORY` | 作業ツリールート配下に Git 作業ツリーではないディレクトリがある。 | 削除前に手動確認する。 |
| `DIRTY_WORKTREE` | ブランチは develop 取り込み済みだが、作業ツリーに未コミット差分が残っている。 | 内容を確認して保存、破棄、別コミットを判断する。 |
| `UNMERGED_WORKTREE` | ブランチ先端が develop 未取り込みで、登録作業ツリーがある。 | 個別完了処理 / リベースするか、明示的に破棄判断する。 |
| `UNMERGED_BRANCH` | ブランチ先端が develop 未取り込みで、登録作業ツリーがない。 | 作業ツリー再作成、個別完了処理、破棄を判断する。 |
| `DETACHED_WORKTREE` | 登録作業ツリーが HEAD がブランチから分離した状態 HEAD。 | 競合、リベース中断、手動チェックアウト状態を確認する。 |
| `DETACHED_HEAD_BRANCH` | マージ済みブランチの先端を HEAD がブランチから分離した状態の作業ツリーが保持している。 | ブランチ削除前に HEAD がブランチから分離した状態の作業ツリーを確認する。 |
| `NON_CODEX_WORKTREE` | `codex/*` 以外のブランチを使う登録作業ツリー。 | この掃除対象から外す。 |

## 手動メモ

生成処理は `## 手動メモ` セクションを保持して書き換える。人間の判断はここに残す。

```markdown
- `codex/example-task`: 視覚的な QA 完了まで保持。担当: Codex。次回確認: 2026-07-06。
- `E:\AstralRecord-Worktrees\old-task`: バックアップ後に削除してよいことを確認済み。次回後片付けで削除。
```

`DIRTY_WORKTREE`、`DETACHED_WORKTREE`、`UNREGISTERED_PATH`、`NON_GIT_DIRECTORY` はブランチ名だけで判断しない。パスを確認し、保持理由を具体的に報告する。

## 報告

作業ツリー関連スキルの結果には、短い管理行を含める。

```markdown
## 作業ツリー管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <カテゴリ + ブランチ/パス>
```
