# Worktree 管理コンテンツ

AstralRecord の task worktree を作成、finalize、merge、監査、削除するときは、この参照を使う。

## 管理ファイル

既定のローカル管理ファイル:

```text
E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
```

このファイルはローカル運用コンテンツであり、通常はコミット対象ではない。残っている worktree を次のどれとして扱うべきか判断できるようにする。

- develop 取り込み済みで削除してよい
- develop 取り込み済みだが dirty なので手動確認が必要
- develop 未取り込みで作業中
- develop 未取り込みで停止中
- rebase、競合、手動 checkout などで detached になっている
- Git worktree 未登録、または Git worktree ではないため手動確認が必要

## 更新タイミング

worktree 関連 skill が次の操作を行ったら、管理ファイルを更新または再生成する。

- Prepare で新しい `codex/*` branch と task worktree を作成した。
- Finalize が成功、失敗、または意図的な保持で終わった。
- 一括 merge の dry-run または execute で `codex/*` branch の状態が変わった。
- prune の dry-run または execute で worktree / branch の検出、削除、保持が発生した。

既定の生成コマンド:

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

実削除する場合は `--execute --write-management` を使う。

## 状態分類

レポートと後続判断では次の分類を使う。

| 分類 | 意味 | 既定の扱い |
|:--|:--|:--|
| `REMOVABLE_WORKTREE` | branch は develop 取り込み済みで worktree は clean。 | 明示的な execute cleanup で削除する。 |
| `REMOVABLE_BRANCH` | branch は develop 取り込み済みで worktree 未接続。 | 明示的な execute cleanup で branch を削除する。 |
| `STALE_METADATA` | Git に存在しない path の worktree metadata が残っている。 | execute cleanup で `git worktree prune` する。 |
| `UNREGISTERED_PATH` | worktree root 配下に Git checkout らしい未登録ディレクトリがある。 | 削除前に手動確認する。 |
| `NON_GIT_DIRECTORY` | worktree root 配下に Git worktree ではないディレクトリがある。 | 削除前に手動確認する。 |
| `DIRTY_WORKTREE` | branch は develop 取り込み済みだが、worktree に未コミット差分が残っている。 | 内容を確認して保存、破棄、別 commit を判断する。 |
| `UNMERGED_WORKTREE` | branch 先端が develop 未取り込みで、登録 worktree がある。 | 個別 finalize / rebase するか、明示的に破棄判断する。 |
| `UNMERGED_BRANCH` | branch 先端が develop 未取り込みで、登録 worktree がない。 | worktree 再作成、個別 finalize、破棄を判断する。 |
| `DETACHED_WORKTREE` | 登録 worktree が detached HEAD。 | 競合、rebase 中断、手動 checkout 状態を確認する。 |
| `DETACHED_HEAD_BRANCH` | merged branch の先端を detached worktree が保持している。 | branch 削除前に detached worktree を確認する。 |
| `NON_CODEX_WORKTREE` | `codex/*` 以外の branch を使う登録 worktree。 | この掃除対象から外す。 |

## 手動メモ

生成処理は `## 手動メモ` セクションを保持して書き換える。人間の判断はここに残す。

```markdown
- `codex/example-task`: visual QA 完了まで保持。担当: Codex。次回確認: 2026-07-06。
- `E:\AstralRecord-Worktrees\old-task`: backup 後に削除してよいことを確認済み。次回 cleanup で削除。
```

`DIRTY_WORKTREE`、`DETACHED_WORKTREE`、`UNREGISTERED_PATH`、`NON_GIT_DIRECTORY` は branch 名だけで判断しない。path を確認し、保持理由を具体的に報告する。

## 報告

worktree 関連 skill の結果には、短い管理行を含める。

```markdown
## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>
```
