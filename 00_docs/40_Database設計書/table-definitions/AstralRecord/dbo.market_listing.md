# dbo.market_listing テーブル定義

マーケット出品を管理するテーブルです。
出品価格、相場判定スナップショット、出品状態、購入者情報、購入可能な残数を保持します。
複数 entry にまたがる escrow 元は `dbo.market_listing_source` で管理し、`source_inventory_entry_id` は既存データ互換用の先頭 source を保持します。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `AstralRecord` |
| スキーマ名 | `dbo` |
| テーブル名 | `market_listing` |
| 完全修飾名 | `dbo.market_listing` |
| 主キー | `listing_id` |
| 外部キー参照先 | `dbo.account.uuid`, `dbo.inventory_entry.inventory_entry_id` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `listing_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 出品 ID |
| `seller_account_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 出品者アカウント UUID |
| `buyer_account_id` | `UNIQUEIDENTIFIER` |  |  |  | 購入者アカウント UUID |
| `source_inventory_entry_id` | `UNIQUEIDENTIFIER` |  |  |  | 互換用の先頭 escrow 元 entry。全 source は `market_listing_source` |
| `item_category` | `NVARCHAR(50)` |  | ○ |  | item カテゴリ |
| `item_id` | `NVARCHAR(100)` |  | ○ |  | item マスタ ID |
| `instance_type` | `NVARCHAR(30)` |  |  |  | `EQUIPMENT` / `RUNE` |
| `instance_id` | `UNIQUEIDENTIFIER` |  |  |  | 個体 UUID |
| `quantity` | `INT` |  | ○ |  | 出品時の総数量 |
| `remaining_quantity` | `INT` |  | ○ | `0` | 現在 escrow に残る購入可能数量。売却済み・取り下げ済みは `0` |
| `currency_id` | `NVARCHAR(50)` |  | ○ |  | 支払い通貨 ID |
| `unit_price` | `BIGINT` |  | ○ |  | 単価 |
| `total_price` | `BIGINT` |  | ○ |  | 総額 |
| `price_floor` | `BIGINT` |  | ○ |  | 店売り価格下限 |
| `reference_unit_price` | `BIGINT` |  |  |  | 相場参照単価 |
| `price_deviation_rate` | `DECIMAL(18,6)` |  |  |  | `unit_price / reference_unit_price` |
| `price_confidence` | `NVARCHAR(20)` |  | ○ |  | `HIGH` / `MEDIUM` / `LOW` |
| `valuation_signature` | `NVARCHAR(300)` |  |  |  | 個体評価シグネチャ |
| `valuation_snapshot_json` | `NVARCHAR(MAX)` |  |  |  | 出品時評価 JSON |
| `status` | `NVARCHAR(20)` |  | ○ | `N'ACTIVE'` | 出品状態 |
| `status_reason` | `NVARCHAR(200)` |  |  |  | 状態理由 |
| `listed_at` | `DATETIME2(3)` |  | ○ |  | 出品日時 |
| `expires_at` | `DATETIME2(3)` |  | ○ |  | 掲載期限 |
| `sold_at` | `DATETIME2(3)` |  |  |  | 購入成立日時 |
| `canceled_at` | `DATETIME2(3)` |  |  |  | キャンセル日時 |
| `proceeds_claim_idempotency_key` | `NVARCHAR(128)` |  |  |  | 売上受取を再送するための確定済みキー |
| `proceeds_claim_amount` | `BIGINT` |  |  |  | 確定済み売上受取額 |
| `proceeds_claim_affected_entry_ids_json` | `NVARCHAR(MAX)` |  |  |  | 確定済み受取で更新した通貨 entry ID の JSON 配列 |
| `proceeds_claimed_at` | `DATETIME2(3)` |  |  |  | 売上受取確定日時 |
| `version` | `INT` |  | ○ | `1` | 楽観ロック用バージョン |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## 制約定義

| 制約名 | 条件 | 説明 |
|:--|:--|:--|
| `PK_market_listing` | `listing_id` | 主キー |
| `FK_market_listing_seller_account` | `seller_account_id` → `dbo.account(uuid)` | 出品者 |
| `FK_market_listing_buyer_account` | `buyer_account_id` → `dbo.account(uuid)` | 購入者 |
| `FK_market_listing_source_inventory_entry` | `source_inventory_entry_id` → `dbo.inventory_entry(inventory_entry_id)` | 互換用先頭 source entry |
| `CK_market_listing_quantity` | `[quantity] >= 1` | 数量は 1 以上 |
| `CK_market_listing_remaining_quantity` | `[remaining_quantity] >= 0 AND [remaining_quantity] <= [quantity]` | 購入可能残数は総数量の範囲内 |
| `CK_market_listing_price` | `[unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [price_floor] >= 0` | 価格制約 |
| `CK_market_listing_confidence` | `[price_confidence] IN (N'HIGH', N'MEDIUM', N'LOW')` | 信頼度 |
| `CK_market_listing_status` | `[status] IN (N'ACTIVE', N'SOLD', N'CANCELED', N'EXPIRED', N'SUSPENDED')` | 状態 |
| `CK_market_listing_version` | `[version] >= 1` | バージョン |
| `CK_market_listing_valuation_json` | `[valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1` | 評価 JSON |
| `CK_market_listing_proceeds_claim_amount` | `[proceeds_claim_amount] IS NULL OR [proceeds_claim_amount] >= 1` | 受取済み額 |
| `CK_market_listing_proceeds_claim_entries_json` | `[proceeds_claim_affected_entry_ids_json] IS NULL OR ISJSON([proceeds_claim_affected_entry_ids_json]) = 1` | 受取済み通貨 entry JSON |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_market_listing` | `listing_id` | CLUSTERED | 主キー検索 |
| `IX_market_listing_status_listed_at` | `status`, `listed_at` | NONCLUSTERED | 出品一覧 |
| `IX_market_listing_seller_status` | `seller_account_id`, `status` | NONCLUSTERED | アカウント別出品数集計 |
| `IX_market_listing_item_status_price` | `item_category`, `item_id`, `status`, `unit_price` | NONCLUSTERED | 商品検索 |
| `IX_market_listing_instance_active_status` | `instance_type`, `instance_id`, `is_deleted`, `status` | NONCLUSTERED | 個体出品状態の key-range 更新ロック |
| `IX_market_listing_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[market_listing] (
    [listing_id]               UNIQUEIDENTIFIER NOT NULL,
    [seller_account_id]        UNIQUEIDENTIFIER NOT NULL,
    [buyer_account_id]         UNIQUEIDENTIFIER     NULL,
    [source_inventory_entry_id] UNIQUEIDENTIFIER     NULL,
    [item_category]            NVARCHAR(50)     NOT NULL,
    [item_id]                  NVARCHAR(100)    NOT NULL,
    [instance_type]            NVARCHAR(30)         NULL,
    [instance_id]              UNIQUEIDENTIFIER     NULL,
    [quantity]                 INT              NOT NULL,
    [remaining_quantity]       INT              NOT NULL CONSTRAINT [DF_market_listing_remaining_quantity] DEFAULT (0),
    [currency_id]              NVARCHAR(50)     NOT NULL,
    [unit_price]               BIGINT           NOT NULL,
    [total_price]              BIGINT           NOT NULL,
    [price_floor]              BIGINT           NOT NULL,
    [reference_unit_price]     BIGINT               NULL,
    [price_deviation_rate]     DECIMAL(18,6)        NULL,
    [price_confidence]         NVARCHAR(20)     NOT NULL,
    [valuation_signature]      NVARCHAR(300)        NULL,
    [valuation_snapshot_json]  NVARCHAR(MAX)        NULL,
    [status]                   NVARCHAR(20)     NOT NULL CONSTRAINT [DF_market_listing_status] DEFAULT (N'ACTIVE'),
    [status_reason]            NVARCHAR(200)        NULL,
    [listed_at]                DATETIME2(3)     NOT NULL,
    [expires_at]               DATETIME2(3)     NOT NULL,
    [sold_at]                  DATETIME2(3)         NULL,
    [canceled_at]              DATETIME2(3)         NULL,
    [proceeds_claim_idempotency_key] NVARCHAR(128)       NULL,
    [proceeds_claim_amount]    BIGINT               NULL,
    [proceeds_claim_affected_entry_ids_json] NVARCHAR(MAX) NULL,
    [proceeds_claimed_at]      DATETIME2(3)         NULL,
    [version]                  INT              NOT NULL CONSTRAINT [DF_market_listing_version] DEFAULT (1),
    [created_at]               DATETIME2(3)     NOT NULL,
    [updated_at]               DATETIME2(3)     NOT NULL,
    [created_by]               UNIQUEIDENTIFIER NOT NULL,
    [updated_by]               UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]               BIT              NOT NULL CONSTRAINT [DF_market_listing_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_market_listing] PRIMARY KEY CLUSTERED ([listing_id]),
    CONSTRAINT [FK_market_listing_seller_account] FOREIGN KEY ([seller_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_listing_buyer_account] FOREIGN KEY ([buyer_account_id])
        REFERENCES [dbo].[account] ([uuid]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_listing_source_inventory_entry] FOREIGN KEY ([source_inventory_entry_id])
        REFERENCES [dbo].[inventory_entry] ([inventory_entry_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_listing_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_market_listing_remaining_quantity] CHECK ([remaining_quantity] >= 0 AND [remaining_quantity] <= [quantity]),
    CONSTRAINT [CK_market_listing_price] CHECK ([unit_price] >= 1 AND [total_price] = [unit_price] * [quantity] AND [price_floor] >= 0),
    CONSTRAINT [CK_market_listing_confidence] CHECK ([price_confidence] IN (N'HIGH', N'MEDIUM', N'LOW')),
    CONSTRAINT [CK_market_listing_status] CHECK ([status] IN (N'ACTIVE', N'SOLD', N'CANCELED', N'EXPIRED', N'SUSPENDED')),
    CONSTRAINT [CK_market_listing_version] CHECK ([version] >= 1),
    CONSTRAINT [CK_market_listing_valuation_json] CHECK ([valuation_snapshot_json] IS NULL OR ISJSON([valuation_snapshot_json]) = 1),
    CONSTRAINT [CK_market_listing_proceeds_claim_amount] CHECK ([proceeds_claim_amount] IS NULL OR [proceeds_claim_amount] >= 1),
    CONSTRAINT [CK_market_listing_proceeds_claim_entries_json] CHECK ([proceeds_claim_affected_entry_ids_json] IS NULL OR ISJSON([proceeds_claim_affected_entry_ids_json]) = 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_status_listed_at]
    ON [dbo].[market_listing] ([status], [listed_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_seller_status]
    ON [dbo].[market_listing] ([seller_account_id], [status]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_item_status_price]
    ON [dbo].[market_listing] ([item_category], [item_id], [status], [unit_price]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_instance_active_status]
    ON [dbo].[market_listing] ([instance_type], [instance_id], [is_deleted], [status]);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_is_deleted]
    ON [dbo].[market_listing] ([is_deleted]);
GO
```
