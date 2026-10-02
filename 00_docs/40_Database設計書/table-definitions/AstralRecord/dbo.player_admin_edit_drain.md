# dbo.player_admin_edit_drain

編集セッション開始時に対象となった各サーバーの退避確認を保持する。主キーは (`edit_session_id`, `server_id`)。

| カラム | 型 | NULL | 内容 |
|:--|:--|:--|:--|
| `edit_session_id` | UNIQUEIDENTIFIER | × | `dbo.player_admin_edit_session` 参照。cascade なし |
| `server_id` | NVARCHAR(64) | × | 対象サーバー |
| `server_session_id` | UNIQUEIDENTIFIER | × | 登録時のサーバー起動セッション |
| `saved` / `offline` | BIT | × | 保存完了・オフライン確認 |
| `ack_id` | UNIQUEIDENTIFIER | ○ | 冪等な確認ID |
| `acknowledged_at_utc` | DATETIME2(3) | ○ | 確認時刻。`ack_id` と同時にNULLまたは非NULL |

`IX_player_admin_edit_drain_server_ack` はサーバー別の未処理確認照会に使う。確認済みでも行は編集証跡として残す。
