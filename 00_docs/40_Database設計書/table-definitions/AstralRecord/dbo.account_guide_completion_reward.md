# dbo.account_guide_completion_reward

全ガイド達成報酬の配信済み記録。アカウントごとに一度だけ作成し、後日のガイド追加や再ログインで再配信しない。ガイド手順の達成記録とは独立している。

| 列 | 型 | NULL | 説明 |
|:--|:--|:--|:--|
| `account_id` | UNIQUEIDENTIFIER | 不可 | `dbo.account.uuid`、主キー |
| `mail_id` | NVARCHAR(128) | 可 | 通常受取時は同一トランザクションで作成した `dbo.player_mail_delivery.mail_id`。複製元から受取済み状態を継承した場合は NULL |
| `claimed_at` | DATETIME2(3) | 不可 | 配信確定日時 |
| `claimed_by` | UNIQUEIDENTIFIER | 不可 | 操作したユーザー UUID |
| `inherited_from_account_id` | UNIQUEIDENTIFIER | 可 | 複製による継承元アカウント UUID。通常受取時は NULL |

通常受取の `mail_id` は一意。メールと記録は同一ゲーム DB トランザクションで書き込む。アカウント複製時は複製元か上書き先に受取済み記録があれば、新アカウントへ受取済み状態だけを継承し、報酬メールは再配信しない。削除済みアカウントの記録も監査と再送抑止のため保持する。
