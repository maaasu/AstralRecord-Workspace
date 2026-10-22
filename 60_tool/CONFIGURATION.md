# 設定と新環境

## 設定の選び方

環境固有値を変更するときは、対応する`*.config.json`を同じディレクトリの`*.local.json`へコピーして編集します。localは**全体の置換**であり、差分だけをマージする方式ではありません。localファイルはGit管理外です。

番号付きBATでは、明示の`-ConfigPath <path>`（DBは`--config <path>`）を最優先にし、未指定ならlocal、localがなければconfigを使用します。16/17はlocalの作成が必須です。

| 入口 | 設定ファイル（`60_tool/`からの相対パス） | 補足 |
|---|---|---|
| 01/02/03/10 | `deploy-debug/deploy-debug.local.json` → `deploy-debug.config.json` | 10のBATも同じ設定優先順位を使用 |
| 04 | `db-rebuild/db-rebuild.local.json` → `db-rebuild.config.json` | BATがsource側の設定を指定する |
| 09 | `astralarchitect-deploy/astralarchitect-deploy.local.json` → `astralarchitect-deploy.config.json` | `-PluginsDirectory`が配置先を優先上書き |
| 11 | `db-reset-except-release-notes/db-reset-except-release-notes.local.json` → `db-reset-except-release-notes.config.json` | BATがsource側の設定を指定する |
| 13 | `db-migrate/db-migrate.local.json` → `db-migrate.config.json` | ゲームDBのmanifestを含む |
| 14 | `management-db-migrate/management-db-migrate.local.json` → `management-db-migrate.config.json` | 管理DBのmanifestを含む |
| 15 | `db-migrate/history-db-migrate.local.json` → `history-db-migrate.config.json` | 履歴DBのmanifestを含む |
| 16 | `maintenance/maintenance.local.json` | `maintenance.example.json`をコピー |
| 17 | `player-patch-migration/player-patch-migration.local.json` | `player-patch-migration.example.json`をコピー |
| 05/06/07/08/12 | 各引数・プロジェクト設定 | 配布環境向けの共通configは使用しない |

DBツールのEXE/`dotnet run`を直接使う場合は、`--config`を指定してください。BATのlocal選択とは別です。01/10が実行するDB migrationの設定は、選択されたdeploy-debug設定の`databaseMigrations` / `historyDatabaseMigrations` / `managementDatabaseMigrations`の`configPath`が正本です。個別BAT用localへ切り替えるなら、この3項目も更新します。

旧`master-data-reload/master-data-reload.ps1`は固定の`master-data-reload.config.json`を読みます。03の設定変更先ではありません。新規の運用手順には03を使用します。

## 配置先の正本

[APP-01 / MC-01 / DEV-01導入メモ](control-deploy/APP-01-MC-01-DEV-01.md)の配置を使用します。2026-10-20に以下の共有・ディレクトリの存在を読み取りで再確認しました。

| 用途 | DEV-01のToolから参照するパス | 配置先PC内のパス |
|---|---|---|
| ソース | `C:\AstralRecord-Workspace` | DEV-01の同じパス |
| Dev | `C:\server\AstralRecord-Dev\10_Dev` | DEV-01の同じパス |
| API | `\\192.168.0.88\app-01\AstralRecordApi` | APP-01 `C:\app-01\AstralRecordApi` |
| Web | `\\192.168.0.88\app-01\AstralRecordWeb` | APP-01 `C:\app-01\AstralRecordWeb` |
| API用Filebaseの配布先 | `\\192.168.0.88\app-01\workspace\40_filebase` | APP-01 `C:\app-01\workspace\40_filebase` |
| Build | `\\MC-01\mc-01\20_Build` | MC-01 `E:\mc-01\20_Build` |
| Channel1 / Channel2 | `\\MC-01\mc-01\11_Channel1` / `12_Channel2` | MC-01 `E:\mc-01\11_Channel1` / `12_Channel2` |
| Lobby / Proxy | `\\MC-01\mc-01\01_Lobby` / `00_Proxy` | MC-01 `E:\mc-01\01_Lobby` / `00_Proxy` |

ToolのAPI URLは配置済みWebと同じ`https://app-01:444`です。Geyser Extension配置先はProxyの`plugins/Geyser-Velocity/extensions/`です。現在のControl運用ではProxyのManaged配布は未対応のため、16のnetwork配布先は空のままにします。

Dev workflowのruntime serverIdは`Dev`、Build/Channelは`Build` / `Channel1` / `Channel2`です。16の`servers[].id`に使う小文字`dev` / `build` / `channel1` / `channel2`はファイル配布内の識別子です。`migration.serverIds`はPluginの`api.serverId`に合わせてください。

`maintenance.example.json`と`player-patch-migration.example.json`にはこの配置を反映しています。サンプルの配布・migrationは無効のままです。既存localは自動上書きしません。

## 秘密値とソースの場所

APIキー・migrationキーとDB接続文字列は、配置済みAPIの`appsettings.json`から参照します。Tool用JSONへ複製しません。

- Dev/16/17: `migration.apiSettingsPath`にAPIの共有側`appsettings.json`を指定。空なら設定された環境変数を使用。
- DB: `sourceApiAppsettingsPath`に同じファイルを指定。`connectionStrings`は空でよい。
- 10: APIの`token.txt`、API/Webのキー一致、URL、migrationキー分離、リリースノート同期設定を配置前に検査。秘密値はログへ表示しない。

deploy-debug設定の`projectPath` / `buildOutputPath` / `fileDatabase.sourcePath`は絶対パスです。別チェックアウトをビルドするときは、そのチェックアウト用の完全なlocalを作り、全ソース・出力・migrationのproject/configパスを合わせて変更してください。配置先はソースの場所とは別に確認します。

API用Filebaseには2種類のパスが必要です。Toolの`fileDatabase.deployPath`はDEV-01から書き込む共有パス、APIの`FileDatabase:RootPath`はAPP-01自身が読むローカルパスです。同じディレクトリを指すように設定します。

## API/Webの初回設定

配置済みの設定は配布時にも保持されます。初回は配置先の`appsettings.json`を編集してください。

1. API配置先に、既存DiscordSRV Botのトークンを非空の`token.txt`として安全に配置します。値はGitへ追加せず、配布バックアップにも複製しません。
2. APIの`appsettings.json`で`ApiKey:Key`、`SkillTreeRuntime:Key`、`SkillTreeRuntime:MigrationKey`を設定します。migrationキーは非空で、他の2キーと異なる値にします。DB接続文字列と`FileDatabase:RootPath`も同じファイルで確認します。
3. Web配置先の`appsettings.json`で`AstralRecordApi:BaseUrl`を`https://app-01:444`に設定し、`AstralRecordApi:ApiKey`をAPI側の`ApiKey:Key`と一致させます。`ReleaseNotes:SyncOnStartup`を有効にします。
4. Toolの`migration.apiSettingsPath`とDB用`sourceApiAppsettingsPath`にはAPI設定ファイルの共有パスを指定します。キーの実値をTool設定へコピーしません。
5. Control管理下では先にManaged設定を整え、10の`-PreflightOnly`でキー・URL等を検査します。TLSとFilebaseの移行は下表の実環境確認に従って別途完了させます。

## 2026-10-20の実環境確認

次は実機を読み取った時点の状態です。新環境への移行完了を意味しません。サーバー起動停止、DB migration、公開、一括移行は実行していません。

| 項目 | 確認結果 | 必要な対応 |
|---|---|---|
| API/DB共有 | Dev・Build・Channel1・Channel2は同一の`https://192.168.0.88:444/api`を参照。APIのゲーム/マスタ/履歴/管理DBは同一SQL Server上 | Devのマスタ公開が他Channelへ影響する前提で運用。分離には別のAPI/DBが必要 |
| APIのTLS | `app-01:444`は`CN=device_server`、SANも`device_server`の自己署名証明書を返す。名前不一致・信頼エラーを実測。Plugin4台の`api.ssl.verifyEnabled`はfalse | 接続名と一致する証明書と各クライアントの信頼を整える。Toolの検証省略は有効化しない |
| APIのFilebase | 配置済み`FileDatabase:RootPath`は旧`C:\server\FileDatabase\file`。旧・新フォルダが両方存在し、Toolの新配布先と不一致 | 下記の内容差を確認してから、APIの参照先を`C:\app-01\workspace\40_filebase`へ切替。設定ファイルだけ変更して完了とは扱わない |
| DEV-01のControl | Agent/Runnerは稼働、管理markerあり。一方Tool設定3種に`controlGate`なし | 管理WebのJava基本運転とToolの共存は別工程。APP-01のAPI/Web/Filebaseを含む登録済みHost/root/Serverのbindingが必要 |
| 16/17 | localの配置先・runtime IDは新配置に一致。migrationはfalse。16はJAR配布だけ有効 | TLS、共有範囲、gate連携の完了後に必要な処理だけ有効化 |

Controlのbindingがない状態では01/02/03/16/17はLegacyとして拒否されます。[gateの設定](control-gate/README.md)と[基本運転からの切替](control-deploy/BASIC-OPERATIONS.md)に従います。実台帳のGUIDと各PCの管理者レポートは[切替記録](environment-cutover/README.md)に整理しました。

10も管理markerがあるPCではLegacy設定のまま実行できません。Managed設定の10は`-PreflightOnly`だけを使用し、実配布は01の管理workflowで行います。現backendはManagedの直接配布を拒否するためです。

01/02/03の配布backend起動は、frontend自身と同じ`$PSHOME/pwsh.exe`を使うよう修正しました。通常配布とReleaseManagement分岐の両方をPowerShell 7で実行するfixtureを追加しています。準備中の手順と実台帳は[新環境への切替](environment-cutover/README.md)を参照してください。

Filebaseの`.yml/.yaml/.json`（`99.work`除外）を比較すると、新配置はリポジトリの2,185件と全件一致しました。旧配置は2,195件で、旧だけにEriva mob8件・dungeon2件があり、共通ファイル34件は改行・末尾空白以外にも本文差があります。残る980件のバイト差は改行だけです。リポジトリを正として切り替えることはユーザー確認済みです。実機反映は管理者実行と接続復旧を待っています。

Toolの管理登録は初回だけです。通常更新で管理者によるハッシュ再承認は不要になりました。更新版CLIも初回ログインを保持し、15分ごとの再ログインは不要です。Runner本体の更新とAPI/Filebase変更に必要な停止・同時書込防止は引き続き確認します。

## 配置しない確認

### 2026-10-22のManaged設定見直し

DEV-01の`deploy-debug.local.json`を作成し、01/02/03/10に共通のManaged設定を用意しました。16/17のlocalにも同じControl originと共有Environmentを設定しました。localはGit管理外なので、別PCや再作成したチェックアウトへ自動では引き継がれません。

- 01/03は共有APIを使う`Dev`/`Build`/`Channel1`/`Channel2`を対象にし、02はDevだけを対象にします。Plugin、API、Web、Filebaseの配布元・先を管理rootに対応付けました。
- 16は既存のJAR配布3件を維持し、通常Workflowに必要な`migration.enabled=true`へ変更しました。これは起動後のパッチ公開用で、全員分の一括更新ではありません。JARだけを配る構成なので`workflow.seedMasterData=false`とし、共有マスタの再seedは行いません。Filebase配布を追加する場合はseedの要否を見直してください。未設定のnetwork配布先は空のままです。
- 17はbindingを準備しましたが、明示的な一括更新・復旧用なので`migration.enabled=false`を維持しました。必要なときに対象とPreview結果を確認して使用します。
- 10はManagedでも`-PreflightOnly`専用です。実配布には01を使用します。

同日の実管理画面ではAPP-01とDEV-01が接続中、MC-01は未接続（最終報告2026-10-21 17:11 JST）でした。Server GUID、共有Environment、APP/MC/DEVの既存rootは切替記録と一致しています。一方、DEV-01の追加root `f9c06531-2e7e-40f2-b03a-ee2ee9ebe5aa`（`C:\AstralRecord-Workspace`）は未報告でした。01/03のFilebase配布元と16のnetwork配布元のbindingには、この追加rootを使用しています。

実行再開には、Dev停止・Session/holdなしを確認後、管理者として`environment-cutover/Update-ControlCutoverHost.ps1 -Phase ConfigureResources`でDEV追加rootを導入し、管理画面のroot報告を確認します。MC-01の接続復旧も必要です。DEV-01の通常Windowsユーザーでは、正式インストール済みの`%LOCALAPPDATA%\AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe`でログイン再利用の成功を確認しました。その後の管理者側でのCLI参照先とroot登録の確認結果は下記を参照してください。ブラウザのログインはCLIのログインと別です。管理markerを削除したりLegacyへ戻したりして解消しないでください。

通常フォルダーへlocal3件を反映した後、管理markerとのTool root一致とManaged判定、01/02/03のBATの`-Plan`、16のBATのPlan、10のBATの`-PreflightOnly`は成功しました。実配布や予約取得は行っていません。同日のAPIへの通常TLS接続では証明書名不一致と信頼チェーンエラーが継続し、配置済み設定の`FileDatabase:RootPath`も旧`C:\server\FileDatabase\file`でした。01/03の再開には[切替手順](environment-cutover/README.md)に従った証明書とFilebase参照先の切替も必要です。

設定のbinding整合とPlanの成功は、実台帳へのroot登録・接続状態・停止状態・APIのTLS/Filebase切替完了を保証しません。下記の検査と[切替手順](environment-cutover/README.md)を区別します。

#### 同日17:33 JSTの追跡確認

管理者実行時の`InvalidCliPath`は、上記ユーザープロファイル内のCLIが管理者側から確認できなかったことが原因でした。正規CLIパッケージを`C:\AstralRecord-Worktrees\.tools\managed-tool-setup\Cli`へ配置し、全357ファイルのSHA-256一致と通常ユーザーでのログイン再利用を確認しました。パッケージへ資格情報をコピーせず、local3件の`controlGate.cliPath`と管理者用セットアップのCLI参照先をこの配置へ揃えました。

続く`RunnerSafetyCheckFailed`では、読み取り専用snapshotがDevの`Running`、Sessionあり、holdなしを返しました。Agentを起動してControlとの接続を戻し、17:10:11 JSTにDevの正常停止成功とSessionなしを確認しました。17:23:44 JSTの`DEV-01-control-cutover-ConfigureResources.json`は`success=true`、`stage=Completed`となり、17:33 JSTのControl画面でも追加root `f9c06531-2e7e-40f2-b03a-ee2ee9ebe5aa`の報告、希望/適用版4/4、DEV-01接続中を確認しています。Agent/Runnerの両サービスも稼働中です。

同じ画面ではAPP-01（最終報告16:19 JST）とMC-01（同14:10 JST）は未接続でした。APIの通常TLS接続は証明書名不一致・信頼チェーンエラーが継続し、配置済み設定のFilebase参照先も旧パスのままです。DEV追加rootの登録完了だけで、共有APIを更新する01/03や全体配布16の再開可能とは扱いません。

```powershell
# APIへの要求や配置を行わない計画
.\60_tool\01-deploy-debug.bat -Plan
.\60_tool\16-maintenance.bat -Phase Plan -WorldCopy Skip -NetworkPlugins Skip

# API/Webの既存設定の検査。TLS疎通やFilebase切替の完了は保証しない
.\60_tool\10-release-management-deploy.bat -PreflightOnly

# DBへ接続せずmanifestとSQLの存在だけ確認
.\60_tool\13-db-migrate.bat --validate-only
.\60_tool\14-management-db-migrate.bat --validate-only
.\60_tool\15-history-db-migrate.bat --validate-only
```

`tests/tool-config-routing.tests.ps1`は一時フォルダと偽の外部コマンドで、DB BATのconfig/local/明示指定、10のPowerShell 7起動と設定選択、09の設定優先順位を確認します。DB接続、ビルド、実サーバー配布は行いません。
