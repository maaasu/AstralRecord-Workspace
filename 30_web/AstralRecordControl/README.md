# AstralRecordControl

Minecraft本体・既存AstralRecordWeb/API・SQL Serverが停止していてもログインできる独立管理系です。.NET 10 / Windows x64を使用します。設計の正本は [AstralRecordControl設計書](../../00_docs/30_WEB設計書/AstralRecordControl/README.md) です。

## 実装済みの機能（P0・P1）

- 単一ownerのパスワード＋TOTP、使い捨て回復コード、8時間の固定セッション、全セッション失効、停止中のローカルCLIによるowner作成/リセット。
- PC・Server・Environment台帳、Agentトークンの一度限り表示/失効、希望設定版と適用版の区別。
- 各PCからHTTPS/WSSで接続するAgent。Hello/Reconcile/heartbeat、再接続、設定snapshotのhash検証と同期、イベントACK、storeId/巻戻り検出。
- 同一PCのRunnerへのACL付きnamed pipe。設定の原子的適用、snapshot、永続hold/fence。

- Javaプロセスの起動・通常停止・再起動・理由付きKill・1行コンソール、Sessionと実行台帳、標準出力/標準エラーの永続ログとcursor取得。
- 全資源の中央予約、各Hostの永続hold/fence、Agentのdurable Inbox/Outbox、CLIと01/02/03/16/17の共通gate。
- 停止タイムアウトの要確認保持、同じ予約内の救済Kill、Runner再起動時の残存プロセス照合とローカル管理者の緊急復旧。

変更操作は既定で無効です。`Control:EnableProcessControl=true`、Runnerの`EnableProcessControl=true`に加え、対象Hostすべての`P1.Operations`/`P1.LegacyGate` capabilityが必要です。Runner/Agentの本番起動は固定`%ProgramData%\AstralRecordControl\managed-operations.enabled`を確認します。markerは既存ツールの共通gate導入を検査したinstallerの`-EnableManagedOperations`で作成します。詳しくは[導入手順](../../60_tool/control-deploy/README.md)と[既存ツールgate](../../60_tool/control-gate/README.md)を参照してください。

P1の対象はJavaの独立した基本運転です。依存チェーンの自動起動/停止（`dependsOnServerIds`）、`AstralReady`依存判定、Bedrock/Velocity、ファイル編集・バックアップ・予約実行・更新Adapterは後続段階です。非対応設定や操作を開始済みとして扱いません。

## プロジェクト

| 配置 | 責務 |
|---|---|
| `src/AstralRecordControl.Contracts` | protocol v1、厳密JSON、inventory/operation/gate DTOと検証 |
| `src/AstralRecordControl.Web` | owner認証/CLI資格情報、台帳、予約と操作、画面とログ取得 |
| `src/AstralRecordControl.Cli` | 停止中owner管理、15分認証、gate、ローカルRunner点検/復旧 |
| `src/AstralRecordControl.Agent` | outbound接続、Inbox/Outbox、Runner IPC、設定/操作照合 |
| `src/AstralRecordControl.Runner` | ローカル設定/hold/実行台帳、プロセス所有、ログ、named pipe |
| `tests` | 一時SQLite/HTTP/IPCを使う境界・状態・永続化検証 |
| `../../60_tool/control-deploy` | Windows向けpublish / 登録 / 配布前検査 / Web・Agent・停止確認後Runner更新 |

DevのHostは開発PC、チャンネルのHostはMC用PCです。どちらもAgentとRunnerを各1サービス置きます。管理Webはその2台と別PCでも構いません。各Host内のServer IDを別々に登録し、共有API/masterの境界はEnvironmentで明示します。

## ビルドとテスト

.NET 10 SDKを用意し、このディレクトリで実行します。DBや実サービスは一時fixtureを除いて作成しません。

```powershell
dotnet test AstralRecordControl.slnx -c Release
pwsh -NoProfile -File ../../60_tool/control-deploy/tests/deployment-preflight.ps1
pwsh -NoProfile -File ../../60_tool/control-gate/tests/gate-entry.tests.ps1
```

実際の登録/設定は [導入手順](../../60_tool/control-deploy/README.md) を使用します。テスト用`Testing`環境は一時データルートを明示した自動テスト専用です。通常導入で指定しません。

## 認証と経路

管理者は`/Login`でパスワード認証後、初回は`/Mfa/Enroll`で認証アプリを登録、以後は`/Mfa/Verify`でTOTPまたは回復コードを確認します。MFA前のCookieで台帳へ入れません。`/Security`で再認証と全セッション失効、`/Logout`で現在のセッションを失効します。回復コードは登録時の表示を安全に保管します。

台帳画面は`/`、`/Hosts`、`/Servers`です。HTTPSを必須とし、`Control:PublicOrigin`はAgentからも到達できる固定originです。TLSはKestrelで終端します。転送ヘッダを無条件に信頼しません。VPN経由を標準とし、Agent/Runnerの待受ポートを外部公開しません。

| 経路 | 認証 / 用途 |
|---|---|
| `/api/control/v1/overview`、`hosts`、`environments`、`servers` | MFA済みOwner Cookie。更新はCSRF、作成はIdempotency-Key + If-None-Match、更新はIf-Match |
| `/api/control/v1/hosts/{id}/credentials` | 再認証済みOwner。発行はPOST、失効はDELETE `/{credentialId}` |
| `/hubs/control-ui/v1` | Owner Cookie、セッション失効で切断 |
| `/hubs/control-agent/v1` | Agent専用Bearer、Hello→Reconcile→heartbeat |
| `/api/agent/v1/inventory` | Agent専用Bearer + X-Control-Connection-Epoch |
| `/health/live` | 機密を含まない生存確認。通常環境ではHTTPSのみ |
| `/api/control/v1/servers/{id}/operations`、`operations/{id}`、`servers/{id}/logs` | Owner Cookie。変更はCSRF、操作作成はIdempotency-Key + If-None-Match |
| `/api/control/v1/reservations`、`reservations/{id}/validate`、`release`、`gate/catalog` | Owner Cookieまたは15分CLI Bearer。Cookie変更はCSRF |
| `/api/control/v1/auth/cli-token`、`cli-token/revoke` | パスワード＋TOTP/回復コードで発行、CLI Bearerで自己失効 |

JSONはcamelCase/定義済み文字列enumで、未知フィールド、重複フィールド、不正な型を拒否します。メッセージ上限1MiB、inventory JSON上限512KiBです。inventoryは保存済みの文字列そのもののSHA-256を検証し、Runner保存→Agent保存→Web ACKの順で適用します。

Agent tokenをOwner Cookieとして使うことも、その逆もできません。tokenは256bitのランダム値で、Webにはhashのみ保存します。Agent上ではLocalMachine DPAPIとAgent専用ACLで保存し、URL/引数/通常ログへ出力しません。

## 保存と復旧

既定は`%ProgramData%\AstralRecordControl\Web|Agent|Runner`です。各サービスのSQLiteはローカルディスク/WAL/busy timeout 5秒で、別サービスのDBを直接開きません。各プロセスは排他lockファイルを保持します。DB・Data Protection keys・credentials・configは配布パッケージに含めません。

Web/Agent/Runnerの現行schemaは2です。schema1からはSQLite backupを作成してtransaction内で移行します。未知版や版情報のない既存schemaを持つDBは起動を拒否し、自動破棄/自動採用しません。Agent DBのstoreId変更やACKより古い状態への巻戻りは要復旧です。トークンの再発行だけで台帳のstoreIdを変更しません。同じHostへの自動再登録は提供せず、旧Hostを無効化して新しいHost IDへ明示登録し直します。

DPAPI鍵/トークンを別PCへコピーするだけでは復旧できません。Webは停止中CLIでowner/MFAを再設定し、Agentは新規Hostとして再登録します。既存ServerのPC間移送は後続段階です。

## 操作・gate・復旧

サーバー台帳から運転画面へ進みます。操作はconfigRevision・stateRevision・Sessionを照合し、全資源予約→全Host準備→Runner受付の順で実行します。HTTP受付は202で、結果は操作IDから確認します。同じIdempotency-Key/同じ内容は元の操作を返し、異なる内容は409です。Console成功はstdinへの書込完了を意味し、Minecraft内のコマンド成功判定ではありません。

Runnerはshellを介さず登録済み実行ファイルをArgumentListで起動します。WebやAgentが停止してもプロセスとログ読取を継続します。Runnerが再起動した場合、生きている旧SessionはUnmanagedです。PID・生成時刻・実行ファイルfingerprintが一致しても標準入出力を再取得できないため、自動再採用しません。停止タイムアウトでは自動Killせず、同じWorkflowの要確認状態とholdを保持します。

CLIは`login --server-url https://control.example:5443`で対話認証し、15分の専用資格情報を現在のWindowsユーザーのDPAPI/限定ACLへ保存します。gateの各コマンドには`--expected-server-url`を必須とし、設定先とsessionのoriginを照合します。leaseファイルも限定ACLです。完全な実行例とbindingは[共通gate手順](../../60_tool/control-gate/README.md)を参照してください。

```powershell
AstralRecordControl.Cli.exe runner snapshot
AstralRecordControl.Cli.exe runner check-update
AstralRecordControl.Cli.exe runner inspect --server-id <GUID>
# OS管理者で、表示されたSessionと実プロセスを確認した後に実行
AstralRecordControl.Cli.exe runner recover --server-id <GUID> --session-id <GUID> --action KillAfterInspection --reason "残存プロセスを確認済み"
```

緊急復旧はローカルOS管理者のみ許可します。対象を確認できない場合は停止扱いへ変更しません。Console結果不明だけの場合、同じRunnerが所有するSessionは`AbortAfterInspection`で入力を再送せずFailed確定し稼働を維持できます。Runnerのholdと中央予約は別々に残るため、ローカル復旧後もWebの保守履歴で照合して解放します。ログはServer/Sessionごとに100MiBで分割し、1行64KiBを超える出力は`truncated`付きに分割します。30分ごとに`logRetentionDays`を過ぎた閉じたsegmentを削除し、稼働中の最新segmentを保持します。古いcursorや削除途中の中断には`gap`を返します。
