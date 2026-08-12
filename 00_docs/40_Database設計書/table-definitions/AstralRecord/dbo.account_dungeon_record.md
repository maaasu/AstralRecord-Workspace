# dbo.account_dungeon_record テーブル設計

アカウント単位でダンジョン踏破記録を保持します。カルトグラフのダンジョン外表示では、本テーブルに一度以上記録されたダンジョンだけを表示します。

## 基本情報

| 項目 | 内容 |
|:--|:--|
| テーブル名 | `account_dungeon_record` |
| 完全修飾名 | `dbo.account_dungeon_record` |
| 主キー | `account_dungeon_record_id` |
| 外部キー | `account_id -> dbo.account(uuid)` |

## カラム

| カラム | 型 | PK | NOT NULL | 既定値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `account_dungeon_record_id` | `UNIQUEIDENTIFIER` | ✓ | ✓ |  | 踏破記録 ID |
| `account_id` | `UNIQUEIDENTIFIER` |  | ✓ |  | アカウント ID |
| `dungeon_id` | `NVARCHAR(100)` |  | ✓ |  | ダンジョンマスタ ID |
| `clear_count` | `BIGINT` |  | ✓ | `1` | 踏破回数 |
| `first_cleared_at` | `DATETIME2(3)` |  | ✓ |  | 初回踏破日時 |
| `last_cleared_at` | `DATETIME2(3)` |  | ✓ |  | 最新踏破日時 |
| `created_at` | `DATETIME2(3)` |  | ✓ |  | 作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ✓ |  | 更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ✓ |  | 更新者 UUID |
| `is_deleted` | `BIT` |  | ✓ | `0` | 論理削除フラグ |

## 制約・インデックス

| 名称 | 種別 | 対象 | 説明 |
|:--|:--|:--|:--|
| `PK_account_dungeon_record` | PK | `account_dungeon_record_id` | 主キー |
| `FK_account_dungeon_record_account` | FK | `account_id -> dbo.account(uuid)` | アカウント参照 |
| `UX_account_dungeon_record_account_dungeon` | UNIQUE | `account_id, dungeon_id` | アカウント内のダンジョン記録を一意化 |
| `IX_account_dungeon_record_account_last_cleared` | INDEX | `account_id, last_cleared_at` | 最新踏破順の一覧取得 |
| `IX_account_dungeon_record_is_deleted` | INDEX | `is_deleted` | 論理削除フィルタ |
| `CK_account_dungeon_record_clear_count` | CHECK | `clear_count >= 1` | 踏破回数の下限 |

## DDL

```sql
CREATE TABLE [dbo].[account_dungeon_record] (
    [account_dungeon_record_id] UNIQUEIDENTIFIER NOT NULL,
    [account_id]                 UNIQUEIDENTIFIER NOT NULL,
    [dungeon_id]                 NVARCHAR(100)    NOT NULL,
    [clear_count]                BIGINT           NOT NULL CONSTRAINT [DF_account_dungeon_record_clear_count] DEFAULT (1),
    [first_cleared_at]           DATETIME2(3)     NOT NULL,
    [last_cleared_at]            DATETIME2(3)     NOT NULL,
    [created_at]                 DATETIME2(3)     NOT NULL,
    [updated_at]                 DATETIME2(3)     NOT NULL,
    [created_by]                 UNIQUEIDENTIFIER NOT NULL,
    [updated_by]                 UNIQUEIDENTIFIER NOT NULL,
    [is_deleted]                 BIT              NOT NULL CONSTRAINT [DF_account_dungeon_record_is_deleted] DEFAULT (0),

    CONSTRAINT [PK_account_dungeon_record] PRIMARY KEY CLUSTERED ([account_dungeon_record_id]),
    CONSTRAINT [FK_account_dungeon_record_account] FOREIGN KEY ([account_id])
        REFERENCES [dbo].[account] ([uuid])
        ON DELETE CASCADE
        ON UPDATE NO ACTION,
    CONSTRAINT [UX_account_dungeon_record_account_dungeon] UNIQUE ([account_id], [dungeon_id]),
    CONSTRAINT [CK_account_dungeon_record_clear_count] CHECK ([clear_count] >= 1)
);

CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_account_last_cleared]
    ON [dbo].[account_dungeon_record] ([account_id], [last_cleared_at] DESC);

CREATE NONCLUSTERED INDEX [IX_account_dungeon_record_is_deleted]
    ON [dbo].[account_dungeon_record] ([is_deleted]);
```
