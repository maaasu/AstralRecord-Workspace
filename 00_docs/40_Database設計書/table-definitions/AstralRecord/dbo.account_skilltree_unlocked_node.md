# dbo.account_skilltree_unlocked_node

`dbo.account_skilltree_state` 配下の解放済みノード一覧を保持する子テーブル。
1 行が 1 ノード解放を表す。

## カラム

| カラム | 型 | NULL | 既定値 | 説明 |
|:--|:--|:--|:--|:--|
| `account_skilltree_unlocked_node_id` | UNIQUEIDENTIFIER | 不可 | - | 解放ノード行 ID |
| `account_skilltree_state_id` | UNIQUEIDENTIFIER | 不可 | - | `dbo.account_skilltree_state.account_skilltree_state_id` |
| `node_id` | NVARCHAR(100) | 不可 | - | filebase `skilltree` ノード ID |
| `created_at` | DATETIME2(3) | 不可 | - | 作成日時 |
| `updated_at` | DATETIME2(3) | 不可 | - | 更新日時 |
| `created_by` | UNIQUEIDENTIFIER | 不可 | - | 作成者 |
| `updated_by` | UNIQUEIDENTIFIER | 不可 | - | 更新者 |

## 制約

- 主キー: `PK_account_skilltree_unlocked_node (account_skilltree_unlocked_node_id)`
- 外部キー: `FK_account_skilltree_unlocked_node_state (account_skilltree_state_id)` -> `dbo.account_skilltree_state(account_skilltree_state_id)`
- 一意制約: `UX_account_skilltree_unlocked_node_state_node (account_skilltree_state_id, node_id)`
- `node_id` は空文字不可

## 運用メモ

- ノード定義の正本は filebase `40_filebase/35.features.skilltree/nodes/*.json`。
- 配置・接続構造は `40_filebase/35.features.skilltree/structures/*.json` に保持し、DB には保存しない。
- このテーブルは「どのノードを解放済みか」だけを保持し、座標情報は保持しない。
