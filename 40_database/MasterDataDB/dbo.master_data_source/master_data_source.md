# dbo.master_data_source テーブル定義

filebase の `config.yml` に定義されたマスタデータ種別を保持するテーブル。  
Seeder はこのテーブルを起点に追跡対象ディレクトリを決定し、API は有効な source のみを参照する。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `MasterDataDB` |
| スキーマ名 | `dbo` |
| テーブル名 | `master_data_source` |
| 完全修飾名 | `dbo.master_data_source` |
| 主キー | `source_id` |
| 外部キー参照先 | なし |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `source_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | source レコード ID |
| `source_key` | `NVARCHAR(80)` |  | ○ |  | 論理 source 名。例: `item`, `loot.pool`, `loot.table`, `set_effect` |
| `source_path` | `NVARCHAR(400)` |  | ○ |  | `50_filebase` ルートからの相対パス |
| `source_kind` | `NVARCHAR(30)` |  | ○ |  | `FEATURE` / `SHARED` / `META` / `SYSTEM` |
| `schema_version` | `INT` |  |  |  | source 既定の YAML スキーマバージョン |
| `is_enabled` | `BIT` |  | ○ | `1` | Seeder/API の参照対象に含めるか |
| `created_at` | `DATETIME2(3)` |  | ○ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | レコード最終更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID。Seeder の場合は system UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 最終更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

---

## 制約定義

| 制約名 | カラム | 種別 |
|:--|:--|:--|
| `PK_master_data_source` | `source_id` | PK |
| `UQ_master_data_source_key` | `source_key` | UNIQUE |
| `CK_master_data_source_kind` | `source_kind` | CHECK |
| `CK_master_data_source_schema_version` | `schema_version` | CHECK |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `PK_master_data_source` | `source_id` | CLUSTERED | 主キー検索 |
| `UQ_master_data_source_key` | `source_key` | UNIQUE | source 解決 |
| `IX_master_data_source_is_enabled` | `is_enabled`, `is_deleted` | NONCLUSTERED | 有効 source 一覧取得 |

---

## 用途

| 用途 | 説明 |
|:--|:--|
| 追跡対象定義 | `50_filebase/config.yml` の `database` 配列を SQL 上で保持する |
| source 有効化制御 | `is_enabled = 0` により Seeder/API から一時的に除外する |
| source 種別判定 | `source_kind` により feature/shared/meta/system を区別する |

---

## ソースコード参照

未実装。実装時は API 側に `MasterDataDbContext` / `MasterDataRepository` / `MasterDataSeeder` を追加する。
