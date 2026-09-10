# dbo.market_listing_create_receipt テーブル定義

マーケット出品作成の操作 ID、要求内容、確定済み応答を同一 transaction で保存する冪等 receipt です。Plugin は応答未受信時に同じ操作 ID の POST 再送または結果照会を行い、二重 escrow を防ぎます。

| カラム名 | データ型 | PK | NotNull | 説明 |
|:--|:--|:--:|:--:|:--|
| `operation_id` | `UNIQUEIDENTIFIER` | ○ | ○ | Plugin が作成する出品作成操作 ID |
| `seller_account_id` | `UNIQUEIDENTIFIER` |  | ○ | 出品者アカウント UUID |
| `request_hash` | `CHAR(64)` |  | ○ | 操作 ID 再利用を検出する SHA-256 hash |
| `listing_id` | `UNIQUEIDENTIFIER` |  | ○ | 確定した出品 ID |
| `response_json` | `NVARCHAR(MAX)` |  | ○ | 確定時の `MarketListingResponse` JSON |
| `completed_at` | `DATETIME2(3)` |  | ○ | 操作確定日時 |
| `created_at` | `DATETIME2(3)` |  | ○ | receipt 作成日時 |

| 制約名 | 条件 |
|:--|:--|
| `PK_market_listing_create_receipt` | `operation_id` |
| `FK_market_listing_create_receipt_seller_account` | `seller_account_id` → `dbo.account(uuid)` |
| `FK_market_listing_create_receipt_listing` | `listing_id` → `dbo.market_listing(listing_id)` |
| `CK_market_listing_create_receipt_hash` | `request_hash` は64文字 |
| `CK_market_listing_create_receipt_response_json` | `response_json` は有効な JSON |

| インデックス名 | カラム | 用途 |
|:--|:--|:--|
| `IX_market_listing_create_receipt_seller_operation` | `seller_account_id`, `operation_id` | 出品者による結果照会 |
