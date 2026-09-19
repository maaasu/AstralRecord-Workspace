# AstralRecord Workspace

AstralRecord のモノレポです。この README はプロジェクトと資料の入口です。作業の進め方・Skillの選択は [AGENTS.md](AGENTS.md)、実装ルールや仕様は対象の資料を参照してください。

## プロジェクト一覧

| プロジェクト | 役割 | 参照先 |
|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft MMO RPG Plugin | [Pluginガイド](PLUGIN_GUIDE.md) |
| `10_plugin/AstralRecordLobby/` | Lobby Plugin | [Network設計](00_docs/10_Plugin設計書/feature/33-network/33_0-概要.md) |
| `10_plugin/AstralRecordProxy/` | Velocity Proxy Plugin | [Network設計](00_docs/10_Plugin設計書/feature/33-network/33_0-概要.md) |
| `10_plugin/AstralRecordGeyserExtension/` | Bedrock向けカスタムヘッド登録 | [Extension README](10_plugin/AstralRecordGeyserExtension/README.md) |
| `10_plugin/AstralArchitect/` | AI建築候補の作成・適用 | [AstralArchitect README](10_plugin/AstralArchitect/README.md) |
| `20_api/AstralRecordApi/` | Plugin・Web向けREST API | [APIガイド](API_GUIDE.md) |
| `30_web/AstralRecordWeb/` | 管理・公開用Web UI | [Webの資料](#astralrecord-web) |
| `00_docs/40_Database設計書/` | SQL Serverのスキーマ・テーブル定義 | [Database設計書](00_docs/40_Database設計書/README.md) |
| `40_filebase/` | YAML・JSONのマスタデータ | [Filebaseの資料](#astralrecord-filebase) |
| `50_resourcepack/` | Resource Pack（開発停止中） | [Resource Packの資料](#astralrecord-resource-pack) |
| `60_tool/` | ビルド・配置・開発ツール | [ツール一覧・運用手順](60_tool/README.md) |

## コミットルール

対象ファイルの選別・除外対象・メッセージ形式は [COMMIT_RULES.md](COMMIT_RULES.md) を参照してください。

## AstralRecord API

- [APIガイド](API_GUIDE.md): 実装ルール。
- [API設計書](00_docs/20_API設計書/README.md): 機能別の契約・エンドポイント仕様。
- [ネットワーク運用手順](00_docs/10_Plugin設計書/feature/33-network/33_5-例外・ログ・運用.md): ManagementDBを含む初回配置。

## AstralRecord Plugin

- [Pluginガイド](PLUGIN_GUIDE.md): 実装・メッセージ・共通基盤のルール。
- [プレイヤー保存契約](00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md): ローカル状態、非同期保存、ACK、外部取引の境界。

設計方針としてライトビハインド方式を採用し、クラッシュ時の未反映データや書き込み途中の整合性への対応は対象外とします。

## AstralRecord Web

`30_web/AstralRecordWeb/AstralRecordWeb/` は ASP.NET Core Razor Pages によるWeb UIです。ページは `Pages/` に配置し、Page Modelパターンを使用します。

- [ページ作成・変更手順](30_web/AstralRecordWeb/.agents/prompts/pages.md): ファイル構成、責務分割、UI・API契約・導線の確認。
- [Web設計書](00_docs/30_Web設計書/README.md): 画面・認証・画面遷移。
- [プロフィール・討伐モブ図鑑のAPI設計](00_docs/20_API設計書/feature/35-web-profile/35_README.md): 公開範囲、本人の討伐記録、能力値・ドロップ表示。
- [ログイン・本人確認の運用](00_docs/30_Web設計書/feature/01-web-auth/5-例外・ログ・運用/01_5.00-例外・ログ・運用.md): 認証方式の切替、Cookie、配置時の注意。
- [配置・DB更新手順](60_tool/README.md): API/Web配置前のmigrationと単独復旧。

## AstralRecord Filebase

- [Filebase作業ルール](40_filebase/AGENTS.md): 対象資料の選択と関連実装への影響確認。
- [Filebase設計書](00_docs/50_Filebase設計書/README.md): カテゴリ別仕様・スキーマ・作成時チェックリスト。
- [型・定数の生成手順](60_tool/README.md): 共有ステータス・タグカタログ変更後の再生成と検証。

フォルダ直下のスキーマMarkdownは `docs.<name>.YAMLスキーマ定義.md` とします。Obsidianで非表示になる `.` 始まりの名前は使いません。SQL Serverの定義は [Database設計書](00_docs/40_Database設計書/README.md) で管理します。

## AstralRecord Resource Pack

現在は開発停止中です。作成・修正を再開するときだけ、[Resource Pack README](50_resourcepack/README.md) と [作業ルール](50_resourcepack/AGENTS.md) を参照してください。対象バージョン・アセット構成・ビルド・検証手順はそちらで管理します。
