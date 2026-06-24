# AstralRecord Workspace Skills

このディレクトリを `E:\AstralRecord-Workspace` で利用する workspace-local skill の正本とする。

## 正本ルール

1. `.codex/skills/<skill-name>/SKILL.md` が存在するディレクトリを有効 skill として扱う。
2. skill の判定は `SKILL.md` の frontmatter `name` / `description` を正とする。
3. skill 利用時は `$<skill-name>` 形式で明示し、対象パスは絶対パスで指定する。
4. skill 追加時にルート `AGENTS.md` へ個別追記しない。
5. workspace 固有の判断基準は skill 本文か `references/` に置く。

## 使い方の基本

依頼文では、skill 名、作業内容、対象の絶対パスを指定する。

```text
Use $<skill-name> to <task> for <absolute-path> and report the result.
```

パスは `E:\AstralRecord-Workspace\...` から始まる絶対パスを推奨する。曖昧な「このへん」「docs」などの指定は避ける。

## `$astralrecord-code`

AstralRecord monorepo の実装変更を行う正本 skill。設計書パスが指定された場合は設計書を入力として実装し、実装後に関連する設計書へ反映する。直接のカスタム指示が指定された場合は、対象プロジェクトのコーディングルールに従って最小変更する。

Plugin と API の個別ルールは `$astralrecord-code` 本体に詰め込まず、`references/` 配下の専用ファイルに分けて管理する。

### 使う場面

- `00_docs` 配下の設計書や feature 設計書に基づいて Plugin/API/Web などへ実装を移したい。
- 「表示アイテムを apple から iron_ingot に変更」のような直接の実装指示を、既存規約に沿って反映したい。
- Plugin、API、Web、Database、Filebase、Resourcepack のどれを触るべきかを判定してから実装したい。

### 実行例

```text
Use $astralrecord-code to implement the design for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

```text
Use $astralrecord-code to change the display item from apple to iron_ingot for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-code to add the requested API behavior for E:\AstralRecord-Workspace\20_api\AstralRecordApi and report the result.
```

```text
Use $astralrecord-code to implement the requested change for E:\AstralRecord-Workspace\10_plugin\AstralRecord, reflect it in related design docs, and report the result.
```

### 注意点

- 先に対象プロジェクトを判定し、ルート `AGENTS.md`、ルート `README.md`、対象プロジェクト用の `references/` を読む。
- Plugin 実装では、ルート `README.md` の AstralRecord Plugin セクションと `astralrecord-code/references/plugin-code.md` を読む。
- API 実装では、ルート `README.md` の AstralRecord API セクションと `astralrecord-code/references/api-code.md` を読む。
- API エンドポイントを追加・変更する場合は、`00_docs/20_API設計書/feature/` の設計書とルート `README.md` の API 一覧をあわせて更新する。
- 旧 `.agents/prompts` 配下の補助プロンプトは、対応する `references/` に統合済みとして扱う。
- 設計書に未決事項がある場合、ユーザー判断なしに仕様を補完しない。
- docs の修正だけを行う依頼は `$astralrecord-docs-fix`、docs のレビューだけを行う依頼は `$astralrecord-docs-review` を使う。

`10_plugin/AstralRecord` のテスト雛形追加、MockBukkit 化、一時サーバースクリプト整備が主目的なら `$astralrecord-plugin-test` を優先する。

## `$astralrecord-plugin-test`

AstralRecord Plugin (`10_plugin/AstralRecord`) 向けに、JUnit / MockBukkit のテスト雛形追加、手動確認のテスト化、一時 Purpur/Paper サーバー起動スクリプト作成、AI デバッグ用の再現基盤整備を行う skill。

### 使いどころ

- `10_plugin/AstralRecord` の挙動確認を手動サーバ起動からテストへ寄せたい
- `MockBukkit` で確認できる範囲を増やしたい
- Purpur / Paper の一時検証サーバーを PowerShell で立ち上げたい
- 現在の動作サーバー一式を clone して integration 用 dev server を作りたい
- 手動再現手順を AI が追える形へ固定したい

### 実行例

```text
Use $astralrecord-plugin-test to add a JUnit and MockBukkit test scaffold for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-plugin-test to convert manual verification steps for E:\AstralRecord-Workspace\10_plugin\AstralRecord into tests and a temporary Purpur server script, then report the result.
```

```text
Use $astralrecord-plugin-test to prepare an integration dev server by cloning the configured live server package for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

### 補足

- 機能仕様そのものを変える依頼なら `$astralrecord-code` を使う。
- コード修正を伴わないレビューだけなら `$astralrecord-code-review` を使う。
- テスト追加後に task branch / worktree から `develop` へ戻したい場合は `$astralrecord-git-worktree-develop` を組み合わせる。

## `$astralrecord-docs-review`

AstralRecord の設計書をレビューする skill。ソースコードは読まず、設計書だけを対象にして、設計上の矛盾、不適切なロジック、未決事項、フォーマット差分を確認する。

### 使う場面

- `00_docs/10_プラグイン設計書` 配下の feature 設計書をレビューしたい。
- 概要、モデル定義、ユースケース、メソッド仕様、統合フロー、例外・ログ・運用の整合性を見たい。
- 実装ではなく、設計として破綻していないかを確認したい。

### 実行例

```text
Use $astralrecord-docs-review to review design docs for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

```text
Use $astralrecord-docs-review to review design docs centered on E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\03-player\3-メソッド仕様\03_3.02-サービス.md and report the result.
```

### 注意点

- この skill はソースコード、DB 定義、生成物、実行環境を参照しない。
- 判断に設計者の意思が必要な場合は、未確認事項として報告する。
- 現在の専門ルールは `astralrecord-docs-review/references/plugin-design-docs.md` にある。

## `$astralrecord-docs-fix`

`$astralrecord-docs-review` のレビュー結果を入力にして、AstralRecord の設計書 Markdown を修正する skill。

### 使う場面

- レビュー結果の `AR-DOC-*` 指摘を設計書へ反映したい。
- `修正可否: 自動修正可` の指摘だけを反映したい。
- docs の記述、構成、命名だけを直したい。

### 実行例

```text
Use $astralrecord-docs-fix to fix design docs for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status using E:\AstralRecord-Workspace\review-result.md and report the result.
```

```text
Use $astralrecord-docs-fix to fix only AR-DOC-001 for E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

### 注意点

- この skill はソースコード、DB 定義、生成物、実行環境を参照しない。
- `要確認` または `設計判断待ち` の指摘は、ユーザー判断が示された場合だけ修正する。
- plugin 設計書を修正した後は、docs review skill の `docs_structure_audit.py` を形式確認に使う。

## `$astralrecord-code-review`

AstralRecord monorepo のソースコードをレビューする skill。設計書とコードの整合、コーディングルール準拠、バグ・アルゴリズム破綻、死コード、セキュリティ、パフォーマンス、テスト不足、可読性などを点検し、ソースを編集せずに指摘レポートを出す。

### 使う場面

- 実装済みのコードがコーディングルール (`PLUGIN_GUIDE.md` / `API_GUIDE.md` / プロジェクト `AGENTS.md`) に沿っているかを確認したい。
- `00_docs/10_プラグイン設計書` などの設計書とコードの食い違いを洗い出したい。
- バグ、境界値の取り扱い不備、並行性・例外処理の問題、死コード・重複・未使用要素を検出したい。
- 修正は別途 `$astralrecord-code` / `$astralrecord-docs-fix` に渡す前提で、指摘だけ受け取りたい。

### 実行例

```text
Use $astralrecord-code-review to review code for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-code-review to review the implementation against the design at E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status and report the result.
```

```text
Use $astralrecord-code-review to review API endpoints under E:\AstralRecord-Workspace\20_api\AstralRecordApi for coding rule compliance and report the result.
```

### 注意点

- この skill はソース、設計書、設定ファイルを編集しない。修正が必要な指摘は `$astralrecord-code-fix` / `$astralrecord-docs-fix` に渡す（新規実装は `$astralrecord-code`）。
- 設計書だけのレビューは `$astralrecord-docs-review` を使う。コードレビューと docs レビューを混在させない。
- 「死コード」「未使用」を主張する前に grep で呼び出し元を確認する。確信が持てない場合は `要確認` として残す。
- 大規模リファクタや再設計提案はスコープ外。最小修正案にとどめ、構造的な変更は別タスクとして提案する。

## `$astralrecord-code-fix`

`$astralrecord-code-review` のレビュー結果を入力にして、AstralRecord monorepo のソースコードを修正する skill。`$astralrecord-docs-fix` のコード版に相当する。

### 使う場面

- レビュー結果の `AR-CODE-*` 指摘をコードへ反映したい。
- `修正可否: 自動修正可` の指摘だけを最小修正で反映したい。
- plugin / API / Web / database / filebase / resourcepack の指摘を、コーディングルールに沿って解決したい。

### 実行例

```text
Use $astralrecord-code-fix to fix code for E:\AstralRecord-Workspace\10_plugin\AstralRecord using E:\AstralRecord-Workspace\00_docs\99_資料\レビュー結果\<review-result>.md and report the result.
```

```text
Use $astralrecord-code-fix to fix only AR-CODE-001 for E:\AstralRecord-Workspace\20_api\AstralRecordApi and report the result.
```

```text
Use $astralrecord-code-fix to apply review fixes for E:\AstralRecord-Workspace\10_plugin\AstralRecord based on the review result already in the conversation, and report the result.
```

### 注意点

- この skill は設計書 (`00_docs/...` Markdown) を編集しない。docs への反映が必要な場合は `残事項` に記録し、`$astralrecord-docs-fix` に引き継ぐ。
- レビュー結果なしでの新規実装やリファクタは行わない。新規実装は `$astralrecord-code` を使う。
- `要確認` / `設計判断待ち` / `Q-CODE-*` は、レビュー結果・ユーザー回答・必読コンテキストから解決できる場合だけ修正し、解決できないものは未対応に残す。
- `00_docs\99_資料\レビュー結果` 配下にレビュー結果ファイルがある場合、修正済み指摘の `修正状態` を更新し、未完了時はファイル名先頭の `<fixed-count>／<finding-count>` を更新する（全件修正時は先頭を `[完了] ` にする）。
- 既存パターン (enum / ID / DTO / リポジトリ / メッセージ / ログカテゴリ / リソースキー) を優先し、指摘外の構造変更は行わない。

## `$astralrecord-commit-current-diff`

AstralRecord workspace の現在の branch / worktree にある未コミット差分を確認し、今回の作業に関係するファイルだけを stage して commit する skill。

### 使う場面

- すでに task branch / worktree があり、その場の差分整理と commit だけを行いたい。
- `target/`、IDE 設定、`.env`、`appsettings.Development.json` などを誤って取り込まずに、今回の変更だけを commit したい。
- `.codex/skills`、plugin、API、docs などで、現在の未コミット差分から関係ファイルだけを選別したい。

### 実行例

```text
Use $astralrecord-commit-current-diff to commit the current skill changes for E:\AstralRecord-Workspace\.codex\skills and report the result.
```

```text
Use $astralrecord-commit-current-diff to commit the current plugin changes for E:\AstralRecord-Workspace\10_plugin\AstralRecord from the current task branch and report the result.
```

```text
Use $astralrecord-commit-current-diff to commit the current review-fix changes for E:\AstralRecord-Workspace\00_docs\99_資料\レビュー結果 and report the result.
```

### 注意点

- `develop` 直コミットは既定で行わず、明示指示がない限り停止して `$astralrecord-git-worktree-develop` を案内する。
- `git add .` や `git add -A` は使わず、対象ファイルを個別に stage する。
- branch 作成、worktree 作成、`develop` への merge、cleanup を含む場合は `$astralrecord-git-worktree-develop` を使う。

## `$astralrecord-git-worktree-develop`

AstralRecord workspace で task ごとに branch と git worktree を分け、prepare・commit・develop への merge・cleanup を管理する git 運用 skill。plugin 変更がある場合の版番号更新は、finalize 時に最新 local `develop` へ rebase した後でだけ行う。

### 使う場面

- `develop` 直作業を避けて、task ごとに安全な branch / worktree を作りたい。
- `target/`、IDE 設定、`.env`、`appsettings.Development.json` などを誤って取り込まずに task 差分だけを commit したい。
- 完了後に `develop` へ fast-forward merge し、成功時だけ branch / worktree を片付けたい。
- 並列で実装した複数 task を、最後に 1 件ずつ安全に finalize したい。

### 実行例

```text
Use $astralrecord-git-worktree-develop to prepare a task branch and worktree for skill changes under E:\AstralRecord-Workspace\.codex\skills and report the branch name and worktree path.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for E:\AstralRecord-Workspace\.codex\skills, merge it into develop, and clean up the task branch and worktree if successful.
```

```text
Use $astralrecord-git-worktree-develop to finalize the current task worktree for E:\AstralRecord-Workspace\10_plugin\AstralRecord, keep the worktree if conflicts occur, and report the result.
```

### 注意点

- task の開始時は local `develop` から branch / worktree を切り、作業はその worktree で進める。
- `git add .` や `git add -A` は使わず、対象ファイルを個別に stage する。
- plugin を触る task でも、`pom.xml` の版番号更新は実装中ではなく finalize 時に行う。
- rebase / merge conflict が起きた場合は停止し、branch / worktree を残したまま報告する。

## `$astralrecord-merge-codex-branches-develop`

AstralRecord workspace の local `codex/*` branch を監査し、fast-forward 可能な branch だけを `develop` に順次マージする git 運用 skill。

### 使う場面

- 複数の Codex task branch をまとめて local `develop` へ取り込みたい。
- まず dry-run で、どの `codex/*` branch が merge 可能か確認したい。
- remote fetch / pull / push を行わず、local branch だけを対象にしたい。
- 非 fast-forward branch は merge commit にせず、個別 rebase / finalize 対象として残したい。

### 実行例

```text
Use $astralrecord-merge-codex-branches-develop to audit local codex/* branches for E:\AstralRecord-Workspace and report the result.
```

```text
Use $astralrecord-merge-codex-branches-develop to merge all fast-forwardable local codex/* branches into develop for E:\AstralRecord-Workspace and report the result.
```

```text
Use $astralrecord-merge-codex-branches-develop to merge fast-forwardable local codex/* branches into develop and delete only successfully merged branches for E:\AstralRecord-Workspace.
```

### 注意点

- 既定は dry-run。実 merge はユーザーが明示した場合だけ行う。
- `origin/codex/*` は対象外。remote 状態の更新も行わない。
- merge commit は既定で作らず、fast-forward できない branch があれば停止または skip 対象として報告する。
- cleanup は明示指示がある場合だけ行い、成功した local branch だけを削除する。

## `$astralrecord-plugin-version`

AstralRecord Plugin (`10_plugin/AstralRecord`) の版番号を更新する skill。`pom.xml` の `<version>` を正本として、SemVer ベースの開発版・リリース版・RC/alpha/beta へ更新する。通常の task 運用では、最新版 local `develop` に rebase 済みの finalize 直前 worktree でだけ使う。

### 使う場面

- プラグイン実装後に、rebase 済み finalize 直前の版番号を確定したい。
- `1.0-SNAPSHOT` のような曖昧な開発版から、`1.0.0-dev.YYYYMMDD.N` のように細かく管理したい。
- `plugin.yml` を直接触らず、Maven の `project.version` を安全に更新したい。

### 実行例

```text
Use $astralrecord-plugin-version to update the plugin version for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

```text
Use $astralrecord-plugin-version to bump the plugin to a release candidate for E:\AstralRecord-Workspace\10_plugin\AstralRecord and report the result.
```

### 注意点

- `src/main/resources/plugin.yml` は `${project.version}` 参照のため、原則 `pom.xml` だけを更新する。
- デフォルトではコミットハッシュを版番号に埋め込まない。必要な追跡情報はコミット結果として別管理する。
- 並列 task が複数走っている間は使わず、各 task を最新 `develop` へ rebase した後の finalize 時にだけ使う。
- plugin 以外だけを変更した依頼には使わない。

## `$astralrecord-code-version-commit-develop`

`$astralrecord-code` で実装し、その前後を `$astralrecord-git-worktree-develop` で囲って task branch / worktree 上で進める統合 skill。plugin 版番号更新が必要な場合でも、実装中には行わず finalize 側へ遅延させる。

### 使う場面

- 実装から版番号更新、task branch / worktree の作成、commit、`develop` への merge、cleanup までを 1 回の依頼で続けて進めたい。
- plugin 実装時は版番号更新を入れたいが、最新版 `develop` へ rebase した finalize 時にだけ実行したい。
- 実装ルール、版番号ルール、git 運用ルールを既存 skill の正本に委譲したまま順序だけ統合したい。
- 並列実行では、prepare + 実装までを先に進め、最後に merge skill へ渡したい。

### 実行例

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord and merge the resulting files back into develop through a task worktree with late plugin versioning during finalize.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested skill change for E:\AstralRecord-Workspace\.codex\skills, skip plugin versioning if the plugin was not touched, and merge the resulting files back into develop through a task worktree.
```

```text
Use $astralrecord-code-version-commit-develop to implement the requested plugin behavior for E:\AstralRecord-Workspace\10_plugin\AstralRecord in a dedicated task worktree, stop before finalize for parallel execution, and report the branch name and worktree path for later merge.
```

### 注意点

- 実装そのもののルールは `$astralrecord-code` を正本とする。
- plugin 変更が含まれる場合でも `$astralrecord-plugin-version` は finalize 時にだけ実行する。
- git 運用条件は `$astralrecord-git-worktree-develop` を正本とし、prepare / finalize のどちらかが停止した場合はその時点で停止する。
- 並列実行時は、prepare + 実装を終えた worktree を保持し、最後に `$astralrecord-git-worktree-develop` で 1 件ずつ finalize する。
- コミット対象は、task worktree 上で直前の実装と、finalize 時に必要になった版番号更新で変更したファイルだけに限定する。

## skill 追加時の README 更新

新しい skill を追加したら、この README に次の項目を追記する。

- skill 名
- 何をする skill か
- 使う場面
- 実行例
- 注意点
