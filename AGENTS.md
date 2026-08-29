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

## サブエージェント利用方針

- タスク開始時に、独立して実行できる調査、実装、修正、検証を分解し、1件以上ある場合は原則としてサブエージェントを起動する。サブエージェント利用を任意の最適化ではなく、品質を維持できる範囲での標準手順とする。
- 適用する `AGENTS.md` と Skill を確認した後、最初の調査・実装・出力・検証のツール呼び出し前に、独立作業の有無、難易度、使用モデル、思考レベル、担当を確定し、必要なサブエージェントを先に起動する。この開始前判定を後回しにしてメインエージェントだけで作業を開始しない。
- 開始前に起動するのは、その時点で独立して着手できる担当とする。差分生成後に初めて独立できるレビュー担当などは、その前提が揃った時点で起動してよい。ただし、開始時の分解・担当決定を省略したり、起動条件が揃った担当の起動を不当に先送りしたりしてはならない。
- サブエージェント名は `<担当内容>_<モデル>_<思考レベル>` の形式とし、使用モデルと思考レベルを名前から判別できるようにする。例: `reference_check_luna_low`。
- 検索、抽出、定型変換、単純な照合など、判断が少ない軽量作業は Luna を使用する。思考レベルは原則 `low` とし、複数条件の照合など判断を伴う場合だけ `medium` に上げる。
- Excel・CSV等の定型抽出、テンプレート転記、定型変換、行数・シート構成・禁止値・数式エラー等の機械的な出力検証は、原則として Luna の軽量作業に分類する。メインエージェントは出力範囲や統合方針の判断と最終確認を担当する。
- 複数資料の整理、限定された設計判断、独立した成果物の作成・修正など、中程度の作業は Terra を使用する。思考レベルは原則 `medium` とし、仕様の競合解消や影響範囲の判断が必要な場合は `high` に上げる。
- Sol は、高難度の設計判断、全体統合、品質への影響が大きい作業など、Luna または Terra では品質を担保しにくい場合に限定する。サブエージェントへ Sol を指定する前に、Terra で代替できない理由を確認する。
- 自身が Sol または Terra の場合でも、下位モデルで品質を維持できる独立作業はサブエージェントへ委譲する。大量の資料調査、機械的な検証、結果だけを受け取ればよい解析など、メインのコンテキスト消費が大きい作業も同様とする。
- 委譲によって必要な前提が欠落する、ファイル間の一貫性が崩れる、統合コストが増えるなど、品質低下が見込まれる場合はサブエージェントを使用せず、メインエージェントが継続してよい。この例外を適用する場合は、サブエージェントを使わない理由を作業開始時の進捗報告で明示する。
- Skill にサブエージェントの起動条件、モデル、思考レベル、分割方法が定義されている場合は、その Skill の規則を本章より優先する。
- 指定したモデルまたは思考レベルを利用できない、同時実行枠が不足している、Skill 指定の起動条件を満たせないなど、本章または Skill のサブエージェント規則どおりに実施できない場合は、上位モデルへの置換、メインエージェントでの代行、規則を省略した続行を勝手に行わない。実施できない条件と代替案を示し、ユーザーの確認を得てから続行する。
- 委譲時は担当範囲と所有ファイルを明示し、委譲範囲についてメインエージェントが同じ調査・作業を重複実施しない。サブエージェントからは調査過程や大量の中間出力を返させず、統合と横断確認に必要な結論・根拠・未解決事項だけを受け取る。

## 文字コード

- PowerShellでUTF-8を読むときは `Get-Content -Raw -Encoding UTF8 -LiteralPath '<absolute-path>'` を使う。
- `Select-String` でも `-Encoding UTF8` を指定する。
- 既存ファイルを文字コード変換目的で再保存しない。

## Skillの選択

- 新規実装や仕様反映は対象プロジェクトのworker、`.codex/skills` の変更は `$skill-creator`、レビュー指摘の修正は対応するfix skillを使う。
- taskの早期終了条件とLight/Standard gateの選択は `$astralrecord-code-version-commit-develop/references/task-routing.md` を読む。
