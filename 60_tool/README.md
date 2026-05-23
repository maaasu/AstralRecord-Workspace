# Debug Deploy Tool

`E:\AstralRecord-Workspace\60_tool\deploy-debug.bat` を実行すると、API / WEB / プラグインのビルドと配置をまとめて実行します。

## できること

- API を `dotnet publish` でビルド
- WEB を `dotnet publish` でビルド
- プラグインを `mvn clean package` でビルド
- API / WEB の現在配置内容を `bak` に退避
- API / WEB を共有フォルダへ反映
- プラグイン jar を実行環境へコピー
- FileDatabase を共有フォルダへ反映
- 設定で有効な場合だけ `192.168.0.88` の IIS を停止してから反映し、最後に起動

## ファイル構成

- [deploy-debug.bat](/E:/AstralRecord-Workspace/60_tool/deploy-debug.bat:1)
  - ダブルクリック実行用の入口
- [deploy-debug.ps1](/E:/AstralRecord-Workspace/60_tool/deploy-debug.ps1:1)
  - 本体の PowerShell スクリプト
- [deploy-debug.config.json](/E:/AstralRecord-Workspace/60_tool/deploy-debug.config.json:1)
  - パスや設定ファイル保護ルールを持つ設定ファイル

## 使い方

1. `E:\AstralRecord-Workspace\60_tool\deploy-debug.config.json` の値を確認します。
2. `E:\AstralRecord-Workspace\60_tool\deploy-debug.bat` を実行します。
3. コンソールに `Deployment completed successfully` が出れば完了です。

## 設定ファイルの扱い

API / WEB は `preserveFilePatterns` に指定したファイルを上書きしません。  
また、`preserveDirectories` に指定したディレクトリは削除しません。

初期設定では以下を保護しています。

- `appsettings*.json`
- API の `logs`
- WEB の `logs`

このため、実行環境側にある `appsettings.json` や `appsettings.Development.json` はデプロイ後もそのまま残ります。

一方で、`bak` はデプロイ直前の実行環境の内容を退避するためのフォルダです。`bak` 内の設定ファイルは、次回実行時に「その時点の実行環境内容」で更新されます。

もし `web.config` も保護したい場合は、対象の `preserveFilePatterns` に次を追加してください。

```json
"preserveFilePatterns": [
  "appsettings*.json",
  "web.config"
]
```

## アプリ停止と IIS 制御

API / WEB は配置直前に `app_offline.htm` を配置先へ一時作成し、ASP.NET Core アプリを停止してから反映します。  
反映後は `app_offline.htm` を削除します。

初期状態では `iis.enabled` は `false` です。

```json
"iis": {
  "enabled": false,
  "host": "192.168.0.88",
  "executablePath": ""
}
```

- `false`: IIS の停止と起動を行わず、そのまま配置します。
- `true`: `iisreset.exe` を使って IIS を停止してから反映し、最後に起動します。

この端末に `iisreset.exe` が入っていない場合は、次のどちらかにしてください。

- `iis.enabled` を `false` のまま使う
- `iis.executablePath` に `iisreset.exe` のフルパスを設定する

## 現在の対象

- API
  - プロジェクト: `E:\AstralRecord-Workspace\20_api\AstralRecordApi\AstralRecordApi\AstralRecordApi.csproj`
  - publish 出力: `E:\AstralRecord-Workspace\20_api\AstralRecordApi\AstralRecordApi\bin\Release\net10.0\publish`
  - 配置先: `\\192.168.0.88\server\AstralRecordApi`
- WEB
  - プロジェクト: `E:\AstralRecord-Workspace\30_web\AstralRecordWeb\AstralRecordWeb\AstralRecordWeb.csproj`
  - publish 出力: `E:\AstralRecord-Workspace\30_web\AstralRecordWeb\AstralRecordWeb\bin\Release\net10.0\publish`
  - 配置先: `\\192.168.0.88\server\AstralRecordWeb`
- プラグイン
  - プロジェクト: `E:\AstralRecord-Workspace\10_plugin\AstralRecord`
  - ビルド成果物: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\dist`
  - 配置先: `\\192.168.0.88\server\CraftyController\crafty-__saas-windows-medium-amd64__-_03629d64\servers\5bf4f70b-2c02-4a6b-b23f-8453237d2d97\plugins`
- FileDatabase
  - 開発環境: `E:\AstralRecord-Workspace\40_filebase`
  - 配置先: `\\192.168.0.88\server\FileDatabase\file`
  - 保持ディレクトリ: `99.work`

## 注意点

- 実行には `dotnet`、`mvn`、`robocopy`、`iisreset` が必要です。
- `iisreset` のリモート実行権限がない場合は IIS の停止と起動に失敗します。
- プラグインは `AstralRecord-*.jar` を配置先から削除して、最新 jar を 1 つだけ配置します。
- FileDatabase はフォルダ同期で反映します。初期設定では実行環境の `99.work` は削除しません。
- `bak` は履歴を複数世代で持つ仕組みではありません。毎回上書きされます。
