# APP-01 / MC-01 / DEV-01への導入

2026-10-19に確認した配置を正本とする導入メモ。秘密値はこのファイルへ記載しない。

## 配置

| 用途 | PC内のパス | DEV-01からのパス |
|---|---|---|
| リポジトリ・Tool | DEV-01 `C:\AstralRecord-Workspace` | 同左 |
| Dev | DEV-01 `C:\server\AstralRecord-Dev\10_Dev` | 同左 |
| API | APP-01 `C:\app-01\AstralRecordApi` | `\\192.168.0.88\app-01\AstralRecordApi` |
| 公開Web | APP-01 `C:\app-01\AstralRecordWeb` | `\\192.168.0.88\app-01\AstralRecordWeb` |
| API用Filebase | APP-01 `C:\app-01\workspace\40_filebase` | `\\192.168.0.88\app-01\workspace\40_filebase` |
| Proxy | MC-01 `E:\mc-01\00_Proxy` | `\\MC-01\mc-01\00_Proxy` |
| Lobby | MC-01 `E:\mc-01\01_Lobby` | `\\MC-01\mc-01\01_Lobby` |
| Channel1 | MC-01 `E:\mc-01\11_Channel1` | `\\MC-01\mc-01\11_Channel1` |
| Channel2 | MC-01 `E:\mc-01\12_Channel2` | `\\MC-01\mc-01\12_Channel2` |
| Build | MC-01 `E:\mc-01\20_Build` | `\\MC-01\mc-01\20_Build` |
| Minecraftバックアップ | MC-01 `E:\mc-01\90_Backup` | `\\MC-01\mc-01\90_Backup` |

APP-01の共有は`C:\app-01`、MC-01の共有は`E:\mc-01`。共有パスは配布に使い、RunnerのServer root・Java実行ファイルには各PC内のローカルパスを設定する。Control管理WebはAPP-01に既存Web/APIと別サービス・別SQLiteで置く。

Agent/RunnerはDEV-01・MC-01に加え、APP-01にも各1組導入する。APP-01の組はMinecraftを登録せず、API/Web/Filebaseの配布ディレクトリを共通gateで保護する。各PCのHostとlocal rootを登録してから、そのPCの実パスに対応するbindingを作る。

| サーバー | 種別 | listen port | 既存runtime serverId |
|---|---|---|---|
| Dev | Purpur Java | 25567 | `Dev` |
| Proxy | Velocity | 25565 | 基本運転にruntime連携は不要。対応版Web/Agent/Runnerと`P1.Velocity`能力が必要 |
| Lobby | Purpur Java | 25569 | `Lobby` |
| Channel1 | Purpur Java | 25570 | `Channel1` |
| Channel2 | Purpur Java | 25571 | `Channel2` |
| Build | Purpur Java | 25568 | `Build` |

PurpurのJARは`purpur-1.21.11-2560.jar`。既存起動BATには`E:\PurpurServer`と未確認のJavaパスが残るため、そのまま使用しない。Runnerは登録済みJavaを直接起動し、Server directoryを作業ディレクトリとして扱う。Java 21の実パスとメモリ割当を各PCで確定して登録する。

## Tool設定

01/02/03は`deploy-debug/deploy-debug.config.json`を使用する。新Devの`plugins`、新API/Web、APP-01上のFilebaseへ配置する。API接続先は配置済みWeb設定の`https://app-01:444`と一致させ、認証キーは新APIの`appsettings.json`から参照する。DBツールも同じ配置先設定を参照し、DB接続文字列をリポジトリへ複製しない。ゲーム/マスタ/履歴/管理DBの既存接続先はAPP-01を参照していることを確認済み。

16/17はGit管理外の`maintenance.local.json` / `player-patch-migration.local.json`を使用する。上表のrootと実際のruntime serverIdを対応させる。配布対象・公開対象は今回の更新範囲から決め、サンプルの小文字`channel1`/`channel2`をそのまま採用しない。ControlのHost/Server/Environment GUIDはWebで発行後に`controlGate`へ設定する。GUID未発行の状態でManaged設定や管理運用markerを作成しない。

今回のローカル設定はDev→Build/Channel1/Channel2のJARだけを配布対象とし、配置データ・Filebase・World・network配布は無効にしている。API証明書と共有範囲が未確定のため、16の公開処理と17の一括移行は`migration.enabled=false`に保つ。16の`-Phase Plan -WorldCopy Skip -NetworkPlugins Skip`は配布先の確認に使用できる。Control GUIDの登録とAPI接続確認が済んでから、必要な機能だけを有効化する。

P4までProxyとProxy上のGeyserへのManaged network配布を有効化しない。16の対応する`networkPlugins.destinations[].deployDirectory`は空にし、今回の導入では`-NetworkPlugins Skip`を使用する。Velocityの基本運転対応だけではnetwork配布の起動確認Adapterは完成しない。外部起動したProxyを台帳へ登録して初期`Stopped`を停止証明に使うことはできず、その配布をControlのManaged運用へ含めない。

DEV-01の通常PATHには開発ツールがなく、検証用SDKとチェックサム検証済みJDK/Mavenは`C:\AstralRecord-Worktrees\.tools`に保存している。Toolを実行するPowerShellセッションにだけPATHを追加できる。OS全体のPATHは変更しない。

```powershell
$tools = 'C:\AstralRecord-Worktrees\.tools'
$bundle = Join-Path $tools 'deployment-20261019'
$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $bundle 'Jdk') -Directory | Select-Object -First 1).FullName
$mavenBin = Join-Path (Get-ChildItem -LiteralPath (Join-Path $bundle 'Maven') -Directory | Select-Object -First 1).FullName 'bin'
$pwshBin = 'C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell'
$env:PATH = (Join-Path $tools 'dotnet') + ';' + (Join-Path $env:JAVA_HOME 'bin') + ';' + $mavenBin + ';' + $pwshBin + ';' + $env:PATH
```

依存SqlClientが取り込む`System.Security.Cryptography.Pkcs`はAPIとSQLツールで修正版`9.0.21`へ固定する。対象は[MicrosoftのPKCS修正情報](https://github.com/dotnet/runtime/security/advisories/GHSA-846f-f764-76mx)と[追加修正情報](https://github.com/dotnet/runtime/security/advisories/GHSA-2fmv-rv2c-59x7)。テスト専用SQLiteの既存監査警告と、配布APIの依存関係は分けて確認する。

## 配布前に確定する事項

- Controlの完全なHTTPS origin、DNS、外部からの接続ポートと転送先。配置済み公開Webの設定には`https://astralrecord.com`があるが、Controlのサブドメインや転送設定の確定値としては使用しない。
- Controlは証明書の自動取得・自動更新を使用する。Windows証明書ストアへの保存、WebサービスSIDへの秘密鍵読取許可、更新後のWeb再起動を一組にする。取得/更新には[win-acmeの証明書ストア](https://www.win-acme.com/reference/cli)と[更新後スクリプト](https://www.win-acme.com/reference/plugins/installation/script)を使用できる。DNS/HTTP challengeの到達性と既存IISのbindingを確認してから導入する。
- `https://app-01:444`は旧`device_server`名の証明書を返し、名前不一致と信頼エラーがある。APIの証明書/接続名も正しく設定する。TLS検証の無効化では対応しない。
- DevとチャンネルのAPI・DBを共有するか。共有時のDevマスタ公開は同じ環境のチャンネルにも影響する。
- 各PCでの管理者PowerShell実行経路。調査時点ではDEV-01の実行セッションは非管理者で、APP-01/MC-01のWinRMは未接続。共有へのファイル配置だけではサービス登録・IIS設定・証明書登録を実行できない。

## 導入順序

1. 新APIの`FileDatabase:RootPath`を`C:\app-01\workspace\40_filebase`へ変更する。現行設定は旧`C:\server\FileDatabase\file`である。既存設定をアクセス制限付きで退避し、他の値を維持して配置する。
2. 更新対象Minecraftを正常停止し、API/DBの共有範囲と既存バックアップを確認する。DBの再構築・リセットは実施しない。
3. `10-release-management-deploy.bat -PreflightOnly`で既存キー・API/Web設定を検査する。ゲーム/History/Managementの非破壊migrationは従来の配布入口で実施し、失敗時はバイナリを置き換えない。
4. API/Webを更新し、新Filebaseの参照先・TLS・必要なDBスキーマを確認する。Pluginはテスト済みJARを停止中の各RPGサーバーへ配布する。ProxyにはAstralRecord本体を配布しない。
5. [通常のControl導入手順](README.md)に沿い、APP-01でWebと配布資源保護用Agent/Runner、DEV-01/MC-01で各1組のAgent/Runnerを停止状態で登録する。APP-01にはMinecraft定義を作らず、`C:\app-01`内のAPI/Web/Filebaseに対応するlocal rootを登録する。owner作成と各PCのAgent token入力は対話で行い、秘密を引数やこのメモへ保存しない。管理Webは管理者名とパスワードだけでログインする。
6. 各PCのローカルroot・Java・Host ID・Agent SIDを設定し、`-PreflightOnly`後に接続する。希望版と適用ACK版の一致を確認する。最初はプロセス/ファイル/予約の変更機能をfalseに保つ。
7. 01/02/03/16/17を併用する場合は`controlGate`を登録済みGUIDと配布元/先へ対応付け、全入口の導入確認と3台の管理運用markerを作成した後に変更機能を有効化する。01/03のAPI/Web/Filebase配布先bindingはAPP-01のHost/rootへ対応させる。16のProxy/Geyser配布は上記制約に従って無効のままにする。WebのJava基本運転だけを先行する場合は[基本運転への切り替え](BASIC-OPERATIONS.md)に従い、DEV-01/MC-01のmarker導入と停止確認を行う。この段階では旧ToolのLegacy実行は拒否したままとし、配布処理の共存まで完了したとは扱わない。
8. Devを先に検証し、その後にMC-01の各Javaサーバーへ広げる。バックアップは同PCでは`E:\mc-01\90_Backup`、DEV-01ではUNCのbackupOnly rootを使う。Dev側のサービスから共有へアクセスできるWindows認証/共有権限は別途確認する。バックアップの対象はMinecraftのServerFilesであり、APP-01のSQL/API/公開状態は含まない。

## 検証記録

APIは875テスト、Pluginは1022テストが成功し、API/WebのpublishとPluginのworktree内dist出力を確認した。DB用3ツールは警告なしでbuildし、ゲーム20・History 3・Management 4のmanifestをDBへ適用せず検査した。配置済みAPIの接続設定で4つのDBへ接続し、`SELECT DB_NAME()`だけで接続先を確認した。JSON・配置パス・01のPlan・16のPlan・配布前fixtureも確認した。09は既存PowerShell 7 bootstrapへ統一し、引数/終了コードのfixtureとAstralArchitectの17テスト・一時配布/中断復旧/排他fixtureが成功した。独立2観点のレビューと第2回確認で、Proxy配布制限とAPP-01資源保護の2指摘を解消した。

API本体とSQLツールのPKCS監査警告は修正版固定で解消した。APIテストにだけ含まれる既存`SQLitePCLRaw.lib.e_sqlite3 2.1.11`の監査警告は、未変更のEF SQLite参照に由来し、配布するAPIには含まれない。初回Plugin clean buildの既存`serialVersionUID`警告は未変更の`RewardUnavailableException`に由来する。新しい未説明警告はない。

このメモの作成と配布物の準備だけで、本番サービス登録・Minecraft起動停止・DB migration・証明書発行まで完了したとは扱わない。
