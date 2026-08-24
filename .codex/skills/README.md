# AstralRecord Workspace Skills Catalog

このファイルはskillの選択用カタログです。実行時は対象skillの `SKILL.md` だけを読み、リンクされた参照は現在のtaskに必要な場合だけ読みます。詳細な手順、チェックリスト、報告形式は各skillに重複させません。

`SKILL.md` があるディレクトリだけを有効skillとし、skill名・descriptionは各 `SKILL.md` のfrontmatterを正本とします。

## 共通ルーティング

| 目的 | skill |
|:--|:--|
| worktree作成・finalize・merge | `$astralrecord-git-worktree-develop` |
| 実装から品質ゲート・develop反映 | `$astralrecord-code-version-commit-develop` |
| 新規実装・仕様反映 | `$astralrecord-code` |
| Minecraft内スキル | `$astralrecord-skill-author` |
| 本番filebase作成 | `$astralrecord-master-data-author` |
| 指定filebaseの高速直接作成 | `$astralrecord-master-data-create-direct` |
| skill定義・参照・script更新 | `$skill-creator` |
| 現在のtask差分だけcommit | `$astralrecord-commit-current-diff` |
| コード・workspace skillレビュー | `$astralrecord-code-review` |
| コードレビュー指摘の修正 | `$astralrecord-code-fix` |
| 設計書レビュー | `$astralrecord-docs-review` |
| 設計書レビュー指摘の修正 | `$astralrecord-docs-fix` |
| 複数codex branchのmerge | `$astralrecord-merge-codex-branches-develop` |
| merged worktreeの監査・掃除 | `$astralrecord-prune-codex-worktrees` |

## 専用ルーティング

| 対象・目的 | skill |
|:--|:--|
| AstralArchitectの建築候補 | `$astralarchitect-builder` |
| Pluginの恒久・診断・integration test | `$astralrecord-plugin-test` |
| Plugin版番号 | `$astralrecord-plugin-version` |
| player/logger propertiesの未使用削除 | `$astralrecord-unused-properties-prune` |

## 差分のないtask

質問、説明、診断、読み取り専用レビューでは、変更が発生しない限りworktree、commit、build、品質ゲートを起動しません。必要な対象資料だけを読みます。

## 差分のあるtask

- 差分が発生する場合は、並列作業の有無にかかわらず、まず統合入口でtaskの種類とgateを分類します。
- 並列作業では、YAML単位ではなく独立して検証できるpackage単位でworktreeを分けます。
- filebaseの直接develop commitは、明示的な単一ライター作業に限ります。
- Pluginの版番号更新は、rebase後のfinalizeで必要な場合だけ行います。
- 対象外プロジェクトのguide、test policy、reference、plugin version手順は読みません。

## 新しいskill

新しいskillを追加した場合は、ここに「skill名・目的・使う場面」だけを1行追加します。詳細はskill内に置き、ルート `AGENTS.md` へ個別追記しません。
