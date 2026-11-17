# 内部APIの探し方

AstralRecord APIは、Plugin、Proxy、Lobby、Geyser拡張、Webから利用します。機能を手掛かりにControllerを見つけ、ルートや入出力は実装で確認します。

## 調べる順番

1. [Controllers](../../../20_api/AstralRecordApi/AstralRecordApi/Controllers)でルートとHTTPメソッドを確認します。全ルートの入口は作業ツリーのルートから一覧できます。

   ```powershell
   rg -n '\[(Route|Http(Get|Post|Put|Patch|Delete|Head|Options))\b' 20_api/AstralRecordApi/AstralRecordApi/Controllers
   ```

2. 入出力は [Models](../../../20_api/AstralRecordApi/AstralRecordApi/Models)、保存・整合性はControllerが使う [Repositories](../../../20_api/AstralRecordApi/AstralRecordApi/Repositories) や [Services](../../../20_api/AstralRecordApi/AstralRecordApi/Services) を辿ります。関連テストは [AstralRecordApi.Tests](../../../20_api/AstralRecordApi/AstralRecordApi.Tests) にあります。
3. 利用側は [RPG Pluginの機能](../../../10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature) のRepositoryや、[WebのAPIクライアント](../../../30_web/AstralRecordWeb/AstralRecordWeb/Services) から対象ルートやDTO名で検索します。ネットワーク経路は [Proxy](../../../10_plugin/AstralRecordProxy)、[Lobby](../../../10_plugin/AstralRecordLobby)、[Geyser拡張](../../../10_plugin/AstralRecordGeyserExtension) も確認します。
4. 依存性の登録と認可は [Program.cs](../../../20_api/AstralRecordApi/AstralRecordApi/Program.cs) と [Authentication](../../../20_api/AstralRecordApi/AstralRecordApi/Authentication)、接続・機能の設定名は [appsettings.json](../../../20_api/AstralRecordApi/AstralRecordApi/appsettings.json) を確認します。稼働中の通常APIでは `/openapi/v1.json` と `/scalar` でも操作を探せます。

機能名からControllerを探すときは [機能索引](機能索引.md) を確認します。Controllerが分かっている場合は、対象実装からルートと入出力を確認します。
