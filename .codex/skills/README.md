# AstralRecord Workspace Skills

このディレクトリを、`E:\AstralRecord-Workspace` で利用する workspace-local skill の正本とする。

## 正本ルール

1. `.codex/skills/<skill-name>/SKILL.md` が存在するディレクトリを有効 skill として扱う。
2. skill の判定は `SKILL.md` の frontmatter `name` / `description` を正とする。
3. skill 利用時は `/<skill-name> <引数>` 形式で指定する。
4. skill 追加時にルート `AGENTS.md` へ個別追記しない。
5. workspace 固有の判断基準は、skill 本文か `references/` に置く。

## 使い方の基本

依頼文では、slash command と引数だけを指定する。

```text
/<skill-name> <absolute-path> [追加指示]
```

パスは `E:\AstralRecord-Workspace\...` から始まる絶対パスを推奨する。相対パスや「このへん」「docs」などの曖昧な指定は避ける。追加指示は必要な場合だけ末尾に書く。

## `/astralrecord-docs-review`

AstralRecord の設計書をレビューする skill。ソースコードは読まず、設計書だけを対象にして、設計上の矛盾、不適切なロジック、未確定事項、フォーマット違反を確認する。

### 使う場面

- `00_docs/10_プラグイン設計書` 配下の feature 設計書をレビューしたい。
- 概要、モデル定義、ユースケース、メソッド仕様、統合フロー、例外・ログ・運用の整合性を見たい。
- 実装ではなく、設計として破綻していないかを確認したい。
- 設計者の意図が不明な点を、欠陥ではなく質問として洗い出したい。

### 実行例

feature 全体をレビューする。

```text
/astralrecord-docs-review E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\07-status
```

特定ファイルを中心にレビューする。

```text
/astralrecord-docs-review E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\03-player\3-メソッド仕様\03_3.02-サービス.md
```

設計書ルート全体の形式・命名も含めて確認する。

```text
/astralrecord-docs-review E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書 形式・命名・設計整合性を確認
```

### 注意点

- この skill はソースコード、DB 定義、生成物、実行環境を参照しない。
- 判断に設計者の意図が必要な場合は、関連設計書や未決事項を確認してからレビューする。
- それでも意図が分からないものは、断定的な指摘ではなく `未確認/質問` として報告する。
- 現在の専用ルールは `astralrecord-docs-review/references/plugin-design-docs.md` にある。将来 API/Web 設計書に対応する場合は `references/api-design-docs.md` や `references/web-design-docs.md` を追加する。

## `/astralrecord-commit-develop`

AstralRecord workspace の差分を確認し、コミット対象として適切なファイルだけを stage して、`develop` にコミットする skill。コミットルールの正本は `E:\AstralRecord-Workspace\COMMIT_RULES.md`。

### 使う場面

- 作業差分を `develop` にコミットしたい。
- `target/`、IDE 設定、`.env`、`appsettings.Development.json` などを誤ってコミットしたくない。
- 差分内容に合わせて適切なコミットメッセージを作ってほしい。
- 未追跡ファイルが多い状態で、今回の作業に関係するファイルだけを選別したい。

### 実行例

現在の差分を確認し、適切なものだけをコミットする。

```text
/astralrecord-commit-develop
```

skill 作成・更新分だけをコミットする。

```text
/astralrecord-commit-develop E:\AstralRecord-Workspace\.codex\skills
```

プラグイン実装変更だけをコミットする。

```text
/astralrecord-commit-develop E:\AstralRecord-Workspace\10_plugin\AstralRecord
```

設計書変更だけをコミットする。

```text
/astralrecord-commit-develop E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書
```

### 注意点

- 現在ブランチが `develop` でない場合、この skill はコミット前に停止して確認する。
- `git add .` や `git add -A` は使わず、対象ファイルを個別に stage する。
- `EXCLUDE` と分類されたファイルは、明示的な指示がない限り stage しない。
- 複数目的の差分が混在している場合は、目的ごとにコミットを分ける。
- 共有設定として明確なもの以外の dot-path は慎重に扱う。

## skill 追加時の README 更新

新しい skill を追加したら、この README に次の項目を追記する。

- skill 名
- 何をする skill か
- 使う場面
- 実行例
- 注意点
