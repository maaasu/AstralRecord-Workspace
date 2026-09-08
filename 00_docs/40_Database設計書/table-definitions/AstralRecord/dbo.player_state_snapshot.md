# dbo.player_state_snapshot

Plugin が計算した player-state snapshot の冪等台帳。`snapshot_id` が同一なら要求 hash を照合し、同一 payload には最初の確定 ACK をそのまま返す。ゲーム内の抽選、素材消費、残高操作はここで再実行しない。POST 応答を受け取れなかった Plugin は `snapshot_id` と `account_id` で本台帳を照会し、保存済み ACK を取得できる。

## カラム

| カラム | 型 | NULL | 説明 |
|:--|:--|:--:|:--|
| `snapshot_id` | UNIQUEIDENTIFIER | × | Plugin 発行の再送キー（PK） |
| `account_id` | UNIQUEIDENTIFIER | × | snapshot 所有アカウント（FK → `dbo.account.uuid`） |
| `request_hash` | CHAR(64) | × | request の SHA-256。異なる payload の snapshot ID 再利用を拒否する |
| `ack_payload_json` | NVARCHAR(MAX) | × | 最初に commit した ACK。再送でも同一内容を返す |
| `created_at` / `completed_at` | DATETIME2(3) | × | transaction 開始・確定時刻 |
| `created_by` | UNIQUEIDENTIFIER | × | Plugin が送る更新者アカウント UUID |

## 制約・索引

- `PK_player_state_snapshot(snapshot_id)`
- `FK_player_state_snapshot_account(account_id)` は account の論理削除後も台帳を保持するため `NO ACTION`。
- `request_hash` は 64 桁 hexadecimal、`ack_payload_json` は有効 JSON を CHECK する。
- `IX_player_state_snapshot_account_completed(account_id, completed_at)` はアカウント単位の台帳監査・保守用。
- 結果照会で復元した `ack_payload_json` は、内部の `snapshotId` / `accountId` が検索条件と一致する場合だけ有効な完成結果として返す。

## 関連する account 進行版

`dbo.account.progress_version`（INT、既定 `1`、`>= 1`）は `updated_at` と分離した進行度専用の楽観ロック版。level・experience・class 進行または mode が変わると増分する。位置・menu shortcut・識別情報などの更新では増分しない。
