# AstralRecord Plugin — AI Agent Guide

このファイルは **AI エージェント専用** の作業手順書です。
プロジェクトルール（言語選定 / ディレクトリ構成 / コーディング規約 / JavaDoc 規約 など、人間・AI 問わず編集者全員に必要な情報）は [README.md](README.md) を参照してください。

## 必須手順

1. **コードを変更する作業を行う前に、[README.md](README.md) を必ず読む**。プロジェクトの構造・規約・データアクセス方針はそちらに記載されている。
2. **設計書（`docs/`）を追加・修正する作業を行う前に、`docs/README.md` を必ず読む**。採番・命名・構造ルールはそちらを正本とする。
3. 変更内容に応じて、下表の補助プロンプトのうち **該当するものだけ** を追加で読む（不要な補助プロンプトは開かない）。
4. ファイル読み取りは UTF-8 を優先する。文字化けがある場合は他のエンコーディングで再読し、文字化けのまま実装を進めない。

## 補助プロンプト（変更内容に該当するものだけ読む）

| 変更内容 | 読むファイル |
|:--|:--|
| コード追加・修正全般、配置先・言語選定の判断 | [.agents/prompts/code.md](.agents/prompts/code.md) |
| 設計書（`docs/`）の新規作成・改修、feature 設計書の分割/採番/命名 | [.agents/prompts/docs.md](.agents/prompts/docs.md) |
| ログ出力 / `LogId` / `logger.properties` の追加・変更 | [.agents/prompts/logger.md](.agents/prompts/logger.md) |
| プレイヤー向けメッセージ / `MsgId` / `player.properties` の追加・変更 | [.agents/prompts/player_msg.md](.agents/prompts/player_msg.md) |
| DB 契約・スキーマ、または file 系マスタデータに依存する実装、API/Database/Filebase と連動する変更 | [.agents/prompts/database.md](.agents/prompts/database.md) |

該当しない領域の補助プロンプトはトークン節約のため開かない。複数領域にまたがる変更の場合のみ、関係するファイルを必要分だけ追加で読む。

### 設計書依頼の自動判定（E:\AstralRecord-Workspace 向け）

`AstralRecord` を対象に、以下の条件に当てはまる依頼は「設計書改修依頼」とみなす。

- `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\` 配下の作成・編集依頼
- `00_docs/10_プラグイン設計書/feature/` 配下の作成・編集依頼
- 「設計書」「仕様書」「ドキュメント整備」「章構成」「採番」「命名」などの依頼
- 特定 feature の説明文書更新依頼（例: `user`, `inventory` の設計書更新）

この場合、依頼文に明示されていなくても次を自動で入力として読むこと。

1. `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\README.md`
2. 対象 feature の `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\<feature>\NN_README.md`（存在する場合）
3. 本表の `[.agents/prompts/docs.md](.agents/prompts/docs.md)`

## このファイルに書かないもの

以下の情報は AI エージェント専用ではないため、すべて [README.md](README.md) または該当する補助プロンプトに置く。AGENTS.md に重複記載しない。

- 技術スタック・ディレクトリ構成・データ管理方針
- 言語選定ルール（Java / Kotlin の使い分け）
- コーディング規約（パッケージ配置、ハードコーディング禁止、`AstPlayer` 利用など）
- JavaDoc / KDoc の記載ルール
- ステータスシステム等のドメイン仕様
