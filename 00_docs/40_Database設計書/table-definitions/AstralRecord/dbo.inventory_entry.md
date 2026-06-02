補足: プロファイル分離（GAME/BUILDER）は親テーブル `dbo.inventory.inventory_profile` で管理します。
各インベントリの中身を保持するテーブルです。
連続アイテム・消耗のようなスタック型アイテムと、装備・ルーン・ペットのようなインスタンス生成型アイテムの両方を 1 テーブルで扱えるようにしています。
どのインベントリ種別に属するかの判定は親テーブル `dbo.inventory.inventory_type` とプラグイン側コードで行います。

---

## テーブル情報

| 項目 | 値 |
|:---|:---|
| スキーマ名 | `dbo` |
| テーブル名 | `inventory_entry` |
| 完全修飾名 | `dbo.inventory_entry` |
| 主キー | `inventory_entry_id` |
| 外部キー | `dbo.inventory.inventory_id` |

---

## カラム設定

| カラム名 | データ型 | PK | NotNull | デフォルト | 説明 |
|:---|:---|:---:|:---:|:---:|:---|
| `inventory_entry_id` | `UNIQUEIDENTIFIER` | ✓ | ✓ |  | インベントリエントリ ID |
| `inventory_id` | `UNIQUEIDENTIFIER` |  | ✓ |  | 所属インベントリ ID |
| `slot_index` | `INT` |  |  |  | スロット番号。スロットレスの場合は `NULL` |
| `item_category` | `NVARCHAR(30)` |  | ✓ |  | アイテムカテゴリ。例: `CONSUMABLE`, `MATERIAL`, `CURRENCY`, `EQUIPMENT`, `RUNE`, `PET` |
| `item_id` | `NVARCHAR(100)` |  |  |  | YAML マスタのアイテム ID。スタック型で使用 |
| `instance_type` | `NVARCHAR(30)` |  |  |  | インスタンス生成種別。例: `EQUIPMENT`, `RUNE`, `PET` |
| `instance_id` | `UNIQUEIDENTIFIER` |  |  |  | インスタンス生成先の ID |
| `quantity` | `BIGINT` |  | ✓ | `1` | 所持数。スタック型は 1 以上、インスタンス生成型は常に 1 |
| `metadata_json` | `NVARCHAR(MAX)` |  |  |  | 補足的な拡張用メタデータ |
| `created_at` | `DATETIME2(3)` |  | ✓ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ✓ |  | レコード更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ✓ | `0` | 論理削除フラグ |

---

## 制約設定

| 制約名 | 種別 | 定義 |
|:---|:---|:---|
| `PK_inventory_entry` | PK | `inventory_entry_id` |
| `FK_inventory_entry_inventory` | FK | `inventory_id -> dbo.inventory(inventory_id)` |
| `CK_inventory_entry_slot_index` | CHECK | `[slot_index] IS NULL OR [slot_index] >= 0` |
| `CK_inventory_entry_quantity` | CHECK | `[quantity] >= 1` |
| `CK_inventory_entry_payload` | CHECK | `([item_id] IS NOT NULL AND [instance_type] IS NULL AND [instance_id] IS NULL) OR ([item_id] IS NULL AND [instance_type] IS NOT NULL AND [instance_id] IS NOT NULL)` |
| `DF_inventory_entry_quantity` | DEFAULT | `quantity = 1` |
| `DF_inventory_entry_is_deleted` | DEFAULT | `is_deleted = 0` |

### 設定根拠

| 項目 | 説明 |
|:---|:---|
| スタック型 | `item_id` と `quantity` を使用。連続インベントリや消耗品向け |
| インスタンス生成型 | `instance_type` と `instance_id` を使用。装備・ルーン・ペット向け |
| FK を絞らない理由 | 将来 `PET` などの新インスタンステーブルを追加しても本テーブルのスキーマ変更を抑えるため |

---

## インデックス設定

| インデックス名 | カラム | 種別 | 用途 |
|:---|:---|:---|:---|
| `PK_inventory_entry` | `inventory_entry_id` | CLUSTERED | 主キー検索 |
| `IX_inventory_entry_inventory_id` | `inventory_id` | NONCLUSTERED | インベントリ単位取得 |
| `UX_inventory_entry_inventory_slot` | `inventory_id`, `slot_index` | UNIQUE FILTERED | スロット重複防止 |
| `UX_inventory_entry_inventory_item` | `inventory_id`, `item_id` | UNIQUE FILTERED | スロットレスなスタック型重複防止 |
| `IX_inventory_entry_instance` | `instance_type`, `instance_id` | NONCLUSTERED | インスタンス紐付き |
| `IX_inventory_entry_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[inventory_entry] (
    [inventory_entry_id]    UNIQUEIDENTIFIER  NOT NULL,
    [inventory_id]          UNIQUEIDENTIFIER  NOT NULL,
    [slot_index]            INT                   NULL,
    [item_category]         NVARCHAR(30)      NOT NULL,
    [item_id]               NVARCHAR(100)         NULL,
    [instance_type]         NVARCHAR(30)          NULL,
    [instance_id]           UNIQUEIDENTIFIER      NULL,
    [quantity]              BIGINT            NOT NULL  CONSTRAINT [DF_inventory_entry_quantity] DEFAULT (1),
    [metadata_json]         NVARCHAR(MAX)         NULL,
    [created_at]            DATETIME2(3)      NOT NULL,
    [updated_at]            DATETIME2(3)      NOT NULL,
    [created_by]            UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]            UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]            BIT               NOT NULL  CONSTRAINT [DF_inventory_entry_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_inventory_entry] PRIMARY KEY CLUSTERED ([inventory_entry_id]),
    CONSTRAINT [FK_inventory_entry_inventory] FOREIGN KEY ([inventory_id])
        REFERENCES [dbo].[inventory] ([inventory_id])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_inventory_entry_slot_index] CHECK ([slot_index] IS NULL OR [slot_index] >= 0),
    CONSTRAINT [CK_inventory_entry_quantity] CHECK ([quantity] >= 1),
    CONSTRAINT [CK_inventory_entry_payload] CHECK (
        ([item_id] IS NOT NULL AND [instance_type] IS NULL AND [instance_id] IS NULL)
        OR ([item_id] IS NULL AND [instance_type] IS NOT NULL AND [instance_id] IS NOT NULL)
    )
);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_inventory_id]
    ON [dbo].[inventory_entry] ([inventory_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_inventory_entry_inventory_slot]
    ON [dbo].[inventory_entry] ([inventory_id], [slot_index])
    WHERE [slot_index] IS NOT NULL
      AND [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_inventory_entry_inventory_item]
    ON [dbo].[inventory_entry] ([inventory_id], [item_id])
    WHERE [slot_index] IS NULL
      AND [item_id] IS NOT NULL
      AND [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_instance]
    ON [dbo].[inventory_entry] ([instance_type], [instance_id]);
GO

CREATE NONCLUSTERED INDEX [IX_inventory_entry_is_deleted]
    ON [dbo].[inventory_entry] ([is_deleted]);
GO
```

---

## 用途

| 用途 | 説明 |
|:---|:---|
| スタックインベントリ | `slot_index` と `item_id` でスタックアイテムを管理 |
| 流動インベントリ | `slot_index = NULL`、`item_id` + `quantity` で無限定な管理 |
| 装備インベントリ | `instance_type = 'EQUIPMENT'` と `instance_id` で装備実体を管理 |
| ルーンインベントリ | `instance_type = 'RUNE'` と `instance_id` でルーン実体を管理 |
| ストレージインベントリ | `STORAGE` 種別で収納アイテムを管理。`slot_index` は収納順を表す連番、`metadata_json.acquiredAt` は収納元 entry の作成日時で獲得順ソートに使用 |
| 装備スロットインベントリ | `instance_type = 'EQUIPMENT'` と `instance_id` で装着中の装備スロット（`EQUIP_SLOT`）を管理。`slot_index` は装備部位（1=メインハンド, 2=頭, 3=胴, 4=脚, 5=足） |
| ホットバーインベントリ | `HOTBAR` 種別で `slot_index` 1〜9 を Bukkit ホットバー 0〜8 に対応させて保存。`metadata_json` は互換用スナップショット |
| アクセサリスロットインベントリ | `instance_type = 'EQUIPMENT'` と `instance_id` でアクセサリスロット（`ACCESSORY_SLOT`）を管理。`slot_index` 1 = オフハンド、2 = 首飾り、3 = 指輪、4 = 耳飾り、5 = 腕輪、6 = ベルト、7 = 護符 |
| 将来のペット追加 | `instance_type = 'PET'` を追加すれば同じ枠組みで扱える |
