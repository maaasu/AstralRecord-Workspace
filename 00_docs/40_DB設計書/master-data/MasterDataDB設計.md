# MasterDataDB設計

## 1. 目的

`MasterDataDB` は、`50_filebase` の YAML マスタを SQL Server 上に正規化して保持し、API が常時参照するための DB である。  
現行の「API 起動時に全 YAML を読み、全件メモリキャッシュする」構成をやめ、API/プラグイン双方が必要なデータを必要な単位で取得できる状態にする。

## 2. 背景

現状の問題:

- API が item / buff / class / loot / recipe などの静的データを起動時に全ロードする。
- プラグイン側も API から全 item を取得してローカルキャッシュする。
- マスタ量が増えるほど起動時間、メモリ使用量、再ロード時の影響範囲が増える。
- filebase YAML の正本性と API 配信用データの責務が曖昧になっている。

移行後の責務:

| 層 | 責務 |
|:--|:--|
| `50_filebase` | YAML authoring source。人が編集・レビューする正本 |
| Seeder | YAML を検証・正規化し、MasterDataDB へ upsert する |
| `MasterDataDB` | API 配信用の静的マスタデータを保持する |
| API | MasterDataDB から必要なマスタを取得し、DTO として返す |
| プラグイン | API から必要なマスタだけを取得し、用途別にキャッシュする |

## 3. DB 境界

| DB | 含めるデータ | 含めないデータ |
|:--|:--|:--|
| `AstralRecord` | user/account/inventory/equipment_instance/rune_instance など、プレイヤー単位で変化するデータ | item/class/skill/buff/loot/recipe などの静的マスタ |
| `MasterDataDB` | filebase から生成された静的マスタ、参照関係、投入履歴 | プレイヤー所持数、装備個体値、ログイン状態などの動的データ |

## 4. 採用方針

MasterDataDB は「完全正規化テーブル群」ではなく、検索キーだけを薄く正規化し、本体は JSON ペイロードで保持する。

理由:

- item/equipment/recipe/loot の構造差が大きく、全フィールドを RDB 正規化すると変更コストが高い。
- API レスポンス DTO は YAML 構造と近く、JSON ペイロードからの変換が自然。
- API の主なアクセスは `master_type + master_id`、カテゴリ一覧、参照整合性検証で足りる。

## 5. 主要テーブル

| テーブル | 役割 | 詳細 |
|:--|:--|:--|
| `dbo.master_data_source` | filebase の source 定義 | `40_database/MasterDataDB/dbo.master_data_source/master_data_source.md` |
| `dbo.master_data_entry` | マスタ本体 JSON と検索キー | `40_database/MasterDataDB/dbo.master_data_entry/master_data_entry.md` |
| `dbo.master_data_reference` | `ref:` 参照関係 | `40_database/MasterDataDB/dbo.master_data_reference/master_data_reference.md` |
| `dbo.master_data_seed_run` | Seeder 実行履歴 | `40_database/MasterDataDB/dbo.master_data_seed_run/master_data_seed_run.md` |

## 6. キャッシュ方針

| 実行主体 | 方針 |
|:--|:--|
| API | 全件常駐キャッシュを持たない。必要に応じて `master_type + master_id` 単位の短時間キャッシュを持つ |
| プラグイン | 起動時全 item ロードを廃止し、表示・使用・生成に必要な ID だけ取得する |
| Seeder | 実行中だけ YAML を読み、DB upsert 後は永続キャッシュを持たない |

## 7. 未決事項

| 項目 | 状態 | 判断待ち内容 |
|:--|:--|:--|
| 物理 DB 名 | 決定 | 本設計では `MasterDataDB` とする |
| Seeder API の認可 | 未決 | 管理者 API キーのみ許可するか、内部ネットワーク限定にするか |
| キャッシュ TTL | 未決 | API/プラグインの TTL 既定値 |
