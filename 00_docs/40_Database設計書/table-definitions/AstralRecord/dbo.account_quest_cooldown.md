# dbo.account_quest_cooldown

アカウントごとのクエスト再受領可能日時を保存します。

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_quest_cooldown_id` | UNIQUEIDENTIFIER | 不可 | クールダウン行 ID |
| `account_quest_state_id` | UNIQUEIDENTIFIER | 不可 | クエスト状態への外部キー |
| `quest_id` | NVARCHAR(100) | 不可 | クエスト ID |
| `cooldown_until` | DATETIME2(3) | 不可 | クールダウン終了日時 |
| `created_at` / `updated_at` | DATETIME2(3) | 不可 | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | 不可 | 作成・更新者 |

`account_quest_state_id` と `quest_id` の組み合わせは一意です。
