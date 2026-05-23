# MasterDataDBテーブル一覧

## 1. テーブル一覧

| テーブル | 主キー | 主な検索キー | 説明 |
|:--|:--|:--|:--|
| `dbo.master_data_source` | `source_id` | `source_key` | filebase の source 定義 |
| `dbo.master_data_entry` | `entry_id` | `master_type`, `master_id`, `category`, `type` | マスタ本体 JSON |
| `dbo.master_data_reference` | `reference_id` | `from_entry_id`, `reference_type`, `reference_id_value` | マスタ間参照 |
| `dbo.master_data_seed_run` | `seed_run_id` | `started_at`, `status` | Seeder 実行履歴 |

## 2. master_type 方針

`master_type` は API の取得境界に合わせる。filebase のディレクトリ名そのものではなく、API/Seeder が扱う論理種別とする。

| master_type  | 対応 filebase                                   | 主な API                       |
| :----------- | :-------------------------------------------- | :--------------------------- |
| `item`       | `10.features.item/**/v*.yml`                  | `/api/item/{itemId}`         |
| `set_effect` | `10.features.item/equipment/set_effect/*.yml` | `/api/seteffect/{setId}`     |
| `class`      | `20.features.class/*.yml`                     | `/api/class/{classId}`       |
| `skill`      | `30.features.skill/*.yml`                     | `/api/skill/{skillId}`       |
| `mob`        | `40.features.mob/**/*.yml`                    | 未定                           |
| `buff`       | `70.shared.buff/*.yml`                        | `/api/buff/{buffId}`         |
| `loot.pool`  | `80.shared.loot/pool/*.yml`                   | `/api/loot/pools/{poolId}`   |
| `loot.table` | `80.shared.loot/table/*.yml`                  | `/api/loot/tables/{tableId}` |
| `recipe`     | `85.shared.recipe/*.yml`                      | `/api/recipe/{recipeId}`     |

## 3. entry 一意性

有効レコードは以下で一意とする。

```sql
master_type + master_id
```

同一 ID を複数カテゴリに置く運用は不可。item の `category` は検索補助であり、一意性の一部に含めない。

## 4. JSON ペイロード

`payload_json` は Seeder が YAML を以下の方針で変換した JSON とする。

| 変換対象 | 方針 |
|:--|:--|
| `schemaVersion` | `schema_version` カラムにも複製する |
| `id` | `master_id` カラムにも複製する |
| `category` | `category` カラムにも複製する |
| `type` | `type` カラムにも複製する |
| `name` | `display_name` カラムにも複製する |
| `ref:` | 参照先 ID に正規化し、`master_data_reference` にも登録する |
| 範囲値 | 現行 API と同じ `"1~3"` 形式へ正規化する |

## 5. DDL

DDL は `00_docs/40_Database設計書/table-definitions/MasterDataDB/init.sql` を正本とする。
