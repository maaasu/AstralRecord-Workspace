# dbo.player_admin_server_runtime

管理者編集に参加するサーバー起動セッションの登録・生存情報。主キーは `server_id`。

| カラム | 型 | NULL | 内容 |
|:--|:--|:--|:--|
| `server_id` | NVARCHAR(64) | × | サーバーID |
| `server_session_id` | UNIQUEIDENTIFIER | × | 現在の起動セッション |
| `role` | VARCHAR(16) | × | `PROXY` / `LOBBY` / `RPG` |
| `enabled` | BIT | × | 退避対象への参加状態 |
| `item_catalog_hash` | CHAR(64) | ○ | 当該サーバーのアイテムカタログSHA-256 |
| `class_catalog_hash` | CHAR(64) | ○ | 当該サーバーのクラスカタログSHA-256 |
| `registered_at_utc` / `last_seen_utc` | DATETIME2(3) | × | UTCの登録・最終生存確認時刻 |

編集開始時には設定済みの必須サーバーと登録済みの有効サーバーを照合する。生存時刻やカタログの不一致はAPIが判定する。
