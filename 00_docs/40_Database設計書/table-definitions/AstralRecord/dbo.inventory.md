# dbo.inventory テーブル設計

アカウントごと・用途ごと・プロファイルごとのインベントリ本体を保持するテーブルです。
実際の中身は `dbo.inventory_entry` に保持します。

---

## テーブル情報

| 項目 | 値 |
|:---|:---|
| スキーマ名 | `dbo` |
| テーブル名 | `inventory` |
| 完全修飾名 | `dbo.inventory` |
| 主キー | `inventory_id` |
| 親テーブル | `dbo.account.uuid` |

---

## カラム設計

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:---|:---|:---:|:---:|:---:|:---|
| `inventory_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | インベントリ ID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 所有アカウント UUID |
| `inventory_type` | `NVARCHAR(30)` |  | ○ |  | インベントリ種別コード。例: `NORMAL`, `CURRENCY`, `EQUIPMENT`, `RUNE`, `HOTBAR` |
| `inventory_profile` | `NVARCHAR(20)` |  | ○ | `GAME` | プロファイル。`GAME` / `BUILDER` |
| `slot_capacity` | `INT` |  |  |  | スロット上限。未指定時は `NULL` |
| `is_enabled` | `BIT` |  | ○ | `1` | 利用可否 |
| `metadata_json` | `NVARCHAR(MAX)` |  |  |  | 拡張メタデータ |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## 制約設計

| 制約名 | 種別 | 定義 |
|:---|:---|:---|
| `PK_inventory` | PK | `inventory_id` |
| `FK_inventory_account` | FK | `account_id -> dbo.account(uuid)` |
| `UQ_inventory_account_type_profile` | UNIQUE | `account_id, inventory_type, inventory_profile` |
| `CK_inventory_profile` | CHECK | `[inventory_profile] IN (N'GAME', N'BUILDER')` |
| `CK_inventory_slot_capacity` | CHECK | `[slot_capacity] IS NULL OR [slot_capacity] >= 0` |
| `DF_inventory_profile` | DEFAULT | `inventory_profile = 'GAME'` |
| `DF_inventory_is_enabled` | DEFAULT | `is_enabled = 1` |
| `DF_inventory_is_deleted` | DEFAULT | `is_deleted = 0` |

---

## インデックス設計

| インデックス名 | カラム | 種別 | 用途 |
|:---|:---|:---|:---|
| `PK_inventory` | `inventory_id` | CLUSTERED | 主キー |
| `UQ_inventory_account_type_profile` | `account_id`, `inventory_type`, `inventory_profile` | UNIQUE | アカウント＋種別＋プロファイルの一意性 |
| `IX_inventory_account_id` | `account_id` | NONCLUSTERED | アカウント別取得 |
| `IX_inventory_inventory_type` | `inventory_type` | NONCLUSTERED | 種別別取得 |
| `IX_inventory_inventory_profile` | `inventory_profile` | NONCLUSTERED | プロファイル別取得 |
| `IX_inventory_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[inventory] (
    [inventory_id]       UNIQUEIDENTIFIER  NOT NULL,
    [account_id]         UNIQUEIDENTIFIER  NOT NULL,
    [inventory_type]     NVARCHAR(30)      NOT NULL,
    [inventory_profile]  NVARCHAR(20)      NOT NULL  CONSTRAINT [DF_inventory_profile] DEFAULT ('GAME'),
    [slot_capacity]      INT                   NULL,
    [is_enabled]         BIT               NOT NULL  CONSTRAINT [DF_inventory_is_enabled] DEFAULT (1),
    [metadata_json]      NVARCHAR(MAX)         NULL,
    [created_at]         DATETIME2(3)      NOT NULL,
    [updated_at]         DATETIME2(3)      NOT NULL,
    [created_by]         UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]         BIT               NOT NULL  CONSTRAINT [DF_inventory_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_inventory] PRIMARY KEY CLUSTERED ([inventory_id]),
    CONSTRAINT [FK_inventory_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_inventory_account_type_profile] UNIQUE ([account_id], [inventory_type], [inventory_profile]),
    CONSTRAINT [CK_inventory_profile] CHECK ([inventory_profile] IN (N'GAME', N'BUILDER')),
    CONSTRAINT [CK_inventory_slot_capacity] CHECK ([slot_capacity] IS NULL OR [slot_capacity] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_account_id]
    ON [dbo].[inventory] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_inventory_type]
    ON [dbo].[inventory] ([inventory_type]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_inventory_profile]
    ON [dbo].[inventory] ([inventory_profile]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_is_deleted]
    ON [dbo].[inventory] ([is_deleted]);
GO
```
