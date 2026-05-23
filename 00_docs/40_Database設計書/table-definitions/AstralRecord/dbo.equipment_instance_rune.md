# dbo.equipment_instance_rune テーブル定義

装備個体に装着されたルーン情報を管理するテーブルです。  
装着中のルーン itemId と、どのスロットに入っているかを保持します。

---

## テーブル情報

| 項目      | 値                                            |
|:--------|:---------------------------------------------|
| データベース名 | AstralRecord                                 |
| スキーマ名   | dbo                                          |
| テーブル名   | equipment_instance_rune                      |
| 完全修飾名   | dbo.equipment_instance_rune                  |
| 主キー     | rune_id                                      |
| 外部キー参照先 | dbo.equipment_instance.equipment_instance_id |

---

## カラム定義

| カラム名                  | データ型             | PK | NotNull | 説明             |
|:----------------------|:-----------------|:--:|:-------:|:---------------|
| rune_id               | UNIQUEIDENTIFIER | ○  |    ○    | ルーン装着レコード ID   |
| equipment_instance_id | UNIQUEIDENTIFIER |    |    ○    | 対象装備個体 ID      |
| slot_index            | INT              |    |    ○    | ルーンスロット番号      |
| rune_instance_id      | UNIQUEIDENTIFIER |    |         | 装着したルーン個体 ID   |
| item_id               | NVARCHAR(100)    |    |    ○    | 装着したルーン itemId |
| created_at            | DATETIME2(3)     |    |    ○    | 作成日時           |
| updated_at            | DATETIME2(3)     |    |    ○    | 更新日時           |
| created_by            | UNIQUEIDENTIFIER |    |    ○    | 作成者 UUID       |
| updated_by            | UNIQUEIDENTIFIER |    |    ○    | 更新者 UUID       |

---

## 制約定義

### 主キー制約

| 制約名                        | カラム     | 種別 |
|:---------------------------|:--------|:---|
| PK_equipment_instance_rune | rune_id | PK |

### 外部キー制約

| 制約名                                           | カラム                   | 参照先                                           | ON DELETE | ON UPDATE |
|:----------------------------------------------|:----------------------|:----------------------------------------------|:----------|:----------|
| FK_equipment_instance_rune_equipment_instance | equipment_instance_id | dbo.equipment_instance(equipment_instance_id) | CASCADE   | NO ACTION |

### UNIQUE 制約

| 制約名                                   | カラム                               | 説明             |
|:--------------------------------------|:----------------------------------|:---------------|
| UQ_equipment_instance_rune_slot_index | equipment_instance_id, slot_index | 同一個体でスロット重複を防ぐ |

---

## DDL

```sql
CREATE TABLE [dbo].[equipment_instance_rune] (
    [rune_id]                UNIQUEIDENTIFIER NOT NULL,
    [equipment_instance_id]  UNIQUEIDENTIFIER NOT NULL,
    [slot_index]             INT              NOT NULL,
    [rune_instance_id]       UNIQUEIDENTIFIER     NULL,
    [item_id]                NVARCHAR(100)    NOT NULL,
    [created_at]             DATETIME2(3)     NOT NULL,
    [updated_at]             DATETIME2(3)     NOT NULL,
    [created_by]             UNIQUEIDENTIFIER NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER NOT NULL,

    CONSTRAINT [PK_equipment_instance_rune] PRIMARY KEY CLUSTERED ([rune_id]),
    CONSTRAINT [FK_equipment_instance_rune_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_equipment_instance_rune_slot_index] UNIQUE ([equipment_instance_id], [slot_index])
);
GO
```

---

## 用途

| 用途        | 説明                |
|:----------|:------------------|
| ルーン装着状態保持 | 個体ごとの現在装着ルーンを保持する |
| スロット管理    | どのスロットが使用中かを判定する  |
