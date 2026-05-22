# dbo.equipment_instance_enchant テーブル定義

装備個体に付与されたエンチャント情報を管理するテーブルです。  
`equipment[].enchant.pools[].entries[]` から抽選され、実際に付与されたステータス値を保持します。

---

## テーブル情報

| 項目      | 値                                              |
|:--------|:-----------------------------------------------|
| データベース名 | `AstralRecord`                                 |
| スキーマ名   | `dbo`                                          |
| テーブル名   | `equipment_instance_enchant`                   |
| 完全修飾名   | `dbo.equipment_instance_enchant`               |
| 主キー     | `enchant_id`                                   |
| 外部キー参照先 | `dbo.equipment_instance.equipment_instance_id` |

---

## カラム定義

| カラム名                    | データ型               | PK | NotNull | デフォルト値 | 説明                       |
|:------------------------|:-------------------|:--:|:-------:|:------:|:-------------------------|
| `enchant_id`            | `UNIQUEIDENTIFIER` | ○  |    ○    |        | エンチャントレコードID             |
| `equipment_instance_id` | `UNIQUEIDENTIFIER` |    |    ○    |        | 対象装備個体ID（FK）             |
| `slot_index`            | `INT`              |    |    ○    |        | エンチャントスロット番号（0始まり）       |
| `pool_index`            | `INT`              |    |    ○    |        | 付与元エンチャントプール番号（0始まり）   |
| `status`                | `NVARCHAR(50)`     |    |    ○    |        | 付与されたステータス（`StatusType`） |
| `type`                  | `NVARCHAR(20)`     |    |    ○    |        | 補正方式（`FLAT` / `SCALAR`）  |
| `value`                 | `DECIMAL(18, 4)`   |    |    ○    |        | 実際に付与された数値（範囲から決定された後の値） |
| `created_at`            | `DATETIME2(3)`     |    |    ○    |        | レコード作成日時                 |
| `updated_at`            | `DATETIME2(3)`     |    |    ○    |        | レコード最終更新日時               |
| `created_by`            | `UNIQUEIDENTIFIER` |    |    ○    |        | 作成者の UUID                |
| `updated_by`            | `UNIQUEIDENTIFIER` |    |    ○    |        | 最終更新者の UUID              |

---

## 制約定義

### 主キー制約

| 制約名                             | カラム          | 種別 |
|:--------------------------------|:-------------|:---|
| `PK_equipment_instance_enchant` | `enchant_id` | PK |

### 外部キー制約

| 制約名                                                | カラム                     | 参照先                                             | ON DELETE | ON UPDATE |
|:---------------------------------------------------|:------------------------|:------------------------------------------------|:----------|:----------|
| `FK_equipment_instance_enchant_equipment_instance` | `equipment_instance_id` | `dbo.equipment_instance(equipment_instance_id)` | CASCADE   | NO ACTION |

### UNIQUE 制約

| 制約名                                        | カラム                                   | 説明                |
|:-------------------------------------------|:--------------------------------------|:------------------|
| `UQ_equipment_instance_enchant_slot_index` | `equipment_instance_id`, `slot_index` | 同一個体でスロット番号の重複を防ぐ |
| `UQ_equipment_instance_enchant_pool_index` | `equipment_instance_id`, `pool_index` | 同一個体で同一プールの多重付与を防ぐ |

---

## インデックス定義

| インデックス名                                               | カラム                     | 種別             | 用途            |
|:------------------------------------------------------|:------------------------|:---------------|:--------------|
| `PK_equipment_instance_enchant`                       | `enchant_id`            | CLUSTERED（主キー） | 主キー検索         |
| `IX_equipment_instance_enchant_equipment_instance_id` | `equipment_instance_id` | NONCLUSTERED   | 個体別エンチャント一覧取得 |

---

## DDL

```sql
CREATE TABLE [dbo].[equipment_instance_enchant] (
    [enchant_id]                  UNIQUEIDENTIFIER  NOT NULL,
    [equipment_instance_id]       UNIQUEIDENTIFIER  NOT NULL,
    [slot_index]                  INT               NOT NULL,
    [pool_index]                  INT               NOT NULL,
    [status]                      NVARCHAR(50)      NOT NULL,
    [type]                        NVARCHAR(20)      NOT NULL,
    [value]                       DECIMAL(18, 4)    NOT NULL,
    [created_at]                  DATETIME2(3)      NOT NULL,
    [updated_at]                  DATETIME2(3)      NOT NULL,
    [created_by]                  UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]                  UNIQUEIDENTIFIER  NOT NULL,

    CONSTRAINT [PK_equipment_instance_enchant] PRIMARY KEY CLUSTERED ([enchant_id]),
    CONSTRAINT [FK_equipment_instance_enchant_equipment_instance] FOREIGN KEY ([equipment_instance_id])
        REFERENCES [dbo].[equipment_instance] ([equipment_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_equipment_instance_enchant_slot_index] UNIQUE ([equipment_instance_id], [slot_index]),
    CONSTRAINT [UQ_equipment_instance_enchant_pool_index] UNIQUE ([equipment_instance_id], [pool_index])
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_enchant_equipment_instance_id]
    ON [dbo].[equipment_instance_enchant] ([equipment_instance_id]);
GO
```

---

## 用途

| 用途         | 説明                            |
|:-----------|:------------------------------|
| エンチャント情報保持 | 個体に付与された具体的なエンチャントステータスを保持する  |
| 上書き制御      | スロット上限到達時にどのプール由来のエンチャントかを識別する |
| ステータス計算    | 装備の最終ステータス算出時に利用する            |

---

## ソースコード参照

| 種別    | パス    |
|:------|:------|
| Table | `TBD` |
