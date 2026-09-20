# dbo.web_trusted_browser テーブル定義

Minecraft本人確認後に「このブラウザを信頼する」を選択したWeb管理者のトークンを、ManagementDBへ失効可能なハッシュとして保持する。トークンの有効期間は発行からの固定日数ではなく、最後に管理画面を利用してから7日間とする。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `trusted_browser_id` | `UNIQUEIDENTIFIER` | NO | 信頼ブラウザ登録の主キー |
| `player_uuid` | `UNIQUEIDENTIFIER` | NO | Web管理者のMinecraft UUID |
| `session_version` | `UNIQUEIDENTIFIER` | NO | 登録時の認証情報セッション世代。世代更新で無効になる |
| `token_hash` | `NVARCHAR(128)` | NO | ブラウザへ発行したトークンのSHA-256ハッシュ。一意 |
| `created_at_utc` | `DATETIME2(3)` | NO | 信頼登録日時 |
| `last_used_at_utc` | `DATETIME2(3)` | NO | 最後に管理画面の認可へ成功した日時 |
| `revoked_at_utc` | `DATETIME2(3)` | YES | 明示または運用上の失効日時。NULLは未失効 |

Webはトークン本体をHttpOnly・Secure Cookieだけに保持し、API・DBにはハッシュだけを保存する。APIはWeb管理フラグ、現在の`session_version`、トークンハッシュ、`revoked_at_utc IS NULL`、`last_used_at_utc >= 現在時刻-7日`を確認し、成功時に`last_used_at_utc`を現在時刻へ更新する。
