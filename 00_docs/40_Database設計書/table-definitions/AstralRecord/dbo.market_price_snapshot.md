# dbo.market_price_snapshot テーブル定義

マーケット相場見積のスナップショットを保持するテーブルです。
出品時・監査時の価格判定根拠を残し、Web 表示や運用調査に利用します。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `AstralRecord` |
| スキーマ名 | `dbo` |
| テーブル名 | `market_price_snapshot` |
| 完全修飾名 | `dbo.market_price_snapshot` |
| 主キー | `snapshot_id` |
| 外部キー参照先 | `dbo.market_listing.listing_id`, `dbo.market_transaction.transaction_id` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `snapshot_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | スナップショット ID |
| `listing_id` | `UNIQUEIDENTIFIER` |  |  |  | 関連出品 ID |
| `transaction_id` | `UNIQUEIDENTIFIER` |  |  |  | 関連取引 ID |
| `item_category` | `NVARCHAR(50)` |  | ○ |  | item カテゴリ |
| `item_id` | `NVARCHAR(100)` |  | ○ |  | item マスタ ID |
| `instance_type` | `NVARCHAR(30)` |  |  |  | 個体種別 |
| `instance_id` | `UNIQUEIDENTIFIER` |  |  |  | 個体 UUID |
| `valuation_signature` | `NVARCHAR(300)` |  |  |  | 個体評価シグネチャ |
| `reference_scope` | `NVARCHAR(50)` |  | ○ |  | 相場参照範囲 |
| `sample_count` | `INT` |  | ○ |  | 参照件数 |
| `confidence` | `NVARCHAR(20)` |  | ○ |  | 信頼度 |
| `sell_price` | `BIGINT` |  | ○ |  | 店売り単価 |
| `suggested_unit_price` | `BIGINT` |  | ○ |  | 推奨単価 |
| `reference_unit_price` | `BIGINT` |  |  |  | 約定履歴から算出した参照単価 |
| `allowed_min_unit_price` | `BIGINT` |  | ○ |  | 許容下限単価 |
| `allowed_max_unit_price` | `BIGINT` |  | ○ |  | 許容上限単価 |
| `judgement` | `NVARCHAR(50)` |  | ○ |  | 判定結果 |
| `roll_quality_score` | `DECIMAL(8,4)` |  |  |  | ランダムステータス品質スコア |
| `roll_quality_bucket` | `NVARCHAR(10)` |  |  |  | `D` / `C` / `B` / `A` / `S` |
| `evaluated_at` | `DATETIME2(3)` |  | ○ |  | 評価日時 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |

---

## 制約定義

| 制約名 | 条件 | 説明 |
|:--|:--|:--|
| `PK_market_price_snapshot` | `snapshot_id` | 主キー |
| `FK_market_price_snapshot_listing` | `listing_id` → `dbo.market_listing(listing_id)` | 出品参照 |
| `FK_market_price_snapshot_transaction` | `transaction_id` → `dbo.market_transaction(transaction_id)` | 取引参照 |
| `CK_market_price_snapshot_sample_count` | `[sample_count] >= 0` | 参照件数 |
| `CK_market_price_snapshot_confidence` | `[confidence] IN (N'HIGH', N'MEDIUM', N'LOW')` | 信頼度 |
| `CK_market_price_snapshot_price` | 価格カラムが 0 以上 | 価格 |
| `CK_market_price_snapshot_roll_score` | `[roll_quality_score] IS NULL OR [roll_quality_score] BETWEEN 0 AND 100` | 品質スコア |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_market_price_snapshot` | `snapshot_id` | CLUSTERED | 主キー検索 |
| `IX_market_price_snapshot_listing` | `listing_id` | NONCLUSTERED | 出品別参照 |
| `IX_market_price_snapshot_item_evaluated` | `item_category`, `item_id`, `evaluated_at` | NONCLUSTERED | 商品別調査 |
| `IX_market_price_snapshot_signature_evaluated` | `valuation_signature`, `evaluated_at` | NONCLUSTERED | 個体条件別調査 |

---

## DDL

```sql
CREATE TABLE [dbo].[market_price_snapshot] (
    [snapshot_id]            UNIQUEIDENTIFIER NOT NULL,
    [listing_id]             UNIQUEIDENTIFIER     NULL,
    [transaction_id]         UNIQUEIDENTIFIER     NULL,
    [item_category]          NVARCHAR(50)     NOT NULL,
    [item_id]                NVARCHAR(100)    NOT NULL,
    [instance_type]          NVARCHAR(30)         NULL,
    [instance_id]            UNIQUEIDENTIFIER     NULL,
    [valuation_signature]    NVARCHAR(300)        NULL,
    [reference_scope]        NVARCHAR(50)     NOT NULL,
    [sample_count]           INT              NOT NULL,
    [confidence]             NVARCHAR(20)     NOT NULL,
    [sell_price]             BIGINT           NOT NULL,
    [suggested_unit_price]   BIGINT           NOT NULL,
    [reference_unit_price]   BIGINT               NULL,
    [allowed_min_unit_price] BIGINT           NOT NULL,
    [allowed_max_unit_price] BIGINT           NOT NULL,
    [judgement]              NVARCHAR(50)     NOT NULL,
    [roll_quality_score]     DECIMAL(8,4)         NULL,
    [roll_quality_bucket]    NVARCHAR(10)         NULL,
    [evaluated_at]           DATETIME2(3)     NOT NULL,
    [created_at]             DATETIME2(3)     NOT NULL,

    CONSTRAINT [PK_market_price_snapshot] PRIMARY KEY CLUSTERED ([snapshot_id]),
    CONSTRAINT [FK_market_price_snapshot_listing] FOREIGN KEY ([listing_id])
        REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_price_snapshot_transaction] FOREIGN KEY ([transaction_id])
        REFERENCES [dbo].[market_transaction] ([transaction_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_price_snapshot_sample_count] CHECK ([sample_count] >= 0),
    CONSTRAINT [CK_market_price_snapshot_confidence] CHECK ([confidence] IN (N'HIGH', N'MEDIUM', N'LOW')),
    CONSTRAINT [CK_market_price_snapshot_price] CHECK (
        [sell_price] >= 0
        AND [suggested_unit_price] >= 0
        AND ([reference_unit_price] IS NULL OR [reference_unit_price] >= 0)
        AND [allowed_min_unit_price] >= 0
        AND [allowed_max_unit_price] >= [allowed_min_unit_price]
    ),
    CONSTRAINT [CK_market_price_snapshot_roll_score] CHECK ([roll_quality_score] IS NULL OR [roll_quality_score] BETWEEN 0 AND 100)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_listing]
    ON [dbo].[market_price_snapshot] ([listing_id]);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_item_evaluated]
    ON [dbo].[market_price_snapshot] ([item_category], [item_id], [evaluated_at]);
GO

CREATE NONCLUSTERED INDEX [IX_market_price_snapshot_signature_evaluated]
    ON [dbo].[market_price_snapshot] ([valuation_signature], [evaluated_at]);
GO
```
