# AstralRecord Workspace

AstralRecord のモノレポです。この README はプロジェクトと資料の入口です。作業の進め方・Skillの選択は [AGENTS.md](AGENTS.md)、機能別の作業ルールと、依頼をまたいで参照する要望・判断理由は [AIパーソナライズ](00_docs/10_AIパーソナライズ/README.md) を参照してください。

## プロジェクト一覧

| プロジェクト | 役割 | 参照先 |
|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft MMO RPG Plugin | [Pluginガイド](PLUGIN_GUIDE.md) |
| `10_plugin/AstralRecordLobby/` | Lobby Plugin | [ネットワークPluginのビルド](60_tool/12-build-network-plugins/README.md) |
| `10_plugin/AstralRecordProxy/` | Velocity Proxy Plugin | [ネットワークPluginのビルド](60_tool/12-build-network-plugins/README.md) |
| `10_plugin/AstralRecordGeyserExtension/` | Bedrock向けカスタムヘッド、パック配布、ダメージ数値変換 | [Extension README](10_plugin/AstralRecordGeyserExtension/README.md) |
| `10_plugin/AstralArchitect/` | AI建築候補の作成・適用 | [AstralArchitect README](10_plugin/AstralArchitect/README.md) |
| `20_api/AstralRecordApi/` | Plugin・Web向けREST API | [APIの探し方](00_docs/10_AIパーソナライズ/API/README.md) |
| `30_web/AstralRecordWeb/` | 管理・公開用Web UI | [Webの資料](#astralrecord-web) |
| `00_docs/40_Database設計書/` | SQL Serverのスキーマ・テーブル定義 | [Database設計書](00_docs/40_Database設計書/README.md) |
| `40_filebase/` | YAML・JSONのマスタデータ | [Filebaseの資料](#astralrecord-filebase) |
| `50_resourcepack/` | Java / Bedrock 向けリソースパック | [Resource Packの資料](#astralrecord-resource-pack) |
| `60_tool/` | ビルド・配置・開発ツール | [ツール一覧・運用手順](60_tool/README.md) |

## コミットルール

対象ファイルの選別・除外対象・メッセージ形式は [COMMIT_RULES.md](COMMIT_RULES.md) を参照してください。

## AstralRecord API

- [APIの探し方](00_docs/10_AIパーソナライズ/API/README.md)から、Controller、利用側のコード、APIの実装・テスト規約を確認できます。
- [ネットワーク運用手順](60_tool/14-management-db-migrate/README.md#ネットワーク管理の初回導入): ManagementDBを含む初回配置。

## AstralRecord Plugin

- [Pluginガイド](PLUGIN_GUIDE.md): 実装・メッセージ・共通基盤のルール。
- [プレイヤー状態の保存](00_docs/10_AIパーソナライズ/プラグイン/プレイヤー状態の保存.md): 非同期保存を採用した理由と、外部取引・再送時の扱い。

## AstralRecord Web

`30_web/AstralRecordWeb/AstralRecordWeb/` は ASP.NET Core Razor Pages によるWeb UIです。ページは `Pages/` に配置し、Page Modelパターンを使用します。

- [ページ作成・変更手順](00_docs/10_AIパーソナライズ/Web/Razorページの作業ルール.md): ファイル構成、責務分割、UI・API契約・導線の確認。
- [Razor Pages](30_web/AstralRecordWeb/AstralRecordWeb/Pages) と [Webの設定・認証](30_web/AstralRecordWeb/AstralRecordWeb/Program.cs)。
- [Webログイン変更時の配置](60_tool/14-management-db-migrate/README.md#webログイン変更時の配置)。
- [配置・DB更新手順](60_tool/README.md): API/Web配置前のmigrationと単独復旧。

## AstralRecord Filebase

- [Filebase作業ルール](00_docs/10_AIパーソナライズ/ファイルベース/作業ルール.md): 対象資料の選択と関連実装への影響確認。
- [Filebase設計書](00_docs/50_Filebase設計書/README.md): カテゴリ別仕様・スキーマ・作成時チェックリスト。
- [型・定数の生成手順](60_tool/README.md): 共有ステータス・タグカタログ変更後の再生成と検証。

フォルダ直下のスキーマMarkdownは `docs.<name>.YAMLスキーマ定義.md` とします。Obsidianで非表示になる `.` 始まりの名前は使いません。SQL Serverの定義は [Database設計書](00_docs/40_Database設計書/README.md) で管理します。

## AstralRecord Resource Pack

Java 版と統合版にダメージ数値、アクションリング、アイテムモデルのリソースパックを配布します。対象バージョン、ビルド、配布方法は [Resource Pack README](50_resourcepack/README.md) と [作業ルール](00_docs/10_AIパーソナライズ/リソースパック/作業ルール.md) を参照してください。
