# 60_tool

実行入口となる bat は、このディレクトリ直下に採番して配置しています。

## 実行入口

| 番号 | bat | 用途 |
| --- | --- | --- |
| 01 | `01-deploy-debug.bat` | API / Web / Plugin / FileDatabase のデバッグデプロイ |
| 02 | `02-deploy-debug-plugin-only.bat` | Plugin のみデバッグデプロイ |
| 03 | `03-master-data-reload.bat` | Filebase 同期と MasterDataDB seed |
| 04 | `04-db-rebuild.bat` | AstralRecord / MasterDataDB / HistoryDB の再構築 |
| 05 | `05-skilltree-editor.bat` | ビルド済みスキルツリーエディタのローカル起動 |
| 06 | `06-skilltree-editor-build.bat` | スキルツリーエディタのフロントエンドだけをビルド |
| 07 | `07-generate-status-types.bat` | 共有ステータスカタログからKotlin / C# / TypeScriptを生成 |

bat はどのカレントディレクトリから実行しても動作するよう、内部で専用ディレクトリのスクリプトを絶対パス解決します。

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
├─ deploy-debug/
│  ├─ deploy-debug.ps1
│  ├─ deploy-debug.config.json
│  └─ normalize-source-encoding.ps1
├─ master-data-reload/
│  ├─ master-data-reload.ps1
│  └─ master-data-reload.config.json
├─ db-rebuild/
│  ├─ DbRebuildTool.csproj
│  ├─ Program.cs
│  ├─ db-rebuild.config.json
│  └─ README.md
├─ status-catalog-codegen/
│  ├─ StatusCatalogCodegen.csproj
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

スキルツリーエディタは初回のみ `skilltree-editor/src/SkillTreeEditor.Client` で `npm ci` と `npm run build` を実行してください。開発時の2プロセス起動やpublish手順は `skilltree-editor/README.md` を参照してください。

共有ステータスカタログを変更した後は`07-generate-status-types.bat`を実行します。生成漏れだけを検査する場合は`07-generate-status-types.bat -Check`を使用できます。

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
