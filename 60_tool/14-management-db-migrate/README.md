# ManagementDB Migration Tool

`20260917_web_credentials.sql`、`20260920_trusted_admin_browser.sql`、`20260926_donations.sql`、`20261002_player_admin_edit.sql`、`20261019_defense.sql` の明示登録済み migration だけを非破壊で適用・検査する専用runnerです。防衛戦の5テーブルについて主キー・全列・制約・外部キー・索引（フィルタの有無を含む）と履歴行を検査します。20260917以降の `YYYYMMDD_` / `YYYYMMDD-` 形式のSQLファイルがmanifestから漏れた場合は停止します。ゲーム用 `db-migrate` の対象や保護条件は変更しません。

`ConnectionStrings:Management` を配置先APIの `appsettings.json` から優先して読み、未設定時は同じ `ConnectionStrings:SqlServer` のDB名だけを `ManagementDB` に置換します。レビュー済みSQL本文のSHA-256、接続先、SQLの `USE`、各バッチ後のDB名を検査し、ManagementDB以外へ接続・移動しません。`dbo.player` と `dbo.schema_migration` がない初回環境では変更せず、`ManagementDB/init.sql` の適用を案内して停止します。

通常は `01-deploy-debug.bat` または `10-release-management-deploy.bat` がAPI/Web配置前に自動実行します。単独の手動復旧時だけ、次を実行します。

```powershell
.\14-management-db-migrate.bat
```

`--validate-only` はmanifestとSQLだけを検査し、DBへ接続・変更しません。`tests/management-db-migrate.manifest.ps1` はDBなしで登録漏れとSQL改変の拒否を検証します。統合テストは `ASTRALRECORD_SQLSERVER_TEST_CONNECTION` を設定したローカルSQL Server上のランダム名DBだけを作成・削除します。

## ネットワーク管理の初回導入

ManagementDBを準備し、スキーマを確認してからAPI、Proxy、RPG/Webの順に配置します。新しいManagementDBには [初期化SQL](../../00_docs/40_Database設計書/table-definitions/ManagementDB/init.sql) を適用します。既存環境の初回導入ではDBをバックアップし、接続先のサーバー名をAPIの配置先設定と照合して、下記のSQLを適用・確認します。この初回導入SQLは通常のmigrationランナーの登録対象外です。

作業ツリーのルートから実行します。Windows統合認証の例です。 `$networkSqlServer` には照合済みの接続先を指定します。

```powershell
$networkMigration = '00_docs/40_Database設計書/table-definitions/ManagementDB/migrations/20260916_managed_network_and_bans.sql'
sqlcmd -S $networkSqlServer -E -b -d ManagementDB -i $networkMigration
if ($LASTEXITCODE -ne 0) { throw 'ManagementDB network migration failed.' }

sqlcmd -S $networkSqlServer -E -b -d ManagementDB -Q "IF OBJECT_ID(N'dbo.network_settings', N'U') IS NULL OR OBJECT_ID(N'dbo.network_ban', N'U') IS NULL OR OBJECT_ID(N'dbo.network_management_audit', N'U') IS NULL OR NOT EXISTS (SELECT 1 FROM dbo.schema_migration WHERE migration_id = N'20260916_managed_network_and_bans') THROW 51020, 'Network migration verification failed.', 1;"
if ($LASTEXITCODE -ne 0) { throw 'ManagementDB network migration verification failed.' }
```

既存プレイヤーの件数、UUID、Web管理権限、公開状態が保持されていることを確かめてからAPIを配置します。

1. ProxyとLobbyの共通APIキーをAPIの設定に合わせ、サンプル値から配置環境の値へ変更します。APIの `Network:AuthoritySyncKey` とProxyの `api.authoritySyncKey` を同じ専用値に設定します。RPGとAPIには別の `api.networkModerationKey` / `Network:ModerationKey` を設定します。専用キーにはそれぞれ共通APIキーと異なる値を使います。
2. Proxyの初回取り込みが完了するまで旧設定を保持します。APIに管理設定が未登録なら旧YAMLの非API設定を取り込み、登録済みならWeb管理値を使います。
3. Web管理画面のネットワーク設定で、チャンネル、定員、権限、ホワイトリスト、Discord通知を確認します。RPGの `network.channelName`、Lobbyの接続先、VelocityのサーバーIDを揃えます。
4. Velocityを公開入口にしてオンライン認証とmodern forwardingを構成します。Paper側のポートはループバックまたはfirewallで直接接続を制限し、forwardingの共通secretを揃えます。ProxyとLobbyのAPI接続先は実際の配置に合わせ、本番のHTTPSでは証明書検証を使います。
5. Proxyの設定取得、入場、Webからの設定反映を確認します。BANを確認する場合は専用の検証ユーザーを使います。

## Webログイン変更時の配置

- Web管理を許可する場合は、プレイヤー本人のUUIDをAPI側の `WebAuth__AdditionalAdminPlayerUuids__0` に設定します。複数人は末尾を `0`、`1`、`2` と連番にします。設定後はAPIを再起動し、運用者はご自身のMinecraftアカウントのログインコードで入ります。本人の初回のコードログイン時に管理DBへプレイヤー行が作成されます。
- 設定による権限を取り消す前に、その管理者本人が所有する編集を安全に完了または取り消してください。その後、対象UUIDを設定から削除し、残りを連番にしてAPIを再起動します。既存セッションも以後の管理操作で再判定されます。管理DBの `web_admin` フラグによる権限は別途管理します。
- 自分のキャラクターを編集中は、参加停止により自分のログインコードを発行できません。削除に再認証が必要になった場合は編集を取り消し、Web管理を許可済みの別アカウントで操作してください。

- APIとWebを更新する前にManagementDBをバックアップし、認証情報と信頼済みブラウザのmigrationを適用します。Web切替では旧インスタンスを停止し、既存ブラウザの再ログインと管理画面へのアクセスを確認します。
- リバースプロキシ配下では接続元IPの扱いを確認し、複数Webインスタンスを運用する場合は境界側でも試行回数を制限します。
- パスワード変更の確定が分からない場合は、再ログインして設定を確認します。ゲーム内コードでの復旧はMinecraftサーバーが利用できる環境で行います。
- Cookie・認証世代の実装は [Webの起動設定](../../30_web/AstralRecordWeb/AstralRecordWeb/Program.cs) と [Webのセッション検証](../../30_web/AstralRecordWeb/AstralRecordWeb/Authorization/WebSessionEvents.cs)、API側は [WebAuthController](../../20_api/AstralRecordApi/AstralRecordApi/Controllers/WebAuthController.cs) を参照します。

## 寄付とDiscord連携の配置

1. ManagementDBの `20260926_donations.sql` を適用し、有償通貨のFilebaseをMasterDataDBへseedしてから、API・Web・Pluginを配置します。新規DBは初期化SQLを使います。
2. APIとWebの `Donations__WebKey` に共通APIキーと異なる同じ値を設定し、`Donations__DiscordClientId` と `Donations__DiscordClientSecret` を揃えます。APIには `Donations__DiscordGuildId`、Webには `Donations__DiscordRedirectUri` を設定します。Discord側の登録と、WebのHTTPSコールバック `/Donations/Discord` のURLを一致させます。
3. APIの `DataProtection__KeyPath` をデプロイ先と分離した永続ディレクトリに設定し、API実行アカウントだけに読み書きを許可します。複数インスタンスでは同じアプリ名と鍵を使用します。暗号化済みのDiscord OAuth情報と旧申請の明細を復元できるよう、ManagementDBと鍵を整合したバックアップとして保管します。
