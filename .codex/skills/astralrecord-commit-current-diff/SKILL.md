---
name: astralrecord-commit-current-diff
description: AstralRecord workspace の現在の branch / worktree にある未コミット差分を確認し、今回の作業に関係するファイルだけを stage して commit する。すでに作業場所が決まっており、branch 作成・worktree 作成・develop 反映ではなく、今いる場所の差分整理とコミットだけを安全に行いたい場合に使う。`develop` 直コミットは明示指示がない限り停止する。
---

# AstralRecord 現在差分コミット

## 基本ルール

現在の branch または worktree で、依頼された変更に属するファイルだけを commit する。この skill では branch の作成・切り替え・merge を行わない。`git add .` や `git add -A` は絶対に使わない。

commit message の形式は `E:\AstralRecord-Workspace\COMMIT_RULES.md` を正本とする。無関係なユーザー変更は unstage のまま保持し、安全に分離できない場合は停止する。

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\COMMIT_RULES.md` を読む。
3. 現在の repository 状態を確認する。
   - `git status --short --branch`
   - `git status --porcelain=v1 -uall`
   - `git diff --stat`
   - targeted `git diff -- <path>`
4. branch のコンテキストを確認する。
   - 専用の task branch または task worktree を優先する。
   - 現在の branch が `develop` で、ユーザーが `develop` への直接 commit を明示していない場合は停止し、`$astralrecord-git-worktree-develop` を案内する。
   - repository が detached HEAD の場合は、そこでの commit をユーザーが明示的に要求しない限り停止する。
5. 共有 classifier を実行する。
   - `python <current-worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <current-worktree-root>`
   - ユーザーが明示的に上書きしない限り、`EXCLUDE` は stage 対象外とする。
   - `REVIEW` は実際の diff とユーザー依頼に基づく判断が必要な対象とする。
6. ファイルを選ぶ。
   - 依頼された変更に属するファイルだけを含める。
   - untracked のテキストファイルは、task に属するか判断するのに必要な範囲だけ読む。
   - 生成物、ローカル設定、秘密情報、log、temp、無関係な変更を除外する。
   - 現在の diff に複数の独立 task が混在している場合、安全に分けられるなら commit を分割する。分離できない場合は競合する path を報告して停止する。
7. 安全に stage する。
   - 明示的な path だけを使う: `git add -- <path1> <path2> ...`
   - `git diff --cached --stat` を実行する。
   - `git diff --cached --check` を実行する。
   - `python <current-worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <current-worktree-root>` を実行する。
   - 無関係なファイルを誤って stage した場合は `git restore --staged -- <path>` で外す。
8. commit message を作成する。
   - `E:\AstralRecord-Workspace\COMMIT_RULES.md` に従う。
   - 主目的に合う type `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`chore` のいずれかを使う。
   - summary は日本語で書く。
   - process ではなく実際の diff を記載する。
9. commit する。
   - `git commit -m "<type>: <summary>"`
   - commit hash、stage したファイル、除外したファイル、残っている unstage path を報告する。

## 安全確認

次のいずれかに該当する場合は commit 前に停止する。

- 直接 commit の明示指示がないのに現在の branch が `develop` である。
- ユーザーの明示的な承認なしに repository が detached HEAD である。
- 選択したファイルに無関係な目的が混在し、安全に分離できない。
- 選択したファイルに秘密情報またはマシン固有設定が含まれているように見える。
- staged diff が空である。
- `git diff --cached --check` が失敗する。
- mojibake check が失敗する。

## 他スキルとの関係

- branch 作成、worktree 作成、rebase、`develop` への merge、cleanup をユーザーが求める場合は `$astralrecord-git-worktree-develop` を使う。
- 実装と commit flow を1つの依頼で求める場合は `$astralrecord-code-version-commit-develop` を使う。
- branch または worktree がすでにあり、現在の未 commit diff の整理と commit だけが必要な場合にこの skill を使う。
- review skill は、Git 事前確認で専用の `develop` 以外の worktree が選ばれた後に限りこれを使える。レビュー記録を `develop` 直接 commit の例外に依存させず、書き込む前に review worktree を用意する。

## 使用例

```text
$astralrecord-commit-current-diff を使って、E:\AstralRecord-Workspace\.codex\skills の現在の skill 変更だけを commit し、結果を報告してください。
```

```text
$astralrecord-commit-current-diff を使って、現在の task branch から E:\AstralRecord-Workspace\10_plugin\AstralRecord の plugin 変更だけを commit し、結果を報告してください。
```

```text
$astralrecord-commit-current-diff を使って、E:\AstralRecord-Worktrees\<task-slug>\00_docs\99_資料\レビュー結果 のレビュー記録差分だけを task branch に commit し、結果を報告してください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## Commit結果
- `branch`: <current-branch>
- `worktree`: <current-worktree-root>
- `commit`: <commit-hash> / 未実行

## Stage対象
- `included`: <stage した主なファイル>
- `excluded`: <除外した主なファイル>
- `remaining`: <未 stage のまま残したファイル> / なし

## 注意事項
- <停止理由 or follow-up があれば記載>
```
