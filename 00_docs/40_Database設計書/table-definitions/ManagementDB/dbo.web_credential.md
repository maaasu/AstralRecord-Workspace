# dbo.web_credential テーブル定義

Minecraft UUID単位でWeb固定ログインID、パスワードハッシュ、有効状態、セッション失効版を保持する。ゲームDBの`user`へ外部キーを張らず、ゲームリセット後も残す。

| カラム | 型 | NULL | 内容 |
|---|---|---|---|
| `player_uuid` | `UNIQUEIDENTIFIER` | NO | 管理プレイヤーUUID。主キー |
| `login_id` | `NVARCHAR(64)` | YES | API生成の固定ログインID。無効化後も予約保持する。NULL以外で一意 |
| `password_hash` | `NVARCHAR(512)` | YES | PBKDF2-HMAC-SHA512の自己記述ハッシュ。無効化時はNULL |
| `enabled` | `BIT` | NO | ID・パスワードログインの有効状態 |
| `session_version` | `UNIQUEIDENTIFIER` | NO | Cookie失効照合用。認証情報の有効化・変更・無効化ごとに更新 |
| `created_at_utc` | `DATETIME2(3)` | NO | UTC作成時刻 |
| `updated_at_utc` | `DATETIME2(3)` | NO | UTC更新時刻 |

コードログインの消費成功時に無効状態の行を作成する。初回有効化と再有効化には、Webが保護Cookieから導出した5分以内のMinecraftコード認証日時を要求する。通常の変更・無効化は同日時または現在パスワードで認証する。各更新は`session_version`を条件にしたCASで行い、成功時に新しい版へ更新する。
