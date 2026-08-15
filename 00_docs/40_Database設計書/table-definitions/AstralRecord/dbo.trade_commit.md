# dbo.trade_commit

## 役割

プレイヤー間トレード確定の冪等台帳。item の inventory membership、装備／ルーン個体の所有 account、Gold 額面の更新と同じ `AstralRecord` transaction で terminal 行を保存する。

## カラム

| カラム | 型 | NULL | 説明 |
|---|---|---|---|
| `operation_id` | uniqueidentifier | NO | Plugin session 単位の冪等キー（PK） |
| `player_a_account_id` | uniqueidentifier | NO | 参加者 A の account |
| `player_b_account_id` | uniqueidentifier | NO | 参加者 B の account |
| `request_hash` | char(64) | NO | operation ID を含む正規化要求の SHA-256 |
| `result_payload_json` | nvarchar(max) | NO | 確定済み応答（A/B affected entry ID を含む） |
| `completed_at` | datetime2(3) | NO | transaction 確定日時 |
| `created_at` | datetime2(3) | NO | 台帳作成日時 |
| `created_by` | uniqueidentifier | NO | 実行 account |

## 制約・運用

- 同じ `operation_id` と同じ要求ハッシュは保存済み応答を返し、item、個体 owner、Gold を再移管しない。
- 同じ `operation_id` を異なる account、明細、数量、Gold、更新者で再利用した場合は API が 409 を返す。
- `PENDING` 行は作らない。transaction 未commit の操作は台帳にも所有権にも Gold にも存在しない。
- 受取側の Plugin は応答内 A/B affected entry ID を API 正本から再同期して、後続 autosave による membership 巻き戻しを防ぐ。
