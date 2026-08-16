# dbo.market_account_state テーブル定義

マーケット利用状態と出品枠 Tier をアカウント単位で保持するテーブルです。
成立取引数を元に最大出品枠数を算出し、Plugin/Web 共通の制限判定に使用します。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `AstralRecord` |
| スキーマ名 | `dbo` |
| テーブル名 | `market_account_state` |
| 完全修飾名 | `dbo.market_account_state` |
| 主キー | `account_id` |
| 外部キー参照先 | `dbo.account.uuid` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `account_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | アカウント UUID |
| `completed_trade_count` | `INT` |  | ○ | `0` | Tier 算出対象の累計成立取引数 |
| `tier` | `NVARCHAR(10)` |  | ○ | `N'T0'` | `T0` / `T1` / `T2` / `T3` / `T4` |
| `max_active_listing_count` | `INT` |  | ○ | `3` | 互換名の最大出品枠数。API response では `maxListingSlotCount` として返す |
| `suspended_until` | `DATETIME2(3)` |  |  |  | マーケット利用停止期限 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## 制約定義

| 制約名 | 条件 | 説明 |
|:--|:--|:--|
| `PK_market_account_state` | `account_id` | 主キー |
| `FK_market_account_state_account` | `account_id` → `dbo.account(uuid)` | アカウント参照 |
| `CK_market_account_state_trade_count` | `[completed_trade_count] >= 0` | 成立取引数は 0 以上 |
| `CK_market_account_state_tier` | `[tier] IN (N'T0', N'T1', N'T2', N'T3', N'T4')` | Tier 値制限 |
| `CK_market_account_state_limit` | `[max_active_listing_count] >= 0` | 出品上限は 0 以上 |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_market_account_state` | `account_id` | CLUSTERED | 主キー検索 |
| `IX_market_account_state_tier` | `tier` | NONCLUSTERED | Tier 別調査 |
| `IX_market_account_state_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[market_account_state] (
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [completed_trade_count]      INT              NOT NULL CONSTRAINT [DF_market_account_state_completed_trade_count] DEFAULT (0),
    [tier]                       NVARCHAR(10)     NOT NULL CONSTRAINT [DF_market_account_state_tier] DEFAULT (N'T0'),
    [max_active_listing_count]   INT              NOT NULL CONSTRAINT [DF_market_account_state_max_active_listing_count] DEFAULT (3),
    [suspended_until]            DATETIME2(3)         NULL,
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_market_account_state_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_market_account_state] PRIMARY KEY CLUSTERED ([account_id]),
    CONSTRAINT [FK_market_account_state_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_market_account_state_trade_count] CHECK ([completed_trade_count] >= 0),
    CONSTRAINT [CK_market_account_state_tier] CHECK ([tier] IN (N'T0', N'T1', N'T2', N'T3', N'T4')),
    CONSTRAINT [CK_market_account_state_limit] CHECK ([max_active_listing_count] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_market_account_state_tier]
    ON [dbo].[market_account_state] ([tier]);
GO

CREATE NONCLUSTERED INDEX [IX_market_account_state_is_deleted]
    ON [dbo].[market_account_state] ([is_deleted]);
GO
```

## 出品枠集計

物理列 `max_active_listing_count` の名称は既存互換のため変更しない。新規出品時は `market_listing` の `ACTIVE` / `SUSPENDED` / 売上未受取の `SOLD` を使用済み枠として集計し、この上限と比較する。売上受取済み・取り下げ済み出品は論理削除され、枠を解放する。
