# dbo.rune_instance テーブル定義

生成済みのルーン個体を管理するテーブルです。  
YAML マスタは保存せず、itemId と個体のメタ情報のみを保持します。

---

## カラム定義

| カラム名             | データ型             | PK | NotNull | 説明             |
|:-----------------|:-----------------|:--:|:-------:|:---------------|
| rune_instance_id | UNIQUEIDENTIFIER | ○  |    ○    | ルーン個体 ID       |
| account_id       | UNIQUEIDENTIFIER |    |    ○    | 所有アカウント UUID   |
| item_id          | NVARCHAR(100)    |    |    ○    | 元 YAML のルーン ID |
| created_at       | DATETIME2(3)     |    |    ○    | 作成日時           |
| updated_at       | DATETIME2(3)     |    |    ○    | 更新日時           |
| created_by       | UNIQUEIDENTIFIER |    |    ○    | 作成者 UUID       |
| updated_by       | UNIQUEIDENTIFIER |    |    ○    | 更新者 UUID       |
| is_deleted       | BIT              |    |    ○    | 論理削除フラグ        |

---

## DDL

```sql
CREATE TABLE [dbo].[rune_instance] (
    [rune_instance_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]        UNIQUEIDENTIFIER NOT NULL,
    [item_id]           NVARCHAR(100)    NOT NULL,
    [created_at]        DATETIME2(3)     NOT NULL,
    [updated_at]        DATETIME2(3)     NOT NULL,
    [created_by]        UNIQUEIDENTIFIER NOT NULL,
    [updated_by]        UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]        BIT              NOT NULL CONSTRAINT [DF_rune_instance_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_rune_instance] PRIMARY KEY CLUSTERED ([rune_instance_id])
);
GO
```
