# dbo.market_listing_source テーブル定義

マーケット出品を構成する escrow 元の inventory entry と、各 entry から確保した数量を保持する対応表です。
スタック品は複数の BAG / HOTBAR entry を 1 件の出品にまとめられます。個体品は 1 件だけを持ちます。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `AstralRecord` |
| スキーマ名 | `dbo` |
| テーブル名 | `market_listing_source` |
| 完全修飾名 | `dbo.market_listing_source` |
| 主キー | `listing_id`, `inventory_entry_id` |
| 外部キー参照先 | `dbo.market_listing.listing_id`, `dbo.inventory_entry.inventory_entry_id` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `listing_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 出品 ID |
| `inventory_entry_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | escrow 元の inventory entry ID |
| `quantity` | `INT` |  | ○ |  | 出品時に当該 entry から確保した数量 |

---

## 制約定義

| 制約名 | 条件 | 説明 |
|:--|:--|:--|
| `PK_market_listing_source` | `listing_id`, `inventory_entry_id` | 同じ entry を同一出品へ重複登録しない |
| `FK_market_listing_source_listing` | `listing_id` → `dbo.market_listing(listing_id)` | 出品参照 |
| `FK_market_listing_source_inventory_entry` | `inventory_entry_id` → `dbo.inventory_entry(inventory_entry_id)` | escrow 元 entry 参照 |
| `CK_market_listing_source_quantity` | `[quantity] >= 1` | 確保数量は 1 以上 |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_market_listing_source` | `listing_id`, `inventory_entry_id` | CLUSTERED | 出品単位の escrow 元取得 |
| `IX_market_listing_source_inventory_entry` | `inventory_entry_id` | NONCLUSTERED | entry からの出品調査 |

---

## DDL

```sql
CREATE TABLE [dbo].[market_listing_source] (
    [listing_id]         UNIQUEIDENTIFIER NOT NULL,
    [inventory_entry_id] UNIQUEIDENTIFIER NOT NULL,
    [quantity]           INT              NOT NULL,

    CONSTRAINT [PK_market_listing_source] PRIMARY KEY CLUSTERED ([listing_id], [inventory_entry_id]),
    CONSTRAINT [FK_market_listing_source_listing] FOREIGN KEY ([listing_id])
        REFERENCES [dbo].[market_listing] ([listing_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [FK_market_listing_source_inventory_entry] FOREIGN KEY ([inventory_entry_id])
        REFERENCES [dbo].[inventory_entry] ([inventory_entry_id]) ON DELETE NO ACTION ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_listing_source_quantity] CHECK ([quantity] >= 1)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_listing_source_inventory_entry]
    ON [dbo].[market_listing_source] ([inventory_entry_id]);
GO
```
