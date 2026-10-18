# AstralRecordControl

Minecraft本体・既存AstralRecordWeb/API・SQL Serverが停止していてもログインできる独立管理系です。.NET 10 / Windows x64を使用します。設計の正本は [AstralRecordControl設計書](../../00_docs/30_WEB設計書/AstralRecordControl/README.md) です。

## P0で利用できる機能

- 単一ownerのパスワード＋TOTP、使い捨て回復コード、8時間の固定セッション、全セッション失効、停止中のローカルCLIによるowner作成/リセット。
- PC・Server・Environment台帳、Agentトークンの一度限り表示/失効、希望設定版と適用版の区別。
- 各PCからHTTPS/WSSで接続するAgent。Hello/Reconcile/heartbeat、再接続、設定snapshotのhash検証と同期、イベントACK、storeId/巻戻り検出。
- 同一PCのRunnerへのACL付きnamed pipe。設定の原子的適用、snapshot、永続hold/fence。

Serverはpilot登録です。起動・停止・コンソール・ファイル・バックアップ・予約実行は未実装で、操作要求は拒否します。Runnerにゲームプロセス起動コードはありません。`autoStart` / `restartOnCrash`もP0では有効化できません。

## プロジェクト

| 配置 | 責務 |
|---|---|
| `src/AstralRecordControl.Contracts` | protocol v1、厳密JSON、inventory DTO/検証 |
| `src/AstralRecordControl.Web` | Razor Pages、Identity/SQLite、owner API、Agent/UI Hub、台帳 |
| `src/AstralRecordControl.Cli` | Web停止中のローカルowner管理 |
| `src/AstralRecordControl.Agent` | outbound接続、Agent DB、Runner IPC、設定適用ACK |
| `src/AstralRecordControl.Runner` | ローカル設定/hold DB、named pipe。P0は実行禁止 |
| `tests` | 一時SQLite/HTTP/IPCを使う境界・状態・永続化検証 |
| `../../60_tool/control-deploy` | Windows向けpublish / 登録 / 配布前検査 / Web・Agent更新 |

DevのHostは開発PC、チャンネルのHostはMC用PCです。どちらもAgentとRunnerを各1サービス置きます。管理Webはその2台と別PCでも構いません。各Host内のServer IDを別々に登録し、共有API/masterの境界はEnvironmentで明示します。

## ビルドとテスト

.NET 10 SDKを用意し、このディレクトリで実行します。DBや実サービスは一時fixtureを除いて作成しません。

```powershell
dotnet test AstralRecordControl.slnx -c Release
pwsh -NoProfile -File ../../60_tool/control-deploy/tests/deployment-preflight.ps1
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

JSONはcamelCase/定義済み文字列enumで、未知フィールド、重複フィールド、不正な型を拒否します。メッセージ上限1MiB、inventory JSON上限512KiBです。inventoryは保存済みの文字列そのもののSHA-256を検証し、Runner保存→Agent保存→Web ACKの順で適用します。

Agent tokenをOwner Cookieとして使うことも、その逆もできません。tokenは256bitのランダム値で、Webにはhashのみ保存します。Agent上ではLocalMachine DPAPIとAgent専用ACLで保存し、URL/引数/通常ログへ出力しません。

## 保存と復旧

既定は`%ProgramData%\AstralRecordControl\Web|Agent|Runner`です。各サービスのSQLiteはローカルディスク/WAL/busy timeout 5秒で、別サービスのDBを直接開きません。各プロセスは排他lockファイルを保持します。DB・Data Protection keys・credentials・configは配布パッケージに含めません。

P0の初期スキーマは1です。未知版や版情報のない既存DBは起動を拒否し、自動破棄/自動採用しません。将来のmigrationは既存DBのbackupと移行処理を追加する必要があります。Agent DBのstoreId変更やACKより古い状態への巻戻りは要復旧です。トークンの再発行だけで台帳のstoreIdを変更しません。P0では同じHostへの自動再登録を提供せず、旧Hostを無効化して新しいHost IDへ明示登録し直します。

DPAPI鍵/トークンを別PCへコピーするだけでは復旧できません。Webは停止中CLIでowner/MFAを再設定し、Agentは新規Hostとして再登録します。既存Serverの移送やゲーム運用の再開はP1以降の機能です。
