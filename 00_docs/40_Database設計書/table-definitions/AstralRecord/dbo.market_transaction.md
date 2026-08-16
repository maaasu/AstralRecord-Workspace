# dbo.market_transaction テーブル定義

マーケットの約定履歴を管理するテーブルです。
相場算出の正本データとして使用するため、出品時点の商品情報と価格を冗長保持します。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `AstralRecord` |
| スキーマ名 | `dbo` |
| テーブル名 | `market_transaction` |
| 完全修飾名 | `dbo.market_transaction` |
| 主キー | `transaction_id` |
| 外部キー参照先 | `dbo.market_listing.listing_id`, `dbo.account.uuid` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `transaction_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 取引 ID |
| `listing_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 出品 ID |
| `seller_account_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 出品者アカウント UUID |
| `buyer_account_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 購入者アカウント UUID |
| `item_category` | `NVARCHAR(50)` |  | ○ |  | item カテゴリ |
| `item_id` | `NVARCHAR(100)` |  | ○ |  | item マスタ ID |
| `instance_type` | `NVARCHAR(30)` |  |  |  | 個体種別 |
| `instance_id` | `UNIQUEIDENTIFIER` |  |  |  | 個体 UUID |
| `quantity` | `INT` |  | ○ |  | 数量 |
| `currency_id` | `NVARCHAR(50)` |  | ○ |  | 支払い通貨 ID |
| `unit_price` | `BIGINT` |  | ○ |  | 単価 |
| `total_price` | `BIGINT` |  | ○ |  | 総額 |
| `fee_amount` | `BIGINT` |  | ○ | `0` | 手数料 |
| `seller_proceeds` | `BIGINT` |  | ○ |  | 売主受取額 |
| `valuation_signature` | `NVARCHAR(300)` |  |  |  | 個体評価シグネチャ |
| `valuation_snapshot_json` | `NVARCHAR(MAX)` |  |  |  | 成立時評価 JSON |
| `idempotency_key` | `NVARCHAR(100)` |  | ○ |  | 二重購入防止キー |
| `completed_at` | `DATETIME2(3)` |  | ○ |  | 成立日時 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |

---

## 制約定義

| 制約名 | 条件 | 説明 |
|:--|:--|:--|
| `PK_market_transaction` | `transaction_id` | 主キー |
| `FK_market_transaction_listing` | `listing_id` → `dbo.market_listing(listing_id)` | 出品参照 |
| `FK_market_transaction_seller_account` | `seller_account_id` → `dbo.account(uuid)` | 出品者 |
| `FK_market_transaction_buyer_account` | `buyer_account_id` → `dbo.account(uuid)` | 購入者 |
| `IX_market_transaction_listing` | `listing_id` | 部分購入を含む出品別約定検索 |
| `UQ_market_transaction_idempotency` | `buyer_account_id`, `idempotency_key` | 二重購入防止 |
| `CK_market_transaction_quantity` | `[quantity] >= 1` | 数量 |
| `CK_market_transaction_price` | `[unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [fee_amount] >= 0 AND [seller_proceeds] = [total_price] - [fee_amount]` | 価格 |
| `CK_market_transaction_valuation_json` | `[valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1` | 評価 JSON |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_market_transaction` | `transaction_id` | CLUSTERED | 主キー検索 |
| `IX_market_transaction_listing` | `listing_id` | NONCLUSTERED | 部分購入を含む出品別約定検索 |
| `UQ_market_transaction_idempotency` | `buyer_account_id`, `idempotency_key` | UNIQUE | 冪等性保証 |
| `IX_market_transaction_item_completed` | `item_category`, `item_id`, `completed_at` | NONCLUSTERED | 相場算出 |
| `IX_market_transaction_signature_completed` | `valuation_signature`, `completed_at` | NONCLUSTERED | 個体条件別相場算出 |
| `IX_market_transaction_seller` | `seller_account_id`, `completed_at` | NONCLUSTERED | 出品者履歴 |
| `IX_market_transaction_buyer` | `buyer_account_id`, `completed_at` | NONCLUSTERED | 購入者履歴 |

---

## DDL

```sql
CREATE TABLE [dbo].[market_transaction] (
    [transaction_id]          UNIQUEIDENTIFIER NOT NULL,
    [listing_id]              UNIQUEIDENTIFIER NOT NULL,
    [seller_account_id]       UNIQUEIDENTIFIER NOT NULL,
    [buyer_account_id]        UNIQUEIDENTIFIER NOT NULL,
    [item_category]           NVARCHAR(50)     NOT NULL,
    [item_id]                 NVARCHAR(100)    NOT NULL,
    [instance_type]           NVARCHAR(30)         NULL,
    [instance_id]             UNIQUEIDENTIFIER     NULL,
    [quantity]                INT              NOT NULL,
    [currency_id]             NVARCHAR(50)     NOT NULL,
    [unit_price]              BIGINT           NOT NULL,
    [total_price]             BIGINT           NOT NULL,
    [fee_amount]              BIGINT           NOT NULL CONSTRAINT [DF_market_transaction_fee_amount] DEFAULT (0),
    [seller_proceeds]         BIGINT           NOT NULL,
    [valuation_signature]     NVARCHAR(300)        NULL,
    [valuation_snapshot_json] NVARCHAR(MAX)        NULL,
    [idempotency_key]         NVARCHAR(100)    NOT NULL,
    [completed_at]            DATETIME2(3)     NOT NULL,
    [created_at]              DATETIME2(3)     NOT NULL,
    [created_by]              UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_market_transaction] PRIMARY KEY CLUSTERED ([transaction_id]),
    CONSTRAINT [FK_market_transaction_listing] FOREIGN KEY ([listing_id])
        REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_transaction_seller_account] FOREIGN KEY ([seller_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_transaction_buyer_account] FOREIGN KEY ([buyer_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [UQ_market_transaction_idempotency] UNIQUE ([buyer_account_id], [idempotency_key]),
    CONSTRAINT [CK_market_transaction_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_market_transaction_price] CHECK ([unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [fee_amount] >= 0 AND [seller_proceeds] = [total_price] - [fee_amount]),
    CONSTRAINT [CK_market_transaction_valuation_json] CHECK ([valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_listing]
    ON [dbo].[market_transaction] ([listing_id]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_item_completed]
    ON [dbo].[market_transaction] ([item_category], [item_id], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_signature_completed]
    ON [dbo].[market_transaction] ([valuation_signature], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_seller]
    ON [dbo].[market_transaction] ([seller_account_id], [completed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_transaction_buyer]
    ON [dbo].[market_transaction] ([buyer_account_id], [completed_at]);
GO
```
