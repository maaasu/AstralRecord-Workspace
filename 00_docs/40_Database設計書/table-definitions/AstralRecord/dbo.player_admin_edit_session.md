# dbo.player_admin_edit_session

管理者編集の対象ユーザーを排他し、サーバー退避から確定・復旧までの状態を保持する。期限切れは再確認の契機であり、期限だけで排他を解除しない。

| カラム | 型 | NULL | 内容 |
|:--|:--|:--|:--|
| `edit_session_id` | UNIQUEIDENTIFIER | × | 主キー |
| `user_uuid` | UNIQUEIDENTIFIER | × | 対象ユーザー。`dbo.user.uuid` を参照 |
| `account_id` | UNIQUEIDENTIFIER | × | 対象キャラクター。`dbo.account.uuid` を参照 |
| `actor_user_uuid` | UNIQUEIDENTIFIER | × | 編集者 |
| `reason` | NVARCHAR(500) | × | 編集理由 |
| `status` | VARCHAR(32) | × | `DRAINING` / `READY` / `APPLYING` / `COMPLETED` / `CANCELED` / `RECOVERY_REQUIRED` |
| `revision` | BIGINT | × | 状態更新の競合検出用。1以上 |
| `expected_server_count` | INT | × | 退避確認を要求するサーバー数。0以上 |
| `item_catalog_hash` | CHAR(64) | ○ | 編集開始時に固定したアイテムカタログのSHA-256 |
| `class_catalog_hash` | CHAR(64) | ○ | 編集開始時に固定したクラスカタログのSHA-256 |
| `created_at_utc` / `updated_at_utc` / `expires_at_utc` | DATETIME2(3) | × | UTCの作成・更新・無操作期限 |
| `completed_at_utc` | DATETIME2(3) | ○ | 終端状態の確定時刻 |

`UX_player_admin_edit_session_active_user` は `DRAINING`、`READY`、`APPLYING`、`RECOVERY_REQUIRED` の `user_uuid` を一意にする。`IX_player_admin_edit_session_account_status` はアカウント削除・編集可否の照会に使う。ユーザー・アカウント参照は cascade しないため、台帳を残したまま物理削除できない。
