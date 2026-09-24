# AstralRecord Monorepo Guide

まず依頼が質問・診断か、変更を伴う作業かを判定し、下の実行ルールで入口を選んでください。対象プロジェクトはパスまたは技術用語から判定し、対象の `Read Next` だけを読みます。複数プロジェクトにまたがる場合だけ、各対象を分けて扱います。対象が判定できない場合は確認してください。

| 対象 | 判定 | Read Next |
|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin / Java・Kotlin | `PLUGIN_GUIDE.md` |
| `10_plugin/AstralRecordGeyserExtension/` | Geyser Extension / Java | `10_plugin/AstralRecordGeyserExtension/README.md` |
| `10_plugin/AstralArchitect/` | AI建築 / Java・Python | `10_plugin/AstralArchitect/AGENTS.md` / `$astralarchitect-builder` |
| `20_api/AstralRecordApi/` | REST API / C# | `API_GUIDE.md` |
| `30_web/AstralRecordWeb/` | Web / Razor Pages | `30_web/AstralRecordWeb/AGENTS.md` |
| `00_docs/40_Database設計書/` | SQL Server schema docs | `00_docs/40_Database設計書/README.md` |
| `40_filebase/` | YAML master data | `00_docs/50_Filebase設計書/README.md` / `40_filebase/AGENTS.md` |
| `50_resourcepack/` | Resource Pack（開発停止） | `50_resourcepack/AGENTS.md` |
| `60_tool/` | build/deploy/dev tools | `60_tool/README.md` |
| `.codex/skills/` | workspace skills | `.codex/skills/README.md` と対象 `SKILL.md` |

## 実行ルール

- 質問、説明、診断、読み取り専用レビューは、変更・commit・buildを伴わない限り worktree を作らない。
- 差分を作る実装・設計書・filebase・skill変更は、原則 `$astralrecord-code-version-commit-develop` を入口にする。既存 task worktree の差分commitだけなら `$astralrecord-commit-current-diff` を使う。
- 例外として、指定filebaseを明示的な単一ライター作業で直接更新する場合は `$astralrecord-master-data-create-direct` の適用条件を確認する。コード変更・複数project変更・並列編集や、branch/worktree/merge方式の指定がある場合は統合入口を使う。
- 並列編集では task ごとに専用 branch / git worktree を使う。worktreeを省略するために同じ作業ツリーを共有してはいけない。
- skillを使うときは `.codex/skills/README.md` のカタログで候補を絞り、対象 `SKILL.md` と必要な参照だけを読む。無関係なskill、プロジェクトガイド、参照資料を先読みしない。
- 詳細なレビュー、build、test、Git、filebase検証の規則は各skillを正本とする。ここに重複して書かない。
- 実行環境や配置先が必要な場合だけ `\\DEVICE_SERVER\server` を確認し、アクセスできなければローカルで継続する。

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
