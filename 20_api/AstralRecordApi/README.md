# Astral Record API

Minecraft Purpur サーバー向け MMO RPG プラグイン Astral Record と連携するための Web API です。

## 役割概要

- 可変データ: SQL Server から取得
- 静的データ: ファイルシステム上のデータ定義を参照
- ランタイム: .NET 10
- フレームワーク: ASP.NET Core Web API

## 設定

設定は `AstralRecordApi/appsettings.json` と `AstralRecordApi/appsettings.Development.json` で管理します。

主な設定項目:

- `ConnectionStrings:SqlServer`
  - SQL Server 接続文字列
- `FileDatabase:RootPath`
  - 静的データファイルのルートパス

## API ドキュメント

各 API の詳細仕様は `docs/api/` 配下のファイルを参照してください。

| エンドポイント | 役割 | ドキュメント |
|---|---|---|
| GET `/api/health` | ヘルスチェック | [docs/api/health.md](docs/api/health.md) |
| GET `/api/user/{uuid}` | ユーザー情報取得 | [docs/api/user.md](docs/api/user.md) |
| POST `/api/user` | ユーザー作成 | [docs/api/user.md](docs/api/user.md) |
| PUT `/api/user/{uuid}` | ユーザー情報更新 | [docs/api/user.md](docs/api/user.md) |
| GET `/api/account?user_id={user_id}` | ユーザー配下のアカウント一覧取得 | [docs/api/account.md](docs/api/account.md) |
| GET `/api/account/{uuid}` | アカウント取得 | [docs/api/account.md](docs/api/account.md) |
| POST `/api/account` | アカウント作成 | [docs/api/account.md](docs/api/account.md) |
| PUT `/api/account/{uuid}` | アカウント更新 | [docs/api/account.md](docs/api/account.md) |
| GET `/api/inventory?account_id={account_id}` | アカウント配下のインベントリ一覧取得 | [docs/api/inventory.md](docs/api/inventory.md) |
| GET `/api/inventory/{inventoryId}` | インベントリ本体取得 | [docs/api/inventory.md](docs/api/inventory.md) |
| POST `/api/inventory` | インベントリ本体作成 | [docs/api/inventory.md](docs/api/inventory.md) |
| PUT `/api/inventory/{inventoryId}` | インベントリ本体更新 | [docs/api/inventory.md](docs/api/inventory.md) |
| GET `/api/inventory/{inventoryId}/entries` | インベントリエントリ一覧取得 | [docs/api/inventory.md](docs/api/inventory.md) |
| GET `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ取得 | [docs/api/inventory.md](docs/api/inventory.md) |
| POST `/api/inventory/{inventoryId}/entries` | インベントリエントリ作成 | [docs/api/inventory.md](docs/api/inventory.md) |
| PUT `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ更新 | [docs/api/inventory.md](docs/api/inventory.md) |
| DELETE `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ削除 | [docs/api/inventory.md](docs/api/inventory.md) |
| GET `/api/item` | アイテム一覧取得 | [docs/api/item.md](docs/api/item.md) |
| GET `/api/item/{itemId}` | アイテム取得 | [docs/api/item.md](docs/api/item.md) |
| POST `/api/equipment/instances` | 装備インスタンス作成 | [docs/api/equipment.md](docs/api/equipment.md) |
| GET `/api/equipment/instances/{instanceId}` | 装備インスタンス取得 | [docs/api/equipment.md](docs/api/equipment.md) |
| POST `/api/equipment/enchant` | エンチャント適用 | [docs/api/equipment.md](docs/api/equipment.md) |
| DELETE `/api/equipment/enchant` | エンチャント削除 | [docs/api/equipment.md](docs/api/equipment.md) |
| POST `/api/equipment/enhance` | 装備強化 | [docs/api/equipment.md](docs/api/equipment.md) |
| POST `/api/equipment/transcendence` | 超越適用 | [docs/api/equipment.md](docs/api/equipment.md) |
| POST `/api/equipment/rune` | ルーン装着 | [docs/api/equipment.md](docs/api/equipment.md) |
| DELETE `/api/equipment/rune` | ルーン解除 | [docs/api/equipment.md](docs/api/equipment.md) |
| GET `/api/equipment/loadouts?account_id={account_id}` | 装備プリセット一覧取得 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| GET `/api/equipment/loadouts/{loadoutId}` | 装備プリセット取得 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| POST `/api/equipment/loadouts` | 装備プリセット作成 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| PUT `/api/equipment/loadouts/{loadoutId}` | 装備プリセット更新 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| DELETE `/api/equipment/loadouts/{loadoutId}` | 装備プリセット削除 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| POST `/api/equipment/loadouts/{loadoutId}/activate` | 装備プリセット有効化 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| GET `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット一覧取得 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| PUT `/api/equipment/loadouts/{loadoutId}/slots` | 装備プリセットスロット登録・更新 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| DELETE `/api/equipment/loadouts/{loadoutId}/slots/{slotType}/{slotIndex}` | 装備プリセットスロット解除 | [docs/api/equipment-loadout.md](docs/api/equipment-loadout.md) |
| POST `/api/rune/instances` | ルーンインスタンス作成 | [docs/api/rune.md](docs/api/rune.md) |
| GET `/api/rune/instances/{instanceId}` | ルーンインスタンス取得 | [docs/api/rune.md](docs/api/rune.md) |
| GET `/api/recipe` | レシピ一覧取得 | [docs/api/recipe.md](docs/api/recipe.md) |
| GET `/api/recipe/{recipeId}` | レシピ取得 | [docs/api/recipe.md](docs/api/recipe.md) |
| GET `/api/class` | クラス一覧取得 | [docs/api/class.md](docs/api/class.md) |
| GET `/api/class/{classId}` | クラス取得 | [docs/api/class.md](docs/api/class.md) |
| GET `/api/skill` | スキル一覧取得 | [docs/api/skill.md](docs/api/skill.md) |
| GET `/api/skill/{skillId}` | スキル取得 | [docs/api/skill.md](docs/api/skill.md) |
| GET `/api/buff` | バフ一覧取得 | [docs/api/buff.md](docs/api/buff.md) |
| GET `/api/buff/{buffId}` | バフ取得 | [docs/api/buff.md](docs/api/buff.md) |
| GET `/api/loot/pool` | ルートプール一覧取得 | [docs/api/loot.md](docs/api/loot.md) |
| GET `/api/loot/pool/{poolId}` | ルートプール取得 | [docs/api/loot.md](docs/api/loot.md) |
| GET `/api/loot/table` | ルートテーブル一覧取得 | [docs/api/loot.md](docs/api/loot.md) |
| GET `/api/loot/table/{tableId}` | ルートテーブル取得 | [docs/api/loot.md](docs/api/loot.md) |

### Scalar API UI

サーバー起動後、以下の URL でインタラクティブな API ドキュメントを確認できます。

```text
http://localhost:{port}/scalar
```

OpenAPI スペック(JSON):

```text
http://localhost:{port}/openapi/v1.json
```

## 開発ルール

- API を追加または変更した場合は、以下もあわせて更新すること
1. 対応する `docs/api/*.md`
2. コントローラーの XML doc コメント (`/// <summary>`)
3. `README.md` の API ドキュメント一覧
