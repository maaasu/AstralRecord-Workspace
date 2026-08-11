# dbo.equipment_instance_enchant テーブル定義

装備個体に付与されたエンチャント情報を管理するテーブルです。  
共通エンチャントマスタから抽選され、実際に付与されたステータス値を保持します。

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
| `enchant_master_id`     | `NVARCHAR(100)`    |    |    ○    |        | 付与元共通エンチャントマスタID       |
| `effect_id`             | `NVARCHAR(100)`    |    |    ○    |        | 候補を一意に表す効果ID             |
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
| `UQ_equipment_instance_enchant_effect_id` | `equipment_instance_id`, `effect_id` | 同一個体で同一効果の多重付与を防ぐ |

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
    [enchant_master_id]           NVARCHAR(100)     NOT NULL,
    [effect_id]                   NVARCHAR(100)     NOT NULL,
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
    CONSTRAINT [UQ_equipment_instance_enchant_effect_id] UNIQUE ([equipment_instance_id], [effect_id])
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
| 重複制御       | 同じ `effect_id` のエンチャントが同一装備へ重複しないよう識別する |
| ステータス計算    | 装備の最終ステータス算出時に利用する            |

---

## 旧スキーマからの移行

`20260810_orb_enchant_effect_id.sql` は既存行を削除せず、`enchant_master_id = legacy`、
`effect_id = legacy_{enchant_id}` として付与済みのステータス値を保持します。旧 `pool_index` だけから
共通マスタの安定した `effect_id` 自体は復元できません。runtimeの候補判定では `legacy_` 行に限り
`status` / `type` が一致し、保存済み `value` が候補定義の固定値または範囲内にある場合を意味的重複として除外します。
これにより既存行を保持したまま同義効果の再付与を防ぎます。
新規付与分は通常の `effect_id` で重複制御します。

---

## ソースコード参照

| 種別    | パス    |
|:------|:------|
| Table | `TBD` |
