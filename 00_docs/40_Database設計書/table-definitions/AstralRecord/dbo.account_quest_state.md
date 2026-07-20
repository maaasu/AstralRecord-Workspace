# dbo.account_quest_state

アカウント単位のクエスト進行状態の親テーブルです。クエストの受領中状態、完了履歴、クールダウンは子テーブルで管理します。

## カラム

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_quest_state_id` | UNIQUEIDENTIFIER | 不可 | クエスト状態 ID |
| `account_id` | UNIQUEIDENTIFIER | 不可 | `dbo.account.uuid` |
| `version` | INT | 不可 | 保存世代 |
| `created_at` / `updated_at` | DATETIME2(3) | 不可 | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | 不可 | 作成・更新者 |
| `is_deleted` | BIT | 不可 | 論理削除フラグ |

## 制約

- `UX_account_quest_state_account` により有効な状態は 1 アカウント 1 行。
- 受領中クエストは `account_quest_active`、目標進行は `account_quest_objective_progress` に保存する。
