# AstralRecord Geyser Extension

Velocity上のGeyserへ、AstralRecordのカスタムプレイヤーヘッドを起動時に登録するJava 21 / Mavenモジュール。Geyser API 2.11.2を使用する。

## 登録元

- `BuiltinHeadTextures` のJava定数: RPGの `GuiItems` と同じ上下左右矢印・無効ボタンの5テクスチャ。
- 認証付き `GET /api/geyser/heads` の `textures`: item / mob / skill / classマスターの `icon: PLAYER_HEAD` と `iconTexture`（Base64のtexturesプロパティ）。
- 同レスポンスの `playerUuids`: 削除されていない登録ユーザーのMinecraft UUID。オンライン一覧やアカウントIDではない。

テクスチャは `PROFILE`、ユーザーUUIDは `UUID` として `GeyserDefineCustomSkullsEvent` 内で登録する。Geyserが生成するBedrockパックをクライアントが適用すると、RPGから送られた対応するヘッドの見た目が変換される。クリックやスクロールの処理はRPGが引き続き担当する。

## ビルド・導入

1. ワークスペースの `60_tool/12-build-network-plugins.bat` を実行する。Extension単体は `-Target Extension`。
2. `60_tool/network-plugin-build/output/AstralRecordGeyserExtension.jar` をVelocityの `plugins/Geyser-Velocity/extensions/` へ配置する。
3. Geyserのカスタムコンテンツを有効にする（現行設定では `gameplay.enable-custom-content: true`）。
4. 初回起動でExtensionデータフォルダに作成される `config.yml` の `api.baseUrl` と認証を設定する。フォルダは通常 `plugins/Geyser-Velocity/extensions/astralrecordgeyser/`。事前に配置してもよい。
5. `api.apiKey` を設定するか、既定の `ASTRALRECORD_API_KEY` 環境変数をVelocityプロセスへ渡す。キーはGitへコミットしない。TLS証明書はJVMから信頼できるものを使用する。
6. 更新済みAPIを起動し、マスターをseedした後にProxyを再起動する。Bedrockクライアントで生成パックを適用して再接続する。

RPG本体や `AstralRecordProxy.jar` をextensionsフォルダに入れない。このJARはGeyserが読み込む。

## 起動・障害時の契約

API取得は起動時イベント内のタイムアウト付きGET一回。全レスポンスの構造を検証した後に登録し、テクスチャとUUIDをそれぞれ重複排除する。リダイレクトは追跡しない。固定ヘッドはAPI接続失敗時も登録し、API由来ヘッドを読み込めなかったことをエラーログに出す。復旧後はProxyを再起動する。

登録対象を増やした場合やプレイヤーがスキンを変更した場合、RPGのマスター再読込だけではBedrock側へ新しい定義を追加できない。APIのマスター更新後、Proxy再起動と再接続が必要。通常のGeyser reloadでカスタム定義が再構築されることは前提にしない。

UUID登録はGeyserのスキン解決に依存する。Floodgate由来UUIDなど、Mojangでスキンを解決できないUUIDの表示は保証しない。UUID一覧の取得・登録と、Geyserによる各スキンの解決成功は別である。

## 固定ヘッドを追加する場合

RPGに固定のプレイヤーヘッドスキンを追加・変更したら、`BuiltinHeadTextures` の定数と `ALL` も同じ変更で同期する。マスター由来テクスチャはこの定数へ追加しない。プログラムに設定する値はBase64の `textures` 値であり、仮のProfile UUIDではない。

公式参照: [Custom Skulls](https://geysermc.org/wiki/geyser/custom-skulls/)、[Extensions](https://geysermc.org/wiki/geyser/extensions/)。
