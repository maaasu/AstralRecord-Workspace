# 新環境への切替

この作業はAPIのFilebase参照先、API証明書、Controlの資源対応、01系の実行環境を一組として切り替えます。リポジトリのFilebaseを正とすることはユーザー確認済みです。証明書検証の省略や管理markerの削除は行いません。

## 現在の確認結果

2026-10-20の実管理画面で、共有Environment `astralrecord-shared`（`f97e8617-b7df-4980-adbd-36918949bf79`）を登録しました。Devを対応付け、ユーザーの登録許可を受けてChannel1/Channel2/Buildも実配置へ登録しました。Dev・Build・Channel1・Channel2・Lobby・Proxyの6件すべてで設定適用を確認済みです。23:08 JSTにAPP-01/DEV-01/MC-01の接続中と、APP-01の希望/適用版1/1、root報告を確認しました。

2026-10-22にAPP-01／MC-01のRunner・Agentを起動して、3台の接続を管理画面で確認しました。Dev・Build・Channel1・Channel2はStopped、Sessionなしです。DEV-01の追加workspace rootは同日17:23 JSTに適用成功を確認済みです。以下の値と実行記録は確認時点の結果として扱い、運用時には現在状態を再確認します。

同日17:57 JSTにAPI証明書の切替が成功しました。`APP-01-api-tls-20261022T085715579Z-d6b90d9e88e9498c989afe7363208ebb.json`は`BoundAndVerified`、新leafは`925424304CBDDF2FB4D40FCB00D0BF708DC437B3`です。DEV-01は17:59、MC-01は17:58 JSTに`TrustVerified`となり、Windowsと登録済みJavaでDNS名・IP双方の通常TLS検証が成功しています。APP-01の初回markerとTools更新、CLIログインも完了しました。19:12 JSTの再確認ではFilebase全2,185件、IIS権限、認証付きTLSの事前確認が成功しています。

同日19:12 JSTのApplyは、予約取得後のcontextにCLI標準出力が混入する不具合で、API停止前に失敗しました。API設定は旧rootのままです。共通gateはinspect以外のCLI標準出力を戻り値から除外し、新規取得・継承・再開・解放の回帰検査を追加しました。実行済みの予約は保護leaseとControlを照合し、設定原本との一致と旧APIの正常稼働を確認してから失敗として解放し、新しい事前確認に基づいて切替を行います。切替完了までは入場・自動更新の停止を続けます。

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
| APP-01 Host | `4ff81871-2069-4a9a-9c98-43421de3b7e8` |
| Build Server | `4c0ca2c4-a943-4ad2-aaf4-cef78f3d3824` |
| Channel1 Server | `c242cc49-17d2-4543-bb15-aa67eb3e8be4` |
| Channel2 Server | `108487e9-51e4-4166-81b1-f2dba4adf80b` |
| DEV追加root（適用済み） | `f9c06531-2e7e-40f2-b03a-ee2ee9ebe5aa` → `C:\AstralRecord-Workspace` |
| APP追加root（報告済み） | `99a1c3c4-dafc-4fc8-9a8b-b3aad1c1ecee` → `C:\app-01` |

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

部分的に収集できない項目は`errorType`だけを記録し、終了コード1を返します。エラーを無視して切替を始めません。DEV-01の現行CLIは`C:\AstralRecord-Worktrees\.tools\managed-tool-setup\Cli\AstralRecordControl.Cli.exe`です。APP-01の今回の切替では`C:\app-01\ControlDeployment\Cutover-20261022\CliVisible\AstralRecordControl.Cli.exe`を使用します。管理者から見えないユーザープロファイル内の実行ファイルをlocal設定へ指定しません。

## 反映の順序

1. MC-01の接続を復旧し、各RPGサーバー・自動書込・入場を止めます。未接続を停止とは判断しません。実Runner snapshotで停止・Session/holdなしを確認します。
2. APP-01のAPI/Web/Filebase用Host/root、DEV-01の`C:\AstralRecord-Workspace\40_filebase`用rootを正規登録します。APP-01にMinecraft定義は作りません。Channel1/Channel2/Buildを実root・Java・runtime IDへ対応付け、共有Environmentに登録します。ProxyのManaged配布は有効化しません。
3. API用証明書は接続名`app-01`に一致するSANを持つものをAPP-01のIIS HTTPS 444へ設定します。既存IP URLを維持する場合はSANにIP `192.168.0.88`も必要です。発行CAをWindowsと実使用Javaの信頼へ導入し、通常の証明書検証でhealthが成功することを確認します。既存Control公開証明書の流用は名前が一致しません。
4. 01/02/03/16/17のlocal設定へ、実台帳のEnvironment/Host/root/Server GUIDを使った完全な`controlGate`を設定します。配布元・配布先をすべて含め、適用ACKと初回CLIログインを確認します。更新版CLIはログインを保持し、手動のトークン発行・コピーを通常手順に含めません。
5. 修正済みToolを、既存markerと同じTool rootへ導入します。通常のスクリプト更新にmarkerの再発行は不要です。APP-01などmarker未導入PCでは、既存Runnerへの初回導入として`Enable-ManagedOperations.ps1`を`-Renew`なしで一度実行します。Agent再接続後に能力報告を確認します。
6. APP-01ローカルへAPI設定原本を退避します。`AstralRecordApiPool`を計画停止し、新Filebaseのハッシュ・IIS identityの権限・設定の環境変数上書きを確認して、`FileDatabase:RootPath`を`C:\app-01\workspace\40_filebase`へ変更します。`IOptions`とsingletonが起動時にパスを固定するため、計画再起動が必要です。`AutoSeedOnStartup=true`なので再起動はマスタ更新も伴います。
7. PluginのTLS検証を有効にし、信頼導入済みJavaで起動します。APIのFilebase読取先、seed結果、Devの起動readyとパッチ公開を確認後、Channelへ広げます。スキルツリー一括移行は別の明示操作です。

この順序の準備だけで実反映完了とは扱いません。実機の実行記録と接続・適用結果を残します。

## API証明書の準備と切替

`Initialize-ApiTls.ps1 -Phase Prepare`はAPP-01で公開CAと`app-01`/`192.168.0.88`に一致するAPI証明書を準備します。2026-10-20の実行は成功し、秘密鍵はAPP-01内に保持しています。公開CERだけをDEV/MCへ配布します。CAのSHA-256照合はスクリプト内部で行い、利用者に値の手入力を求めません。

DEV/MCは`Install-ApiTrust.ps1 -Phase Install`でWindowsと登録済みJavaの信頼を自動更新します。標準JDKの公開CAストアを使用し、証明書への確認質問やパスワード入力を求めません。独自パスワードで管理されたストアでは変更せず停止します。両PCの`TrustInstalled`成功後、APP-01で`-Phase Bind -ExpectedCurrentThumbprint <現在の旧証明書>`を実行します。続いて両PCで`-Phase Verify`を実行し、Windowsと実Javaの両URLで通常TLSが成功したことを確認します。Pluginの検証省略設定は、この確認後に停止中の4台で解除します。

HTTP.sysのJSON取得中だけコンソール出力をUTF-8へ合わせ、終了・失敗時には元の文字コードへ戻します。日本語WindowsのCP932によるJSON破損を避け、標準エラー・終了コード・JSONの内容を検証します。表示メッセージの文字化けを理由にbinding情報を推測して切り替えることはありません。

証明書の更新・復元は実機helpで対応している`ipport`・`certhash`・`appid`等の引数を使います。今回確認した既定policy以外の値や未知の項目がある場合は変更前に停止します。更新コマンドの出力を保存し、終了コード0だけでは成功と判断せず、再取得したbinding全体と通常TLSの接続結果を確認します。

APP-01では既定policyを明示する旧コマンドが引数エラーになったため、更新には証明書選択に必要な7引数だけを使用します。17:54 JSTの実機実行では更新後のbinding全体の一致と、後続のhealthタイムアウト後の旧binding復元が確認できました。初回IIS起動とstartup seedが10秒を超えたため、通常TLS healthの応答待ちを120秒へ延長しています。レポートには公開値だけの`nativeUpdateArguments`も記録します。

CAは10年、API証明書は1年で、今回のAPI証明書は2027-10-20 UTCまでです。期限前にAPP-01で証明書を更新し、CAを失った場合は新CAへの入替が必要です。自動更新タスクはこの作業では作成していません。

## Filebase切替スクリプト

`New-FilebaseCutoverManifest.ps1`はリポジトリのYAML/JSONをSHA-256で固定します。`99.work`は配布と同じく除外します。生成したmanifest自体のSHA-256をAPP-01の呼出しへ指定します。

`Set-ApiFilebaseRoot.ps1`はAPP-01管理者PowerShell 7専用です。既定の`-Phase Preflight`では、新配置のハッシュ、IIS pool identityとFilebaseの読書き権限、設定上書き、通常TLSとAPIキー認証を確認します。`api-filebase-cutover.example.json`は今回の実台帳IDを記載したControl設定例で、APP追加rootとmarkerの正規導入・全Hostの適用ACK確認後に使用します。

IISの`manualGroupMembership`は`ProcessModel.GetAttributeValue`から取得し、標準ApplicationPoolIdentityかつBooleanのfalseの場合だけ共通グループのアクセス権を確認します。取得不能・不明値では進みません。失敗レポートには原因のスクリプト名・行番号・PowerShellの呼出し位置を残し、HTTP本文・設定値・APIキー・例外メッセージは出力しません。

`-Phase Apply -AdmissionClosed -AutomaticWritersStopped`は、利用者入場と自動書込を運用者が止めた後だけ指定します。全4RPGのStoppedを正規Control予約で再確認してからAPI poolを停止し、秘密を含む設定原本を`%ProgramData%\AstralRecordEnvironmentCutover`のSYSTEM/Administrators専用領域へ退避します。変更する設定値は`FileDatabase.RootPath`だけです。再起動後は新rootからの起動時seed、成功したseed run ID、MasterData health、同じFilebaseハッシュを確認します。

起動後の検証失敗ではAPI poolを停止してControl予約を保持します。旧設定を自動で戻して再起動すると旧マスタのseedを再実行するため、DB状態の確認前には行いません。API検証成功後にControl解放の応答・記録だけが失敗した場合は、正常なAPIを維持して`reservationReleaseUncertain`を記録し、Controlの実予約状態を確認します。レポートは共有側の`ControlDeployment\EnvironmentCutover`へ秘密を含めず保存します。

## 共通APIを使う01/03

01/03のManaged local設定に`controlGate.sharedApiRuntimeServerIds`として`Dev`、`Build`、`Channel1`、`Channel2`を登録します。API/Filebaseを更新するときは全4台の停止を予約で確認し、起動段階で全4台を起動してからDevのパッチを公開します。`devWorkflow.migration.serverIds`は`Dev`のまま維持し、runtime ready確認とパッチ公開対象を増やしません。Channel側はControlのプロセス稼働確認に加えて、運用者がPluginの起動成功を確認します。

02のPluginOnlyはDevだけを予約します。共通API構成の03では`-ServerAlreadyRunning`を拒否し、全対象を止める更新を使用します。10のPreflightOnlyは変更やControl予約を行いません。
