# MasterDataDB同期フロー

## 1. 起動時同期

API 起動時に MasterDataDB が空、または `AutoSeedOnStartup = true` の場合に Seeder を実行する。

処理内容:

1. `50_filebase/config.yml` を読み、source 定義を解決する
2. `master_data_seed_run` に `trigger_type = STARTUP`, `status = RUNNING` を登録する
3. source ごとに YAML ファイルを列挙する
4. YAML をスキーマ検証し、API 配信用 JSON へ正規化する
5. `source_file_hash` が既存 entry と一致する場合は skip する
6. 差分がある場合は `master_data_entry` を upsert する
7. `ref:` を解析し、対象 entry の `master_data_reference` を入れ替える
8. filebase から消えた YAML は `master_data_entry.is_deleted = 1` にする
9. 必須参照の未解決を検証する（Plugin 組み込み通貨 `item:astrald` は Filebase の item entry がなくても解決済みとして扱う）
10. 成功時は `master_data_seed_run.status = SUCCEEDED`、失敗時は `FAILED` に更新する

## 2. Seeder API 同期

Seeder API は運用者が明示的に MasterDataDB を再構築・更新するための入口とする。

想定エンドポイント:

| HTTP | パス | 用途 |
|:--|:--|:--|
| `POST` | `/api/master-data/seed` | filebase から MasterDataDB を同期する |
| `GET` | `/api/master-data/seed-runs` | Seeder 実行履歴を取得する |
| `GET` | `/api/master-data/health` | MasterDataDB の参照可能状態を確認する |

認可:

- 通常のゲーム API キーとは分離した管理用 API キーを使う。
- 少なくとも本番では `POST /api/master-data/seed` を一般プラグインから呼ばせない。

## 3. API 参照フロー

通常 API は filebase を直接読まない。

```text
Plugin / Web
  -> AstralRecord API
    -> MasterDataRepository
      -> MasterDataDB.dbo.master_data_entry
```

取得単位:

| ユースケース | API の取得単位 |
|:--|:--|
| アイテム使用 | `master_type = item`, `master_id = itemId` |
| 装備インスタンス生成 | 装備 item と必要な set_effect/rune/buff のみ |
| ルート抽選 | loot.table と参照先 loot.pool のみ |
| レシピ表示 | recipe と参照 item の最小項目のみ |

## 4. 失敗時の扱い

| 失敗 | 起動時 Seeder | Seeder API |
|:--|:--|:--|
| DB 接続不可 | API 起動失敗または degraded 起動 | 503 |
| YAML 構文エラー | 起動失敗 | 400/422 |
| 必須参照未解決 | 起動失敗 | 422 |
| 任意参照未解決 | Warning ログ | 200 + warnings |

## 5. 既存構成からの移行

1. MasterDataDB と Seeder を追加する
2. API の static repository を filebase 読み込みから MasterDataDB 読み込みへ差し替える
3. API 起動時の全 repository 強制生成を廃止する
4. item/list 系 API は summary のみ返し、詳細は ID 単位に寄せる
5. プラグインの `ItemService.loadAll` を廃止し、必要 ID 単位の lazy load に変更する
