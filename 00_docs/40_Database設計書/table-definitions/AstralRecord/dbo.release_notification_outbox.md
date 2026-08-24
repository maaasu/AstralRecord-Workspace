# dbo.release_notification_outbox テーブル定義

公開済みリリースノートのDiscord通知をDBで管理するOutboxです。APIのバックグラウンドワーカーが未送信・再試行対象を取得し、送信結果を更新します。

## カラム

| カラム | 型 | NULL | 既定値 | 内容 |
|---|---|---|---|---|
| `outbox_id` | `UNIQUEIDENTIFIER` | NO |  | 主キー |
| `release_note_id` | `UNIQUEIDENTIFIER` | NO |  | `dbo.release_note` のID |
| `channel` | `NVARCHAR(50)` | NO |  | 通知チャネルの論理名 |
| `status` | `INT` | NO | `0` | `0:Pending, 1:Processing, 2:Sent, 3:Failed` |
| `attempt_count` | `INT` | NO | `0` | 送信試行回数 |
| `next_attempt_at_utc` | `DATETIME2(3)` | NO |  | 次回送信可能日時 |
| `lease_until_utc` | `DATETIME2(3)` | YES |  | 処理リース期限 |
| `lease_token` | `UNIQUEIDENTIFIER` | YES |  | 処理所有者トークン |
| `sent_at_utc` | `DATETIME2(3)` | YES |  | Discord送信成功日時 |
| `discord_message_id` | `NVARCHAR(64)` | YES |  | DiscordメッセージID |
| `last_error` | `NVARCHAR(2000)` | YES |  | 直近のエラー概要 |
| `created_at_utc` | `DATETIME2(3)` | NO |  | 作成日時（UTC） |
| `updated_at_utc` | `DATETIME2(3)` | NO |  | 更新日時（UTC） |

## 制約

- `PK_release_notification_outbox`: `outbox_id`
- `FK_release_notification_outbox_note`: `release_note_id` → `dbo.release_note(release_note_id)`
- `UX_release_notification_outbox_note_channel`: リリースノートとチャネルの組み合わせを一意にする。
- `CK_release_notification_outbox_status`: status は0〜3。
- `CK_release_notification_outbox_attempt_count`: 試行回数は0以上。

## インデックス

- `IX_release_notification_outbox_due`: チャネル、状態、次回送信日時によるOutbox取得用。
