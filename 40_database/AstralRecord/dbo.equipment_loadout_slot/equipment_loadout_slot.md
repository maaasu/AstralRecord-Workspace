# dbo.equipment_loadout_slot テーブル設計

装備プリセット内の各スロットに、どの装備個体を割り当てているかを保持するテーブルです。
装備品の性能や強化状態は `dbo.equipment_instance` 系に保持し、本テーブルはプリセット上の配置だけを管理します。

---

## テーブル情報

| 項目 | 値 |
|:---|:---|
| スキーマ名 | `dbo` |
| テーブル名 | `equipment_loadout_slot` |
| 完全修飾名 | `dbo.equipment_loadout_slot` |
| 主キー | `equipment_loadout_slot_id` |
| 親テーブル | `dbo.equipment_loadout.equipment_loadout_id`, `dbo.equipment_instance.equipment_instance_id` |

---

## カラム設計

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:---|:---|:---:|:---:|:---:|:---|
| `equipment_loadout_slot_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | 装備プリセットスロット ID |
| `equipment_loadout_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 装備プリセット ID |
| `slot_type` | `NVARCHAR(30)` |  | ○ |  | 装備スロット種別。例: `WEAPON`, `HEAD`, `CHEST`, `ACCESSORY` |
| `slot_index` | `INT` |  | ○ | `0` | 同一スロット種別内の番号。アクセサリ複数枠などで使用 |
| `equipment_instance_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 装備個体 ID |
| `created_at` | `DATETIME2(3)` |  | ○ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## 制約設計

| 制約名 | 種別 | 定義 |
|:---|:---|:---|
| `PK_equipment_loadout_slot` | PK | `equipment_loadout_slot_id` |
| `FK_equipment_loadout_slot_loadout` | FK | `equipment_loadout_id -> dbo.equipment_loadout(equipment_loadout_id)` |
| `FK_equipment_loadout_slot_equipment_instance` | FK | `equipment_instance_id -> dbo.equipment_instance(equipment_instance_id)` |
| `CK_equipment_loadout_slot_index` | CHECK | `[slot_index] >= 0` |
| `DF_equipment_loadout_slot_index` | DEFAULT | `slot_index = 0` |
| `DF_equipment_loadout_slot_is_deleted` | DEFAULT | `is_deleted = 0` |

---

## インデックス設計

| インデックス名 | カラム | 種別 | 用途 |
|:---|:---|:---|:---|
| `PK_equipment_loadout_slot` | `equipment_loadout_slot_id` | CLUSTERED | 主キー |
| `IX_equipment_loadout_slot_loadout_id` | `equipment_loadout_id` | NONCLUSTERED | プリセット別取得 |
| `UX_equipment_loadout_slot_position` | `equipment_loadout_id`, `slot_type`, `slot_index` | UNIQUE FILTERED | 同一プリセット内のスロット重複防止 |
| `UX_equipment_loadout_slot_equipment` | `equipment_loadout_id`, `equipment_instance_id` | UNIQUE FILTERED | 同一プリセット内で同一装備個体の二重装備を防止 |
| `IX_equipment_loadout_slot_equipment_instance_id` | `equipment_instance_id` | NONCLUSTERED | 装備個体からの参照確認 |
| `IX_equipment_loadout_slot_is_deleted` | `is_deleted` | NONCLUSTERED | 論理削除フィルタ |

---

## DDL

```sql
CREATE TABLE [dbo].[equipment_loadout_slot] (
    [equipment_loadout_slot_id]  UNIQUEIDENTIFIER  NOT NULL,
    [equipment_loadout_id]       UNIQUEIDENTIFIER  NOT NULL,
    [slot_type]                  NVARCHAR(30)      NOT NULL,
    [slot_index]                 INT               NOT NULL  CONSTRAINT [DF_equipment_loadout_slot_index] DEFAULT (0),
    [equipment_instance_id]      UNIQUEIDENTIFIER  NOT NULL,
    [created_at]                 DATETIME2(3)      NOT NULL,
    [updated_at]                 DATETIME2(3)      NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]                 BIT               NOT NULL  CONSTRAINT [DF_equipment_loadout_slot_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_equipment_loadout_slot] PRIMARY KEY CLUSTERED ([equipment_loadout_slot_id]),
    CONSTRAINT [FK_equipment_loadout_slot_loadout] FOREIGN KEY ([equipment_loadout_id])
        REFERENCES [dbo].[equipment_loadout] ([equipment_loadout_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [FK_equipment_loadout_slot_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_loadout_slot_index] CHECK ([slot_index] >= 0)
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_loadout_id]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id]);
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_slot_position]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id], [slot_type], [slot_index])
    WHERE [is_deleted] = 0;
GO

CREATE UNIQUE NONCLUSTERED INDEX [UX_equipment_loadout_slot_equipment]
    ON [dbo].[equipment_loadout_slot] ([equipment_loadout_id], [equipment_instance_id])
    WHERE [is_deleted] = 0;
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_equipment_instance_id]
    ON [dbo].[equipment_loadout_slot] ([equipment_instance_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_loadout_slot_is_deleted]
    ON [dbo].[equipment_loadout_slot] ([is_deleted]);
GO
```

---

## 用途

| 用途 | 説明 |
|:---|:---|
| 装備スロット割当 | `slot_type` と `slot_index` でプリセット内の装備位置を表します。 |
| アクセサリ複数枠 | `slot_type = 'ACCESSORY'`, `slot_index = 0, 1, 2...` のように扱います。 |
| プリセット共有 | 同じ `equipment_instance_id` は複数プリセットへ登録できます。同一プリセット内では重複登録できません。 |

