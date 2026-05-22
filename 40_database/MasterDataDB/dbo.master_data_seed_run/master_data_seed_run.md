# dbo.master_data_seed_run テーブル定義

MasterDataDB の投入実行履歴を保持する監査テーブル。  
API 起動時 Seeder と Seeder API のどちらで更新されたかを追跡する。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `MasterDataDB` |
| スキーマ名 | `dbo` |
| テーブル名 | `master_data_seed_run` |
| 完全修飾名 | `dbo.master_data_seed_run` |
| 主キー | `seed_run_id` |
| 外部キー参照先 | なし |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `seed_run_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | Seeder 実行 ID |
| `trigger_type` | `NVARCHAR(30)` |  | ○ |  | `STARTUP` / `SEEDER_API` / `MANUAL` |
| `status` | `NVARCHAR(30)` |  | ○ |  | `RUNNING` / `SUCCEEDED` / `FAILED` |
| `source_root_path` | `NVARCHAR(500)` |  | ○ |  | 投入元 `50_filebase` ルート |
| `started_at` | `DATETIME2(3)` |  | ○ |  | 開始日時 |
| `finished_at` | `DATETIME2(3)` |  |  |  | 終了日時 |
| `file_count` | `INT` |  | ○ | `0` | 走査した YAML ファイル数 |
| `upserted_count` | `INT` |  | ○ | `0` | INSERT/UPDATE した件数 |
| `deleted_count` | `INT` |  | ○ | `0` | 論理削除した件数 |
| `skipped_count` | `INT` |  | ○ | `0` | ハッシュ一致などで更新不要だった件数 |
| `error_message` | `NVARCHAR(MAX)` |  |  |  | 失敗理由 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | レコード最終更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 最終更新者 UUID |

---

## 制約定義

| 制約名 | カラム | 種別 |
|:--|:--|:--|
| `PK_master_data_seed_run` | `seed_run_id` | PK |
| `CK_master_data_seed_run_trigger_type` | `trigger_type` | CHECK |
| `CK_master_data_seed_run_status` | `status` | CHECK |
| `CK_master_data_seed_run_counts` | count 系カラム | CHECK |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `IX_master_data_seed_run_started_at` | `started_at DESC` | NONCLUSTERED | 最新実行履歴 |
| `IX_master_data_seed_run_status` | `status`, `started_at DESC` | NONCLUSTERED | 実行中/失敗履歴の確認 |

---

## 運用方針

| 状態 | 方針 |
|:--|:--|
| `RUNNING` | Seeder 開始直後に登録する |
| `SUCCEEDED` | 全 source の upsert と参照検証が完了した場合に更新する |
| `FAILED` | 構文エラー、未解決必須参照、DB 書き込み失敗時に更新する |

API 起動時 Seeder は `trigger_type = STARTUP`、管理用 Seeder API は `trigger_type = SEEDER_API` とする。
