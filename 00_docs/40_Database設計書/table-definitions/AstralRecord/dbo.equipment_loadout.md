# dbo.equipment_loadout テーブル設計

アカウントごとの装備プリセット本体を保持するテーブルです。
実際に各スロットへ装備している装備個体は `dbo.equipment_loadout_slot` に保持します。

---

## テーブル情報

| 項目 | 値 |
|:---|:---|
| スキーマ名 | `dbo` |
| テーブル名 | `equipment_loadout` |
| 完全修飾名 | `dbo.equipment_loadout` |
| 主キー | `equipment_loadout_id` |
| 親テーブル | `dbo.account.uuid` |

---

## カラム設計

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:---|:---|:---:|:---:|:---:|:---|
| `equipment_loadout_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 装備プリセット ID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 所有アカウント UUID |
| `loadout_profile` | `NVARCHAR(20)` |  | ○ | `GAME` | プロファイル。`GAME` / `ADMIN` |
| `loadout_name` | `NVARCHAR(100)` |  | ○ |  | プリセット名 |
| `sort_order` | `INT` |  | ○ | `0` | 表示順 |
| `is_active` | `BIT` |  | ○ | `0` | 現在有効なプリセットか |
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
| `PK_equipment_loadout` | PK | `equipment_loadout_id` |
| `FK_equipment_loadout_account` | FK | `account_id -> dbo.account(uuid)` |
| `CK_equipment_loadout_profile` | CHECK | `[loadout_profile] IN (N'GAME', N'ADMIN')` |
| `DF_equipment_loadout_profile` | DEFAULT | `loadout_profile = 'GAME'` |
| `DF_equipment_loadout_sort_order` | DEFAULT | `sort_order = 0` |
| `DF_equipment_loadout_is_active` | DEFAULT | `is_active = 0` |
| `DF_equipment_loadout_is_deleted` | DEFAULT | `is_deleted = 0` |

---

## インデックス設計

| インデックス名 | カラム | 種別 | 用途 |
|:---|:---|:---|:---|
| `PK_equipment_loadout` | `equipment_loadout_id` | CLUSTERED | 主キー |
| `IX_equipment_loadout_account_profile` | `account_id`, `loadout_profile` | NONCLUSTERED | アカウント＋プロファイル別取得 |
| `UX_equipment_loadout_active` | `account_id`, `loadout_profile` | UNIQUE FILTERED | 有効プリセットをプロファイルごとに 1 件へ制限 |
| `UX_equipment_loadout_name` | `account_id`, `loadout_profile`, `loadout_name` | UNIQUE FILTERED | 同一プロファイル内のプリセット名重複防止 |
| `IX_equipment_loadout_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[equipment_loadout] (
    [equipment_loadout_id]  UNIQUEIDENTIFIER  NOT NULL,
    [account_id]            UNIQUEIDENTIFIER  NOT NULL,
    [loadout_profile]       NVARCHAR(20)      NOT NULL  CONSTRAINT [DF_equipment_loadout_profile] DEFAULT ('GAME'),
    [loadout_name]          NVARCHAR(100)     NOT NULL,
    [sort_order]            INT               NOT NULL  CONSTRAINT [DF_equipment_loadout_sort_order] DEFAULT (0),
    [is_active]             BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_is_active] DEFAULT (0),
    [metadata_json]         NVARCHAR(MAX)         NULL,
    [created_at]            DATETIME2(3)      NOT NULL,
    [updated_at]            DATETIME2(3)      NOT NULL,
    [created_by]            UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]            BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_equipment_loadout] PRIMARY KEY CLUSTERED ([equipment_loadout_id]),
    CONSTRAINT [FK_equipment_loadout_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_loadout_profile] CHECK ([loadout_profile] IN (N'GAME', N'ADMIN'))
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_account_profile]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_active]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile])
    WHERE [is_active] = 1
      AND [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_name]
    ON [dbo].[equipment_loadout] ([account_id], [loadout_profile], [loadout_name])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_is_deleted]
    ON [dbo].[equipment_loadout] ([is_deleted]);
GO
```

---

## 用途

| 用途 | 説明 |
|:---|:---|
| 複数装備セット | 通常用、ボス用、採掘用などの装備プリセットをアカウントごとに保持します。 |
| プリセット切替 | `is_active = 1` のプリセットを現在有効な装備セットとして扱います。 |
| プロファイル分離 | `GAME` / `ADMIN` の用途ごとに有効プリセットを分けます。 |
