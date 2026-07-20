# dbo.account_quest_objective_progress

受領中クエストごとの目標進行数を保存します。

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_quest_objective_progress_id` | UNIQUEIDENTIFIER | 不可 | 進行行 ID |
| `account_quest_active_id` | UNIQUEIDENTIFIER | 不可 | 受領中クエストへの外部キー |
| `objective_id` | NVARCHAR(100) | 不可 | クエスト内目標 ID |
| `progress` | INT | 不可 | 進行数。0 以上 |
| `created_at` / `updated_at` | DATETIME2(3) | 不可 | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | 不可 | 作成・更新者 |

`account_quest_active_id` と `objective_id` の組み合わせは一意です。
