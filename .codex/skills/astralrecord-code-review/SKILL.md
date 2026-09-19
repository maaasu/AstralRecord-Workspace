---
name: astralrecord-code-review
description: AstralRecord モノレポのソースコード、実装データ、workspace skill をレビューし、指摘がある場合のみ固定書式のレビュー記録を専用 task worktree 内へ安全に保存する。コード/skillレビュー、実装監査、ルール準拠、設計整合、バグ・死コード・セキュリティ・テスト・保守性の評価、実装後の独立レビューで使う。対象成果物は編集せず、可能な場合は読み取り専用サブエージェントを活用する。
---

# AstralRecord コードレビュー

## 基本ルール

実装成果物を編集せずにレビューする。対象にはソースコード、実装用 configuration/data、SQL Server schema docs、script、tool、workspace skill 定義を含める。設計書だけのレビューは `$astralrecord-docs-review` の対象とする。指摘が1件以上ある場合だけ正規レビュー記録を作成する。指摘がない場合はレビュー記録を作成せず、作成しなかったことと未解決の質問をレビュー結果に記載する。指摘は提案として扱い、実際の変更は `$astralrecord-code-fix` の担当とする。

すべての判断は、まず文書化されたルール（ルートガイド、プロジェクトの README/AGENTS.md、設計書、`references/*`）に基づける。正本となる文書がある場合、コードだけからプロジェクトルールを推測しない。文書化されたルールがない場合は一般的なエンジニアリング実務に戻し、ルール違反ではなく一般的実務に基づく判断であることを明示する。

選択した repository root を解決し、レビュー前に `<repo-root>\.codex\skills\_shared\review-record-format.md` を最後まで読む。その保存場所、ファイル名、本文スキーマ、状態、検証ルールは必須であり、この skill の例より優先する。

## Git 事前確認

レビュー記録を作成する前に、次を完了する。

1. `git rev-parse --show-toplevel` で選択した checkout を解決し、`git status --short --branch` を確認する。
2. すでに `develop` ではない専用 task worktree 内にいる場合は、その root を `<task-root>` とする。
3. 選択した checkout がメインの `develop` workspace の場合、未コミット変更がレビュー対象と重なるか確認する。重なる場合は書き込み前に停止し、その変更を task worktree へ移すよう要求する。dirty diff を含むかのように stale HEAD のコピーをレビューしてはならない。重ならない場合は `$astralrecord-git-worktree-develop` を Prepare mode で起動して `codex/review-<slug>` を作成し、対象パスをその worktree に読み替えて、新しい root を `<task-root>` とする。
4. task worktree からメイン workspace のリテラルパスへ保存しない。指摘により記録が必要な場合の保存先は `<task-root>\00_docs\99_資料\レビュー結果` だけとする。
5. レビューだけの依頼が既存 task worktree で終わり、記録を作成した場合は `$astralrecord-commit-current-diff` を起動し、検証済み記録だけを commit する。指摘がなく記録を作成しなかった場合は、記録の commit もない。要求されない限り、既存の実装 worktree を finalize しない。
6. この skill がレビュー専用 worktree を作成し、記録を作成した場合は、検証直後に `$astralrecord-git-worktree-develop` を Finalize mode で起動する。記録の stage と commit は Finalize に任せる。記録を作成しなかった場合は不要だったと報告する。finalize がブロックされた場合は branch/worktree を保持して報告する。先に commit してから Finalize を呼んだり、`develop` への書き込みに戻ったりしない。

## 必須コンテキスト

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. 絶対パスまたは技術的な手掛かりから対象プロジェクトを特定する。
   - `10_plugin/AstralRecord` → Minecraft Plugin（Java/Kotlin、Paper/Spigot、Maven）
   - `10_plugin/AstralArchitect` → AI 支援 Minecraft 建築 Plugin（Java/Paper/FAWE/Python）
   - `20_api/AstralRecordApi` → REST API（ASP.NET Core、C#）
   - `30_web/AstralRecordWeb` → Web（Razor Pages）
   - `40_filebase/` → ファイルベースのマスターデータ（YAML/Markdown）
   - `50_resourcepack/` → Minecraft Resource Pack（JSON/PNG）
   - `00_docs/40_Database設計書/` → SQL Server schema docs
   - `.codex/skills/` → Workspace skill（Markdown/Python/YAML）
   - `60_tool/` → Workspace の build/deploy/development tool（PowerShell/C#/TypeScript/BAT）
3. 判断前に対象プロジェクトの文書化されたルールを読む。
   - Plugin: ルートの `PLUGIN_GUIDE.md`、プロジェクトの `README.md`/`AGENTS.md`、`astralrecord-code/references/plugin-code.md`。
   - AstralArchitect: `10_plugin/AstralArchitect/AGENTS.md` と、そこからリンクされたプロジェクトルール。
   - API: ルートの `API_GUIDE.md`、プロジェクトの `README.md`/`AGENTS.md`、`astralrecord-code/references/api-code.md`。
   - Web: ルート `README.md` の「AstralRecord Web」節と `30_web/AstralRecordWeb/AGENTS.md`。
   - Filebase / Resourcepack / Database: ルート `README.md` の該当節と、対象領域の `AGENTS.md`/`README.md`。
   - Workspace skill: `.codex/skills/README.md`、対象の `SKILL.md`、リンクされた references/scripts、`$skill-creator` の指示。
   - Tools: `60_tool/README.md` と、対象にある `AGENTS.md` またはリンクされた tool 文書。
4. レビューが設計書領域を参照する場合は、コードと設計の整合を確認するため関連する `00_docs/...` の設計書を読む。設計書だけのレビューはここでは行わず、`$astralrecord-docs-review` の対象とする。
5. 対象プロジェクトを特定できない場合は停止し、ルート `AGENTS.md` のプロジェクト選択質問をする。

## 手順

1. Git 事前確認を完了し、ファイルへ書き込む前に `<task-root>` を確定する。
2. レビュー範囲を定義する。
   - 対象ファイル: 明示された path、feature ディレクトリ、最近の diff 範囲、または指定 module。
   - レビュー深度: quick scan か deep review か。feature/path が1つ指定された場合の既定値は deep review とする。
3. コードと設計を対応付ける。
   - `00_docs/10_Plugin設計書` 配下の Plugin feature では、`FEATURE_CATALOG.md`、feature 概要、命名規則から対応する code module を特定する。他の設計領域では、その領域に定められた entry-point ルールに従う。
   - 個別指示の範囲（例: 「ホットバー周り」）では、grep/glob で影響ファイルを列挙する。
4. 必要最小限のコードを読む。
   - entry point、public API/command/endpoint/event、service/repository 境界、data model、変更 symbol の call site。
   - 挙動を左右する test、fixture、resource file。
5. 「レビューチェックリスト」に照らしてレビューする。各問題を次のように区別する。
   - ルール違反（文書化されたルールがあり、破られている）。
   - bug / algorithmic defect（誤った挙動、境界値の欠落、race、leak）。
   - 設計不一致（コードが設計書と矛盾する）。
   - dead / 到達不能 / 未使用 code。
   - 保守性 / 可読性の懸念（一般的実務上の問題）。
6. 疑わしい問題を検証する。
   - 「unused」または「dead」と主張する前に grep で call site を追う。
   - ルール違反と主張する前に文書化されたルールを再読する。
   - 疑いが残る場合は、欠陥を作らず指摘を質問へ格下げする。
7. 範囲が非自明で、sub-agent が利用できる場合は、変更を実装していない agent に少なくとも1回の独立した読み取り専用 pass を委譲する。複数 project、security、concurrency、data-integrity の作業では、異なる観点を持つ2人目の読み取り専用 specialist を使う。agent には期待する指摘ではなく、対象 diff と正本のコンテキストを渡す。調整役の reviewer が証拠を重複排除し、正規記録の唯一の writer となる。記録を並列編集させない。
8. レビュー中に、レビュー対象の code、設計書、文書化されたルールから回答できる質問は解決する。許可されたレビュー資料から確認できない判断または事実だけを `## 未確認/質問` に残す。
9. 指摘に紐づく質問が必要な場合は `## 未確認/質問` の下に置き、`関連指摘` で指摘を参照する。指摘本文の中だけに質問を残さない。
10. 指摘が1件以上ある場合は、共有形式で正規記録を1つだけ作成する。下記の code-review 許可種別を使い、新しい指摘はすべて `修正状態: 未修正` で始め、見出しを追加しない。指摘がない場合は記録を作成しない。
    - Round 1 は指摘がある場合だけ記録を作成し、絶対パスを調整役へ返す。
    - Round 2 は記録がある場合にその正規記録パスを入力として受け取り、同じファイルを更新する。既存の ID/本文/timestamp/target を保持し、新しい連番の指摘だけを追加する。2つ目の記録や空の記録を作成しない。
11. 記録を作成した場合は `<task-root>\.codex\skills\_shared\scripts\validate_review_record.py` で検証する。通過するまで修正する。指摘のないレビューでは記録を検証・保存しない。
12. Git 事前確認で定めたレビュー専用の commit/finalize 手順を完了する。統合実装 workflow では、実装・記録・修正を1つの範囲に保つため、commit/finalize の所有権を調整役に残す。

## レビューチェックリスト

対象 project に合わせて深度を調整するが、次のカテゴリを確認する。

- 仕様整合性
  - 設計書（概要 / モデル / ユースケース / メソッド仕様 / 統合フロー / 例外・ログ・運用）と実装が一致しているか。
  - 設計書未決事項 (`9-未決事項`) を勝手に実装で確定していないか。
- コーディングルール遵守
  - 言語規約・パッケージ/レイヤ構成・命名 (`PLUGIN_GUIDE.md`/`API_GUIDE.md` などのルール準拠)。
  - DI、リポジトリ、サービス、DTO、Enum、ID、メッセージ、ログのカテゴリ運用が既存パターンに沿っているか。
- 正しさ / アルゴリズム / バグ
  - ロジックの破綻、論理矛盾、境界値 (空・null・最大値・負値・空白文字列・Unicode・タイムゾーン) の扱い。
  - 例外/エラー処理の握りつぶし、再スロー、ログ粒度、メッセージ ID/カテゴリ。
  - 状態遷移、冪等性、リトライ、ロールバック、トランザクション境界、並行性 (スレッド/コルーチン/非同期/メインスレッド制約)。
  - データ整合性 (DB / filebase / resourcepack / メモリ間の不一致)。
- セキュリティ / 信頼境界
  - 入力検証、認可/権限 (コマンド権限、API ロール)、SQL 注入、パス操作、外部入力のシリアライズ、機微情報のログ出力。
- パフォーマンス / リソース
  - N+1、無駄なループ・無駄なコピー、大きなコレクションへのロック、サーバ tick での重処理、ホットパスの I/O、未解放リソース、ファイナライザ依存。
- 死コード / 重複 / 過剰実装
  - 呼ばれていない public/internal、未使用 import/フィールド/パラメータ、コピペ、過剰抽象、フラグオフのまま残るパス、TODO/FIXME 放置。
- テスト / 検証容易性
  - テストの有無、シナリオ網羅 (正常/異常/境界)、固定値依存、テスト名と検証対象のズレ、フェイク/モックの妥当性。
- ドキュメント / 運用
  - public API、コマンド、設定キー、メッセージ、ログ ID の docs/README 反映状況。
  - resourcepack/filebase の整合 (ID, パス, モデル, JSON スキーマ)。
- 可読性 / 保守性
  - 関数長、責務集中、副作用の局所化、マジックナンバー、コメントが「なぜ」を説明しているか、命名と振る舞いの乖離。

不要な範囲は明示的に「対象外」と書く。範囲外を装って網羅率を水増ししない。

## 報告形式

レビューは日本語で記載する。記録がある場合は構成を変えずに検証済み本文を出力し、共有形式の正規本文と正確な節順を使う。指摘がない場合は記録を作成しなかったことを報告し、未解決の質問をレビュー結果に含める。重要度は `[高]`、`[中]`、`[低]`、`[情報]` のいずれかとする。

code review で使用できる `種別` は次のとおり。

`仕様不整合` | `コーディングルール違反` | `バグ/アルゴリズム` | `セキュリティ` | `パフォーマンス` | `死コード/重複` | `テスト不足` | `ドキュメント不整合` | `可読性/保守性`

新しい設計判断なしに `$astralrecord-code-fix` が解決できる場合だけ `修正可否: 自動修正可` とする。共有形式のフィールド順に `修正対象候補`、`確信度`、`修正状態` を必ず含める。

## レビュー結果ファイル

指摘が1件以上ある場合は、`<task-root>\00_docs\99_資料\レビュー結果` 配下に Markdown 記録を1つだけ保存する。指摘がない場合は記録を保存しない。指摘のない記録を作る代わりに、未解決の質問をレビュー結果へ記載する。ファイル名、メタデータ、フィールド、空値、状態遷移、検証の正本は共有形式だけとする。`<task-root>` が別 worktree の場合、`E:\AstralRecord-Workspace` をリテラルの保存先として使わない。

## 対象外

- ソースの編集、設計書の編集、設定ファイルの書き換え。必要な場合は `$astralrecord-code-fix` に引き継ぐ。
- 設計書だけの不整合チェック。これは `$astralrecord-docs-review` の範囲。
- 大規模リファクタの提案。指摘は最小修正案にとどめ、構造的な再設計は「要確認」または別タスクとして残す。

## 拡張ポイント

プロジェクト固有のレビュー観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-review.md` … `10_plugin/AstralRecord` 固有観点。
- `references/api-code-review.md` … `20_api/AstralRecordApi` 固有観点。
- `references/web-code-review.md` … `30_web/AstralRecordWeb` 固有観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
