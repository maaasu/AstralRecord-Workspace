# Filebase概要

## 1. 目的

`50_filebase` は AstralRecord の静的マスタデータを YAML で管理する領域である。  
移行後は API が直接 filebase を常時参照するのではなく、Seeder が filebase を読み取り、MasterDataDB へ変換・投入する。

## 2. 境界

| 含めるもの | 含めないもの |
|:--|:--|
| item/class/skill/mob/buff/loot/recipe などの静的マスタ | プレイヤー所持品、経験値、装備個体、ログイン履歴 |
| YAML スキーマ定義 | SQL Server の物理テーブル定義 |
| `ref:` 参照ルール | API の認証・認可ルール |

## 3. ランタイム方針

| 処理 | 方針 |
|:--|:--|
| API 起動 | 必要に応じて Seeder を実行し、以後は MasterDataDB を参照する |
| 通常 API リクエスト | filebase を直接読まない |
| Seeder API | filebase を読み、MasterDataDB を更新する |
| プラグイン | filebase を読まない。API から必要なマスタだけ取得する |

## 4. source 種別

| 種別      | 説明                     | 例                       |     |
| :------ | :--------------------- | :---------------------- | --- |
| feature | 機能追加の単位                | item, class, skill, mob |     |
| shared  | 複数 feature から参照される共通定義 | buff, loot, recipe      |     |
| meta    | 運用・共通 enum など          | StatusType              |     |

## 5. MasterDataDB への変換

Seeder は YAML から以下を抽出する。

| 抽出先 | 抽出元 |
|:--|:--|
| `master_data_source` | `config.yml` の `database` 定義 |
| `master_data_entry.master_type` | source 名とサブディレクトリ |
| `master_data_entry.master_id` | YAML の `id` |
| `master_data_entry.category` | YAML の `category` |
| `master_data_entry.type` | YAML の `type` |
| `master_data_entry.display_name` | YAML の `name` |
| `master_data_entry.payload_json` | YAML 全体を正規化した JSON |
| `master_data_reference` | YAML 内の `ref:` 参照 |

## 6. 現行 config.yml の注意

現行 `50_filebase/config.yml` は Seeder source 定義として使う前提だが、実ディレクトリとの一致確認が必要である。

| source | config の path | 実ディレクトリ | 状態 |
|:--|:--|:--|:--|
| `class` | `20.features.classes` | `20.features.class` | 要修正 |
| `skill` | `30.features.classes.skills` | `30.features.skill` | 要修正 |

Seeder 実装時は、投入前チェックで `config.yml` の path が存在しない場合に失敗させる。
