# dbo.equipment_instance テーブル定義

装備の生成済みインスタンス（個体）を管理するテーブルです。  
YAMLマスタは保存せず、`item_id` を参照キーとして個体の動的状態のみを保持します。

---

## テーブル情報

| 項目      | 値                        |
|:--------|:-------------------------|
| データベース名 | `AstralRecord`           |
| スキーマ名   | `dbo`                    |
| テーブル名   | `equipment_instance`     |
| 完全修飾名   | `dbo.equipment_instance` |
| 主キー     | `equipment_instance_id`  |
| 外部キー参照先 | `dbo.account.uuid`       |

---

## カラム定義

| カラム名                              | データ型               | PK | NotNull | デフォルト値 | 説明                                      |
|:----------------------------------|:-------------------|:--:|:-------:|:------:|:----------------------------------------|
| `equipment_instance_id`           | `UNIQUEIDENTIFIER` | ○  |    ○    |        | 装備個体ID                                  |
| `account_id`                      | `UNIQUEIDENTIFIER` |    |    ○    |        | 所有アカウント UUID（FK → `dbo.account.uuid`）   |
| `item_id`                         | `NVARCHAR(100)`    |    |    ○    |        | 元YAMLの装備ID                              |
| `enhance_level`                   | `INT`              |    |    ○    |  `0`   | 現在の強化レベル                                |
| `rune_max_slots`                  | `INT`              |    |    ○    |  `0`   | この個体に生成されたルーン最大スロット数                    |
| `transcendence_rank`              | `INT`              |    |    ○    |  `0`   | 現在の状態変化ランク                              |
| `transcendence_name`              | `NVARCHAR(100)`    |    |         |        | 状態変化によって上書きされた名称（NULL時は変更なし）            |
| `transcendence_enhance_max_level` | `INT`              |    |         |        | 状態変化によって上書きされた強化最大レベル（NULL時は変更なし）       |
| `transcendence_enchant_max_slots` | `INT`              |    |         |        | 状態変化によって上書きされたエンチャント最大スロット数（NULL時は変更なし） |
| `durability_max`                  | `INT`              |    |         |        | 耐久上限（YAMLで durability.max がある場合のみ保持）    |
| `durability_value`                | `INT`              |    |         |        | 現在耐久値（耐久管理対象のときのみ保持）                    |
| `created_at`                      | `DATETIME2(3)`     |    |    ○    |        | レコード作成日時                                |
| `updated_at`                      | `DATETIME2(3)`     |    |    ○    |        | レコード最終更新日時                              |
| `created_by`                      | `UNIQUEIDENTIFIER` |    |    ○    |        | 作成者の UUID                               |
| `updated_by`                      | `UNIQUEIDENTIFIER` |    |    ○    |        | 最終更新者の UUID                             |
| `is_deleted`                      | `BIT`              |    |    ○    |  `0`   | 論理削除フラグ                                 |

---

## 制約定義

### 主キー制約

| 制約名                     | カラム                     | 種別 |
|:------------------------|:------------------------|:---|
| `PK_equipment_instance` | `equipment_instance_id` | PK |

### 外部キー制約

| 制約名                             | カラム          | 参照先                 | ON DELETE | ON UPDATE |
|:--------------------------------|:-------------|:--------------------|:----------|:----------|
| `FK_equipment_instance_account` | `account_id` | `dbo.account(uuid)` | NO ACTION | NO ACTION |

### CHECK 制約

| 制約名                                                     | 条件                                                                                                                               | 説明               |
|:--------------------------------------------------------|:---------------------------------------------------------------------------------------------------------------------------------|:-----------------|
| `CK_equipment_instance_enhance_level`                   | `[enhance_level] >= 0`                                                                                                           | 強化レベルは 0 以上      |
| `CK_equipment_instance_rune_max_slots`                  | `[rune_max_slots] >= 0`                                                                                                          | ルーンスロットは 0 以上    |
| `CK_equipment_instance_transcendence_rank`              | `[transcendence_rank] >= 0`                                                                                                      | 状態変化ランクは 0 以上    |
| `CK_equipment_instance_transcendence_enhance_max_level` | `[transcendence_enhance_max_level] IS NULL OR [transcendence_enhance_max_level] >= 0`                                            | 上書き強化レベルは 0 以上   |
| `CK_equipment_instance_transcendence_enchant_max_slots` | `[transcendence_enchant_max_slots] IS NULL OR [transcendence_enchant_max_slots] >= 0`                                            | 上書きエンチャント枠は 0 以上 |
| `CK_equipment_instance_durability_pair`                 | `([durability_max] IS NULL AND [durability_value] IS NULL) OR ([durability_max] IS NOT NULL AND [durability_value] IS NOT NULL)` | 耐久値はペアで保持        |
| `CK_equipment_instance_durability_range`                | `[durability_max] IS NULL OR ([durability_max] > 0 AND [durability_value] BETWEEN 0 AND [durability_max])`                       | 現在耐久は 0〜上限       |

### デフォルト制約

| 制約名                                        | カラム                  | 値   |
|:-------------------------------------------|:---------------------|:----|
| `DF_equipment_instance_enhance_level`      | `enhance_level`      | `0` |
| `DF_equipment_instance_rune_max_slots`     | `rune_max_slots`     | `0` |
| `DF_equipment_instance_transcendence_rank` | `transcendence_rank` | `0` |
| `DF_equipment_instance_is_deleted`         | `is_deleted`         | `0` |

---

## インデックス定義

| インデックス名                            | カラム                     | 種別             | 用途        |
|:-----------------------------------|:------------------------|:---------------|:----------|
| `PK_equipment_instance`            | `equipment_instance_id` | CLUSTERED（主キー） | 主キー検索     |
| `IX_equipment_instance_account_id` | `account_id`            | NONCLUSTERED   | 所有装備一覧取得  |
| `IX_equipment_instance_item_id`    | `item_id`               | NONCLUSTERED   | itemId別検索 |
| `IX_equipment_instance_is_deleted` | `is_deleted`            | NONCLUSTERED   | 論理削除フィルタ  |

---

## DDL

```sql
CREATE TABLE [dbo].[equipment_instance] (
    [equipment_instance_id]  UNIQUEIDENTIFIER  NOT NULL,
    [account_id]             UNIQUEIDENTIFIER  NOT NULL,
    [item_id]                NVARCHAR(100)     NOT NULL,
    [enhance_level]          INT               NOT NULL  CONSTRAINT [DF_equipment_instance_enhance_level]      DEFAULT (0),
    [rune_max_slots]         INT               NOT NULL  CONSTRAINT [DF_equipment_instance_rune_max_slots]     DEFAULT (0),
    [transcendence_rank]     INT               NOT NULL  CONSTRAINT [DF_equipment_instance_transcendence_rank] DEFAULT (0),
    [transcendence_name]              NVARCHAR(100)        NULL,
    [transcendence_enhance_max_level] INT                  NULL,
    [transcendence_enchant_max_slots] INT                  NULL,
    [durability_max]         INT                   NULL,
    [durability_value]       INT                   NULL,
    [created_at]             DATETIME2(3)      NOT NULL,
    [updated_at]             DATETIME2(3)      NOT NULL,
    [created_by]             UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]             UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]             BIT               NOT NULL  CONSTRAINT [DF_equipment_instance_is_deleted]         DEFAULT (0),

    CONSTRAINT [PK_equipment_instance] PRIMARY KEY CLUSTERED ([equipment_instance_id]),
    CONSTRAINT [FK_equipment_instance_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE NO ACTION
        ON UPDATE NO ACTION,
    CONSTRAINT [CK_equipment_instance_enhance_level] CHECK ([enhance_level] >= 0),
    CONSTRAINT [CK_equipment_instance_rune_max_slots] CHECK ([rune_max_slots] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_rank] CHECK ([transcendence_rank] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_enhance_max_level] CHECK ([transcendence_enhance_max_level] IS NULL OR [transcendence_enhance_max_level] >= 0),
    CONSTRAINT [CK_equipment_instance_transcendence_enchant_max_slots] CHECK ([transcendence_enchant_max_slots] IS NULL OR [transcendence_enchant_max_slots] >= 0),
    CONSTRAINT [CK_equipment_instance_durability_pair] CHECK (
        ([durability_max] IS NULL AND [durability_value] IS NULL)
        OR ([durability_max] IS NOT NULL AND [durability_value] IS NOT NULL)
    ),
    CONSTRAINT [CK_equipment_instance_durability_range] CHECK (
        [durability_max] IS NULL
        OR ([durability_max] > 0 AND [durability_value] BETWEEN 0 AND [durability_max])
    )
);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_account_id]
    ON [dbo].[equipment_instance] ([account_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_item_id]
    ON [dbo].[equipment_instance] ([item_id]);
GO

CREATE NONCLUSTERED INDEX [IX_equipment_instance_is_deleted]
    ON [dbo].[equipment_instance] ([is_deleted]);
GO
```

---

## 用途

| 用途     | 説明                                      |
|:-------|:----------------------------------------|
| 装備個体管理 | 生成済み装備を itemId 単位ではなく個体単位で保持する          |
| 動的状態管理 | 強化レベル・ルーンスロット・状態変化ランクおよび上書き情報・現在耐久を管理する |
| 論理削除   | `is_deleted = 1` で物理削除せず無効化する           |

---

## ソースコード参照

| 種別    | パス    |
|:------|:------|
| Table | `TBD` |

