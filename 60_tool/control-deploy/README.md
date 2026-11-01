# AstralRecordControlの導入

.NET 10 SDK / PowerShell 7 / Windows x64を使用します。`Publish-Control.ps1`はテストと自己完結型publishまで、`Install-Control.ps1`は指定PCでのWindowsサービス登録を行います。リポジトリから実サーバーへの自動配置はしません。

APP-01 / MC-01 / DEV-01の配置と移行時の確認事項は[新環境への導入](APP-01-MC-01-DEV-01.md)を参照してください。

## パッケージ作成

```powershell
pwsh -File ./60_tool/control-deploy/Publish-Control.ps1 -OutputDirectory C:\ControlPackages\p1-001
```

出力は`Web`、`Agent`、`Runner`、`Cli`の4ディレクトリです。バイナリだけを配布し、ProgramData・DB・鍵・ローカル設定を混在させません。現行publish成果物のmanifestは`phase=P3`です。秘密を含むパッケージや許可外のphaseは配布前検査で拒否します。

## 管理PCのCLI

Agentの新規サービス登録と`Initialize-ControlGameHost.ps1`は、Cliパッケージも同じ管理者の`%LOCALAPPDATA%\AstralRecordControl\Cli\bin`へ配置します。CLIをPATHへ追加する操作は不要です。既存PCの更新、またはWebを操作するだけのPCでは次を一度実行します。通常ユーザーのPowerShellでもCLIの配置は可能で、Agent登録時の管理者PowerShellも同じWindowsユーザーを使用してください。

```powershell
./60_tool/control-deploy/Install-ControlCli.ps1 -PackageDirectory C:\ControlPackages\p1-001\Cli
& "$env:LOCALAPPDATA\AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe" login --server-url https://control.example.test:5443
```

ログイン済みなら同じ接続先の資格情報を再利用します。更新時も`session.bin`は保持し、旧バイナリを同じCLIフォルダの`bin.before-*`へ退避します。

## サービス登録と保存先

対象PCの管理者PowerShellで、管理PCはWeb、開発PCとMC用PCはRunner/Agentをそれぞれ登録します。登録だけではサービスを開始しません。

```powershell
./60_tool/control-deploy/Install-Control.ps1 -Component Web -PackageDirectory C:\ControlPackages\p1-001\Web
# 各ゲームPCで実行
./60_tool/control-deploy/Install-Control.ps1 -Component Runner -PackageDirectory C:\ControlPackages\p1-001\Runner
./60_tool/control-deploy/Install-Control.ps1 -Component Agent -PackageDirectory C:\ControlPackages\p1-001\Agent
```

既定のbinary配置は`%ProgramFiles%\AstralRecordControl\<Component>\<releaseId>`、データは`%ProgramData%\AstralRecordControl\<Component>`。専用仮想サービスアカウント`NT SERVICE\AstralRecordControl.<Component>`で動作し、通常の管理者権限を付与しません。データは該当サービス/SYSTEM/Administratorsのみ、バイナリはサービスに読取/実行だけを付与します。登録結果に表示されたAgent SIDをRunnerへ設定します。

Web/Runner/AgentはWindows起動時に自動起動します。Agentは同じPCのRunnerをサービス依存先とするため、Runnerを先に登録してください。初回登録後は設定・owner・資格情報を整えてから起動します。

`-WhatIf`は計画のみ、`-PreflightOnly`は設定検査のみです。異なるdata rootを使用するときは、登録・CLI・Agent設定に同じ値を指定します。

## Webの初回設定

`Web\config.json`を作成します。証明書はLocalMachine/Myに配置し、秘密鍵の読み取りをWebサービスアカウントへ付与します。自己署名証明書を使う場合も、各Agent PCとアクセス端末で正しく信頼する必要があります。

```json
{
  "Control": { "PublicOrigin": "https://control.example.test:5443" },
  "Kestrel": {
    "Endpoints": {
      "Https": {
        "Url": "https://0.0.0.0:5443",
        "Certificate": {
          "Subject": "control.example.test",
          "Store": "My",
          "Location": "LocalMachine",
          "AllowInvalid": false
        }
      }
    }
  }
}
```

Webを停止したまま、配布したCliの実行ファイルを管理者の対話ターミナルで実行します。ownerは1件のみです。パスワードやtokenをコマンド引数へ渡しません。

```powershell
$env:Control__DataRoot = "$env:ProgramData\AstralRecordControl\Web"
C:\ControlPackages\p1-001\Cli\AstralRecordControl.Cli.exe owner create
./60_tool/control-deploy/Install-Control.ps1 -Component Web -PackageDirectory C:\ControlPackages\p1-001\Web -PreflightOnly
Start-Service AstralRecordControl.Web
```

固定URLから管理者名とパスワードでログインします。ログイン状態を保持し、日常操作で追加の認証を求めません。`/Hosts`でPCを登録し、表示される接続手順を対象PCで実行します。`/Security`で全セッションを失効できます。パスワードを失った場合はWeb停止後に`owner reset`を使用します。旧版からの更新ではownerやパスワードの作り直しは不要です。

## 各PCのAgent/Runner設定

対象PCの管理者PowerShellで、一度だけCLIへログインし、画面に表示されたHost IDで登録します。接続用資格情報はCLIが自動発行・保存するため、手動のトークン発行・コピーは不要です。既存のAgent設定・資格情報は自動上書きしません。標準導入ではサービス登録済みAgentの実パスとdata rootを自動検出し、設定後にAgentサービスを起動します。

```powershell
& "$env:LOCALAPPDATA\AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe" login --server-url https://control.example.test:5443
& "$env:LOCALAPPDATA\AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe" agent enroll --host-id '<登録したHost ID>'
```

CLIの成功はローカル設定とサービス起動の完了です。管理画面で接続中と設定の適用を確認します。段階導入スクリプトは`--start-agent false`で設定だけ済ませ、Runner→Agentの順で起動します。手動のAgent `--configure`は旧版との互換・復旧用に残しています。

`Agent\config.json`の`localResources`と`Runner\config.json`の`localResources`に同じ許可リストを設定します。root/executable IDは各PCで作成するGUIDです。ここに実在するローカルのディレクトリと実行ファイルを登録し、junction/symlinkを含むパスを使用しません。Webから任意のOSパスを追加できません。

Runnerの例:

```json
{
  "hostId": "11111111-1111-1111-1111-111111111111",
  "agentSid": "S-1-5-80-...サービス登録時に表示されたAgent SID...",
  "pipeName": "AstralRecordControl.Runner.v1",
  "localResources": {
    "roots": [{ "id": "22222222-2222-2222-2222-222222222222", "displayName": "Minecraft", "path": "C:\\Minecraft" }],
    "executables": [{ "id": "33333333-3333-3333-3333-333333333333", "displayName": "Java", "path": "C:\\Java\\bin\\java.exe", "kind": "Java" }]
  }
}
```

Agentの`hostId`はRunnerと一致させ、`runnerPipeName`はRunnerの`pipeName`と一致させます。既定pipe名は1PC1組を前提にしています。DataRoot/管理rootへサービスアカウントの必要権限を付与します。初期状態ではEnableProcessControl=falseとして登録し、共通gateの導入確認後に有効化します。

```powershell
./60_tool/control-deploy/Install-Control.ps1 -Component Runner -PackageDirectory C:\ControlPackages\p1-001\Runner -PreflightOnly
./60_tool/control-deploy/Install-Control.ps1 -Component Agent -PackageDirectory C:\ControlPackages\p1-001\Agent -PreflightOnly
Start-Service AstralRecordControl.Runner
Start-Service AstralRecordControl.Agent
```

Hello→Reconcile後にHostがOnlineになり、登録root/executableが表示されます。`/Servers`から開発PCへ`dev`、MC用PCへ`channel1`/`channel2`を登録します。希望版と適用版が一致することを確認します。設定適用に失敗した場合は適用版が進まず、既存の設定が維持されます。

## 更新

### PCごとの更新BAT

`Update-Control.bat`はPowerShell 7とパッケージを同梱した配置フォルダの入口です。フォルダ直下にBAT、`Runtime/PowerShell/pwsh.exe`とその配布ファイル、`Deployment/Update-ControlHost.ps1`・`Update-Control.ps1`・`Control.Deployment.psm1`、`Package/Web|Agent|Runner|Cli`を配置します。BATを右クリックして「管理者として実行」するだけで、APP-01はRunner→Agent→Web、MC-01/DEV-01はRunner→Agentの順に更新します。作業ディレクトリやPATHに依存しません。

配置先ごとに直下の`update-host.json`へ`computerName`とPC内の絶対パス`localPackageDirectory`を保存します。別PCから共有上のBATを開いても、その共有先PCでは実行されません。対象PCが違う場合は更新前にPC名と正しいローカルBATパスを案内して拒否します。対象PC自身で共有パスから開いた場合は登録済みのPC内パスへ解決し、既存UpdaterへUNCを渡しません。登録がない共有パスは日本語でPC内からの実行を案内します。

更新開始前に対象サービス・全対象パッケージ・既存設定を検査します。Runnerは既存の停止確認を使用するため、全管理サーバーがStopped、Session/holdなしの場合だけ更新します。ゲームの自動停止や強制終了は行いません。失敗時は後続の更新を中断し、BATの終了コードと配置フォルダ内の`Logs`に結果を残します。初回登録、パスワード、トークンの再入力は不要です。

### コンポーネント別の更新

```powershell
./60_tool/control-deploy/Update-Control.ps1 -Component Web -PackageDirectory C:\ControlPackages\p1-002\Web
./60_tool/control-deploy/Update-Control.ps1 -Component Agent -PackageDirectory C:\ControlPackages\p1-002\Agent
```

Web更新はWebだけ、Agent更新はAgentだけを停止します。サービス停止後に排他leaseを取得し、SQLite本体/WAL/SHMを一組として同じdata root内の`updates/<id>`へ退避してから新binaryへ切り替えます。旧binaryと旧サービスパスを残し、設定・鍵・credentialsを上書きしません。失敗時は退避先とサービス状態を確認し、DBを単純に上書きしてACKを巻き戻さないでください。

更新時にもAutomatic起動を設定し直し、既存AgentにはRunner依存を追加します。Agent更新でRunnerやゲームプロセスは停止しません。Webが一時的に到達不能でも、Agentは復旧後に保存済み資格情報で再接続するため、更新ごとの`agent enroll`は不要です。

`Initialize-ControlGameHost.ps1`の段階導入では設定中だけRunner/AgentをManualにし、正常完了後にAutomaticを確定します。導入が失敗した場合はManualのまま残るため、原因を解消して再実行してください。

Runnerの更新はControl CLIによる`runner check-update`が全ServerのStopped、Sessionなし、holdなしを返した場合だけ行います。Unmanagedや状態不明も拒否します。Agentを停止した後に再検査し、Runnerを更新してから元々稼働していたAgentを起動します。`-CliPath`にはP1 CLIの絶対パスを指定します。
更新途中に失敗した場合は、Agent/Runnerの実際のサービス状態と旧binary・snapshotを確認してから復旧します。

```powershell
./60_tool/control-deploy/Update-Control.ps1 -Component Runner -PackageDirectory C:\ControlPackages\p1-002\Runner -CliPath C:\ControlPackages\p1-002\Cli\AstralRecordControl.Cli.exe
```

### MC-01のJava実行資源をJava 25へ更新

`Update-ControlJavaRuntime.ps1`はMC-01専用です。署名と配布元を別経路で確認したJDK 25 ZIPのSHA-256、既存のJava executable資源ID、Control CLI、閉域APIの公開CA証明書とそのDER SHA-256を指定します。Proxy用Velocity資源は別IDのまま残します。全管理サーバーのStopped・Sessionなし・holdなしを、Agent停止前と停止後にRunnerの`check-update`で確認します。ゲームを自動停止しません。公式ZIPの全entryを検査してMC-01ローカルの`%ProgramFiles%\AstralRecordControl\Java`に版・hash別のreleaseとして展開し、Agent/RunnerサービスSIDへ読取・実行ACLを設定して`java -version`で25系を確認します。新JDKの`cacerts`へ検証済みAPI公開CAを`astralrecord-api-root`として登録し、exportした証明書のSHA-256を再確認してからreleaseを確定します。両サービスの`%ProgramData%\AstralRecordControl\<Component>\config.json`を退避し、指定IDの`path`だけを同じ新`java.exe`へ変更します。Runner、元々稼働していたAgentの順で再開します。APIのTLS検証設定は変更しません。

次の例のZIPとhashは2026-10-23に検証したTemurin 25.0.4.1+1です。MC-01上でZIPをローカル配置し、管理者PowerShell 7から先に`-WhatIf`を実行します。既存の`Update-Control.bat`はControlバイナリを更新しますがJava資源は変更しないため、バイナリ更新を先に完了してからこの手順を実行します。

```powershell
$zip = 'C:\ControlDeployment\Runtime\Java\OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip'
$ca = 'C:\ControlDeployment\Runtime\Java\AstralRecord-Api-Root.cer'
$cli = 'C:\ControlDeployment\Package\Cli\AstralRecordControl.Cli.exe'
$arguments = @{ JdkArchive = $zip; ExpectedArchiveSha256 = '00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283'; ExecutableId = '233fe4fc-6564-4f56-a616-b123e8db9f7d'; CliPath = $cli; TrustedCertificatePath = $ca; ExpectedCertificateSha256 = '868027974e20d5f52d736b6a394f0aee8d40f503c4b32cf3c6438cb733b52164' }
& .\60_tool\control-deploy\Update-ControlJavaRuntime.ps1 @arguments -WhatIf
& .\60_tool\control-deploy\Update-ControlJavaRuntime.ps1 @arguments -Confirm:$false
```

`-WhatIf`はZIP/hash、公開CA、両設定の資源ID・kind・path一致と最初の停止状態を確認し、サービスやファイルを変更しません。更新失敗時は両設定のbackupから復元し、元のAgent/Runner稼働状態へ戻します。復旧にも失敗した場合はbackup pathを表示して停止するため、状態を確認してから再試行します。同じZIP/hashと資源IDでの再実行は登録済みreleaseと公開CAを確認して終了します。新規ZIPまたは異なるJDK root名を使う場合は検証したhashと`-JdkRootName`を更新してください。

01/02/03/16/17をControlと共存させるPCでは、P1ツール入口を導入し、新規Runner登録時は`Install-Control.ps1 -Component Runner -EnableManagedOperations`、既存Runnerへの初回導入は`Enable-ManagedOperations.ps1`で管理運用markerを作成します。markerはTool rootと管理運用の導入を一度記録し、導入後は旧Legacy入口を停止します。通常のTool更新でmarkerの再発行は不要です。既存schema 1のSHA-256記録は互換性のため残しますが、更新後の再承認には使いません。各ツール設定の`controlGate`と初回CLIログインは[既存ツールgate](../control-gate/README.md)を参照してください。Control Web側の変更操作は導入確認後に明示的に有効化します。

旧版との互換運用などで登録記録を明示的に更新する場合だけ、次の`-Renew`を使用します。同じTool root、全Server停止、Session/holdなしを確認し、旧markerを`.backup`へ退避して置き換えます。Runner本体の更新では引き続き停止確認が必要です。

```powershell
./60_tool/control-deploy/Enable-ManagedOperations.ps1 -Renew -CliPath "$env:LOCALAPPDATA\AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe"
```

既存の管理運用markerがあるPCでToolだけを更新するときは、管理者PowerShell 7で`Install-ControlTools.ps1`を実行します。初回導入のPCは先に上記の`Enable-ManagedOperations.ps1`（`-Renew`なし）で現在のTool rootを登録してください。更新前に、同じTool rootを使う01/02/03/16/17等の番号付きToolと、別の管理者によるTool更新が実行中でないことを確認します。このTool更新だけを理由にゲームサービスを停止する必要はありません。配布物の`files.sha256.json`を別経路で照合したSHA-256値を指定し、`-ToolRoot`にはmarkerに登録した同じ絶対パスを指定します。まず`-WhatIf`で対象を確認します。

```powershell
$release = 'C:\ControlPackages\p1-002'
$toolRoot = 'C:\ControlDeployment\Operations\Package\Tools\60_tool'
$manifestHash = '<配布元で確認したfiles.sha256.jsonの64桁SHA-256>'
pwsh -NoProfile -File ./60_tool/control-deploy/Install-ControlTools.ps1 -PackageDirectory $release -ToolRoot $toolRoot -ExpectedManifestSha256 $manifestHash -WhatIf
pwsh -NoProfile -File ./60_tool/control-deploy/Install-ControlTools.ps1 -PackageDirectory $release -ToolRoot $toolRoot -ExpectedManifestSha256 $manifestHash -Confirm:$false
```

manifest内の`Tools/60_tool`だけを検証して変更分を上書きし、対象の旧ファイルだけをTool rootと同じ親フォルダに退避します。余分なファイルや`.local.json`等のローカル設定は削除・上書きせず、markerも変更しません。失敗時は今回変更したファイルの復元を試み、復旧エラーがあれば退避フォルダを残して停止します。サービスの停止・起動やControl設定の変更は行いません。

Webの現行schemaは5、Agent/Runnerは4です。旧schemaからは各サービスのSQLite backupを作成してtransaction内で移行します。未知schemaは起動を拒否します。更新前snapshotとmigration前backupを保持してください。

```powershell
pwsh -NoProfile -File ./60_tool/control-deploy/tests/deployment-preflight.ps1
```

このテストは一時ディレクトリのpackage/config検査だけを実行し、サービス登録、証明書設定、実PCへのデプロイを行いません。

## 既存サービスの基本運転

### MC-01のBedrock UDP許可を修復する

Java 25への更新後、MC-01上でGeyserがUDP 19132を待ち受けているのに外部から応答しない場合に限り、管理者PowerShellで`Repair-ControlBedrockFirewall.ps1`を実行します。MC-01、実行ファイルの存在、UDP 19132の所有プロセス、診断済みの旧Java 21受信許可を確認してから、Java 25の実行ファイルに対するPublic受信UDP 19132だけの独立ルールを作ります。旧ルールやサービスには触れません。同名の正しいルールがあれば再実行しても追加しません。

```powershell
pwsh -NoProfile -File ./60_tool/control-deploy/Repair-ControlBedrockFirewall.ps1 -WhatIf
pwsh -NoProfile -File ./60_tool/control-deploy/Repair-ControlBedrockFirewall.ps1 -Confirm:$false
```

Java実行ファイルが既定の`C:\Program Files\AstralRecordControl\Java\jdk-25.0.4.1+1-00c847d804f4\bin\java.exe`以外なら、`-NewJava 'C:\absolute\path\to\java.exe'`を付けます。結果は`status`（`WhatIf`、`Created`、`Existing`）、`ruleGroup`、実際の`ruleName`（WhatIf時はnull）、限定スコープを含む1行のJSONです。作成したルール名には一意の識別子が付きます。作成後の検証が失敗した場合は、この実行で追加したと確認できるルールだけを削除します。手動で戻すときは、成功時のJSONにある`ruleName`を確認し、MC-01の管理者PowerShellで`Remove-NetFirewallRule -Name '<成功時のruleName>' -PolicyStore PersistentStore`を使用します。旧Javaルールは削除しません。

ローカルのモック検証は`pwsh -NoProfile -File ./60_tool/control-deploy/tests/bedrock-firewall.tests.ps1`です。実ファイアウォールは変更しません。

### MC-01のNetherNet TCP許可を修復する

GeyserをNetherNetへ更新した後、TCP 19132のsignalingが到達しない場合は、MC-01の管理者PowerShellで`Repair-ControlNetherNetFirewall.ps1`を使用します。MC-01のローカルIPが`192.168.0.89`であること、TCP ListenとUDP 19132が同じJava実行ファイル・同じPIDに所有され、`0.0.0.0`または`192.168.0.89`で待ち受けていることを確認します。TCPがIPv6ワイルドカード`::`で表示される場合は、同じPCからIPv4の`192.168.0.89:19132`へ2秒以内に接続できた場合に限り受け入れます。この確認は外部からの到達性を保証しません。UDPも同じJava・PIDの`::`待受を受け入れますが、UDPのIPv4到達性は検証せず、結果JSONの`udpIPv4Verification`を`NotVerified`とします。このスクリプトはTCP許可だけを追加し、UDP待受や既存UDP許可を変更しません。loopback・別IPへの待受や、IPv4接続できないTCPのIPv6限定待受は拒否します。失敗時のエラーには取得済みのTCP/UDP待受アドレス・PIDを含めます。Java実行ファイルは既存UDP修復と同じ既定パスで、必要なら`-Java 'C:\absolute\path\to\java.exe'`を指定します。

```powershell
pwsh -NoProfile -File ./60_tool/control-deploy/Repair-ControlNetherNetFirewall.ps1 -WhatIf
pwsh -NoProfile -File ./60_tool/control-deploy/Repair-ControlNetherNetFirewall.ps1 -Confirm:$false
```

同じJava実行ファイルに対する既存Public受信UDP 19132許可をルールのグループ名に依存せず検索し、1件に確定してからPublic受信TCP 19132ルールを1件だけ追加します。既存UDPルールのaddress、interface、service、user、authentication等に制限がある場合やEdgeTraversalがBlock以外の場合は、範囲を広げず停止します。既存ルールやFirewall profile、サービスは変更しません。正しい管理対象TCPルールがあれば再実行時は追加しません。

結果は`status`（`WhatIf`、`Created`、`Existing`）、`ruleName`、`Java`、`listenerPID`、`localIP`、`ruleScope`（根拠のUDPルール名を含む）、`routerVerification: NotVerified`、`routerNotice`を含む1行JSONです。**ルーターでもTCP 19132を`192.168.0.89:19132`へ転送し、既存UDP 19132転送を維持する必要があります。ルーターの現在設定は未検証です。** このスクリプトはルーターを確認・変更しません。作成後の検証失敗時は、この実行で作成したと確認できるルールだけを削除します。作成処理が識別子を返す前に失敗した場合は、所有を断定して削除せずエラーを返します。手動で戻す場合は成功JSONの`ruleName`を確認し、`Remove-NetFirewallRule -Name '<成功時のruleName>' -PolicyStore PersistentStore`を使用します。

実サーバーでの入口として、PS1と`Repair-ControlNetherNetFirewall.bat`を同じディレクトリへ置き、BATを管理者として実行できます。BATはMC-01に導入済みの`E:\mc-01\ControlDeployment\Automation-20261030\Runtime\PowerShell\pwsh.exe`で隣のPS1を呼び、同じディレクトリの`result.json`と`error.txt`へ標準出力・標準エラーを保存して画面にも表示し、一時停止します。自動昇格やサービスの再起動は行いません。

モック検証は`pwsh -NoProfile -File ./60_tool/control-deploy/tests/nethernet-firewall.tests.ps1`です。実ファイアウォールは変更しません。

P1の基本運転だけを先に有効化する手順は[基本運転への切り替え](BASIC-OPERATIONS.md)を参照してください。旧ToolのLegacy実行を制限したまま、ゲームPCの停止確認・marker・Runner設定、最後にWeb設定の順で切り替えます。

## P2の有効化

新しいpublish成果物はphase=P2です。P1の導入markerと共通gateを維持し、Web/Agent/Runner/CLIをP2版へ更新します。Webは`Control.EnableFileControl`、Agent/Runnerは`enableFileControl`をtrueにして再起動します。初期値はfalseです。Runner更新は従来と同じく全Server停止・Session/holdなしを必須とします。

バックアップ保存先はAgent/RunnerのlocalResources.rootsへ同じGUIDで追加します。例: `{ "id":"44444444-4444-4444-4444-444444444444", "displayName":"Backup NAS", "path":"\\\\nas\\backups", "backupOnly":true }`。UNCはバックアップ専用rootだけで許可し、Server root/実行ファイルには指定できません。Agentサービスの実行アカウントにNASの認証/読み書き権限を別途設定します。WebからNAS資格情報を入力・保存しません。設定後は画面のHost resourcesとP2能力、バックアップ設定の適用ACKを確認してください。

変更機能を有効化する設定やmarker発行は実サーバー導入時に明示して実行します。開発テストは一時fixtureだけを使用します。

## P3の有効化

Web/Agent/Runner/CLIを`phase=P3`のパッケージへ更新します。Runner更新の全Server停止・Session/holdなし条件と、P1の管理運用marker/共通gateを引き続き満たしてください。P3のSchedule変更操作はWebの`Control:EnableSchedules`とAgentの`enableSchedules`をともに`true`へ明示変更した後に有効になります。どちらも初期値はfalseです。Agentが`P3.Schedules`を広告し、Webの希望inventory版とAgent適用ACK版が一致することを確認してから予定を有効化します。P2のバックアップstepを使う場合はWeb/Agent/RunnerのEnableFileControlと`P2.Backups`能力も必要です。

Proxyの日次更新で「ViaVersion も同時に更新する」を使用する場合は、対応版Web/Agent/Runnerへ更新し、`P3.ViaVersionUpdate`能力を確認します。P1.Velocity・P2.Files・P3.GeyserUpdateとファイル操作有効化も必要です。新規フォームはON、既存予約の省略はfalseです。未対応Hostではチェックを外して従来の予約を保存できます。導入済みViaVersionだけを公式stable JARとdigest・最低Javaで停止前に検証し、Velocity→ViaVersion→Geyser→DisplayEntityの順で準備・交換します。全準備後に一度だけ停止し、旧JARのbackupを保持します。途中失敗ではholdを維持して点検し、起動を繰り返さないでください。

Once/Interval/CronとRun Nowは`/Servers/{id}/Schedules`で設定・確認します。Run Nowの通信結果が不明なら、表示された要求IDと照会先を使い、別のIDで再実行しません。`/Servers/{id}/Metrics`はHost/プロセスと取得できたJava人数を表示し、欠測を0として扱いません。`/Notifications`のWebhook送信先はownerのログイン後に登録します。URL全体が保護され画面へ再表示されないため、編集時に秘密を保持するならURL欄を空にします。送信先は公開IPへ到達するHTTPS/443に限定され、外部Webhookへの配信は初回送信と最大3回の再試行です。

P3の検証は一時DB・ローカルfixture・fake HTTP handlerを使用します。実Minecraft、実NAS、外部Webhookへの送信やWindowsサービス登録は開発テストに含めません。
