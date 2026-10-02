# dbo.player_admin_edit_audit

ゲームDBの `dbo.player_admin_edit_operation` から操作IDで冪等投影する長期保持監査。ManagementDBはゲームDBを外部キー参照せず、ゲームDB再構築後も記録を保持する。

| カラム | 型 | NULL | 内容 |
|:--|:--|:--|:--|
| `operation_id` | UNIQUEIDENTIFIER | × | 操作ID（主キー） |
| `edit_session_id` | UNIQUEIDENTIFIER | × | 元の編集セッション |
| `actor_user_uuid` / `target_user_uuid` | UNIQUEIDENTIFIER | × | 編集者・対象ユーザー |
| `account_id` | UNIQUEIDENTIFIER | × | 対象キャラクター |
| `reason` | NVARCHAR(500) | × | 編集セッション開始時に確定した理由 |
| `action` | VARCHAR(16) | × | `APPLY` / `CANCEL` |
| `request_hash` | CHAR(64) | × | 元要求のSHA-256 |
| `before_json` / `after_json` | NVARCHAR(MAX) | × | 変更前後の監査スナップショット |
| `occurred_at_utc` | DATETIME2(3) | × | 元操作の確定時刻 |
| `projection_status` | VARCHAR(16) | × | 確定投影を示す `PROJECTED` |

JSON形式と列挙値を制約する。`IX_player_admin_edit_audit_target_time` は対象ユーザー別の履歴照会、`IX_player_admin_edit_audit_session` はセッション別の照会に使う。ゲームDBのアカウント削除で監査行を削除しない。
