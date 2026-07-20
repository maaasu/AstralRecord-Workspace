# dbo.account_quest_active

アカウントが現在受領しているクエストを保存します。

| カラム | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_quest_active_id` | UNIQUEIDENTIFIER | 不可 | 受領状態 ID |
| `account_quest_state_id` | UNIQUEIDENTIFIER | 不可 | `account_quest_state` への外部キー |
| `quest_id` | NVARCHAR(100) | 不可 | クエスト ID |
| `accepted_at` | DATETIME2(3) | 不可 | 受領日時 |
| `accepted_npc_id` | NVARCHAR(100) | 可 | 受領元 NPC ID |
| `ready_to_turn_in` | BIT | 不可 | NPC 報告可能状態 |
| `created_at` / `updated_at` | DATETIME2(3) | 不可 | 作成・更新日時 |
| `created_by` / `updated_by` | UNIQUEIDENTIFIER | 不可 | 作成・更新者 |

`account_quest_state_id` と `quest_id` の組み合わせは一意です。
