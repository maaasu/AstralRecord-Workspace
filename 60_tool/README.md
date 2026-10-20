# 60_tool — 開発・配布・運用ツール

目的から入口を選び、環境を変更したときは [設定一覧と新環境の確認事項](CONFIGURATION.md) を確認してください。番号付きBATは公開入口、用途別ディレクトリは実装です。BATはどのカレントディレクトリからでも実行できます。

## 普段使う入口

| 目的 | 入口 | 責務・手順 |
|---|---|---|
| Devを更新する | `01-deploy-debug.bat` | 有効なAPI/Web/Plugin/Filebaseのビルド・配置、seed、Dev起動待ち、パッチ公開。[Dev更新](deploy-debug/README.md) |
| Pluginだけ更新する | `02-deploy-debug-plugin-only.bat` | 01の`-PluginOnly`。既定でPluginテストを省略。Filebase/seedは実行しない |
| マスタだけ更新する | `03-master-data-reload.bat` | 01の`-MasterDataOnly`。Filebase同期・seed・再読込または起動待ち・パッチ公開 |
| マスタを編集する | `05-master-data-editor.bat` | 独立したローカルアプリをビルド・起動。[エディタ](skilltree-editor/README.md) |
| Dev/Buildから各サーバーへ配布する | `16-maintenance.bat` | JAR・配置データ・World等の配布、起動待ち、パッチ公開。[メンテナンス配布](maintenance/README.md) |

01/02/03/16では、サーバー停止・起動と入場制限は運用者が行います。03は対応するAPI/Pluginと自動再読込が有効なら、入場・他の書込を止めた稼働中Devも使用できます。プレイヤーの更新は次回ログイン時です。

## 限定した配布・復旧

| 目的 | 入口 | 責務・手順 |
|---|---|---|
| AstralArchitectを更新する | `09-astralarchitect-build-deploy.bat` | Mavenテスト・ビルド後、`AstralArchitect.jar`だけ配置。`-BuildOnly`で配置省略 |
| API/Webだけ更新する | `10-release-management-deploy.bat` | Legacy運用のAPI/Web配布。01と同じ設定優先順位を使用。Managed運用では検査のみ。`-PreflightOnly`で検査 |
| プレイヤーを一括更新する | `17-player-patch-migration.bat` | 大きな仕様変更・復旧時のPreview/Commit。[一括更新](player-patch-migration/README.md) |

17は日常更新とは独立した明示操作です。01/02/03/16から全員分の一括更新は行いません。

## DBを管理する

| 目的 | 入口 | 対象・手順 |
|---|---|---|
| ゲームDBへmigrationを適用する | `13-db-migrate.bat` | AstralRecord。[migration](db-migrate/README.md) |
| 管理DBへmigrationを適用する | `14-management-db-migrate.bat` | ManagementDB。[管理DB migration](management-db-migrate/README.md) |
| 履歴DBへmigrationを適用する | `15-history-db-migrate.bat` | HistoryDB。13と同じrunner、異なるmanifest |
| ゲーム用3DBを再構築する | `04-db-rebuild.bat` | **既存データを削除**して再構築・seed。[DB再構築](db-rebuild/README.md) |
| リリースノートを保持して3DBを再作成する | `11-db-reset-except-release-notes.bat` | リリースノート送信情報を退避・復元し、他のゲームデータを初期化。[DBリセットと復旧](db-reset-except-release-notes/README.md) |

13/14/15は登録済みの非破壊migrationの適用・スキーマ検査です。04/11は初期化操作なので日常更新の代わりには使いません。ManagementDBは長期保持対象で、ゲームDB再構築・リセットの対象外です。

APIを含む01/10では、配置前にゲーム・History・Managementのmigrationを実行します。いずれかの失敗時はAPI/Webを配置しません。

## 開発時だけ使う

| 目的 | 入口 | 責務・手順 |
|---|---|---|
| エディタのフロントだけビルドする | `06-master-data-editor-build.bat` | [エディタ](skilltree-editor/README.md)の起動・配置前準備 |
| 共有ステータスの型を生成する | `07-generate-status-types.bat` | Kotlin/C#/TypeScript生成。[ステータス生成](status-catalog-codegen/README.md) |
| 共有タグの型を生成・検査する | `08-generate-tag-types.bat` | Java/C#/TypeScript生成とfilebaseの参照検査。[タグ生成](tag-catalog-codegen/README.md) |
| ネットワーク用Pluginをビルドする | `12-build-network-plugins.bat` | Lobby/Proxy/Geyser ExtensionのJAR生成。[ネットワークPlugin](network-plugin-build/README.md) |

07/08の`-Check`は生成漏れの検査です。12はローカルに成果物を生成し、配布は16の責務です。

## Controlの導入・管理

[control-deploy](control-deploy/README.md) は独立管理Web・Agent・Runner・CLIのpublish、登録、更新、運用有効化を担当します。ゲーム内容を配布する01/16とは導入手順が異なります。

[control-gate](control-gate/README.md) は01/02/03/16/17の実行予約と資源保護を担当する共通ライブラリです。通常は直接実行しません。Control管理markerがあるPCでは、`controlGate`未設定のLegacy実行は拒否されます。[新環境の導入メモ](control-deploy/APP-01-MC-01-DEV-01.md)と[現在の接続状況](CONFIGURATION.md#2026-10-20の実環境確認)を参照してください。

## 実装の責務

| ディレクトリ・ファイル | 担当すること | 呼び出し元 |
|---|---|---|
| `deploy-debug/dev-update.ps1` | Dev更新の案内・状態管理 | 01/02/03 |
| `deploy-debug/deploy-debug.ps1` | ビルド、DB migration、ファイル配置、API/Web配置前検査 | Dev更新・10 |
| `maintenance/` | ファイル配布、診断、復旧、起動確認、パッチ公開の共通処理 | 16、Dev更新、17 |
| `player-patch-migration/` | プレイヤー一括移行のPreview/Commit | 17 |
| `db-rebuild/` / `db-reset-except-release-notes/` | DB初期化と復旧 | 04/11 |
| `db-migrate/` / `management-db-migrate/` | DBごとのmanifest検査・適用 | 13/14/15、API配布 |
| `astralarchitect-deploy/` | AstralArchitectだけのビルド・配布 | 09 |
| `network-plugin-build/` | ネットワークPluginのビルド | 12 |
| `skilltree-editor/` | マスタ編集アプリ。実行ツール群から独立したWebアプリ | 05/06 |
| `status-catalog-codegen/` / `tag-catalog-codegen/` | 共有カタログから型生成 | 07/08の直下PS1 |
| `control-deploy/` / `control-gate/` | Controlの導入・共通gate | 管理者手順・保守入口 |
| `master-data-reload/` | **旧詳細操作**のFilebase同期・API seed。03は使用しない | PS1直接実行のみ |
| `invoke-powershell7.ps1` | PowerShell 7の検出・起動 | 01/02/03/09/10/16/17 |
| `tests/` | 複数のDBツールにまたがる保護・移行検証 | 開発時 |

公開BAT名・実装パスは、外部参照、既存runの復旧、Controlのハッシュ検証と対応しています。用途分類はこの表を正本とし、単なる整理のために移動・改名しません。

## 実行環境と設定

01/02/03/09/10/16/17はPowerShell 7を使用します。PATHに`pwsh`がない場合は、Git管理外の`powershell7.local.json`に`{"executablePath":"C:\\path\\to\\pwsh.exe"}`を設定します。bootstrapはOSのPATH変更・インストールを行いません。

設定の優先順位、APP-01/MC-01/DEV-01の配置、秘密値の参照先、移行未完了事項は [CONFIGURATION.md](CONFIGURATION.md) にまとめています。各ツールの細かい引数・テスト・復旧方法は、上表から担当ディレクトリのREADMEを参照してください。
