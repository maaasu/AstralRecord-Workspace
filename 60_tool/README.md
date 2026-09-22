# 60_tool

番号付きの実行入口BATはこのディレクトリ直下、複数ファイルで構成される実装や環境設定は用途別の専用ディレクトリに配置します。単一ファイルで完結する小規模なPowerShellジェネレーターだけは直下に置きます。採番したBATは、どのカレントディレクトリからでも実行できます。

## 実行入口

| 番号 | bat | 用途 |
| --- | --- | --- |
| 01 | `01-deploy-debug.bat` | [Dev更新](deploy-debug/README.md)：配置→手動起動待ち→開発アカウント世代移行まで一括実行 |
| 02 | `02-deploy-debug-plugin-only.bat` | 01のPlugin限定ショートカット（従来どおりテスト省略） |
| 03 | `03-master-data-reload.bat` | 01のマスタ限定ショートカット（Filebase同期→seed→起動待ち→移行） |
| 04 | `04-db-rebuild.bat` | AstralRecord / MasterDataDB / HistoryDB の再構築 |
| 05 | `05-skilltree-editor.bat` | ビルド済みスキルツリーエディタのローカル起動 |
| 06 | `06-skilltree-editor-build.bat` | スキルツリーエディタのフロントエンドだけをビルド |
| 07 | `07-generate-status-types.bat` | 共有ステータスカタログからKotlin / C# / TypeScriptを生成 |
| 08 | `08-generate-tag-types.bat` | 共有タグカタログからJava / C# / TypeScriptを生成し、filebaseのタグ参照を検証 |
| 09 | `09-astralarchitect-build-deploy.bat` | AstralArchitectをテスト・ビルドし、指定したMinecraftサーバーへJARを配置 |
| 10 | `10-release-management-deploy.bat` | Release Note 用の API / Web だけをビルド・デプロイ |
| 11 | `11-db-reset-except-release-notes.bat` | Release Note の送信情報を保持して最新 `init.sql` から3 DBを再作成 |
| 12 | `12-build-network-plugins.bat` | Lobby / Velocity Proxy / Geyser Extensionをビルドし、ローカル出力フォルダへJARを生成 |
| 13 | `13-db-migrate.bat` | 既存DBへ宣言済みの本番 migration を冪等適用し、必要スキーマを検査 |
| 14 | `14-management-db-migrate.bat` | ManagementDB の明示登録済み migration を非破壊で適用・検査 |
| 15 | `15-history-db-migrate.bat` | HistoryDB の明示登録済み migration を非破壊で適用・検査 |
| 16 | `16-maintenance.bat` | [本番メンテナンス](maintenance/README.md)：配布→手動起動待ち→対象アカウント世代移行まで一括実行 |

PowerShellから直接実行する場合は`generate-status-types.ps1`または`generate-tag-types.ps1`を使用します。bat はどのカレントディレクトリから実行しても動作するよう、内部で同じディレクトリのスクリプトを絶対パス解決します。

## ディレクトリ構成

```text
60_tool/
├─ 01-deploy-debug.bat
├─ 02-deploy-debug-plugin-only.bat
├─ 03-master-data-reload.bat
├─ 04-db-rebuild.bat
├─ 05-skilltree-editor.bat
├─ 06-skilltree-editor-build.bat
├─ 07-generate-status-types.bat
├─ 08-generate-tag-types.bat
├─ 09-astralarchitect-build-deploy.bat
├─ 10-release-management-deploy.bat
├─ 11-db-reset-except-release-notes.bat
├─ 12-build-network-plugins.bat
├─ 13-db-migrate.bat
├─ 14-management-db-migrate.bat
├─ 15-history-db-migrate.bat
├─ generate-status-types.ps1
├─ generate-tag-types.ps1
├─ deploy-debug/
│  ├─ deploy-debug.ps1
│  ├─ deploy-debug.config.json
│  ├─ normalize-source-encoding.ps1
│  └─ tests/
│     └─ release-management-preflight.integration.ps1
├─ astralarchitect-deploy/
│  ├─ astralarchitect-deploy.ps1
│  ├─ astralarchitect-deploy.config.json
│  └─ tests/
│     └─ astralarchitect-deploy.integration.ps1
├─ master-data-reload/
│  ├─ master-data-reload.ps1
│  └─ master-data-reload.config.json
├─ db-rebuild/
│  ├─ DbRebuildTool.csproj
│  ├─ Program.cs
│  ├─ db-rebuild.config.json
│  └─ README.md
├─ db-migrate/
│  ├─ DbMigrateTool.csproj
│  ├─ Program.cs
│  └─ db-migrate.config.json
├─ management-db-migrate/
│  ├─ ManagementDbMigrateTool.csproj
│  ├─ Program.cs
│  ├─ management-db-migrate.config.json
│  └─ README.md
├─ db-reset-except-release-notes/
│  ├─ DbResetExceptReleaseNotesTool.csproj
│  ├─ Program.cs
│  ├─ db-reset-except-release-notes.config.json
│  ├─ reset-db-except-release-notes.sql
│  └─ README.md
├─ network-plugin-build/
│  ├─ build-network-plugins.ps1
│  └─ output/                     # ローカル生成物（Git管理外）
├─ status-catalog-codegen/
│  ├─ StatusCatalogCodegen.csproj
│  ├─ Program.cs
│  └─ README.md
├─ tag-catalog-codegen/
│  ├─ TagCatalogCodegen.csproj
│  ├─ Program.cs
│  └─ README.md
└─ skilltree-editor/
   ├─ SkillTreeEditor.slnx
   ├─ src/
   │  ├─ SkillTreeEditor.Server/
   │  └─ SkillTreeEditor.Client/
   ├─ tests/
   └─ README.md
```

## 使用方法

01/02/03/16はPowerShell 7で実行します。通常のPATHに `pwsh` がない場合は、Git管理外の `60_tool/powershell7.local.json` に `{"executablePath":"C:\\path\\to\\pwsh.exe"}` を指定できます。共通bootstrapが既存の実行ファイルを直接使い、OSのPATH変更や新しいインストールは行いません。指定した実行ファイルを削除・移動した場合は参照先を更新してください。

`ManagementDB` はプレイヤー識別と運営情報を長期保持するDBで、ゲームDBリセット・再構築の対象外です。`db-rebuild` とゲーム用 `db-migrate` は、接続設定が `ManagementDB` または旧 `WebSiteDB` を指している場合も処理を拒否します。管理DBのスキーマ更新は専用の非破壊migrationで行います。

保護処理は両ツールのDebugビルド後に `tests/management-db-protection.integration.ps1` で検証できます。SQL Serverへ接続しません。旧WebSiteDBからの移行は `ASTRALRECORD_SQLSERVER_TEST_CONNECTION` を設定して `tests/management-db-migration.integration.ps1` で検証します。この検証はランダム名の一時DBだけを作成・削除し、実データには触れません。

1. 必要に応じて各専用ディレクトリの config を確認します。
2. 直下の番号付き bat を実行します。
3. DB 再構築は既存データを保持しないため、`04-db-rebuild.bat` は内容を確認してから実行してください。

`01-deploy-debug.bat` は従来どおり Plugin のテストを含めてビルドします。高速な配置確認用の `02-deploy-debug-plugin-only.bat` は Maven の `maven.test.skip` を有効にし、テストのコンパイルと実行を省略してから Plugin を配置します。

`01-deploy-debug.bat` と `10-release-management-deploy.bat` は API を有効にしている場合、API/Webを停止・配置する前にゲーム用 `db-migrate`、HistoryDB用migration、ManagementDB専用migrationを順に実行します。どれかの適用・スキーマ検査に失敗した場合、IIS停止、`app_offline.htm`、バイナリコピーを行わずに配置を中止します。`02-deploy-debug-plugin-only.bat` では API とDB migrationを実行しません。個別にゲーム用migrationだけを実行する場合は `13-db-migrate.bat`、ManagementDBだけを実行する場合は `14-management-db-migrate.bat`、HistoryDBだけを実行する場合は `15-history-db-migrate.bat` を使用します。各runnerはmanifestに明示されたmigrationだけを対象にし、DB再構築や既存データの削除は行いません。

`10-release-management-deploy.bat` は API と Web だけをデプロイします。初回実行前にAPI配置先へ既存DiscordSRV Botのトークンを `token.txt` として安全に配置してください。Web の配置先 `appsettings.json` は保持されるため、初回だけ `AstralRecordApi:BaseUrl` と `AstralRecordApi:ApiKey` を本番値に設定してください。現在の本番API接続先は `https://device_server:444` です。APIキーはAPI側の `ApiKey:Key` と同じ値を使用し、ソース管理には追加しません。

デプロイ前には、`token.txt` の存在・非空、Webの本番API接続先、APIキーの一致、`ReleaseNotes:SyncOnStartup` が有効であることを検証します。トークンとAPIキーの値は表示せず、バックアップにも `token.txt` を複製しません。配置せず検証だけを行う場合は、次を実行します。

APIを含むデプロイでは、配置先`appsettings.json`の`SkillTreeRuntime:MigrationKey`が非空で、`SkillTreeRuntime:Key`および`ApiKey:Key`と異なることも配置前に検証します。世代移行管理APIは`X-SkillTree-Migration-Key`だけを受け付けます。migration keyの値はログへ出力しません。

```powershell
.\10-release-management-deploy.bat -PreflightOnly
```

デプロイ前検証の正常系・異常系と、秘密情報が出力されないことを確認する統合テストは次を実行します。テスト用データはシステムの一時ディレクトリだけへ作成し、実サーバーへの書き込みやデプロイは行いません。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy-debug\tests\release-management-preflight.integration.ps1
```

`09-astralarchitect-build-deploy.bat`はAstralArchitectのMavenテストとビルドを行い、成功後に`AstralArchitect.jar`だけを配置します。`-BuildOnly`で配置を省略でき、`-PluginsDirectory "D:\minecraft\plugins"`で今回だけ配置先を上書きできます。既定値は`astralarchitect-deploy/astralarchitect-deploy.config.json`の`pluginsDirectory`で管理します。サーバーの停止・再起動や、既存チケットデータの変更は行いません。

配置先は末尾が`plugins`の絶対パスだけを受け付けます。同じworktreeのビルドと同じ配置先へのデプロイは排他制御されます。配置・中断復旧・データ非変更・不正パスと並行実行の拒否を確認する場合は、次を実行します。テストはシステムの一時ディレクトリだけへJARを配置し、実サーバーへは接続しません。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\astralarchitect-deploy\tests\astralarchitect-deploy.integration.ps1
```

`12-build-network-plugins.bat`は`AstralRecordLobby`、`AstralRecordProxy`、`AstralRecordGeyserExtension`をビルドし、既定では`network-plugin-build/output`へ同名の3つのJARを生成します。サーバーへの配置は行いません。個別ビルドは`-Target Lobby`、`-Target Proxy`、`-Target Extension`、テストのコンパイルと実行を省略する場合は`-SkipTests`、出力先を変更する場合は`-OutputDirectory <path>`を指定します。Extensionの配置先はVelocityの`plugins/Geyser-Velocity/extensions/`です。API接続設定と起動時登録の制約は[Extension README](../10_plugin/AstralRecordGeyserExtension/README.md)を参照してください。

```powershell
.\12-build-network-plugins.bat
.\12-build-network-plugins.bat -Target Proxy -SkipTests
```

スキルツリーエディタは初回のみ `skilltree-editor/src/SkillTreeEditor.Client` で `npm ci` と `npm run build` を実行してください。開発時の2プロセス起動やpublish手順は `skilltree-editor/README.md` を参照してください。

共有ステータスカタログを変更した後は`07-generate-status-types.bat`を実行します。生成漏れだけを検査する場合は`07-generate-status-types.bat -Check`を使用できます。

共有タグカタログを変更した後は`08-generate-tag-types.bat`を実行します。生成漏れと全filebaseの未定義・用途違いタグだけを検査する場合は`08-generate-tag-types.bat -Check`を使用できます。

日常の入口は01（Dev更新）、16（本番メンテナンス）、05（エディタ）です。02/03は01へのショートカット、その他は専用開発・管理操作として扱います。サーバー停止・起動は手動のまま、起動案内後の待機と世代移行をバッチが継続します。詳細は各手順を参照してください。

03も01と同じ `deploy-debug.config.json` / `deploy-debug.local.json` を使用します。初回のDevアカウント・接続設定は [Dev更新手順](deploy-debug/README.md) を参照してください。以下は旧 `master-data-reload.ps1` を直接使う詳細操作の設定であり、03の設定ではありません。

旧Master data reloadスクリプトの実行前には `ASTRALRECORD_API_KEY` を設定してください。

API key は `master-data-reload/master-data-reload.config.json` の `api.apiKey` に設定できます。`apiKey` が空の場合は、`api.apiKeyEnvironmentVariable` で指定した環境変数を使用します。

```json
{
  "api": {
    "apiKey": "your-api-key"
  }
}
```

環境変数を使用する場合は、従来どおり次のように実行できます。

```powershell
$env:ASTRALRECORD_API_KEY = 'your-api-key'
powershell -NoProfile -File E:\AstralRecord-Workspace\60_tool\master-data-reload\master-data-reload.ps1
```

DB 再構築で確認を省略する場合は次のように実行します。

```powershell
E:\AstralRecord-Workspace\60_tool\04-db-rebuild.bat --yes
```

リリースノートの公開・送信情報を保持したまま、`AstralRecord`、`MasterDataDB`、`HistoryDB` を最新 `init.sql` から再作成する場合は次を実行します。既定では `RESET` の確認入力が必要です。

```powershell
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat
```

確認を省略する場合は `--yes` を付けます。

```powershell
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes
```

この操作はSQL Serverのセッションロックで同時実行を防ぎ、API/Webへ実行ID付きの `app_offline.htm` を配置した上で、Release Note 2表を同一トランザクションで一時DBへ退避します。対象DBは `SINGLE_USER WITH ROLLBACK IMMEDIATE` で削除して再作成します。完了後はAPI起動時のSeederまたは `03-master-data-reload.bat` で `MasterDataDB` を再投入してください。復元まで成功した場合だけ一時DBと同じ実行IDの保守マーカーを削除し、途中失敗時は `AstralRecord` を `OFFLINE` にして一時DBと `--restore-backup` 復旧コマンドを残します。`--yes` を付けた場合はBAT終了時の `pause` も省略します。詳細は `db-reset-except-release-notes/README.md` を参照してください。
