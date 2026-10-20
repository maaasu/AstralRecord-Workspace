# 新環境への切替

この作業はAPIのFilebase参照先、API証明書、Controlの資源対応、01系の実行環境を一組として切り替えます。リポジトリのFilebaseを正とすることはユーザー確認済みです。証明書検証の省略や管理markerの削除は行いません。

## 現在の確認結果

2026-10-20の実管理画面で、共有Environment `astralrecord-shared`（`f97e8617-b7df-4980-adbd-36918949bf79`）を登録しました。停止中・SessionなしのDevを対応付け、希望config3 / 適用3を確認しました。現在の登録はDev・Lobby・Proxyの3台です。Channel1/Channel2/BuildとAPP-01の配布資源は未登録で、MC-01は最終報告21:35 JSTの未接続状態でした。

| 項目 | 実台帳で確認した値 |
|---|---|
| Control origin | `https://control.astralrecord.com` |
| DEV-01 Host | `0604b62f-c10b-46e8-a0cf-cca70aab6b5f` |
| Dev Server | `bf33c4ed-8b58-4c3c-99b7-6fcd08360697` |
| DEV管理root | `6a3ef4c4-386a-42ef-ac71-32b917de7023` → `C:\server\AstralRecord-Dev` |
| DEV Java | `C:\Program Files\AstralRecordControl\Java\jdk-25.0.4.1+1\bin\java.exe` |
| MC-01 Host | `1a61b33d-27fc-4a1a-8a68-4d61953f7229` |
| MC管理root | `5f051f6a-38dd-4bbb-a4ab-4dd0e2d80a7d` → `E:\mc-01`（最終報告値） |
| MC Java | `C:\Program Files\Java\jdk-21.0.10\bin\java.exe`（最終報告値） |

旧`astral-servers.plan.json`のServer GUIDは現在の実台帳と異なるため、実設定へ流用しません。登録済みサーバーを同名で作り直さず、現在のIDと適用ACKを確認して設定します。

## 管理者による情報収集

現在のCodex実行ユーザーはDEV-01の標準ユーザーで、Administratorsには属しません。APP-01/MC-01のWinRMは利用できず、リモートSCMはアクセス拒否です。IIS・機械証明書・サービス・管理markerの更新には各PCの管理者実行が必要です。

`Get-EnvironmentFacts.ps1`と`EnvironmentFacts.psm1`を同じフォルダに置き、各PCの管理者PowerShell 7から実行します。読み取り専用の収集処理で、設定やサービスは変更しません。秘密値・接続文字列・秘密鍵・Java引数はレポートへ出しません。`-CliPath`には現行Control CLIの実パスを指定すると、認証秘密を使わずローカルRunnerのsnapshotも収集できます。

```powershell
pwsh -NoProfile -File .\Get-EnvironmentFacts.ps1 -CliPath '<現行CLIの絶対パス>'
```

既定の出力先:

- APP-01: `C:\app-01\ControlDeployment\EnvironmentCutover\APP-01-environment-facts.json`
- MC-01: `E:\mc-01\ControlDeployment\EnvironmentCutover\MC-01-environment-facts.json`
- DEV-01: `C:\AstralRecord-Worktrees\logs\EnvironmentCutover\DEV-01-environment-facts.json`

部分的に収集できない項目は`errorType`だけを記録し、終了コード1を返します。エラーを無視して切替を始めません。既存のCLI候補はDEV-01の`C:\AstralRecord-Worktrees\.tools\control-password-simplification\Release\Cli\AstralRecordControl.Cli.exe`です。APP/MCでは、現地に配置した現行パッケージ内のCLIを使用します。

## 反映の順序

1. MC-01の接続を復旧し、各RPGサーバー・自動書込・入場を止めます。未接続を停止とは判断しません。実Runner snapshotで停止・Session/holdなしを確認します。
2. APP-01のAPI/Web/Filebase用Host/root、DEV-01の`C:\AstralRecord-Workspace\40_filebase`用rootを正規登録します。APP-01にMinecraft定義は作りません。Channel1/Channel2/Buildを実root・Java・runtime IDへ対応付け、共有Environmentに登録します。ProxyのManaged配布は有効化しません。
3. API用証明書は接続名`app-01`に一致するSANを持つものをAPP-01のIIS HTTPS 444へ設定します。既存IP URLを維持する場合はSANにIP `192.168.0.88`も必要です。発行CAをWindowsと実使用Javaの信頼へ導入し、通常の証明書検証でhealthが成功することを確認します。既存Control公開証明書の流用は名前が一致しません。
4. APP-01ローカルのアクセス制限された非公開フォルダへAPI設定原本を退避します。`AstralRecordApiPool`を計画停止し、新Filebaseのハッシュ・IIS identityの権限・設定の環境変数上書きを確認して、`FileDatabase:RootPath`を`C:\app-01\workspace\40_filebase`へ変更します。`IOptions`とsingletonが起動時にパスを固定するため、計画再起動が必要です。`AutoSeedOnStartup=true`なので再起動はマスタ更新も伴います。
5. 01/02/03/16/17のlocal設定へ、実台帳のEnvironment/Host/root/Server GUIDを使った完全な`controlGate`を設定します。配布元・配布先をすべて含め、適用ACKと正規のCLI対話ログインを確認します。
6. 修正済みの`dev-update.ps1`を常設Toolへ導入します。収集した`managedMarker`とサービス状態を確認し、既存markerとTool rootが一致するPCでは`Enable-ManagedOperations.ps1 -Renew -CliPath <現行CLI>`で更新します。markerがないPCに`-Renew`は使いません。新規Runner登録は`Install-Control.ps1 -Component Runner -EnableManagedOperations`、既存Runnerへの初回導入は`Enable-ManagedOperations.ps1`（`-Renew`なし）という[正規導入手順](../control-deploy/README.md)へ分岐します。停止・Session/holdなし・管理者権限など各手順の条件を満たし、旧markerは正規処理で保全します。
7. PluginのTLS検証を有効にし、信頼導入済みJavaで起動します。APIのFilebase読取先、seed結果、Devの起動readyとパッチ公開を確認後、Channelへ広げます。スキルツリー一括移行は別の明示操作です。

この順序の準備だけで実反映完了とは扱いません。実機の実行記録と接続・適用結果を残します。
