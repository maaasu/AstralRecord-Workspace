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
| GET `/api/user/mcid/{mcid}` | Minecraft ID 指定ユーザー情報取得 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| GET `/api/user/mcids?prefix={prefix}` | 参加履歴のある Minecraft ID 一覧取得 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| GET `/api/user/by-ip?globalIp={globalIp}&excludeUuid={uuid}` | 同一グローバルIPの別登録済みユーザー有無取得 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| POST `/api/user` | ユーザー作成 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| POST `/api/user/history` | ユーザー履歴登録 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.04-履歴登録系.md` |
| PUT `/api/user/{uuid}` | ユーザー情報更新 | `00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.00-索引.md` |
| GET `/api/account?user_id={user_id}` | ユーザー配下のアカウント一覧取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/account/{uuid}` | アカウント取得 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| POST `/api/account` | アカウント作成 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| PUT `/api/account/{uuid}` | アカウント更新 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| DELETE `/api/account/{uuid}` | アカウント削除 | `00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.00-索引.md` |
| GET `/api/player-setting?user_id={user_id}` | ユーザー単位プレイヤー設定一覧取得 | `00_docs/20_API設計書/feature/03-player-setting/3-エンドポイント仕様/03_3.00-索引.md` |
| GET `/api/player-setting/{userSettingId}` | プレイヤー設定取得 | `00_docs/20_API設計書/feature/03-player-setting/3-エンドポイント仕様/03_3.00-索引.md` |
| POST `/api/player-setting` | プレイヤー設定作成 | `00_docs/20_API設計書/feature/03-player-setting/3-エンドポイント仕様/03_3.00-索引.md` |
| PUT `/api/player-setting/{userSettingId}` | プレイヤー設定更新（楽観ロック） | `00_docs/20_API設計書/feature/03-player-setting/3-エンドポイント仕様/03_3.00-索引.md` |
| GET `/api/adventure-record/mob?account_id={account_id}&category={category}` | アカウント単位の Mob 討伐記録一覧取得 | `00_docs/20_API設計書/feature/19-adventure-record/3-エンドポイント仕様/19_3.00-索引.md` |
| POST `/api/adventure-record/mob/defeat` | Mob 討伐記録の登録・更新 | `00_docs/20_API設計書/feature/19-adventure-record/3-エンドポイント仕様/19_3.00-索引.md` |
| GET `/api/adventure-record/dungeon?account_id={account_id}` | アカウント単位のダンジョン踏破記録一覧取得 | `00_docs/20_API設計書/feature/19-adventure-record/3-エンドポイント仕様/19_3.00-索引.md` |
| POST `/api/adventure-record/dungeon/clear` | ダンジョン踏破記録の冪等upsert・回数加算 | `00_docs/20_API設計書/feature/19-adventure-record/3-エンドポイント仕様/19_3.00-索引.md` |
| GET `/api/account-skilltree/{accountId}` | アカウント単位のスキルツリー進行取得 | `00_docs/20_API設計書/feature/20-skilltree/3-エンドポイント仕様/20_3.00-索引.md` |
| PUT `/api/account-skilltree/{accountId}` | アカウント単位のスキルツリー進行保存 | `00_docs/20_API設計書/feature/20-skilltree/3-エンドポイント仕様/20_3.00-索引.md` |
| POST `/api/account-skilltree/{accountId}/repair-invalid-state` | 構造不整合の全解除・対象ユーザー限定補償メール配信 | `00_docs/20_API設計書/feature/20-skilltree/3-エンドポイント仕様/20_3.00-索引.md` |
| GET `/api/account-waystone/{accountId}` | アカウント単位のウェイストーン開放状態取得 | `00_docs/20_API設計書/feature/25-waystone/3-エンドポイント仕様/25_3.00-索引.md` |
| POST `/api/account-waystone/{accountId}/unlock` | ウェイストーン開放登録 | `00_docs/20_API設計書/feature/25-waystone/3-エンドポイント仕様/25_3.00-索引.md` |
| GET `/api/account-quest/{accountId}` | アカウント単位のクエスト進行取得 | `00_docs/20_API設計書/feature/27-quest/3-エンドポイント仕様/27_3.00-索引.md` |
| PUT `/api/account-quest/{accountId}` | アカウント単位のクエスト進行保存 | `00_docs/20_API設計書/feature/27-quest/3-エンドポイント仕様/27_3.00-索引.md` |
| GET `/api/login-bonus/claims?account_id={account_id}&from={from}&to={to}` | ログインボーナス受取履歴取得 | `00_docs/20_API設計書/feature/26-login-bonus/3-エンドポイント仕様/26_3.00-索引.md` |
| POST `/api/login-bonus/accounts/{accountId}/claims` | ログインボーナス受取済み日登録 | `00_docs/20_API設計書/feature/26-login-bonus/3-エンドポイント仕様/26_3.00-索引.md` |
| DELETE `/api/login-bonus/accounts/{accountId}/claims/{claimDate}` | 報酬付与失敗時のログインボーナス受取登録取消 | `00_docs/20_API設計書/feature/26-login-bonus/3-エンドポイント仕様/26_3.00-索引.md` |
| POST `/api/web-auth/challenges` | Web ログインチャレンジ発行 | `00_docs/20_API設計書/feature/24-web-auth/3-エンドポイント仕様/24_3.00-索引.md` |
| POST `/api/web-auth/challenges/consume` | Web ログインチャレンジ消費 | `00_docs/20_API設計書/feature/24-web-auth/3-エンドポイント仕様/24_3.00-索引.md` |
| GET `/api/market/listings` | マーケット出品一覧取得 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| GET `/api/market/listings/{listingId}` | マーケット出品取得 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| GET `/api/market/accounts/{accountId}/summary` | アカウント単位のマーケット利用状態取得 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| POST `/api/market/price-quote` | マーケット相場見積 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| POST `/api/market/listings` | マーケット出品作成 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| POST `/api/market/listings/{listingId}/purchase` | マーケット購入確定 | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| POST `/api/market/listings/{listingId}/cancel` | マーケット出品キャンセル | `00_docs/20_API設計書/feature/23-market/3-エンドポイント仕様/23_3.00-索引.md` |
| GET `/api/inventory?account_id={account_id}` | アカウント配下のインベントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}` | インベントリ本体取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory` | インベントリ本体作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/{inventoryId}` | インベントリ本体更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/{inventoryId}/entries` | インベントリエントリ一覧取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ取得 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| POST `/api/inventory/{inventoryId}/entries` | インベントリエントリ作成 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ更新 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| PUT `/api/inventory/{inventoryId}/entries` | インベントリエントリ一括置換 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| DELETE `/api/inventory/entries/{inventoryEntryId}` | インベントリエントリ削除 | `00_docs/20_API設計書/feature/13-inventory/3-エンドポイント仕様/13_3.00-索引.md` |
| GET `/api/item` | アイテム一覧取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| GET `/api/item/{itemId}` | アイテム取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| GET `/api/seteffect` | セット効果一覧取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| GET `/api/seteffect/{setId}` | セット効果取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| POST `/api/equipment/instances` | 装備インスタンス作成 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/instances/{instanceId}` | 装備インスタンス取得 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/enchant/{enchantMasterId}` | 共通エンチャントマスタ取得 | `00_docs/20_API設計書/feature/04-item/3-エンドポイント仕様/04_3.00-索引.md` |
| POST `/api/equipment/orb-operations` | オーブ支払い・装備更新・台帳確定 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| GET `/api/equipment/orb-operations/{operationId}` | オーブ操作結果照会 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
| DELETE `/api/equipment/enchant` | エンチャント削除 | `00_docs/20_API設計書/feature/14-equipment/3-エンドポイント仕様/14_3.00-索引.md` |
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
| GET `/api/skill-bind-presets?account_id={account_id}` | スキルバインドプリセット一覧取得 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.02-バインドプリセット.md` |
| PUT `/api/skill-bind-presets/{accountId}/{presetIndex}` | スキルバインドプリセット保存 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.02-バインドプリセット.md` |
| PUT `/api/skill-bind-presets/{accountId}/selected` | 選択中スキルバインドプリセット保存 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.02-バインドプリセット.md` |
| POST `/api/account-skills/{accountId}/{learnedSkillId}/forget` | 習得済みスキル個体の忘却 | `00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.03-習得済みスキル.md` |
| GET `/api/buff` | バフ一覧取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/buff/{buffId}` | バフ取得 | `00_docs/20_API設計書/feature/05-buff/3-エンドポイント仕様/05_3.00-索引.md` |
| GET `/api/loot/pool` | ルートプール一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/pool/{poolId}` | ルートプール取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table` | ルートテーブル一覧取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/loot/table/{tableId}` | ルートテーブル取得 | `00_docs/20_API設計書/feature/06-loot/3-エンドポイント仕様/06_3.00-索引.md` |
| GET `/api/mob` | Mob 一覧取得（`category` クエリで絞り込み可） | `00_docs/20_API設計書/feature/16-mob/3-エンドポイント仕様/16_3.00-索引.md` |
| GET `/api/mob/{mobId}` | Mob 詳細取得 | `00_docs/20_API設計書/feature/16-mob/3-エンドポイント仕様/16_3.00-索引.md` |
| GET `/api/gathering?category={category}` | 採集オブジェクト一覧取得。`category=MINING/HARVESTING` で絞り込み可 | 未作成 |
| GET `/api/gathering/{gatheringId}` | 採集オブジェクト詳細取得 | 未作成 |
| GET `/api/gathering-spawner` | 採集スポナー一覧取得 | 未作成 |
| GET `/api/gathering-spawner/{spawnerId}` | 採集スポナー詳細取得 | 未作成 |
| GET `/api/world` | WorldMasterData 一覧取得 | `00_docs/20_API設計書/feature/17-world/3-エンドポイント仕様/17_3.00-検索.md` |
| GET `/api/world/{worldId}` | WorldMasterData 詳細取得 | `00_docs/20_API設計書/feature/17-world/3-エンドポイント仕様/17_3.00-検索.md` |
| GET `/api/guide` | ゲーム内ガイド一覧取得 | `00_docs/20_API設計書/feature/27-guide/3-エンドポイント仕様/27_3.00-検索.md` |
| GET `/api/guide/{guideId}` | ゲーム内ガイド詳細取得 | `00_docs/20_API設計書/feature/27-guide/3-エンドポイント仕様/27_3.00-検索.md` |
| GET `/api/account-guide/{accountId}` | アカウント単位のガイド進行取得 | `00_docs/20_API設計書/feature/27-guide/3-エンドポイント仕様/27_3.00-検索.md` |
| POST `/api/account-guide/{accountId}/steps/complete` | ガイド手順達成の冪等登録 | `00_docs/20_API設計書/feature/27-guide/3-エンドポイント仕様/27_3.00-検索.md` |
| GET `/api/mail?user_id={user_id}&filter={filter}` | 期限内メール一覧取得 | `00_docs/20_API設計書/feature/18-mail/3-エンドポイント仕様/18_3.00-索引.md` |
| PUT `/api/mail/{mailId}/read` | メール既読更新 | `00_docs/20_API設計書/feature/18-mail/3-エンドポイント仕様/18_3.00-索引.md` |
| PUT `/api/mail/{mailId}/delete` | プレイヤー単位メール削除 | `00_docs/20_API設計書/feature/18-mail/3-エンドポイント仕様/18_3.00-索引.md` |
| POST `/api/master-data/seed` | filebase から MasterDataDB を同期 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/seed-runs` | Seeder 実行履歴取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/master-data/health` | MasterDataDB の参照可能状態取得 | `00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.00-索引.md` |
| GET `/api/network/admissions/{uuid}` | Lobby参加可否・権限取得 | `00_docs/20_API設計書/feature/33-network/3-エンドポイント仕様/33_3.00-エンドポイント仕様.md` |
| GET `/api/network/servers` | サーバー人数・権限別定員一覧取得 | `00_docs/20_API設計書/feature/33-network/3-エンドポイント仕様/33_3.00-エンドポイント仕様.md` |

## テスト運用ルール

- APIの単体テスト・Repositoryテストは、本番の `<task-root>/40_filebase` を読み込まず、参照せず、接続しない。必要なマスタ形状はテスト内の固定JSON/YAMLまたはin-memory DBへ最小限だけ投入する。
- `MasterDataSeeder` やマスタ参照APIをテストする場合も、期待値を本番ファイルから取得せず、テスト入力を明示的に固定する。本番Filebaseとの接続をテスト成功条件にしない。
- 本番Filebaseを使う動作確認が必要な場合は、通常の単体・Repositoryテストとは分離した明示的な統合検証として扱う。

## SQL Server 統合テスト

オーブ操作とスキルバインドプリセット選択の本番用 `UPDLOCK` / `HOLDLOCK` 分岐は、
`localhost\SQLEXPRESS` 上に専用prefixとrandom UUIDを持つ一時 DB を作成する opt-in テストで検証します。
通常の `dotnet test` では環境変数未指定を検知して実DB処理を行わず終了します。専用テストを実行する場合は次を使用してください。

```powershell
$env:ASTRALRECORD_RUN_SQLSERVER_INTEGRATION = '1'
dotnet test 20_api/AstralRecordApi/AstralRecordApi.Tests/AstralRecordApi.Tests.csproj --filter 'Category=SqlServerIntegration'
Remove-Item Env:ASTRALRECORD_RUN_SQLSERVER_INTEGRATION
```

## Scalar API UI

サーバー起動後、以下の URL でインタラクティブな API ドキュメントを確認できます。

```text
http://localhost:{port}/scalar
```

OpenAPI スペック（JSON）:

```text
http://localhost:{port}/openapi/v1.json
```
