# dbo.account_mob_record テーブル設計

アカウント単位で Mob 討伐記録を保持します。冒険記録 GUI の魔物録・厄災録では本テーブルの `defeat_count` と `last_defeated_at` を使用します。

## 基本情報

| 項目 | 内容 |
|:--|:--|
| テーブル名 | `account_mob_record` |
| 完全修飾名 | `dbo.account_mob_record` |
| 主キー | `account_mob_record_id` |
| 外部キー | `account_id -> dbo.account(uuid)` |

## カラム

| カラム | 型 | PK | NOT NULL | 既定値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `account_mob_record_id` | `UNIQUEIDENTIFIER` | ✓ | ✓ |  | 討伐記録 ID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ✓ |  | アカウント ID |
| `mob_id` | `NVARCHAR(100)` |  | ✓ |  | Mob マスタ ID |
| `mob_category` | `NVARCHAR(20)` |  | ✓ |  | `ENEMY` / `BOSS` |
| `defeat_count` | `BIGINT` |  | ✓ | `1` | 討伐数 |
| `first_defeated_at` | `DATETIME2(3)` |  | ✓ |  | 初回討伐日時 |
| `last_defeated_at` | `DATETIME2(3)` |  | ✓ |  | 最新討伐日時 |
| `created_at` | `DATETIME2(3)` |  | ✓ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ✓ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ✓ | `0` | 論理削除フラグ |

## 制約・インデックス

| 名称 | 種別 | 対象 | 説明 |
|:--|:--|:--|:--|
| `PK_account_mob_record` | PK | `account_mob_record_id` | 主キー |
| `FK_account_mob_record_account` | FK | `account_id -> dbo.account(uuid)` | アカウント参照 |
| `UX_account_mob_record_account_mob` | UNIQUE | `account_id, mob_id` | アカウント内の Mob 記録を一意化 |
| `IX_account_mob_record_account_category_last_defeated` | INDEX | `account_id, mob_category, last_defeated_at` | 図鑑カテゴリ別の最新順取得 |
| `IX_account_mob_record_is_deleted` | INDEX | `is_deleted` | 論理削除フィルタ |
| `CK_account_mob_record_category` | CHECK | `mob_category` | `ENEMY` / `BOSS` のみ許可 |
| `CK_account_mob_record_defeat_count` | CHECK | `defeat_count >= 1` | 討伐数の下限 |

## DDL

```sql
CREATE TABLE [dbo].[account_mob_record] (
    [account_mob_record_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]            UNIQUEIDENTIFIER NOT NULL,
    [mob_id]                NVARCHAR(100)    NOT NULL,
    [mob_category]          NVARCHAR(20)     NOT NULL,
    [defeat_count]          BIGINT           NOT NULL CONSTRAINT [DF_account_mob_record_defeat_count] DEFAULT (1),
    [first_defeated_at]     DATETIME2(3)     NOT NULL,
    [last_defeated_at]      DATETIME2(3)     NOT NULL,
    [created_at]            DATETIME2(3)     NOT NULL,
    [updated_at]            DATETIME2(3)     NOT NULL,
    [created_by]            UNIQUEIDENTIFIER NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]            BIT              NOT NULL CONSTRAINT [DF_account_mob_record_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_mob_record] PRIMARY KEY CLUSTERED ([account_mob_record_id]),
    CONSTRAINT [FK_account_mob_record_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UX_account_mob_record_account_mob] UNIQUE ([account_id], [mob_id]),
    CONSTRAINT [CK_account_mob_record_category] CHECK ([mob_category] IN (N'ENEMY', N'BOSS')),
    CONSTRAINT [CK_account_mob_record_defeat_count] CHECK ([defeat_count] >= 1)
);

CREATE NONCLUSTERED INDEX [IX_account_mob_record_account_category_last_defeated]
    ON [dbo].[account_mob_record] ([account_id], [mob_category], [last_defeated_at] DESC);

CREATE NONCLUSTERED INDEX [IX_account_mob_record_is_deleted]
    ON [dbo].[account_mob_record] ([is_deleted]);
```
