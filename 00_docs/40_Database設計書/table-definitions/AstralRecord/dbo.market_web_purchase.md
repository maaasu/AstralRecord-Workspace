# dbo.market_web_purchase

Webから本人確認を経て登録されたマーケット購入要求を保持する。主キー `operation_id` は画面で生成し、再送時も同じ値を使う。ゲームサーバーでオンライン中のアカウントは `PENDING` のまま保留し、Plugin のアカウント保存境界で決済する。オフラインならAPIが登録直後に決済する。

| 列 | 型 | 用途 |
|:--|:--|:--|
| operation_id | uniqueidentifier | 冪等操作ID |
| actor_user_uuid | uniqueidentifier | Web認証済み本人UUID |
| buyer_account_id | uniqueidentifier | 購入先アカウント |
| listing_id | uniqueidentifier | 出品 |
| quantity | bigint | 購入数量 |
| status | nvarchar(16) | PENDING / COMPLETED / FAILED |
| transaction_id | uniqueidentifier NULL | 確定済み約定 |
| error_code | nvarchar(100) NULL | 確定拒否理由 |
| created_at / updated_at | datetime2(3) | 登録・更新時刻 |

スキーマ更新は `20260926_market_web_purchase.sql` を `db-migrate` で適用する。
