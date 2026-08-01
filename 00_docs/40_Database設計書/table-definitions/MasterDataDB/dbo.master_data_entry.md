# dbo.master_data_entry テーブル定義

filebase YAML 1 ファイルを API 配信用の 1 マスタレコードとして保持するテーブル。  
API はこのテーブルを `master_type` + `master_id` で検索し、必要なマスタだけを取得する。

---

## テーブル情報

| 項目 | 値 |
|:--|:--|
| データベース名 | `MasterDataDB` |
| スキーマ名 | `dbo` |
| テーブル名 | `master_data_entry` |
| 完全修飾名 | `dbo.master_data_entry` |
| 主キー | `entry_id` |
| 外部キー参照先 | `dbo.master_data_source.source_id` |

---

## カラム定義

| カラム名 | データ型 | PK | NotNull | デフォルト値 | 説明 |
|:--|:--|:--:|:--:|:--|:--|
| `entry_id` | `UNIQUEIDENTIFIER` | ○ | ○ |  | マスタレコード ID |
| `source_id` | `UNIQUEIDENTIFIER` |  | ○ |  | 所属 source。参照先: `dbo.master_data_source.source_id` |
| `master_type` | `NVARCHAR(80)` |  | ○ |  | API 検索用のマスタ種別。例: `item`, `loot.pool`, `recipe` |
| `master_id` | `NVARCHAR(120)` |  | ○ |  | YAML の `id` |
| `category` | `NVARCHAR(60)` |  |  |  | item/recipe などのカテゴリ検索用 |
| `type` | `NVARCHAR(60)` |  |  |  | YAML の `type` 検索用 |
| `schema_version` | `INT` |  | ○ |  | YAML の `schemaVersion` |
| `display_name` | `NVARCHAR(200)` |  |  |  | YAML の `name`。一覧表示補助 |
| `source_file_path` | `NVARCHAR(500)` |  | ○ |  | `50_filebase` ルートからの相対ファイルパス |
| `source_file_hash` | `CHAR(64)` |  | ○ |  | 正規化前 YAML 内容の SHA-256 |
| `payload_json` | `NVARCHAR(MAX)` |  | ○ |  | API レスポンスへ変換可能な JSON ペイロード |
| `payload_version` | `BIGINT` |  | ○ | `1` | 同一 `master_type` + `master_id` の更新世代 |
| `effective_from` | `DATETIME2(3)` |  | ○ | `SYSUTCDATETIME()` | API 参照可能になった日時 |
| `created_at` | `DATETIME2(3)` |  | ○ |  | レコード作成日時 |
| `updated_at` | `DATETIME2(3)` |  | ○ |  | レコード最終更新日時 |
| `created_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 作成者 UUID。Seeder の場合は system UUID |
| `updated_by` | `UNIQUEIDENTIFIER` |  | ○ |  | 最終更新者 UUID |
| `is_deleted` | `BIT` |  | ○ | `0` | 論理削除フラグ |

`rebuild` でもentryは物理削除せずtombstoneとして保持する。特に削除済みシジルは、既に装着されていた個体の補償メール添付を受領時に解決するためpayloadを保持する。

---

## 制約定義

| 制約名 | カラム | 種別 |
|:--|:--|:--|
| `PK_master_data_entry` | `entry_id` | PK |
| `FK_master_data_entry_source` | `source_id` | FK |
| `CK_master_data_entry_schema_version` | `schema_version` | CHECK |
| `CK_master_data_entry_payload_json` | `payload_json` | CHECK `ISJSON` |
| `CK_master_data_entry_payload_version` | `payload_version` | CHECK |

---

## インデックス定義

| インデックス名 | カラム | 種別 | 用途 |
|:--|:--|:--|:--|
| `UX_master_data_entry_type_id_active` | `master_type`, `master_id` | UNIQUE FILTERED | 有効マスタの一意性保証 |
| `UX_master_data_entry_source_file_active` | `source_file_path` | UNIQUE FILTERED | 同一 YAML ファイルの二重投入防止 |
| `IX_master_data_entry_source` | `source_id`, `is_deleted` | NONCLUSTERED | source 別取得 |
| `IX_master_data_entry_category` | `master_type`, `category`, `is_deleted` | NONCLUSTERED | カテゴリ別一覧 |
| `IX_master_data_entry_type` | `master_type`, `type`, `is_deleted` | NONCLUSTERED | type 別一覧 |
| `IX_master_data_entry_hash` | `source_file_hash` | NONCLUSTERED | 差分検出 |

---

## 取得方針

| 取得単位 | WHERE 条件 | 用途 |
|:--|:--|:--|
| 単一マスタ | `master_type = @type AND master_id = @id AND is_deleted = 0` | プラグイン/API の通常照会 |
| カテゴリ一覧 | `master_type = @type AND category = @category AND is_deleted = 0` | item カテゴリ一覧 |
| source 一覧 | `source_id = @sourceId AND is_deleted = 0` | Seeder 検証・管理画面 |

---

## 設計備考

- 全 item/class/skill/buff/loot/recipe を API 起動時にメモリへ全展開しない。
- API は DB から必要な `master_type` + `master_id` を取得し、必要に応じて短時間・小容量のアプリ内キャッシュを使う。
- `payload_json` はレスポンス DTO へ変換可能な正規化 JSON とし、YAML 固有表現は Seeder で解決する。
- 参照整合性は `dbo.master_data_reference` で検証し、`payload_json` 内へ DB 外部キーを直接埋め込まない。
