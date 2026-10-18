# AstralRecordControlの導入

.NET 10 SDK / PowerShell 7 / Windows x64を使用します。`Publish-Control.ps1`はテストと自己完結型publishまで、`Install-Control.ps1`は指定PCでのWindowsサービス登録を行います。リポジトリから実サーバーへの自動配置はしません。

## パッケージ作成

```powershell
pwsh -File ./60_tool/control-deploy/Publish-Control.ps1 -OutputDirectory C:\ControlPackages\p1-001
```

出力は`Web`、`Agent`、`Runner`、`Cli`の4ディレクトリです。バイナリだけを配布し、ProgramData・DB・鍵・ローカル設定を混在させません。現行publish成果物のmanifestは`phase=P3`です。秘密を含むパッケージや許可外のphaseは配布前検査で拒否します。

## サービス登録と保存先

対象PCの管理者PowerShellで、管理PCはWeb、開発PCとMC用PCはRunner/Agentをそれぞれ登録します。登録だけではサービスを開始しません。

```powershell
./60_tool/control-deploy/Install-Control.ps1 -Component Web -PackageDirectory C:\ControlPackages\p1-001\Web
# 各ゲームPCで実行
./60_tool/control-deploy/Install-Control.ps1 -Component Runner -PackageDirectory C:\ControlPackages\p1-001\Runner
./60_tool/control-deploy/Install-Control.ps1 -Component Agent -PackageDirectory C:\ControlPackages\p1-001\Agent
```

既定のbinary配置は`%ProgramFiles%\AstralRecordControl\<Component>\<releaseId>`、データは`%ProgramData%\AstralRecordControl\<Component>`。専用仮想サービスアカウント`NT SERVICE\AstralRecordControl.<Component>`で動作し、通常の管理者権限を付与しません。データは該当サービス/SYSTEM/Administratorsのみ、バイナリはサービスに読取/実行だけを付与します。登録結果に表示されたAgent SIDをRunnerへ設定します。

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

固定URLからログインし、MFA登録と回復コードの保管を完了します。`/Hosts`で`dev-pc`、`mc-pc`を登録し、Hostごとのtokenを発行します。再認証が必要な場合は`/Security`を使用します。パスワードやMFAを失った場合はWeb停止後に`owner reset`を使用します。全セッションと以前のMFA/回復コードが失効します。

## 各PCのAgent/Runner設定

サービス登録済みのAgent実行ファイルで、管理者として次を実行します。URL、Host ID、tokenを対話入力します。tokenはLocalMachine DPAPIで保護し、credentialsのACLをAgent/SYSTEM/Administratorsへ制限します。

```powershell
& '<Agent配置先>\AstralRecordControl.Agent.exe' --configure --data-root "$env:ProgramData\AstralRecordControl\Agent"
```

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

```powershell
./60_tool/control-deploy/Update-Control.ps1 -Component Web -PackageDirectory C:\ControlPackages\p1-002\Web
./60_tool/control-deploy/Update-Control.ps1 -Component Agent -PackageDirectory C:\ControlPackages\p1-002\Agent
```

Web更新はWebだけ、Agent更新はAgentだけを停止します。サービス停止後に排他leaseを取得し、SQLite本体/WAL/SHMを一組として同じdata root内の`updates/<id>`へ退避してから新binaryへ切り替えます。旧binaryと旧サービスパスを残し、設定・鍵・credentialsを上書きしません。失敗時は退避先とサービス状態を確認し、DBを単純に上書きしてACKを巻き戻さないでください。

Runnerの更新はControl CLIによる`runner check-update`が全ServerのStopped、Sessionなし、holdなしを返した場合だけ行います。Unmanagedや状態不明も拒否します。Agentを停止した後に再検査し、Runnerを更新してから元々稼働していたAgentを起動します。`-CliPath`にはP1 CLIの絶対パスを指定します。
更新途中に失敗した場合は、Agent/Runnerの実際のサービス状態と旧binary・snapshotを確認してから復旧します。

```powershell
./60_tool/control-deploy/Update-Control.ps1 -Component Runner -PackageDirectory C:\ControlPackages\p1-002\Runner -CliPath C:\ControlPackages\p1-002\Cli\AstralRecordControl.Cli.exe
```

01/02/03/16/17をControlと共存させるPCでは、P1ツール入口を導入し、新規Runner登録時は`Install-Control.ps1 -Component Runner -EnableManagedOperations`、既存Runner更新後は`Enable-ManagedOperations.ps1`で管理運用markerを作成します。markerにはgateと各PowerShell入口のSHA-256を記録し、SYSTEM/Administratorsだけに変更権限を与えます。marker導入後、旧Legacy入口は停止します。各ツール設定の`controlGate`とCLI対話ログインは[既存ツールgate](../control-gate/README.md)を参照してください。Control Web側の変更操作は導入確認後に明示的に有効化します。

導入済みツールを更新した後は、Runnerの全Serverを停止し、holdがない状態で管理者PowerShellからmarkerを再発行します。`-Renew`は既存markerと同じツールrootだけを対象にし、P1 Runnerパッケージと`enableProcessControl=true`の設定を確認します。CLIによるRunnerの`check-update`を再発行直前にも実行し、旧markerを同じ保護ディレクトリに`.backup`として退避してから原子的に置き換えます。状態不明、Unmanaged、稼働中、holdありは拒否します。旧markerは調査・復旧のため保持してください。

```powershell
./60_tool/control-deploy/Enable-ManagedOperations.ps1 -Renew -CliPath 'C:\Program Files\AstralRecordControl\Cli\AstralRecordControl.Cli.exe'
```

Web/Agent/Runnerの現行schemaは4です。旧schemaからは各サービスのSQLite backupを作成してtransaction内で移行します。未知schemaは起動を拒否します。更新前snapshotとmigration前backupを保持してください。

```powershell
pwsh -NoProfile -File ./60_tool/control-deploy/tests/deployment-preflight.ps1
```

このテストは一時ディレクトリのpackage/config検査だけを実行し、サービス登録、証明書設定、実PCへのデプロイを行いません。

## P2の有効化

新しいpublish成果物はphase=P2です。P1の導入markerと共通gateを維持し、Web/Agent/Runner/CLIをP2版へ更新します。Webは`Control.EnableFileControl`、Agent/Runnerは`enableFileControl`をtrueにして再起動します。初期値はfalseです。Runner更新は従来と同じく全Server停止・Session/holdなしを必須とします。

バックアップ保存先はAgent/RunnerのlocalResources.rootsへ同じGUIDで追加します。例: `{ "id":"44444444-4444-4444-4444-444444444444", "displayName":"Backup NAS", "path":"\\\\nas\\backups", "backupOnly":true }`。UNCはバックアップ専用rootだけで許可し、Server root/実行ファイルには指定できません。Agentサービスの実行アカウントにNASの認証/読み書き権限を別途設定します。WebからNAS資格情報を入力・保存しません。設定後は画面のHost resourcesとP2能力、バックアップ設定の適用ACKを確認してください。

変更機能を有効化する設定やmarker発行は実サーバー導入時に明示して実行します。開発テストは一時fixtureだけを使用します。

## P3の有効化

Web/Agent/Runner/CLIを`phase=P3`のパッケージへ更新します。Runner更新の全Server停止・Session/holdなし条件と、P1の管理運用marker/共通gateを引き続き満たしてください。P3のSchedule変更操作はWebの`Control:EnableSchedules`とAgentの`enableSchedules`をともに`true`へ明示変更した後に有効になります。どちらも初期値はfalseです。Agentが`P3.Schedules`を広告し、Webの希望inventory版とAgent適用ACK版が一致することを確認してから予定を有効化します。P2のバックアップstepを使う場合はWeb/Agent/RunnerのEnableFileControlと`P2.Backups`能力も必要です。

Once/Interval/CronとRun Nowは`/Servers/{id}/Schedules`で設定・確認します。Run Nowの通信結果が不明なら、表示された要求IDと照会先を使い、別のIDで再実行しません。`/Servers/{id}/Metrics`はHost/プロセスと取得できたJava人数を表示し、欠測を0として扱いません。`/Notifications`のWebhook送信先はownerの再認証後に登録します。URL全体が保護され画面へ再表示されないため、編集時に秘密を保持するならURL欄を空にします。送信先は公開IPへ到達するHTTPS/443に限定され、外部Webhookへの配信は初回送信と最大3回の再試行です。

P3の検証は一時DB・ローカルfixture・fake HTTP handlerを使用します。実Minecraft、実NAS、外部Webhookへの送信やWindowsサービス登録は開発テストに含めません。
