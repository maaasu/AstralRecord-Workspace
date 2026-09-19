---
name: astralrecord-commit-current-diff
description: AstralRecord ワークスペースの現在のブランチ／作業ツリーにある未コミット差分を確認し、今回の作業に関係するファイルだけをステージングしてコミットする。すでに作業場所が決まっており、ブランチ作成・作業ツリー作成・develop への反映ではなく、今いる場所の差分整理とコミットだけを安全に行いたい場合に使う。`develop` への直接コミットは明示指示がない限り停止する。
---

# AstralRecord 現在差分コミット

## 基本ルール

現在のブランチまたは作業ツリーで、依頼された変更に属するファイルだけをコミットする。このスキルではブランチの作成・切り替え・マージを行わない。`git add .` や `git add -A` は絶対に使わない。

コミットメッセージの形式は `E:\AstralRecord-Workspace\COMMIT_RULES.md` を正本とする。無関係なユーザー変更はステージ解除のまま保持し、安全に分離できない場合は停止する。

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\COMMIT_RULES.md` を読む。
3. 現在のリポジトリ状態を確認する。
   - `git status --short --branch`
   - `git status --porcelain=v1 -uall`
   - `git diff --stat`
   - 対象を絞った `git diff -- <path>`
4. ブランチのコンテキストを確認する。
   - 専用の作業ブランチまたは作業ツリーを優先する。
   - 現在のブランチが `develop` で、ユーザーが `develop` への直接コミットを明示していない場合は停止し、`$astralrecord-git-worktree-develop` を案内する。
   - リポジトリが HEAD がブランチから分離した状態 HEAD の場合は、そこでのコミットをユーザーが明示的に要求しない限り停止する。
5. 共有分類ツールを実行する。
   - `python <current-worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <current-worktree-root>`
   - ユーザーが明示的に上書きしない限り、`EXCLUDE` はステージ対象外とする。
   - `REVIEW` は実際の差分とユーザー依頼に基づく判断が必要な対象とする。
6. ファイルを選ぶ。
   - 依頼された変更に属するファイルだけを含める。
   - 未追跡のテキストファイルは、作業に属するか判断するのに必要な範囲だけ読む。
   - 生成物、ローカル設定、秘密情報、ログ、一時ファイル、無関係な変更を除外する。
   - 現在の差分に複数の独立作業が混在している場合、安全に分けられるならコミットを分割する。分離できない場合は競合するパスを報告して停止する。
7. 安全にステージする。
   - 明示的なパスだけを使う: `git add -- <path1> <path2> ...`
   - `git diff --cached --stat` を実行する。
   - `git diff --cached --check` を実行する。
   - `python <current-worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <current-worktree-root>` を実行する。
   - 無関係なファイルを誤ってステージした場合は `git restore --staged -- <path>` で外す。
8. コミットメッセージを作成する。
   - `E:\AstralRecord-Workspace\COMMIT_RULES.md` に従う。
   - 主目的に合う種別 `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`chore` のいずれかを使う。
   - 要約は日本語で書く。
   - 処理ではなく実際の差分を記載する。
9. コミットする。
   - `git commit -m "<type>: <summary>"`
   - コミットハッシュ、ステージしたファイル、除外したファイル、残っているステージ解除パスを報告する。

## 安全確認

次のいずれかに該当する場合はコミット前に停止する。

- 直接コミットの明示指示がないのに現在のブランチが `develop` である。
- ユーザーの明示的な承認なしにリポジトリが HEAD がブランチから分離した状態 HEAD である。
- 選択したファイルに無関係な目的が混在し、安全に分離できない。
- 選択したファイルに秘密情報またはマシン固有設定が含まれているように見える。
- ステージ済み差分が空である。
- `git diff --cached --check` が失敗する。
- 文字化け確認が失敗する。

## 他スキルとの関係

- ブランチ作成、作業ツリー作成、リベース、`develop` へのマージ、後片付けをユーザーが求める場合は `$astralrecord-git-worktree-develop` を使う。
- 実装とコミット流れを1つの依頼で求める場合は `$astralrecord-code-version-commit-develop` を使う。
- ブランチまたは作業ツリーがすでにあり、現在の未コミット差分の整理とコミットだけが必要な場合にこのスキルを使う。
- レビュースキルは、Git 事前確認で専用の `develop` 以外の作業ツリーが選ばれた後に限りこれを使える。レビュー記録を `develop` 直接コミットの例外に依存させず、書き込む前にレビュー作業ツリーを用意する。

## 使用例

```text
$astralrecord-commit-current-diff を使って、E:\AstralRecord-Workspace\.codex\skills の現在のスキル変更だけをコミットし、結果を報告してください。
```

```text
$astralrecord-commit-current-diff を使って、現在の作業ブランチから E:\AstralRecord-Workspace\10_plugin\AstralRecord のプラグイン変更だけをコミットし、結果を報告してください。
```

```text
$astralrecord-commit-current-diff を使って、E:\AstralRecord-Worktrees\<task-slug>\00_docs\99_資料\レビュー結果のレビュー記録差分だけを作業ブランチにコミットし、結果を報告してください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## コミット結果
- `branch`: <current-branch>
- `worktree`: <current-worktree-root>
- `commit`: <commit-hash> / 未実行

## ステージ対象
- `included`: <ステージした主なファイル>
- `excluded`: <除外した主なファイル>
- `remaining`: <未ステージのまま残したファイル> / なし

## 注意事項
- <停止理由 or 後続対応があれば記載>
```
