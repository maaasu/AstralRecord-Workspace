# AstralRecord Monorepo Guide

まず依頼が質問・診断か、変更を伴う作業かを判定し、下の実行ルールで入口を選んでください。対象プロジェクトはパスまたは技術用語から判定し、対象の `Read Next` だけを読みます。複数プロジェクトにまたがる場合だけ、各対象を分けて扱います。対象が判定できない場合は確認してください。

| 対象 | 判定 | Read Next |
|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin / Java・Kotlin | `PLUGIN_GUIDE.md` |
| `10_plugin/AstralRecordGeyserExtension/` | Geyser Extension / Java | `10_plugin/AstralRecordGeyserExtension/README.md` |
| `10_plugin/AstralArchitect/` | AI建築 / Java・Python | `10_plugin/AstralArchitect/AGENTS.md` / `$astralarchitect-builder` |
| `20_api/AstralRecordApi/` | REST API / C# | `00_docs/10_AIパーソナライズ/API/README.md` |
| `30_web/AstralRecordWeb/` | Web / Razor Pages | `30_web/AstralRecordWeb/.agents/prompts/pages.md` |
| `30_web/AstralRecordControl/` | 独立管理Web / Agent / Runner / C# | `30_web/AstralRecordControl/AGENTS.md` |
| `00_docs/10_AIパーソナライズ/` | AIが引き継ぐ要望・判断理由 | `00_docs/10_AIパーソナライズ/README.md` |
| `00_docs/20_ゲームデザイン設計書/` | ゲームデザインのHTML資料の作成・修正 | `00_docs/20_ゲームデザイン設計書/AGENTS.md` |
| `00_docs/40_Database設計書/` | SQL Server schema docs | `00_docs/40_Database設計書/README.md` |
| `40_filebase/` | YAML master data | `00_docs/50_Filebase設計書/README.md` / `40_filebase/AGENTS.md` |
| `50_resourcepack/` | Java / Bedrock リソースパック | `50_resourcepack/AGENTS.md` |
| `60_tool/` | build/deploy/dev tools | `60_tool/README.md` |
| `.codex/skills/` | workspace skills | `.codex/skills/README.md` と対象 `SKILL.md` |

## 実行ルール

- 資料・画面・コードは既存の規約と同種の例に揃える。改善案は理由と適用範囲を示してユーザーの承認を得てから、対象の新規・既存箇所と規約・見本に反映する。
- 質問、説明、診断、読み取り専用レビューは、変更・commit・buildを伴わない限り worktree を作らない。
- 差分を作る実装・設計書・filebase・skill変更は、原則 `$astralrecord-code-version-commit-develop` を入口にする。既存 task worktree の差分commitだけなら `$astralrecord-commit-current-diff` を使う。
- 通常の変更はローカルの専用作業ツリーで実装・検証し、統合入口に従ってローカル `develop` に反映する。
- リポジトリ内の資料・スクリプトは、各工程で `git rev-parse --show-toplevel` が返す作業ルートから解決する。既存資料の Windows 固定パスは、そのリポジトリ内を指す場合に限り作業ルートに読み替える。外部配置先は実行環境の設定を使う。
- 例外として、指定filebaseを明示的な単一ライター作業で直接更新する場合は `$astralrecord-master-data-create-direct` の適用条件を確認する。コード変更・複数project変更・並列編集や、branch/worktree/merge方式の指定がある場合は統合入口を使う。
- **ブランチを `develop` にマージするときは、必ず `git merge --no-ff` でマージコミットを残す。** 早送り（`--ff-only`）マージ、squash、ブランチの履歴を消す取り込みは使わない。リベースでブランチを最新の `develop` に揃えてからマージコミットを作る。この規則は全ブランチ・全スキルに共通で、例外はユーザーが明示した場合だけ。
- 並列編集では task ごとに専用 branch / git worktree を使う。worktreeを省略するために同じ作業ツリーを共有してはいけない。
- 無関係な未コミット差分だけを理由にコミット・マージを停止しない。対象外の内容とステージ状態を保持し、[Git スキルの分離・競合確認](.codex/skills/astralrecord-git-worktree-develop/references/unrelated-changes.md)に従う。
- skillを使うときは `.codex/skills/README.md` のカタログで候補を絞り、対象 `SKILL.md` と必要な参照だけを読む。無関係なskill、プロジェクトガイド、参照資料を先読みしない。
- 詳細なレビュー、build、test、Git、filebase検証の規則は各skillを正本とする。ここに重複して書かない。
- 参考用・確認用のプレビューには、背景や合成素材を含め、画像生成AIを使わない。対象の実素材や描画定義を使ってレンダリングするか、再現した実行環境でキャプチャを取得し、どちらで作成したかを明記する。
- 実行環境や配置先が必要な場合だけ `\\DEVICE_SERVER\server` を確認し、アクセスできなければローカルで継続する。

## AIが引き継ぐ方針と判断

実装・レビュー・修正では、[AIパーソナライズの索引](00_docs/10_AIパーソナライズ/README.md) の読む場面から、今回の対象・操作に関係するメモだけを選んで読む。継続する要望や判断理由を記録・整理するときは [記録と整理](00_docs/10_AIパーソナライズ/記録と整理.md) を確認し、対象領域のメモと索引を関連する変更と一緒に整える。

## サブエージェント利用方針

- 適用する `AGENTS.md` と Skill を確認したら、独立して進められる作業を判断し、必要なサブエージェントを起動する。前提の共有や成果物の統合が難しく品質を損なう場合は、理由を進捗報告に示してメインエージェントが担当する。
- サブエージェントには GPT-6 を使用する。検索・抽出・定型変換・機械的な検証は Luna、複数資料の整理・実装・設計判断・レビューは Sol を基本とし、特に複雑で高い判断力が必要な場合だけ Astra を使用する。思考レベルは作業の難易度に合わせて選ぶ。
- 委譲時は担当範囲と所有ファイルを明示する。編集担当はファイル単位で分け、メインエージェントは結果の統合と最終確認を行う。レビューなど、差分ができてから着手できる担当はその時点で起動する。
- Skill にサブエージェントの起動条件や分担がある場合は、その規則を優先する。指定モデルを利用できない場合は代替モデルを勝手に選ばず、ユーザーに確認する。

## 文字コード

- PowerShellでUTF-8を読むときは `Get-Content -Raw -Encoding UTF8 -LiteralPath '<absolute-path>'` を使う。
- `Select-String` でも `-Encoding UTF8` を指定する。
- 既存ファイルを文字コード変換目的で再保存しない。

## Skillの選択

- 統合入口を使う変更では、そこで分類した後に対象workerを読む。`.codex/skills` の変更は `$skill-creator`、レビュー指摘の修正は対応するfix skillを使う。
- `$astralrecord-code-version-commit-develop/references/task-routing.md` は統合入口を使う変更でだけ読む。差分のない質問・説明・診断・読み取り専用レビューでは読まない。
