# 基本運転への切り替え

接続済みのAgent/Runnerを、WebからJavaサーバーを起動・通常停止・再起動できる状態へ切り替える。一度だけ各PCの管理者PowerShell 7で実行する。パスワードやAgentトークンの再入力、サーバーの再登録は不要。

## 対象

- DEV-01: Dev。Javaは`C:\Program Files\AstralRecordControl\Java\jdk-21.0.12.1+1\bin\java.exe`。
- MC-01: Lobby / Channel1 / Channel2。Javaは`C:\Program Files\Java\jdk-21.0.10\bin\java.exe`。
- Velocityは台帳登録のみ。現行P1では起動・停止できない。旧Craftyで起動したプロセスもRunnerへ引き継げない。
- P2のファイル操作・バックアップ、P3の予定実行は今回有効化しない。

## ゲームPC

PC台帳でDEV-01/MC-01が接続中、希望設定版と適用版が一致していること。各サーバーの引数は実際のJAR名を含む必要がある。自動起動とクラッシュ時の自動再起動は無効にし、最初は手動起動する。

CraftyやBATから起動しているMinecraftは、そのコンソールで`stop`して終了を待つ。Velocityは`shutdown`。有効化スクリプトはJavaプロセスが残っている場合に拒否し、強制終了しない。無関係なJavaアプリも終了してから実行する。

`Enable-ControlGameOperations.ps1`へ常設の`60_tool`、Control CLIの絶対パス、既存レポート保存先を指定する。既存サービス・設定の対応、外部Javaプロセス、Runner内の全サーバー停止・Session/holdなしを検査してから切り替える。

```powershell
& '.\Enable-ControlGameOperations.ps1' `
  -ToolRoot 'C:\AstralRecord-Workspace\60_tool' `
  -CliPath 'C:\ControlPackages\Cli\AstralRecordControl.Cli.exe' `
  -ReportDirectory 'C:\ControlDeployment'
```

共通gateの7ファイルを検証し、正規の`Enable-ManagedOperations.ps1`でmarkerを発行する。Runnerの`enableProcessControl`を有効にしてRunner→Agentの順で起動する。サービスはOS起動時にも起動するよう設定する。Minecraft自身の自動起動とは別の設定である。

`<PC名>-operations-enabled.json`が最新結果。失敗時も最新結果を上書きするため、古い成功結果を流用しない。両PCの成功レポートをAPP-01へコピーする。配布済みPC専用CMDではこのコピーも行う。再実行は全サーバー停止中に行う。

## APP-01

両PCの成功レポートと台帳のHost IDを指定して実行する。24時間以内の成功、Host ID、適用済み設定、稼働サービスを検査する。レポートは実行時の記録なので、最終的な接続状態・適用版は画面でも確認する。

```powershell
& '.\Enable-ControlWebOperations.ps1' `
  -HostReports @('C:\ControlDeployment\DEV-01-operations-enabled.json','C:\ControlDeployment\MC-01-operations-enabled.json') `
  -ExpectedHosts @{'DEV-01'='<実Host ID>'; 'MC-01'='<実Host ID>'} `
  -ReportDirectory 'C:\ControlDeployment'
```

Webの`Control.EnableProcessControl`だけを有効化し、認証・HTTPS・DB・鍵を維持する。設定のバックアップを非公開のWebデータフォルダに残し、Webを再起動してローカルHTTPSを検査する。失敗時はWeb設定の復旧を試みる。ゲームのRunnerやJavaは停止しない。

## 最初の実運転確認

1. Controlを再読込し、DEV-01が接続中でDevの設定適用版が一致していることを確認する。
2. サーバー台帳→Dev→運転で「起動」を1回押す。
3. 履歴とコンソールを確認する。Runningはプロセス存在を示すため、Minecraftの`Done`ログまで待つ。
4. 「停止」を押し、保存・終了ログとStoppedへの遷移を確認する。タイムアウト時は強制終了を繰り返さず、履歴のエラーを確認する。
5. Devで確認後、MC-01のLobby、Channel1、Channel2も1台ずつ確認する。

実環境の受入作業とfixtureテストは区別する。実行結果を確認するまで、起動・停止の実機検証済みとは扱わない。

## 既存Tool

marker発行後、01/02/03/16/17のLegacy実行は拒否される。Webの基本運転だけを先行する場合もこの制限を維持する。旧BAT/Craftyとの並行操作を避ける。

Tool再開には[共通gate設定](../control-gate/README.md)が必要。実サーバーGUID・Environment GUIDを使い、配布元/先の全パスを登録rootへ対応付ける。01/03にはAPP-01のAgent/Runner・配布先rootと、DevのFilebase配布元rootの追加が必要。16のBuild定義やProxy配布も現段階の対応範囲を確認する。計画ファイルの仮GUIDで手動登録済み台帳を上書きしない。

## fixture検証

```powershell
pwsh -NoProfile -File ./60_tool/control-deploy/tests/web-operations.fixture.ps1
pwsh -NoProfile -File ./60_tool/control-deploy/tests/deployment-preflight.ps1
```

一時フォルダとmockを使用し、実サービスやMinecraftを操作しない。

失敗レポートの`ExternalJavaRunning`はJavaプロセスの終了待ち、`AutomaticStartConfigured`はサーバー設定の自動起動・クラッシュ再起動の無効化、`EmptyRunnerInventory`は台帳設定の適用を先に確認する。`RecoveryIncomplete`の場合はサービス状態と履歴を確認し、稼働しているRunnerを強制停止しない。
