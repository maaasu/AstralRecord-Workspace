# 60_tool

番号付きの実行入口BATはこのディレクトリ直下、複数ファイルで構成される実装や環境設定は用途別の専用ディレクトリに配置します。単一ファイルで完結する小規模なPowerShellジェネレーターだけは直下に置きます。採番したBATは、どのカレントディレクトリからでも実行できます。

## 実行入口

| 番号 | bat | 用途 |
| --- | --- | --- |
| 01 | `01-deploy-debug.bat` | API / Web / Plugin / FileDatabase のデバッグデプロイ（Plugin のテストを実行） |
| 02 | `02-deploy-debug-plugin-only.bat` | Plugin のテストコンパイル・実行を省略してビルド、デバッグデプロイ |
| 03 | `03-master-data-reload.bat` | Filebase 同期と MasterDataDB seed |
| 04 | `04-db-rebuild.bat` | AstralRecord / MasterDataDB / HistoryDB の再構築 |
| 05 | `05-skilltree-editor.bat` | ビルド済みスキルツリーエディタのローカル起動 |
| 06 | `06-skilltree-editor-build.bat` | スキルツリーエディタのフロントエンドだけをビルド |
| 07 | `07-generate-status-types.bat` | 共有ステータスカタログからKotlin / C# / TypeScriptを生成 |
| 08 | `08-generate-tag-types.bat` | 共有タグカタログからJava / C# / TypeScriptを生成し、filebaseのタグ参照を検証 |
| 09 | `09-astralarchitect-build-deploy.bat` | AstralArchitectをテスト・ビルドし、指定したMinecraftサーバーへJARを配置 |
| 10 | `10-release-management-deploy.bat` | Release Note 用の API / Web だけをビルド・デプロイ |
| 11 | `11-db-reset-except-release-notes.bat` | Release Note の送信情報を保持して3 DBのデータをリセット |
| 12 | `12-build-network-plugins.bat` | Lobby / Velocity Proxyプラグインをビルドし、ローカル出力フォルダへJARを生成 |

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

1. 必要に応じて各専用ディレクトリの config を確認します。
2. 直下の番号付き bat を実行します。
3. DB 再構築は既存データを保持しないため、`04-db-rebuild.bat` は内容を確認してから実行してください。

`01-deploy-debug.bat` は従来どおり Plugin のテストを含めてビルドします。高速な配置確認用の `02-deploy-debug-plugin-only.bat` は Maven の `maven.test.skip` を有効にし、テストのコンパイルと実行を省略してから Plugin を配置します。

`10-release-management-deploy.bat` は API と Web だけをデプロイします。初回実行前にAPI配置先へ既存DiscordSRV Botのトークンを `token.txt` として安全に配置してください。Web の配置先 `appsettings.json` は保持されるため、初回だけ `AstralRecordApi:BaseUrl` と `AstralRecordApi:ApiKey` を本番値に設定してください。現在の本番API接続先は `https://device_server:444` です。APIキーはAPI側の `ApiKey:Key` と同じ値を使用し、ソース管理には追加しません。

デプロイ前には、`token.txt` の存在・非空、Webの本番API接続先、APIキーの一致、`ReleaseNotes:SyncOnStartup` が有効であることを検証します。トークンとAPIキーの値は表示せず、バックアップにも `token.txt` を複製しません。配置せず検証だけを行う場合は、次を実行します。

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

`12-build-network-plugins.bat`は`AstralRecordLobby`と`AstralRecordProxy`をビルドし、既定では`network-plugin-build/output`へ`AstralRecordLobby.jar`と`AstralRecordProxy.jar`を生成します。サーバーへの配置は行いません。片方だけをビルドする場合は`-Target Lobby`または`-Target Proxy`、テストのコンパイルと実行を省略する場合は`-SkipTests`、出力先を変更する場合は`-OutputDirectory <path>`を指定します。

```powershell
.\12-build-network-plugins.bat
.\12-build-network-plugins.bat -Target Proxy -SkipTests
```

スキルツリーエディタは初回のみ `skilltree-editor/src/SkillTreeEditor.Client` で `npm ci` と `npm run build` を実行してください。開発時の2プロセス起動やpublish手順は `skilltree-editor/README.md` を参照してください。

共有ステータスカタログを変更した後は`07-generate-status-types.bat`を実行します。生成漏れだけを検査する場合は`07-generate-status-types.bat -Check`を使用できます。

共有タグカタログを変更した後は`08-generate-tag-types.bat`を実行します。生成漏れと全filebaseの未定義・用途違いタグだけを検査する場合は`08-generate-tag-types.bat -Check`を使用できます。

Master data reload の実行前には `ASTRALRECORD_API_KEY` を設定してください。

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
E:\AstralRecord-Workspace\60_tool\03-master-data-reload.bat
```

DB 再構築で確認を省略する場合は次のように実行します。

```powershell
E:\AstralRecord-Workspace\60_tool\04-db-rebuild.bat --yes
```

リリースノートの公開・送信情報を保持したまま、その他の `AstralRecord`、`MasterDataDB`、`HistoryDB` のデータをリセットする場合は次を実行します。既定では `RESET` の確認入力が必要です。

```powershell
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat
```

確認を省略する場合は `--yes` を付けます。

```powershell
E:\AstralRecord-Workspace\60_tool\11-db-reset-except-release-notes.bat --yes
```

この操作は対象DBを一時的に `SINGLE_USER WITH ROLLBACK IMMEDIATE` にします。実行前にAPI、Web、PluginなどのDB接続元を停止し、完了後は `03-master-data-reload.bat` で `MasterDataDB` を再投入してください。`--yes` を付けた場合はBAT終了時の `pause` も省略します。詳細は `db-reset-except-release-notes/README.md` を参照してください。
