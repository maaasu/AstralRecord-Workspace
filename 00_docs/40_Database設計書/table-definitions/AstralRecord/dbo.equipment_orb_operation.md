# dbo.equipment_orb_operation

## 役割

オーブによる装備操作の確定結果を保持する冪等台帳。オーブ・状態変化素材・ゴールドの消費、装備更新、エンチャント抽選結果と同じ `AstralRecord` DB transaction で terminal 行を登録する。

## カラム

| カラム | 型 | NULL | 説明 |
|---|---|---|---|
| `operation_id` | uniqueidentifier | NO | Plugin が操作ごとに発行する冪等キー（PK） |
| `account_id` | uniqueidentifier | NO | 所有アカウント |
| `equipment_instance_id` | uniqueidentifier | NO | 対象装備個体 |
| `orb_inventory_entry_id` | uniqueidentifier | NO | APIが共通消費順で解決したオーブentry。支払い確定後は実際に消費したentryを保持する |
| `orb_item_id` | nvarchar(128) | NO | 再送内容照合用オーブ item ID |
| `operation_type` | nvarchar(32) | NO | `ENHANCE` / `REPAIR` / `TRANSCENDENCE` / `ENCHANT` |
| `request_hash` | char(64) | NO | operationIdを含む正規化要求のSHA-256 |
| `result_code` | nvarchar(32) | NO | `APPLIED`、`NO_CANDIDATE`、`NO_SLOT`、`PAYMENT_UNAVAILABLE`、`NOT_ELIGIBLE` |
| `result_payload_json` | nvarchar(max) | NO | 初回に確定した業務結果payload。装備本体だけは再送時に現在値へ差し替える |
| `payment_consumed` | bit | NO | 支払いを同transactionで消費した場合1 |
| `affected_inventory_entry_ids_json` | nvarchar(max) | NO | PluginがAPI正本へ再同期するentry ID配列 |
| `created_at` | datetime2(3) | NO | 台帳作成日時 |
| `completed_at` | datetime2(3) | NO | terminal確定日時 |
| `created_by` | uniqueidentifier | NO | 実行アカウント |

## 制約・運用

- 同じ `operation_id` と同じ要求hashは保存済み業務結果を返し、支払い・抽選・装備更新を再実行しない。`equipment` だけは台帳accountが継続所有し、GAME BAG/HOTBARまたはactive loadoutに現在も保持する非削除個体の現在値を返す。所有だけでは不十分で、譲渡・削除・membership欠落時は `targetAvailable=false` / `equipment=null` tombstone として返す。
- 同じ `operation_id` を異なる要求へ再利用した場合はAPIが409を返す。
- 業務失敗も支払いなしのterminal結果として保存し、マスタ更新や再送で結果が変化しないようにする。
- `PENDING` 行は作成しない。transaction未commitの操作は台帳にも装備にも支払いにも存在しない。
