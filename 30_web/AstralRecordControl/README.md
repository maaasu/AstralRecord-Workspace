# AstralRecordControl

Minecraft本体・既存AstralRecordWeb/API・SQL Serverが停止していてもログインできる独立管理系です。.NET 10 / Windows x64を使用します。設計の正本は [AstralRecordControl設計書](../../00_docs/30_WEB設計書/AstralRecordControl/README.md) です。

## 実装済みの機能（P0・P1・P2・P3）

- 単一ownerのパスワード認証とログイン保持、明示logout・全セッション失効、停止中のローカルCLIによるowner作成/リセット。
- PC・Server・Environment台帳、初回CLIログインからのAgent自動登録、希望設定版と適用版の区別。手動トークン操作は復旧用の診断内に配置。
- 各PCからHTTPS/WSSで接続するAgent。Hello/Reconcile/heartbeat、再接続、設定snapshotのhash検証と同期、イベントACK、storeId/巻戻り検出。
- WebSocket/socket通信の切断も再接続待ちへ移り、通信例外だけでAgentサービスを終了しません。Runnerのプロセス・予約holdは維持します。storeの巻戻り等で`RecoveryRequired`となった場合は自動再接続を停止します。
- 同一PCのRunnerへのACL付きnamed pipe。設定の原子的適用、snapshot、永続hold/fence。

- Java/Velocityプロセスの起動・通常停止・再起動・理由付きKill・1行コンソール、Sessionと実行台帳、標準出力/標準エラーの永続ログとcursor取得。Velocityは既存のJava実行ファイルを利用でき、停止の標準値は`shutdown`。旧定義の`stop`も実行時に`shutdown`へ補正する。
- 全資源の中央予約、各Hostの永続hold/fence、Agentのdurable Inbox/Outbox、CLIと01/02/03/16/17の共通gate。
- 停止タイムアウトの要確認保持、同じ予約内の救済Kill、Runner再起動時の残存プロセス照合とローカル管理者の緊急復旧。

変更操作は既定で無効です。`Control:EnableProcessControl=true`、Runnerの`EnableProcessControl=true`に加え、対象Hostすべての`P1.Operations`/`P1.LegacyGate` capabilityが必要です。Runner/Agentの本番起動は固定`%ProgramData%\AstralRecordControl\managed-operations.enabled`を確認します。markerは既存ツールの共通gate導入を検査したinstallerの`-EnableManagedOperations`で作成します。詳しくは[導入手順](../../60_tool/control-deploy/README.md)と[既存ツールgate](../../60_tool/control-gate/README.md)を参照してください。

P1のプロセス運転はJava/Velocityの独立した基本運転です。VelocityはWeb/Agent/Runnerを対応版へ更新し、Hostの`P1.Velocity`能力を確認してから使用します。画面の「サーバー台帳」→Proxy→「操作・ログ」から起動できます。Velocityのタブは設定と操作・ログです。P3では同一Host/Serverの予約実行、監視、Webhook通知を追加しました。依存チェーンの自動起動/停止（`dependsOnServerIds`）、`AstralReady`依存判定、Bedrockのプロセス運転、更新Adapter・別PCへのServer複製/移送はP4以降です。非対応設定や操作を開始済みとして扱いません。

CLIの`login`にはControl管理画面と同じ管理者アカウントを使います。パスワードは入力した文字数だけ`*`を表示し、Backspaceで修正できます。失敗時はHTTPステータスや接続・ローカル権限の区分だけを表示し、応答本文、資格情報、例外メッセージは出力しません。

## プロジェクト

| 配置 | 責務 |
|---|---|
| `src/AstralRecordControl.Contracts` | protocol v1、厳密JSON、inventory/operation/gate DTOと検証 |
| `src/AstralRecordControl.Web` | owner認証/CLI資格情報、台帳、予約と操作、画面とログ取得 |
| `src/AstralRecordControl.Cli` | 停止中owner管理、初回ログインの保持、Agent登録、gate、ローカルRunner点検/復旧 |
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
node --test tests/WebScript.Tests/*.test.cjs
```

実際の登録/設定は [導入手順](../../60_tool/control-deploy/README.md) を使用します。テスト用`Testing`環境は一時データルートを明示した自動テスト専用です。通常導入で指定しません。

## 認証と経路

管理者は`/Login`でパスワード認証すると直接ログインします。`/Security`で全セッションを、`/Logout`で現在のセッションを失効します。旧MFA画面はログイン画面または管理画面へ転送し、旧MFAセッションは再ログインが必要です。未完了の旧MFA Cookieではowner操作を許可しません。

台帳画面は`/`、`/Hosts`、`/Servers`です。HTTPSを必須とし、`Control:PublicOrigin`はAgentからも到達できる固定originです。TLSはKestrelで終端します。転送ヘッダを無条件に信頼しません。VPN経由を標準とし、Agent/Runnerの待受ポートを外部公開しません。

| 経路 | 認証 / 用途 |
|---|---|
| `/api/control/v1/overview`、`hosts`、`environments`、`servers` | パスワード認証済みOwner Cookie。更新はCSRF、作成はIdempotency-Key + If-None-Match、更新はIf-Match |
| `/api/control/v1/hosts/{id}/credentials` | Owner Cookie。発行はPOST、失効はDELETE `/{credentialId}` |
| `/hubs/control-ui/v1` | Owner Cookie、セッション失効で切断 |
| `/hubs/control-agent/v1` | Agent専用Bearer、Hello→Reconcile→heartbeat |
| `/api/agent/v1/inventory` | Agent専用Bearer + X-Control-Connection-Epoch |
| `/health/live` | 機密を含まない生存確認。通常環境ではHTTPSのみ |
| `/api/control/v1/servers/{id}/operations`、`operations/{id}`、`servers/{id}/logs` | Owner Cookie。変更はCSRF、操作作成はIdempotency-Key + If-None-Match |
| `/api/control/v1/reservations`、`reservations/{id}/validate`、`release`、`gate/catalog` | Owner CookieまたはCLI Bearer。Cookie変更はCSRF |
| `/api/control/v1/auth/cli-token`、`cli-token/revoke` | 管理者名＋パスワードで発行、CLI Bearerで自己失効 |
| `/api/control/v1/auth/cli-token` GET、`hosts/{id}/enrollment` POST/DELETE | CLIログインの有効性確認、Agent資格情報の内部発行・登録失敗時の失効 |
| `/api/control/v1/schedules`、`schedules/{id}`、`schedules/{id}/run-now`、`schedules/{id}/requests/{operationId}` | Owner Cookie。変更はCSRF、作成/Run NowはIdempotency-Key、更新はIf-Match。Run Nowは永続受付後202、要求IDで照会・再送 |
| `/api/control/v1/servers/{id}/metrics` | Owner Cookie。期間とminute/hour粒度を指定して時系列を取得 |
| `/api/control/v1/notification-destinations`、`notification-destinations/{id}`、`notifications` | Owner Cookie。送信先変更はCSRF/Idempotency-Key/条件付きrevision、URLは応答しない。配信履歴は最大100件 |

JSONはcamelCase/定義済み文字列enumで、未知フィールド、重複フィールド、不正な型を拒否します。メッセージ上限1MiB、inventory JSON上限512KiBです。inventoryは保存済みの文字列そのもののSHA-256を検証し、Runner保存→Agent保存→Web ACKの順で適用します。

Agent tokenをOwner Cookieとして使うことも、その逆もできません。tokenは256bitのランダム値で、Webにはhashのみ保存します。Agent上ではLocalMachine DPAPIとAgent専用ACLで保存し、URL/引数/通常ログへ出力しません。

## 保存と復旧

既定は`%ProgramData%\AstralRecordControl\Web|Agent|Runner`です。各サービスのSQLiteはローカルディスク/WAL/busy timeout 5秒で、別サービスのDBを直接開きません。各プロセスは排他lockファイルを保持します。DB・Data Protection keys・credentials・configは配布パッケージに含めません。

Webの現行schemaは5、Agent/Runnerは4です。旧schemaからは各サービスのSQLite backupを作成してtransaction内で移行します。未知版や版情報のない既存schemaを持つDBは起動を拒否し、自動破棄/自動採用しません。Agent DBのstoreId変更やACKより古い状態への巻戻りは要復旧です。トークンの再発行だけで台帳のstoreIdを変更しません。同じHostへの自動再登録は提供せず、旧Hostを無効化して新しいHost IDへ明示登録し直します。

DPAPI鍵/トークンを別PCへコピーするだけでは復旧できません。Webは停止中CLIでownerパスワードを再設定し、Agentは新規Hostとして再登録します。既存ServerのPC間移送は後続段階です。

## 操作・gate・復旧

概要は全体の件数と注意事項に絞り、サーバー台帳では最新の報告に基づく稼働/停止状態を表示します。PC未接続や古い報告は確認不能と表示します。台帳のボタンから操作・ログまたは設定・詳細へ進みます。操作はconfigRevision・stateRevision・Sessionを照合し、全資源予約→全Host準備→Runner受付の順で実行します。HTTP受付は202で、結果は操作IDから確認します。同じIdempotency-Key/同じ内容は元の操作を返し、異なる内容は409です。Console成功はstdinへの書込完了を意味し、Minecraft内のコマンド成功判定ではありません。

運転・保守履歴は詳細ボタンで処理結果と予約・復旧情報を表示します。チェックボックスで単体/複数の履歴を選び、確認画面で削除できます。削除は完了済みかつ予約解放済み・要復旧なしに限り、管理Webの履歴本文と操作結果JSON実ファイルを削除します。再送照合用ID/hash/statusは保持し、実行PCのコンソールログ・バックアップ本体・復旧journalは対象に含めません。ファイル削除失敗は画面で案内し、定期保持処理で再試行します。

Runnerはshellを介さず登録済み実行ファイルをArgumentListで起動します。WebやAgentが停止してもプロセスとログ読取を継続します。Runnerが再起動した場合、生きている旧SessionはUnmanagedです。PID・生成時刻・実行ファイルfingerprintが一致しても標準入出力を再取得できないため、自動再採用しません。停止タイムアウトでは自動Killせず、同じWorkflowの要確認状態とholdを保持します。

CLIは各管理PCのWindowsユーザーで一度`login --server-url https://control.example:5443`を実行し、ログインをDPAPIで保持します。同じControlへの再実行は有効なログインを再利用します。`agent enroll --host-id <GUID>`は登録済みサービスからAgentの実パスを検出し、接続用資格情報を自動配置してAgentを起動します。運用者によるtokenコピーは不要です。段階導入では`--start-agent false`で設定だけ行います。gateの各コマンドは`--expected-server-url`で接続先を照合します。CLIの配置・初回登録は[導入手順](../../60_tool/control-deploy/README.md)、bindingは[共通gate手順](../../60_tool/control-gate/README.md)を参照してください。

WebのCookieは365日で利用時に延長し、サーバー側セッションとCLI資格は明示失効・owner資格変更まで有効です。旧8時間Cookie・旧15分CLI資格は更新前の期限を引き継ぐため、期限後の初回ログインで新方式へ移ります。既存Agentの設定・資格情報を作り直す必要はありません。

```powershell
AstralRecordControl.Cli.exe runner snapshot
AstralRecordControl.Cli.exe runner check-update
AstralRecordControl.Cli.exe runner inspect --server-id <GUID>
# OS管理者で、表示されたSessionと実プロセスを確認した後に実行
AstralRecordControl.Cli.exe runner recover --server-id <GUID> --session-id <GUID> --action KillAfterInspection --reason "残存プロセスを確認済み"
```

緊急復旧はローカルOS管理者のみ許可します。対象を確認できない場合は停止扱いへ変更しません。Console結果不明だけの場合、同じRunnerが所有するSessionは`AbortAfterInspection`で入力を再送せずFailed確定し稼働を維持できます。Runnerのholdと中央予約は別々に残るため、ローカル復旧後もWebの保守履歴で照合して解放します。ログはServer/Sessionごとに100MiBで分割し、1行64KiBを超える出力は`truncated`付きに分割します。30分ごとに`logRetentionDays`を過ぎた閉じたsegmentを削除し、稼働中の最新segmentを保持します。古いcursorや削除途中の中断には`gap`を返します。

## ファイル・バックアップ・復元（P2）

P1の導入条件に加え、Webの`Control:EnableFileControl=true`、Agent/Runnerの`enableFileControl=true`を設定します。初期値はすべてfalseです。対象Hostは`P2.Files`/`P2.Backups`を通知します。既存Minecraftへの有効化や実サービス登録は実装テストに含めません。

サーバー設定から`Files`へ進み、一覧/テキスト読取/64KiB以下のUTF-8編集、フォルダ作成/コピー/移動/退避削除を行います。読み込み上限は2MiB、UTF-8以外はArtifactとして取得します。保存はSHA-256のetagを照合し、BOMと既存改行を保持します。書き込みは停止中だけ許可します。ZIPはパス/link/展開容量/entry数/圧縮比を検査し、新規配置は空のServerフォルダとEULA確認を必要とします。

転送はHTTPの最大4MiB chunkと全体SHA-256を検証し、同じ転送ID・ファイルでoffsetから再開できます。既定上限は1件20GiB、Web stage合計100GiB、未完了の期限は2時間です。`/Transfers`から進捗確認・再開・保存ができます。ブラウザで64MiBを超える保存はファイル保存API対応ブラウザを使用します。Web/Agentはファイル全体をメモリへ読み込みません。

`Backups`で保存先、除外、非圧縮/ZIP、保持数/日数、取得後の再起動を設定します。設定はinventoryへ保存し、Agentの適用ACKを確認してから使います。保存先はServer root外の登録済みrootです。NASは両サービスのresourcesで`backupOnly=true`を指定したUNC rootだけを許可します。Agentのサービスアカウントへ共有先の権限を設定してください。任意のブラウザ入力パスへ接続しません。

取得は同じ予約内で正常停止と終了を確認してからローカルstageを作り、各entryとmanifestのSHA-256を検証して保存先へ確定します。最新7件または30日以内の成功世代を残し、保護/復元中/未完了の世代を保持します。世代の保護・削除・downloadも画面から操作できます。

復元の範囲は常に`ServerFiles`です。SQL/API/マスターデータ/公開状態は含みません。Replaceはsnapshotの状態へ戻し、Mergeはその他のファイルを保持します。旧データは同volumeの保護退避フォルダに残し、stageと交換過程をjournalへ保存します。既定で再起動しません。中断時はRunnerのdata journalとholdを保持して起動を禁止し、`/Workflows`から同じ復元を理由付きで再開します。入場制限は自動解除しません。

## 予約実行・監視・通知（P3）

予約実行はWebの`Control:EnableSchedules=true`とAgentの`enableSchedules=true`を明示設定し、P1の管理運用markerと共通gateを維持して有効化します。初期値はfalseです。Webで定義を保存してもAgentのinventory適用ACKまでは発火しません。Once、60秒以上のInterval、秒なし5フィールドCron（標準`Asia/Tokyo`）を扱い、夏時間の重複時刻は先のUTCだけを採用します。開始期限を60秒超過した予定はSkipに記録し、停止中の予定をまとめて追走しません。同一Host/Serverの最大16段の連鎖は同じローカル予約を使い、段間待機は0～86400秒です。PC横断/Environment WorkflowはP4です。

`/Servers/{id}/Schedules`で定義・適用版・実行履歴を確認できます。Run Nowは初期offline時に503で拒否し、受付済み要求は同じIdempotency-Keyで照会・再送します。受付後に通信結果が不明なら202と照会先を返し、Agentが開始前の拒否を確定した場合はRejectedとして保存・表示します。新しい要求IDで重複実行しません。バックアップprofileはinventory内で希望版と適用ACKを区別し、定義更新時にも既存profileを保持します。

Agentは約10秒ごとにHost/プロセスCPU、メモリ、ディスク空き、稼働時間を採取し、Javaの`127.0.0.1` status pingから取得できる人数だけを報告します。取得不能はnull/欠測で、0人や停止へ変換しません。TPS/MSPTはP3のデータ源に含みません。Webの`/Servers/{id}/Metrics`では1分粒度7日、1時間粒度90日の系列を表示します。

`/Notifications`でOwnerがWebhook送信先と履歴を管理します。URL全体をData Protectionで保護し、画面/APIへ戻しません。送信先は公開到達可能なHTTPS・443番だけを許可し、リダイレクトとプロキシを使わずDNS解決先の公開IPへ固定して送ります。Web停止中のAgentイベントもUTC発生時刻と同じeventIdでACKまで保持します。初回失敗後1/5/30分の最大3回再試行後にdead-letterとなり、送信失敗はゲーム操作の結果を変更しません。外部Webhookへの厳密なat-most-once配信は保証しないため、受信側はeventIdで重複排除してください。

完了済み操作・Web/AgentのSchedule履歴・通知は90日で本文を退役させ、再送照合に必要なID/hashを保持します。Runnerの復旧監査も90日を過ぎ、対象の要復旧状態とholdがない場合に削除します。非終端操作・要復旧保守・未ACKイベントは期限だけで削除しません。大量のPruneBackups結果はローカル予約では件数/hashへ要約し、Web転送に依存しません。検証は一時fixtureで行い、実Minecraft、実NAS、外部Webhook、実サービス登録には接続しません。
