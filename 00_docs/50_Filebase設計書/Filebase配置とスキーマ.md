# Filebase配置とスキーマ

## 1. ディレクトリ配置

| パス | master_type | 説明 |
|:--|:--|:--|
| `00.meta/` | 対象外または `meta` | 共通 enum・運用メモ |
| `10.features.item/` | `item` | item 共通・カテゴリ別 YAML |
| `10.features.item/equipment/set_effect/` | `set_effect` | 装備セット効果 |
| `20.features.class/` | `class` | 職業マスタ |
| `30.features.skill/` | `skill` | スキルマスタ |
| `40.features.mob/` | `mob` | mob/npc/boss など |
| `70.shared.buff/` | `buff` | バフ定義 |
| `80.shared.loot/pool/` | `loot.pool` | ルートプール |
| `80.shared.loot/table/` | `loot.table` | ルートテーブル |
| `85.shared.recipe/` | `recipe` | レシピ |

## 2. YAML 共通キー

全マスタ YAML は以下を持つ。

| キー | 型 | 必須 | MasterDataDB 反映先 | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | `schema_version` | スキーマバージョン |
| `id` | String | ○ | `master_id` | マスタ ID。`master_type` 内で一意 |
| `name` | String | △ | `display_name` | 表示名。表示を持たない定義では任意 |
| `category` | String | △ | `category` | item/recipe など分類が必要な source で使用 |
| `type` | String | △ | `type` | class/loot/recipe など種別固定値が必要な source で使用 |

## 3. スキーマ定義ファイル

| source | スキーマ定義 |
|:--|:--|
| item | `00_docs/50_Filebase設計書/10.features.item/item.YAMLスキーマ定義.md` |
| class | `00_docs/50_Filebase設計書/20.features.class.YAMLスキーマ定義.md` |
| skill | `00_docs/50_Filebase設計書/30.features.skill.YAMLスキーマ定義.md` |
| mob | `00_docs/50_Filebase設計書/40.features.mob/mob.YAMLスキーマ定義.md` |
| mob boss | `00_docs/50_Filebase設計書/40.features.mob/boss.YAMLスキーマ定義.md` |
| mob enemy | `00_docs/50_Filebase設計書/40.features.mob/enemy.YAMLスキーマ定義.md` |
| mob npc | `00_docs/50_Filebase設計書/40.features.mob/npc.YAMLスキーマ定義.md` |
| buff | `00_docs/50_Filebase設計書/70.shared.buff.YAMLスキーマ定義.md` |
| loot.pool | `00_docs/50_Filebase設計書/80.shared.loot/pool.YAMLスキーマ定義.md` |
| loot.table | `00_docs/50_Filebase設計書/80.shared.loot/table.YAMLスキーマ定義.md` |
| recipe | `00_docs/50_Filebase設計書/85.shared.recipe.YAMLスキーマ定義.md` |

## 4. ファイル命名

| 対象 | 命名 |
|:--|:--|
| マスタ YAML | `v<schemaVersion>.<id>.yml` |
| スキーマ定義 | `<source>.YAMLスキーマ定義.md` |
| メタ資料 | 内容がわかる Markdown 名 |

Seeder は `*.yml` / `*.yaml` のみを投入対象とし、スキーマ定義 Markdown は投入しない。

## 5. バリデーション

Seeder は最低限以下を検証する。

| 検証 | 失敗時 |
|:--|:--|
| YAML 構文が正しい | `FAILED` |
| `schemaVersion` が存在し 1 以上 | `FAILED` |
| `id` が空でない | `FAILED` |
| `master_type + id` が重複しない | `FAILED` |
| `category` が配置ディレクトリと矛盾しない | `FAILED` |
| `ref:` の必須参照が解決できる | `FAILED` |
| source path が存在する | `FAILED` |
