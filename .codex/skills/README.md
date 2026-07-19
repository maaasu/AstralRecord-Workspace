# AstralRecord Workspace Skills

このディレクトリは、AstralRecord モノレポで使う workspace-local skill のカタログです。
実行時の詳細ルールは各 skill の `SKILL.md` を唯一の実行正本として扱います。

## 基本方針

1. `.codex/skills/<skill-name>/SKILL.md` が存在するディレクトリを有効 skill として扱う。
2. skill の判定は `SKILL.md` の frontmatter `name` / `description` を正とする。
3. ユーザー向けの説明、`description`、`agents/openai.yaml` の表示文は日本語で書く。
4. skill 本文、チェックリスト、スクリプト内部の実装説明は英語でもよい。
5. skill 追加時にルート `AGENTS.md` へ個別追記しない。
6. workspace 固有の判断基準は skill 本文か `references/` に置く。

## Worktree First

実装、設計書修正、レビュー修正のように差分が発生する作業は、原則として task ごとの branch / git worktree で行います。

標準の流れは次の通りです。

1. `$astralrecord-git-worktree-develop` で task worktree を作る。
2. 作成された worktree の中で、目的に合う作業 skill を使う。
3. 作業が終わったら `$astralrecord-git-worktree-develop` で commit、rebase、develop への fast-forward merge、cleanup を行う。
4. 複数 task を並列で進めた場合は、最後に 1 件ずつ finalize する。
5. プラグインの版番号更新は並列作業中に行わず、finalize 時に最新 `develop` へ rebase した後だけ行う。
6. worktree の作成・finalize・掃除のたびに `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を更新し、残った worktree が消し忘れか未完了か分かる状態にする。
7. merged 済み `codex/*` branch や不要な task worktree が溜まったら、`$astralrecord-prune-codex-worktrees` で dry-run 監査してから掃除する。

1 回の依頼で worktree 作成から develop への merge まで進めたい場合は、統合入口として `$astralrecord-code-version-commit-develop` を使います。

## Filebase マスタの並列・直接作成

`$astralrecord-master-data-create-direct` は、小規模な `40_filebase` 変更を現在の `develop` へ直接 commit するための例外ルートです。このルートは直列・単一ライター専用とし、複数 task が同じ main workspace の作業ツリー、Git index、HEAD を共有して同時に編集・stage・commit してはいけません。

複数 task が実際に YAML を並列編集する場合は、YAML 1 ファイルごとではなく、独立した area / combat / economy などの coherent package ごとに task branch / worktree を作ります。各 package では、所有 path、予約 ID または ID prefix、共通ファイル担当、他 package への依存を作業前に明示し、並列実装後は依存順に 1 件ずつ finalize します。finalize では最新 `develop` へ rebase した後、filebase 全体の ID 重複と変更マスタの参照解決を再検証してから merge します。

worktree を増やさずに案出しだけを並列化する場合、並列 task は読み取り専用で YAML 案と ID 一覧を返し、1 つの統合 task だけが `$astralrecord-master-data-create-direct` を使って順次反映・commit します。

## 依頼文の書き方

skill 名、作業内容、対象の絶対パスを指定します。

```text
$<skill-name> を使って、<absolute-path> に対して <task> を行い、結果を報告してください。
```

対象パスは `E:\AstralRecord-Workspace\...` から始まる絶対パスを推奨します。曖昧な「このへん」「docs」などの指定は避けてください。

## 標準フロー

| 目的 | 使う skill | 補足 |
|:--|:--|:--|
| task worktree を作る | `$astralrecord-git-worktree-develop` | prepare と finalize の両方を担当し、worktree 管理ファイルを更新する |
| 新規実装・仕様反映 | `$astralrecord-code` | 実装と関連設計書の同期を扱う |
| 実装から develop merge まで一気通貫 | `$astralrecord-code-version-commit-develop` | worktree first の統合入口 |
| コードレビュー | `$astralrecord-code-review` | ソースを編集しない |
| コードレビュー指摘の修正 | `$astralrecord-code-fix` | レビュー結果を入力にして最小修正する |
| 設計書レビュー | `$astralrecord-docs-review` | ソースコードを読まない |
| 設計書レビュー指摘の修正 | `$astralrecord-docs-fix` | docs だけを編集する |
| 現在の branch/worktree の差分だけ commit | `$astralrecord-commit-current-diff` | branch 作成や merge はしない |
| 複数の `codex/*` branch をまとめて監査・merge | `$astralrecord-merge-codex-branches-develop` | 既定は dry-run、監査後に worktree 管理ファイルを更新 |
| merged 済み `codex/*` branch / task worktree を掃除 | `$astralrecord-prune-codex-worktrees` | 既定は dry-run、管理ファイル作成、未登録ディレクトリは手動確認 |
| 本番向け filebase マスタ追加 | `$astralrecord-master-data-author` | コンセプト・ステータス・命名方針と YAML スキーマに沿って追加 |
| 指定 filebase マスタの高速作成・直接 commit | `$astralrecord-master-data-create-direct` | 単一ライターで develop の対象ファイルだけを作成・更新し、worktree を使わず直列 commit |
| プラグインのテスト・検証基盤整備 | `$astralrecord-plugin-test` | 機能仕様変更は `$astralrecord-code` を使う |
| プラグイン版番号更新 | `$astralrecord-plugin-version` | finalize 直前の rebased worktree で使う |
| player/logger プロパティの未使用削除 | `$astralrecord-unused-properties-prune` | 専用スクリプトの結果を根拠にする |

## 作業 Skill

### `$astralrecord-code`

AstralRecord モノレポ全体の実装変更を行います。設計書パスが指定された場合は設計書を入力として実装し、実装後に関連する設計書へ反映します。

使う場面:

- `00_docs` 配下の設計書や feature 設計書に基づいて Plugin/API/Web などへ実装を移したい。
- 既存規約に沿って、直接の実装指示を反映したい。
- 対象プロジェクトを判定してから、最小範囲で実装したい。

### `$astralrecord-code-review`

AstralRecord モノレポのソースコードをレビューします。設計書とコードの整合、コーディングルール、バグ、死コード、セキュリティ、テスト不足などを点検し、ソースは編集しません。

使う場面:

- 実装済みコードがルールに沿っているか確認したい。
- 設計書とコードの食い違いを洗い出したい。
- 修正前提の指摘レポートだけを受け取りたい。

### `$astralrecord-code-fix`

`$astralrecord-code-review` のレビュー結果を入力にして、コード側の指摘を最小修正します。レビュー結果なしの新規実装は `$astralrecord-code` を使います。

使う場面:

- `AR-CODE-*` 指摘をコードへ反映したい。
- `修正可否: 自動修正可` の指摘をまとめて直したい。
- 指摘外の構造変更を避けて、既存パターンに沿って直したい。

### `$astralrecord-docs-review`

AstralRecord の設計書をレビューします。ソースコードは読まず、設計上の矛盾、不足、未決事項、フォーマット差分を確認します。

使う場面:

- `00_docs/10_Plugin設計書` 配下の feature 設計書をレビューしたい。
- 実装ではなく、設計として破綻していないか確認したい。
- 設計者判断が必要な点を未確認事項として整理したい。

### `$astralrecord-docs-fix`

`$astralrecord-docs-review` のレビュー結果を入力にして、設計書 Markdown だけを修正します。

使う場面:

- `AR-DOC-*` 指摘を設計書へ反映したい。
- `修正可否: 自動修正可` の指摘だけを反映したい。
- docs の記述、構成、命名だけを直したい。

## Git 運用 Skill

### `$astralrecord-git-worktree-develop`

task ごとに branch と git worktree を作り、作業後の commit、rebase、develop への fast-forward merge、cleanup を管理します。

使う場面:

- `develop` 直作業を避けて task worktree を作りたい。
- 完了済み task worktree を develop へ戻したい。
- 並列作業した複数 task を 1 件ずつ finalize したい。

### `$astralrecord-code-version-commit-develop`

`$astralrecord-git-worktree-develop` と `$astralrecord-code` / `$astralrecord-master-data-author` をつなぐ統合入口です。実装や本番向け filebase マスタ作成から develop への merge まで 1 回の依頼で進めたいときに使います。並列作業で worktree を残す場合も管理ファイルを更新します。

使う場面:

- worktree 作成、実装、commit、develop merge、cleanup まで任せたい。
- `40_filebase` の本番向けマスタ作成を task worktree 上で行い、そのまま develop へ反映したい。
- プラグイン版番号更新を finalize 時まで遅らせたい。
- 並列作業では prepare + 実装で止め、後から finalize したい。

### `$astralrecord-commit-current-diff`

現在の branch / worktree にある未コミット差分から、今回の作業に関係するファイルだけを stage して commit します。branch 作成や merge は行いません。

使う場面:

- すでに task worktree があり、その場の差分整理と commit だけを行いたい。
- 生成物、IDE 設定、ローカル設定を取り込まずに commit したい。
- 複数の差分から今回分だけを選別したい。

### `$astralrecord-merge-codex-branches-develop`

local の `codex/*` branch を監査し、fast-forward 可能な branch だけを `develop` に順次 merge します。監査・merge 後に worktree 管理ファイルを更新します。

使う場面:

- 複数の Codex task branch をまとめて local `develop` へ取り込みたい。
- まず dry-run で merge 可能性を確認したい。
- fast-forward できない branch を個別 rebase / finalize 対象として残したい。

### `$astralrecord-prune-codex-worktrees`

local `develop` へ取り込み済みの `codex/*` branch、不要になった task worktree、stale worktree metadata を dry-run 既定で監査し、安全な候補だけを掃除します。`E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成・更新して、残った worktree の理由を分類します。

使う場面:

- 複数 task の finalize 後に、merged 済み branch / worktree の残骸を整理したい。
- `git worktree list` に残っている欠損パスや stale metadata を prune したい。
- 削除前に、dirty worktree や未 merge branch を保持したまま安全な削除候補だけ見たい。

## 専用補助 Skill

### `$astralrecord-master-data-author`

AstralRecord のゲームコンセプト、ステータス設計、命名方針、初期オーバーワールド制作ブリーフ、filebase YAML スキーマを読み、本番向けの filebase マスタを追加します。

使う場面:

- `40_filebase` に本番向け item / equipment / material / consumable / mob / spawner / loot / shop / world マスタを追加したい。
- 最初のオーバーワールド向けに、敵・素材・初期装備・loot pool/table・spawner を一式作りたい。
- 強制ストーリーではなく、軽い世界観と命名方針に沿ってマスタの統一感を出したい。

`$astralrecord-code` との使い分け:

- filebase マスタ追加だけなら `$astralrecord-master-data-author` を使う。
- Plugin/API/Web/Resource Pack の実装変更も必要なら `$astralrecord-code` を使う。

### `$astralrecord-plugin-test`

`10_plugin/AstralRecord` 向けに、JUnit / MockBukkit のテスト雛形、手動確認のテスト化、一時 Purpur/Paper サーバースクリプト、integration 用 dev server を整備します。

使う場面:

- 手動サーバ起動の確認をテストへ寄せたい。
- MockBukkit で確認できる範囲を増やしたい。
- AI が追える再現手順や検証基盤を固定したい。

### `$astralrecord-plugin-version`

`10_plugin/AstralRecord/pom.xml` を正本としてプラグインの版番号を更新します。

使う場面:

- プラグイン実装後、rebase 済み finalize 直前に版番号を確定したい。
- 開発版、リリース版、RC/alpha/beta の版番号へ更新したい。
- `plugin.yml` を直接触らず Maven の `project.version` を更新したい。

### `$astralrecord-unused-properties-prune`

`player.properties` / `logger.properties` と対応 enum / Java・Kotlin 参照を照合し、未使用定義を洗い出して削除します。

使う場面:

- プロパティファイルのみにある定義を調べたい。
- enum のみにある定義を調べたい。
- enum と properties の両方にあるが、enum 以外から参照されていない定義を削除したい。

## skill 追加時の更新方針

新しい skill を追加したら、この README にはカタログとして次だけを追記します。

- skill 名
- 何をする skill か
- 使う場面
- ほかの skill との使い分け

詳細な手順、チェックリスト、報告形式、スクリプトの使い方は、追加した skill の `SKILL.md` に書きます。
