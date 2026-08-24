# AstralRecord Monorepo Guide

対象プロジェクトをパスまたは技術用語から判定し、対象の `Read Next` だけを読んでください。複数プロジェクトにまたがる場合だけ、各対象を分けて扱います。対象が判定できない場合は確認してください。

| 対象 | 判定 | Read Next |
|:--|:--|:--|
| `10_plugin/AstralRecord/` | Minecraft Plugin / Java・Kotlin | `PLUGIN_GUIDE.md` / `$astralrecord-code` |
| `10_plugin/AstralArchitect/` | AI建築 / Java・Python | `10_plugin/AstralArchitect/AGENTS.md` / `$astralarchitect-builder` |
| `20_api/AstralRecordApi/` | REST API / C# | `API_GUIDE.md` / `$astralrecord-code` |
| `30_web/AstralRecordWeb/` | Web / Razor Pages | `30_web/AstralRecordWeb/AGENTS.md` |
| `00_docs/40_Database設計書/` | SQL Server schema docs | `00_docs/40_Database設計書/README.md` |
| `40_filebase/` | YAML master data | `00_docs/50_Filebase設計書/README.md` / `40_filebase/AGENTS.md` |
| `50_resourcepack/` | Resource Pack（開発停止） | `50_resourcepack/AGENTS.md` |
| `60_tool/` | build/deploy/dev tools | `60_tool/README.md` |
| `.codex/skills/` | workspace skills | `.codex/skills/README.md` と対象 `SKILL.md` |

## 実行ルール

- 質問、説明、診断、読み取り専用レビューは、変更・commit・buildを伴わない限り worktree を作らない。
- 差分を作る実装・設計書・filebase・skill変更は、原則 `$astralrecord-code-version-commit-develop` を入口にする。既存 task worktree の差分commitだけなら `$astralrecord-commit-current-diff` を使う。
- 並列編集では task ごとに専用 branch / git worktree を使う。worktreeを省略するために同じ作業ツリーを共有してはいけない。
- skillを使うときは `.codex/skills/README.md` のカタログで候補を絞り、対象 `SKILL.md` と必要な参照だけを読む。無関係なskill、プロジェクトガイド、参照資料を先読みしない。
- 詳細なレビュー、build、test、Git、filebase検証の規則は各skillを正本とする。ここに重複して書かない。
- 実行環境や配置先が必要な場合だけ `\\DEVICE_SERVER\server` を確認し、アクセスできなければローカルで継続する。

## 文字コード

- PowerShellでUTF-8を読むときは `Get-Content -Raw -Encoding UTF8 -LiteralPath '<absolute-path>'` を使う。
- `Select-String` でも `-Encoding UTF8` を指定する。
- 既存ファイルを文字コード変換目的で再保存しない。

## Skillの選択

- 新規実装や仕様反映は対象プロジェクトのworker、`.codex/skills` の変更は `$skill-creator`、レビュー指摘の修正は対応するfix skillを使う。
- taskの早期終了条件とLight/Standard gateの選択は `$astralrecord-code-version-commit-develop/references/task-routing.md` を読む。
