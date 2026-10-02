# dbo.player_admin_edit_operation

ゲームDB内の確定操作台帳とManagementDBへの監査投影元。操作とゲーム状態変更を同じトランザクションで確定し、投影失敗時も再試行できる。

| カラム | 型 | NULL | 内容 |
|:--|:--|:--|:--|
| `operation_id` | UNIQUEIDENTIFIER | × | 冪等な操作ID（主キー） |
| `edit_session_id` | UNIQUEIDENTIFIER | × | セッション参照。cascade なし |
| `request_hash` | CHAR(64) | × | 要求SHA-256 |
| `action` | VARCHAR(16) | × | `APPLY` / `CANCEL` |
| `response_json` | NVARCHAR(MAX) | × | 再送時に返す確定応答 |
| `before_json` / `after_json` | NVARCHAR(MAX) | × | 変更前後の監査スナップショット |
| `created_at_utc` | DATETIME2(3) | × | 操作確定時刻 |
| `audit_projected_at_utc` | DATETIME2(3) | ○ | ManagementDB監査への投影完了時刻 |

JSON列は有効なJSONであることを制約する。`IX_player_admin_edit_operation_projection` は未投影行を時刻順に探す。監査投影はロック解除の前提にはしないが、未投影行を破棄してはならない。
