---
name: astralrecord-code-review
description: AstralRecord モノレポのソースコードをレビューする。コードレビュー、実装監査、コーディングルール準拠の確認、設計書とコードの整合確認、バグ・アルゴリズム破綻・死コード・セキュリティ問題の検出、命名・例外処理・テスト・保守性の評価を、ソースを編集せずに行いたい場合に使う。
---

# AstralRecord Code Review

## Core Rule

Review source code only. Do not edit source, docs, or configuration during this skill. Treat findings as proposals; actual code/doc changes are the job of `$astralrecord-code` or `$astralrecord-docs-fix`.

Base every judgment on documented rules first (root guide, project README/AGENTS.md, design docs, `references/*`). Do not infer project rules from the code alone when an authoritative document exists. If documented rules are silent, fall back to general engineering practice and clearly mark the finding as a general-practice judgment rather than a rule violation.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Identify the target project from the absolute path or technical signals:
   - `10_plugin/AstralRecord` → Minecraft Plugin (Java/Kotlin, Paper/Spigot, Maven)
   - `20_api/AstralRecordApi` → REST API (ASP.NET Core, C#)
   - `30_web/AstralRecordWeb` → Web (Razor Pages)
   - `40_filebase/` → file-based master data (YAML/Markdown)
   - `50_resourcepack/` → Minecraft Resource Pack (JSON/PNG)
   - `00_docs/40_Database設計書/` → SQL Server schema docs
3. Read documented rules for the target project before judging:
   - Plugin: root `PLUGIN_GUIDE.md`, project `README.md`/`AGENTS.md`, and any `astralrecord-code/references/plugin-code.md`.
   - API: root `API_GUIDE.md`, project `README.md`/`AGENTS.md`, and `astralrecord-code/references/api-code.md`.
   - Web: root `README.md` "AstralRecord Web" section and `30_web/AstralRecordWeb/AGENTS.md`.
   - Filebase / Resourcepack / Database: the corresponding section of root `README.md` and the area's `AGENTS.md`/`README.md`.
4. When the review references a design document area, read the relevant `00_docs/...` design docs to check code↔design consistency. Do not perform a docs-only review here; that belongs to `$astralrecord-docs-review`.
5. If the target project cannot be determined, stop and ask the project-selection question from the root `AGENTS.md`.

## Workflow

1. Define the review scope:
   - File set: explicit path(s), feature directory, recent diff range, or a named module.
   - Review depth: quick scan vs. deep review. Default is deep review when a single feature/path is given.
2. Map code to design:
   - For features under `00_docs/10_Plugin設計書` or `00_docs/20_API設計書`, identify the corresponding code modules from the feature README `対象実装パス` or from naming conventions.
   - For custom-instruction scope (e.g. "ホットバー周り"), use grep/glob to enumerate the affected files.
3. Read the minimum necessary code:
   - Entry points, public APIs/commands/endpoints/events, service/repository boundaries, data models, and call sites of the changed symbols.
   - Tests, fixtures, and resource files that gate the behavior.
4. Review against the checklist (see "Review Checklist"). For each issue, distinguish:
   - Rule violation (documented rule exists and is broken).
   - Bug / algorithmic defect (incorrect behavior, missing edge case, race, leak).
   - Design mismatch (code contradicts a design doc).
   - Dead / unreachable / unused code.
   - Maintainability / readability concern (general practice).
5. Verify suspected issues:
   - Trace call sites with grep before claiming "unused" or "dead".
   - Re-read the documented rule before claiming a rule violation.
   - When a doubt remains, downgrade the finding to a question instead of inventing a defect.
6. Resolve questions that can be answered from the reviewed code, design docs, or documented rules during the review. Only leave `## 未確認/質問` entries for decisions or facts that cannot be confirmed from the allowed review sources.
7. If a finding-specific question is needed (including questions tied to a specific `AR-CODE-*` finding), put it under `## 未確認/質問` and reference the finding from `関連指摘`. Do not leave questions inline only inside the finding body.
8. Report findings in the format below. Do not modify source, docs, or configuration files, except for saving the review result file required by this skill.

## Review Checklist

Adjust depth to the target project, but cover these categories:

- 仕様整合性
  - 設計書 (`対象実装パス` / モデル / ユースケース / メソッド仕様 / 統合フロー / 例外・ログ・運用) と実装が一致しているか。
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

## Report Format

Write the review in Japanese. Start with findings, ordered by severity. Severity is one of `[高]` / `[中]` / `[低]` / `[情報]`. Use this exact section order so downstream skills can consume the result.

```markdown
## 指摘一覧

### AR-CODE-001 [高] <短い指摘タイトル>
- 種別: `仕様不整合` | `コーディングルール違反` | `バグ/アルゴリズム` | `セキュリティ` | `パフォーマンス` | `死コード/重複` | `テスト不足` | `ドキュメント不整合` | `可読性/保守性`
- 対象: `<absolute-or-workspace-relative-path>:<line>` (行が不明な場合は `<path>`)
- 関連箇所: `<path>:<line>` / `なし`
- 根拠: <ルール出典 (README/AGENTS.md/設計書) または一般原則。原則の場合はその旨明示>
- 問題: <何が不整合・誤り・危険・無駄か>
- 影響: <なぜ困るか (機能/運用/性能/セキュリティ/保守性)>
- 修正方針: <最小の変更案。複数案あるなら短く列挙>
- 修正可否: `自動修正可` | `要確認` | `設計判断待ち`
- 確信度: `高` | `中` | `低`
```

連番は `AR-CODE-001` から開始する。`修正可否: 自動修正可` は、追加の設計判断なしに `$astralrecord-code` で対応可能な指摘にだけ付ける。

findings の後に次のセクションを必ず含める。

```markdown
## 未確認/質問

### Q-CODE-001
- 関連指摘: `AR-CODE-001` / `なし`
- 確認事項: <設計者/実装者へ確認したいこと>
- 判断が必要な理由: <レビューだけで確定できない理由>

## 修正スキル入力サマリ
- 自動修正候補: `AR-CODE-001`, `AR-CODE-003` / `なし`
- 要確認: `AR-CODE-002`, `Q-CODE-001` / `なし`
- 推奨修正順: `AR-CODE-001` -> `AR-CODE-003` / `なし`
- 対象範囲: `<review target path>`

## 確認した範囲
- 対象プロジェクト: <project>
- 読んだ設計書/ルール: <paths>
- 読んだソース: <paths or globs>
- 実行した検査: <ビルド/テスト/静的解析の有無。未実行ならその理由>

## 対象外
- <意図的にレビューしなかった範囲とその理由> / `なし`
```

指摘が無い場合は `## 指摘一覧` の下に `指摘なし。` と書き、残りのセクションも省略せず記載する。

## Review Result File

レビュー結果は必ず `E:\AstralRecord-Workspace\00_docs\99_資料\レビュー結果` 配下に Markdown コピーを残す。ファイル名フォーマットは `$astralrecord-docs-review` に合わせる。

```text
yy-MM-dd HH：mm：ss<skill-name-without-astralrecord-prefix>.md
```

未完了時はファイル名の先頭に `(<fixed-count>／<finding-count>) ` を付ける。全件修正済みになった時点で先頭を `[完了] ` にし、本文メタデータの `完了状態` を `完了` にする。
ファイル名とレビュー結果本文の表示用 skill 名では `astralrecord-` prefix を省略する（例: `code-review`）。Windows の制約により `:` と `/` は全角 `：` `／` を使う。新規時は `<fixed-count>` を `0` にする。

保存ファイルには通常レポートのセクションに加えて、対象パス、skill 名、`指摘修正数 / 指摘数`、各指摘の `修正状態`、`修正スキル入力サマリ` を含める。

## Out of Scope

- ソースの編集、設計書の編集、設定ファイルの書き換え。必要な場合は `$astralrecord-code` / `$astralrecord-docs-fix` に引き継ぐ。
- 設計書だけの不整合チェック。これは `$astralrecord-docs-review` の範囲。
- 大規模リファクタの提案。指摘は最小修正案にとどめ、構造的な再設計は「要確認」または別タスクとして残す。

## Extension Points

プロジェクト固有のレビュー観点が増えたら、本文に詰め込まず `references/` に追加する。命名規則:

- `references/plugin-code-review.md` … `10_plugin/AstralRecord` 固有観点。
- `references/api-code-review.md` … `20_api/AstralRecordApi` 固有観点。
- `references/web-code-review.md` … `30_web/AstralRecordWeb` 固有観点。

参照ファイルにはパス検出条件、必読ルールファイル、追加チェック項目、報告書きのテンプレ差分だけを書き、設計書本体や大きなコードを丸ごとコピーしない。
