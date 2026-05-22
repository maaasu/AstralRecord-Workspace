# Filebase 設計書

このディレクトリは `50_filebase` の設計書を扱う。  
filebase は MasterDataDB へ投入するための YAML authoring source であり、API の通常リクエスト時に直接読むデータストアではない。

## 役割

| 領域 | 役割 |
|:--|:--|
| `50_filebase` | 人が編集する YAML 正本 |
| Seeder | YAML を検証・正規化し MasterDataDB へ投入する |
| `MasterDataDB` | API が常時参照する SQL Server DB |

## 基本方針

- YAML は編集しやすさとレビューしやすさを優先する。
- API 配信用の検索性・参照整合性は MasterDataDB 側で担保する。
- `config.yml` は Seeder の source 定義として扱う。
- `ref:` は Seeder で検証し、未解決の必須参照は投入失敗にする。

## ドキュメント一覧

1. [[Filebase概要]]
2. [[Filebase配置とスキーマ]]
3. [[Filebase参照ルール]]

## YAML スキーマ定義一覧

`50_filebase/` 配下の YAML スキーマ定義を `00_docs` から確認できるようにした閲覧用ミラー。  
YAML ファイル本体の正本は `50_filebase/` 側に置くが、設計レビューでは本設計書からキー、型、必須、デフォルト、参照ルールを確認できる。

| master_type | スキーマ定義 |
|:--|:--|
| `item` | `10.features.item/item.YAMLスキーマ定義.md` |
| `class` | `20.features.class.YAMLスキーマ定義.md` |
| `skill` | `30.features.skill.YAMLスキーマ定義.md` |
| `mob` | `40.features.mob/mob.YAMLスキーマ定義.md` |
| `mob.boss` | `40.features.mob/boss.YAMLスキーマ定義.md` |
| `mob.enemy` | `40.features.mob/enemy.YAMLスキーマ定義.md` |
| `mob.npc` | `40.features.mob/npc.YAMLスキーマ定義.md` |
| `buff` | `70.shared.buff.YAMLスキーマ定義.md` |
| `loot.pool` | `80.shared.loot/pool.YAMLスキーマ定義.md` |
| `loot.table` | `80.shared.loot/table.YAMLスキーマ定義.md` |
| `recipe` | `85.shared.recipe.YAMLスキーマ定義.md` |

item のカテゴリ別スキーマ（`bundle` / `consumable` / `currency` / `equipment` / `equipment/set_effect` / `material` / `rune`）は `10.features.item/` 配下に置く。

## 更新ルール

- YAML スキーマを変更した場合は、対象の `50_filebase/**/YAMLスキーマ定義.md` と本設計書を更新する。
- `50_filebase` 側の YAML スキーマ定義を変更した場合は、本設計書の閲覧用ミラーも更新する。
- source パスを変更した場合は `50_filebase/config.yml`、MasterDataDB source 定義、Seeder 仕様を同時に見直す。
- API レスポンスに影響する変更は `00_docs/20_API設計書/` も更新する。
