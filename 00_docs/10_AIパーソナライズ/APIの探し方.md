# 内部APIの探し方

AstralRecord APIは、Plugin、Proxy、Lobby、Geyser拡張、Webから利用します。機能を手掛かりにControllerを見つけ、ルートや入出力は実装で確認します。

## 調べる順番

1. [Controllers](../../20_api/AstralRecordApi/AstralRecordApi/Controllers)でルートとHTTPメソッドを確認します。全ルートの入口は作業ツリーのルートから一覧できます。

   ```powershell
   rg -n '\[(Route|Http(Get|Post|Put|Patch|Delete|Head|Options))\b' 20_api/AstralRecordApi/AstralRecordApi/Controllers
   ```

2. 入出力は [Models](../../20_api/AstralRecordApi/AstralRecordApi/Models)、保存・整合性はControllerが使う [Repositories](../../20_api/AstralRecordApi/AstralRecordApi/Repositories) や [Services](../../20_api/AstralRecordApi/AstralRecordApi/Services) を辿ります。関連テストは [AstralRecordApi.Tests](../../20_api/AstralRecordApi/AstralRecordApi.Tests) にあります。
3. 利用側は [RPG Pluginの機能](../../10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature) のRepositoryや、[WebのAPIクライアント](../../30_web/AstralRecordWeb/AstralRecordWeb/Services) から対象ルートやDTO名で検索します。ネットワーク経路は [Proxy](../../10_plugin/AstralRecordProxy)、[Lobby](../../10_plugin/AstralRecordLobby)、[Geyser拡張](../../10_plugin/AstralRecordGeyserExtension) も確認します。
4. 依存性の登録と認可は [Program.cs](../../20_api/AstralRecordApi/AstralRecordApi/Program.cs) と [Authentication](../../20_api/AstralRecordApi/AstralRecordApi/Authentication)、接続・機能の設定名は [appsettings.json](../../20_api/AstralRecordApi/AstralRecordApi/appsettings.json) を確認します。稼働中の通常APIでは `/openapi/v1.json` と `/scalar` でも操作を探せます。

## 機能から探す

| 用途 | Controllerの入口例 |
|---|---|
| プレイヤー・所持品・保存 | [UserController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/UserController.cs)、[AccountController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountController.cs)、[PlayerStateController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/PlayerStateController.cs)、[InventoryController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/InventoryController.cs)、[PetController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/PetController.cs) |
| マスターとワールド | [MasterDataController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/MasterDataController.cs)、[ItemController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/ItemController.cs)、[MobController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/MobController.cs)、[SkillController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/SkillController.cs)、[WorldController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WorldController.cs)、[WorldPlacementsController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WorldPlacementsController.cs) |
| スキルツリーと進行 | [AccountSkillTreeController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountSkillTreeController.cs)、[SkillTreeEditorController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/SkillTreeEditorController.cs)、[AccountQuestController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountQuestController.cs)、[AdventureRecordController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AdventureRecordController.cs)、[DefenseController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/DefenseController.cs) |
| 取引・メール・報酬 | [TradeController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/TradeController.cs)、[MarketController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/MarketController.cs)、[MailController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/MailController.cs)、[LoginBonusController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/LoginBonusController.cs)、[AccountBenefitsController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountBenefitsController.cs) |
| Webでの認証・表示・運営 | [WebAuthController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WebAuthController.cs)、[WebPlayerProfileController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WebPlayerProfileController.cs)、[WebBestiaryController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WebBestiaryController.cs)、[PlayerAdminController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/PlayerAdminController.cs)、[DonationController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/DonationController.cs)、[AstraldShopController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/AstraldShopController.cs) |
| サーバーの接続・素材配信 | [NetworkController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/NetworkController.cs)、[NetworkManagementController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/NetworkManagementController.cs)、[GeyserController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/GeyserController.cs)、[ResourcePacksController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/ResourcePacksController.cs)、[BuilderSchematicsController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/BuilderSchematicsController.cs) |

DB接続とテーブル対応は [DbContext](../../20_api/AstralRecordApi/AstralRecordApi/Data) と [DB定義](../../00_docs/40_Database設計書/README.md)、マスター投入は [MasterDataSeeder](../../20_api/AstralRecordApi/AstralRecordApi/Services/MasterDataSeeder.cs) と [Filebaseの定義](../../00_docs/50_Filebase設計書/README.md) を確認します。配置は [運用ツール](../../60_tool/README.md)、API変更とテストは [APIの実装規約](../../.codex/skills/astralrecord-code/references/api-code.md) を参照します。
