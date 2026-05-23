# AstralRecord API

`20_api/AstralRecordApi/` は Plugin と Web が利用する REST API です。SQL Server とアプリケーション間の契約を提供します。

## 役割概要

- 可変データは SQL Server から取得する。
- 静的データはファイルシステム上のデータ定義を参照する。
- ランタイムは .NET 10、フレームワークは ASP.NET Core Web API。

## 設定

設定は `20_api/AstralRecordApi/AstralRecordApi/appsettings.json` と `20_api/AstralRecordApi/AstralRecordApi/appsettings.Development.json` で管理します。

- `ConnectionStrings:SqlServer`: SQL Server 接続文字列
- `ConnectionStrings:MasterData`: MasterDataDB 接続文字列
- `ConnectionStrings:History`: HistoryDB 接続文字列
- `FileDatabase:RootPath`: 静的データファイルのルートパス

## API 実装ルール

- API 追加・変更時は `.codex/skills/astralrecord-code/references/api-code.md` を参照する。
- 詳細仕様は `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\` 配下を確認する。
- API 契約変更は Plugin / Web / Database / Filebase への影響を前提に扱う。

## API ドキュメント

各 API の詳細仕様は `00_docs/20_API設計書/feature/` 配下の設計書を参照してください。

| エンドポイント | 役割 | ドキュメント |
|---|---|---|
| GET `/api/health` | ヘルスチェック | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/user/{uuid}` | ユーザー情報取得 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| POST `/api/user` | ユーザー作成 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| POST `/api/user/history` | ユーザー履歴登録 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.04-履歴登録系.md` |
| PUT `/api/user/{uuid}` | ユーザー情報更新 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| GET `/api/account?user_id={user_id}` | ユーザー配下のアカウント一覧取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/account/{uuid}` | アカウント取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| POST `/api/account` | アカウント作成 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| PUT `/api/account/{uuid}` | アカウント更新 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/inventory?account_id={account_id}` | アカウント配下のインベントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}` | インベントリ本体取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory` | インベントリ本体作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/{inventoryId}` | インベントリ本体更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}/entries` | インベントリエントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory/{inventoryId}/entries` | インベントリエントリ作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| DELETE `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ削除 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/item` | アイテム一覧取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| GET `/api/item/{itemId}` | アイテム取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| POST `/api/equipment/instances` | 装備インスタンス作成 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/instances/{instanceId}` | 装備インスタンス取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/enchant` | エンチャント適用 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| DELETE `/api/equipment/enchant` | エンチャント削除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/enhance` | 装備強化 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/transcendence` | 超越適用 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| POST `/api/equipment/rune` | ルーン装着 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| DELETE `/api/equipment/rune` | ルーン解除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/loadouts?account_id={account_id}` | 装備プリセット一覧取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| GET `/api/equipment/loadouts/{loadoutId}` | 装備プリセット取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/equipment/loadouts` | 装備プリセット作成 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| PUT `/api/equipment/loadouts/{loadoutId}` | 装備プリセット更新 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| DELETE `/api/equipment/loadouts/{loadoutId}` | 装備プリセット削除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/equipment/loadouts/{loadoutId}/activate` | 装備プリセット有効化 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| GET `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット一覧取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| PUT `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット登録・更新 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| DELETE `/api/equipment/loadouts/{loadoutId}/slots/{slotType}/{slotIndex}` | 装備プリセットスロット解除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.03-ロードアウト系.md` |
| POST `/api/rune/instances` | ルーンインスタンス作成 | `00_docs/20_API設計書/feature/15-rune/3-エンドポイント仕様/15_3.00-索引.md` |
| GET `/api/rune/instances/{instanceId}` | ルーンインスタンス取得 | `00_docs/20_API設計書/feature/15-rune/3-エンドポイント仕様/15_3.00-索引.md` |
| GET `/api/recipe` | レシピ一覧取得 | `00_docs/20_API設計書/feature/12-recipe/3-エンドポイント仕様/12_3.00-索引.md` |
| GET `/api/recipe/{recipeId}` | レシピ取得 | `00_docs/20_API設計書/feature/12-recipe/3-エンドポイント仕様/12_3.00-索引.md` |
| GET `/api/class` | クラス一覧取得 | `00_docs/20_API設計書/feature/10-class/3-エンドポイント仕様/10_3.00-索引.md` |
| GET `/api/class/{classId}` | クラス取得 | `00_docs/20_API設計書/feature/10-class/3-エンドポイント仕様/10_3.00-索引.md` |
| GET `/api/skill` | スキル一覧取得 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.00-索引.md` |
| GET `/api/skill/{skillId}` | スキル取得 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.00-索引.md` |
| GET `/api/buff` | バフ一覧取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/buff/{buffId}` | バフ取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/loot/pool` | ルートプール一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/pool/{poolId}` | ルートプール取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table` | ルートテーブル一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table/{tableId}` | ルートテーブル取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| POST `/api/master-data/seed` | filebase から MasterDataDB を同期 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/seed-runs` | Seeder 実行履歴取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/health` | MasterDataDB の参照可能状態取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |

## Scalar API UI

サーバー起動後、以下の URL でインタラクティブな API ドキュメントを確認できます。

```text
http://localhost:{port}/scalar
```

OpenAPI スペック（JSON）:

```text
http://localhost:{port}/openapi/v1.json
```
