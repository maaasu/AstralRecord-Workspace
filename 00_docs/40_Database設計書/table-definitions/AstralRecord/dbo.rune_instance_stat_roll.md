# dbo.rune_instance_stat_roll テーブル定義

ルーン生成時に確定したランダムステータス値を保持するテーブルです。  
固定値ではなく範囲指定された rune.stats[].value から、個体ごとに決まった実値を保存します。

---

## カラム定義

| カラム名             | データ型             | PK | NotNull | 説明             |
|:-----------------|:-----------------|:--:|:-------:|:---------------|
| stat_roll_id     | UNIQUEIDENTIFIER | ○  |    ○    | ステータス乱数レコード ID |
| rune_instance_id | UNIQUEIDENTIFIER |    |    ○    | 対象ルーン個体 ID     |
| status           | NVARCHAR(50)     |    |    ○    | 対象ステータス        |
| type             | NVARCHAR(20)     |    |    ○    | 補正方式           |
| random_value     | NVARCHAR(20)     |    |    ○    | 生成時に確定した実値     |
| sort_order       | INT              |    |    ○    | 同一ルーン内の順序      |
| created_at       | DATETIME2(3)     |    |    ○    | 作成日時           |
| updated_at       | DATETIME2(3)     |    |    ○    | 更新日時           |
| created_by       | UNIQUEIDENTIFIER |    |    ○    | 作成者 UUID       |
| updated_by       | UNIQUEIDENTIFIER |    |    ○    | 更新者 UUID       |
| is_deleted       | BIT              |    |    ○    | 論理削除フラグ        |

---

## DDL

```sql
CREATE TABLE [dbo].[rune_instance_stat_roll] (
    [stat_roll_id]       UNIQUEIDENTIFIER NOT NULL,
    [rune_instance_id]   UNIQUEIDENTIFIER NOT NULL,
    [status]             NVARCHAR(50)     NOT NULL,
    [type]               NVARCHAR(20)     NOT NULL,
    [random_value]       NVARCHAR(20)     NOT NULL,
    [sort_order]         INT              NOT NULL CONSTRAINT [DF_rune_instance_stat_roll_sort_order] DEFAULT (0),
    [created_at]         DATETIME2(3)     NOT NULL,
    [updated_at]         DATETIME2(3)     NOT NULL,
    [created_by]         UNIQUEIDENTIFIER NOT NULL,
    [updated_by]         UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]         BIT              NOT NULL CONSTRAINT [DF_rune_instance_stat_roll_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_rune_instance_stat_roll] PRIMARY KEY CLUSTERED ([stat_roll_id]),
    CONSTRAINT [FK_rune_instance_stat_roll_rune_instance] FOREIGN KEY ([rune_instance_id])
        REFERENCES [dbo].[rune_instance] ([rune_instance_id])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UQ_rune_instance_stat_roll_instance_status] UNIQUE ([rune_instance_id], [status], [sort_order])
);
GO
```
