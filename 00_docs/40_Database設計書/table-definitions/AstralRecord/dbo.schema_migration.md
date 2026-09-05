# dbo.schema_migration テーブル定義

本番 migration runner が適用済みSQLの識別子と内容ハッシュを保持する履歴台帳。適用済みSQLの再実行を防ぎ、同じ migration ID の内容が後から変更された場合は配置を停止します。

## テーブル情報

| 項目 | 値 |
|:--|:--|
| スキーマ名 | `dbo` |
| テーブル名 | `schema_migration` |
| 完全修飾名 | `dbo.schema_migration` |
| 主キー | `migration_id` |

## カラム定義

| カラム名 | データ型 | PK | NotNull | 説明 |
|:--|:--|:--:|:--:|:--|
| `migration_id` | `NVARCHAR(128)` | ○ | ○ | migrationの不変識別子 |
| `file_name` | `NVARCHAR(260)` |  | ○ | 適用したSQLファイル名 |
| `script_sha256` | `CHAR(64)` |  | ○ | UTF-8 SQL本文のSHA-256（16進） |
| `applied_at` | `DATETIME2(3)` |  | ○ | 適用確定日時（UTC） |

## 制約

- 主キー: `PK_schema_migration (migration_id)`
- `script_sha256` は64桁の16進文字列とします。
- 同一 `migration_id` のSQL本文変更は許可せず、新しいmigration IDを発行します。

## DDL

```sql
CREATE TABLE [dbo].[schema_migration] (
    [migration_id]  NVARCHAR(128) NOT NULL,
    [file_name]     NVARCHAR(260) NOT NULL,
    [script_sha256] CHAR(64)      NOT NULL,
    [applied_at]    DATETIME2(3)  NOT NULL CONSTRAINT [DF_schema_migration_applied_at] DEFAULT (SYSUTCDATETIME()),
    CONSTRAINT [PK_schema_migration] PRIMARY KEY CLUSTERED ([migration_id]),
    CONSTRAINT [CK_schema_migration_script_sha256]
        CHECK ([script_sha256] LIKE '[0-9A-Fa-f]' + REPLICATE('[0-9A-Fa-f]', 63))
);
```

このテーブルは `db-migrate` が初回実行時に冪等作成します。`init.sql` にも新規DB用の定義を含めます。
