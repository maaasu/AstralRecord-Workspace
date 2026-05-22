# AstralRecord Workspace Skills

このディレクトリを、`E:\AstralRecord-Workspace` で利用する workspace-local skill の正本とする。

## 正本ルール

1. `.codex/skills/<skill-name>/SKILL.md` が存在するディレクトリを有効 skill として扱う。
2. skill の判定は `SKILL.md` の frontmatter `name` / `description` を正とする。
3. skill 利用時は `$<skill-name>` 形式で明示し、対象パスは絶対パスで指定する。
4. skill 追加時にルート `AGENTS.md` へ個別追記しない。
5. workspace 固有の判断基準は、skill 本文か `references/` に置く。

## 使い方の基本

依頼文では、skill 名、作業内容、対象の絶対パスを指定する。

```text
Use $<skill-name> to <task> for <absolute-path> and report the result.
```

パスは `E:\AstralRecord-Workspace\...` から始まる絶対パスを推奨する。相対パスや「このへん」「docs」などの曖昧な指定は避ける。追加指示は必要な場合だけ末尾に書く。

## `$astralrecord-docs-review`

AstralRecord の設計書をレビューする skill。ソースコードは読まず、設計書だけを対象にして、設計上の矛盾、不適切なロジック、未確定事項、フォーマット違反を確認する。

### 使う場面

- `00_docs/10_プラグイン設計書` 配下の feature 設計書をレビューしたい。
- 概要、モデル定義、ユースケース、メソッド仕様、統合フロー、例外・ログ・運用の整合性を見たい。
- 実装ではなく、設計として破綻していないかを確認したい。
- 設計者の意図が不明な点を、欠陥ではなく質問として洗い出したい。

### 実行例

feature 全体をレビューする。

```text
Use $astralrecord-docs-review to review design docs for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

特定ファイルを中心にレビューする。

```text
Use $astralrecord-docs-review to review design docs centered on E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\03-player\3-メソッド仕様\03_3.02-サービス.md and report the result.
```

設計書ルート全体の形式・命名も含めて確認する。

```text
Use $astralrecord-docs-review to review format, naming, and design consistency for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書 and report the result.
```

### 注意点

- この skill はソースコード、DB 定義、生成物、実行環境を参照しない。
- 判断に設計者の意図が必要な場合は、関連設計書や未決事項を確認してからレビューする。
- それでも意図が分からないものは、断定的な指摘ではなく `未確認/質問` として報告する。
- 現在の専用ルールは `astralrecord-docs-review/references/plugin-design-docs.md` にある。将来 API/Web 設計書に対応する場合は `references/api-design-docs.md` や `references/web-design-docs.md` を追加する。

## `$astralrecord-commit-develop`

AstralRecord workspace の差分を確認し、コミット対象として適切なファイルだけを stage して、`develop` にコミットする skill。コミットルールの正本は `E:\AstralRecord-Workspace\COMMIT_RULES.md`。

### 使う場面

- 作業差分を `develop` にコミットしたい。
- `target/`、IDE 設定、`.env`、`appsettings.Development.json` などを誤ってコミットしたくない。
- 差分内容に合わせて適切なコミットメッセージを作ってほしい。
- 未追跡ファイルが多い状態で、今回の作業に関係するファイルだけを選別したい。

### 実行例

現在の差分を確認し、適切なものだけをコミットする。

```text
Use $astralrecord-commit-develop to inspect current changes and commit appropriate files to develop.
```

skill 作成・更新分だけをコミットする。

```text
Use $astralrecord-commit-develop to commit skill changes for E:\AstralRecord-Workspace\.codex\skills and report the result.
```

プラグイン実装変更だけをコミットする。

```text
Use $astralrecord-commit-develop to commit plugin implementation changes for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

設計書変更だけをコミットする。

```text
Use $astralrecord-commit-develop to commit design document changes for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書 and report the result.
```

### 注意点

- 現在ブランチが `develop` でない場合、この skill はコミット前に停止して確認する。
- `git add .` や `git add -A` は使わず、対象ファイルを個別に stage する。
- `EXCLUDE` と分類されたファイルは、明示的な指示がない限り stage しない。
- 複数目的の差分が混在している場合は、目的ごとにコミットを分ける。
- 共有設定として明確なもの以外の dot-path は慎重に扱う。

## `$astralrecord-docs-fix`

`$astralrecord-docs-review` のレビュー結果を入力にして、AstralRecord の設計書 Markdown を修正する skill。レビュー結果の `AR-DOC-*` 指摘、`修正方針`、`修正可否` を根拠に、docs だけを最小変更する。

### 使う場面

- レビュー結果の `自動修正可` 指摘を設計書へ反映したい。
- `AR-DOC-001` など特定の指摘だけを修正したい。
- `要確認` や `設計判断待ち` は勝手に決めず、未対応として残したい。
- ソースコードではなく docs の記述・構成・命名だけを直したい。

### 実行例

レビュー結果ファイルを入力して修正する。

```text
Use $astralrecord-docs-fix to fix design docs for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status using E:\AstralRecord-Workspace\review-result.md and report the result.
```

会話中のレビュー結果から特定 ID だけ修正する。

```text
Use $astralrecord-docs-fix to fix only AR-DOC-001 for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

### 注意点

- この skill はソースコード、DB 定義、生成物、実行環境を参照しない。
- `修正可否: 自動修正可` の指摘だけを既定で修正する。
- `要確認` または `設計判断待ち` の指摘は、ユーザーが判断内容を明示した場合だけ修正する。
- plugin 設計書を修正した後は、docs review skill の `docs_structure_audit.py` を形式確認に使う。

## `$astralrecord-code`

AstralRecord monorepo の実装変更を行う skill。設計書パスが指定された場合は設計書を入力として実装し、直接のカスタム指示が指定された場合は対象プロジェクトのコーディングルールに従って最小変更する。

### 使う場面

- `/code` 相当の依頼を workspace-local skill として実行したい。
- `00_docs` 配下の設計書や feature 設計書に基づいて Plugin/API/Web などへ実装を移したい。
- 「表示アイテムを apple から iron_ingot に変更」のような直接の実装指示を、既存規約に沿って反映したい。
- Plugin、API、Web、Database、Filebase、Resourcepack のどれを触るべきかを判定してから実装したい。

### 実行例

設計書に基づいて実装する。

```text
Use $astralrecord-code to implement the design for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

直接の変更指示を実装する。

```text
Use $astralrecord-code to change the display item from apple to iron_ingot for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

API の変更を実装する。

```text
Use $astralrecord-code to add the requested API behavior for E:\AstralRecord-Workspace\20_api\AstralRecordApi and report the result.
```

### 注意点

- 先に対象プロジェクトを判定し、ルート `AGENTS.md` と対象プロジェクト直下の `AGENTS.md` を読む。
- 対象プロジェクトの `README.md` や `.agents/prompts/*.md` が実装ルールを持つ場合は、それを優先する。
- 設計書に未決事項がある場合、ユーザー判断なしに仕様を補完しない。
- docs の修正だけを行う依頼は `$astralrecord-docs-fix`、docs のレビューだけを行う依頼は `$astralrecord-docs-review` を使う。

## skill 追加時の README 更新

新しい skill を追加したら、この README に次の項目を追記する。

- skill 名
- 何をする skill か
- 使う場面
- 実行例
- 注意点
