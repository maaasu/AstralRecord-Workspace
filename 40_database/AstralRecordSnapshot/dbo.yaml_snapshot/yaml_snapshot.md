# dbo.yaml_snapshot テーブル定義

YAMLファイルのスナップショットを保持し、前回ロード時との差分検出に使用するためのテーブルです。

## テーブル情報

| 項目        | 内容例                       |
|:----------|:--------------------------|
| データベース名   | `AstralRecordSnapshot`    |
| スキーマ名     | `dbo`                     |
| テーブル名     | `yaml_snapshot`           |
| 完全修飾名     | `dbo.yaml_snapshot`       |
| 主キー       | `snapshot_id`             |

## カラム定義

| カラム名           | データ型               | PK | NotNull | デフォルト値 | 説明                 |
|:---------------|:-------------------|:--:|:-------:|:------:|:-------------------|
| `snapshot_id`  | `UNIQUEIDENTIFIER` | ○  |    ○    |        | スナップショットID         |
| `file_path`    | `NVARCHAR(500)`    |    |    ○    |        | YAMLファイルの相対パス      |
| `file_hash`    | `NVARCHAR(64)`     |    |    ○    |        | ファイル内容のSHA-256ハッシュ |
| `content_json` | `NVARCHAR(MAX)`    |    |    ○    |        | YAMLの内容（JSON形式）    |
| `created_at`   | `DATETIME2(3)`     |    |    ○    |        | レコード作成日時           |
| `updated_at`   | `DATETIME2(3)`     |    |    ○    |        | レコード最終更新日時         |
| `created_by`   | `UNIQUEIDENTIFIER` |    |    ○    |        | 作成者の UUID          |
| `updated_by`   | `UNIQUEIDENTIFIER` |    |    ○    |        | 最終更新者の UUID        |
| `is_deleted`   | `BIT`              |    |    ○    |  `0`   | 論理削除フラグ            |

## 制約定義

| 制約種別      | 命名パターン              | 例                             |
|:----------|:--------------------|:------------------------------|
| 主キー制約     | `PK_{テーブル名}`        | `PK_yaml_snapshot`            |
| デフォルト制約   | `DF_{テーブル名}_{カラム名}` | `DF_yaml_snapshot_is_deleted` |
| UNIQUE 制約 | `UQ_{テーブル名}_{カラム名}` | `UQ_yaml_snapshot_file_path`  |

## インデックス定義

| インデックス種別 | 命名パターン              | 例                            |
|:---------|:--------------------|:-----------------------------|
| クラスタード   | `PK_{テーブル名}`        | `PK_yaml_snapshot`           |
| 非クラスタード  | `IX_{テーブル名}_{カラム名}` | `IX_yaml_snapshot_file_path` |

## DDL

```sql
CREATE TABLE [dbo].[yaml_snapshot] (
    [snapshot_id]    UNIQUEIDENTIFIER  NOT NULL,
    [file_path]      NVARCHAR(500)     NOT NULL,
    [file_hash]      NVARCHAR(64)      NOT NULL,
    [content_json]   NVARCHAR(MAX)     NOT NULL,
    [created_at]     DATETIME2(3)      NOT NULL,
    [updated_at]     DATETIME2(3)      NOT NULL,
    [created_by]     UNIQUEIDENTIFIER  NOT NULL,
    [updated_by]     UNIQUEIDENTIFIER  NOT NULL,
    [is_deleted]     BIT               NOT NULL  CONSTRAINT [DF_yaml_snapshot_is_deleted]  DEFAULT (0),

    CONSTRAINT [PK_yaml_snapshot] PRIMARY KEY CLUSTERED ([snapshot_id]),
    CONSTRAINT [UQ_yaml_snapshot_file_path] UNIQUE ([file_path])
);
GO

CREATE NONCLUSTERED INDEX [IX_yaml_snapshot_file_path]
    ON [dbo].[yaml_snapshot] ([file_path]);
GO
```

## 用途

| 用途           | 説明                                                         |
|:-------------|:-----------------------------------------------------------|
| YAMLデータの差分検出 | 前回のロード時と現在のファイル内容を比較し、変更があった場合のみデータベースへ反映するために使用します。       |
| 論理削除         | `is_deleted` が `1` のレコードは、対象のファイルが削除されたか、追跡対象外になったことを示します。 |

---

## ソースコード参照

| 種別         | パス                                                                                                                                 |
|:-----------|:-----------------------------------------------------------------------------------------------------------------------------------|
| Repository | `src/main/java/io/github/maaasu/astralRecord/infrastructure/database/file/yaml/repository/YamlSnapshotRepository.kt`               |
| Repository | `src/main/java/io/github/maaasu/astralRecord/infrastructure/database/file/yaml/repository/impl/SqlServerYamlSnapshotRepository.kt` |
| Table      | `src/main/java/io/github/maaasu/astralRecord/infrastructure/database/file/yaml/model/YamlSnapshot.kt`                              |

