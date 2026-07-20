# dbo.account_quest_completion

アカウントごとのクエスト完了日時を保存します。

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_quest_completion_id` | UNIQUEIDENTIFIER | 不可 | 完了行 ID |
| `account_quest_state_id` | UNIQUEIDENTIFIER | 不可 | クエスト状態への外部キー |
| `quest_id` | NVARCHAR(100) | 不可 | クエスト ID |
| `completed_at` | DATETIME2(3) | 不可 | 完了日時 |
| `created_at` / `updated_at` | DATETIME2(3) | 不可 | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | 不可 | 作成・更新者 |

`account_quest_state_id` と `quest_id` の組み合わせは一意です。
