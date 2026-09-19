---
name: astralrecord-git-worktree-develop
description: AstralRecord workspace の git 運用専用スキル。branch / git worktree 作成、task worktree の commit、develop への rebase / fast-forward merge、成功時の branch / worktree cleanup、worktree 管理コンテンツ更新が明示された場合に使う。実装修正や filebase 作成を含む通常依頼は、まず統合入口 `$astralrecord-code-version-commit-develop` を優先し、このスキルはその prepare / finalize 下位手順として使う。プラグイン変更時の `pom.xml` 版番号更新は finalize で最新 develop へ rebase した後だけ行う。
---

# AstralRecord Git Worktree 運用

## 基本ルール

`develop` に直接 task を実装しない。task ごとに task branch と専用 git worktree を作成し、そこで作業する。範囲を限定した commit と clean な rebase が完了してから `develop` に merge する。

task が `10_plugin/AstralRecord` 配下の Plugin 成果物を変更する場合、並列実装段階では `pom.xml` を更新しない。まず task branch を最新の local `develop` に rebase し、その後、最終 merge の直前に rebase 済み task worktree 内だけで `$astralrecord-plugin-version` を実行する。

commit message の形式は `E:\AstralRecord-Workspace\COMMIT_RULES.md` を正本とする。

worktree 管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## 対応モード

この skill は2つの mode に対応する。

- Prepare mode: local `develop` から task branch と専用 worktree を作成し、後続作業用に branch 名と worktree path を報告する。
- Finalize mode: 既存の task worktree で、要求された未 commit の task file があれば確認して commit する。未 commit file がなく clean branch が `develop` より先行する task commit を持つ場合はそれを受け入れる。その後 rebase し、必要な場合だけ Plugin versioning を実行し、安全なら fast-forward merge と cleanup を行う。

この skill は一度に1つの依頼済み task branch/worktree を管理する。すでに merge 済みの `codex/*` branch の履歴的な cleanup、古い worktree metadata、現在の finalize 対象外に残る task worktree は `$astralrecord-prune-codex-worktrees` に引き継ぐ。

依頼が曖昧な場合は、文言から mode を判断する。

- `prepare`、`start`、`create branch`、`create worktree` → Prepare mode
- `commit`、`merge`、`finalize`、`close task`、`cleanup` → Finalize mode

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\COMMIT_RULES.md` を読む。
3. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
4. repository 状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
5. task slug を決める。
   - 依頼内容から安定した slug を作ることを優先する。
   - 小文字 ASCII、数字、hyphen だけを使う。
   - 既定の branch 形式は `codex/<task-slug>` とする。
6. Prepare mode の場合:
   - メイン workspace の branch は `develop` でなければならない。
   - task branch は現在の local `develop` HEAD を基点にする。別の base branch を暗黙に pull、fetch、switch しない。
   - 既定の worktree root は `E:\AstralRecord-Worktrees\<task-slug>` とする。
   - branch または worktree がすでに存在する場合は、ユーザーが再利用を明示していない限り停止して報告する。
   - branch と worktree を作成する。
   - `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を `--write-management` で再生成し、正確な branch 名と worktree path を報告する。
7. Finalize mode の場合:
   - 現在の worktree が `develop` ではなく専用 task branch 上にあることを確認する。
    - 次を実行する。
     - `git status --porcelain=v1 -uall`
     - `git diff --stat`
      - 対象を絞った `git diff -- <path>`
    - commit 状態を決める。
      - 要求された未 commit の task file がある場合は、下記の stage/check/commit 手順に従う。
      - worktree が clean の場合は、`develop..HEAD` に少なくとも1つの task commit があることを必須とする。`git log --oneline develop..HEAD`、`git diff --stat develop...HEAD`、`git diff --name-status develop...HEAD`、対象を絞った committed diff を確認する。先行するすべての commit と変更 path が要求された task に属する場合だけ続行する。新しい commit は省略して rebase に進む。
      - clean branch に無関係、説明不能、または複数範囲の先行 commit がある場合は、rebase/merge 前に停止し、明示的な分離または承認のため worktree を保持する。
      - worktree が clean で `develop..HEAD` が空の場合は finalize するものがないため停止する。
    - 要求された未 commit の task file がある場合だけ次を行う。
      - `python <worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <worktree-root>` を実行する。
      - 要求された task file だけを明示的な path で stage する。レビューだけの task では、検証済み Markdown 記録を `00_docs/99_資料/レビュー結果` 配下へ stage してよい。`git add .` や `git add -A` は使わない。
      - `git diff --cached --stat` と `git diff --cached --check` を実行する。
      - `python <worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <worktree-root>` を実行する。
      - `COMMIT_RULES.md` に従う日本語 summary で要求された task diff を commit する。
    - task branch を local `develop` に rebase する。
    - rebase により、レビュー済み path、影響する call site、contract、test、resource と交差する upstream 変更が入った場合は、merge 前に停止し、rebase 後の tree に対して対象 verification と review confirmation を再実行する。正規記録がある場合は同じ記録を更新して再検証する。confirmation に指摘がなければ記録は不要とする。統合品質ゲートの blocking criteria を適用する。新しい指摘に修正が必要なら quality gate に戻り、失敗または未解決 blocker があれば停止して worktree を保持する。
    - rebase 後の confirmation またはその修正 pass が正規記録や task file を変更した場合は、worktree-local classifier を実行し、明示した path だけを stage し、cached diff/check/mojibake 検証を再実行する。その後、filebase 検証、versioning、merge の前に rebase 後品質用の範囲限定 commit を別に作る。更新を未 commit のまま merge や cleanup を行わない。
    - rebase 済み branch が `40_filebase` の YAML を変更する場合は、merge 前に rebase 後の filebase 検証を行う。
      - 変更された各 YAML を、適用可能な schema または repository で利用できる YAML 検証 command でもう一度 parse する。
      - rebase 後の tree の全 `40_filebase/**/*.yml` を走査し、Git が conflict と報告しない別 file 間で導入された重複を含め、master ID の重複を確認する。
      - task が導入または変更したすべての reference を rebase 後の tree に対して解決する。対象 category に該当する item、skill、buff、mob、loot、shop、spawner、world reference を含める。
      - 変更 YAML を `00_docs/50_Filebase設計書/作成時チェックリスト.md` と関連 category schema に照らして再読する。
      - 検証が失敗した場合、または重複/reference の結果を安全に解決できない場合は branch/worktree を保持して停止する。先に merge して後で修正しない。
    - rebase 後、branch が Plugin 成果物を実質的に変更するか確認する。
      - `10_plugin/AstralRecord/src/` 配下の Plugin source。
      - `plugin.yml`、`config.yml`、message resource、logger resource などの Plugin resource。
      - `10_plugin/AstralRecord/` 配下の Plugin build file。
    - rebase 済み branch に Plugin 成果物の変更が残る場合は、その rebase 済み worktree で `$astralrecord-plugin-version` を起動し、`10_plugin/AstralRecord/pom.xml` の版番号 commit を別の範囲限定 commit として作成する。
    - rebase が成功し、必要な版番号 commit も完了した場合は、task branch を `develop` に fast-forward merge する。
    - fast-forward merge が成功した後は、ユーザーが保持を明示していない限り、完了報告前に必ず task worktree を削除し task branch を削除する。完了済み task worktree を後の cleanup に残さない。
    - 成功、失敗、意図した保持のいずれの場合も、`--write-management` で `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再生成する。
8. merge または rebase conflict が発生した場合:
    - 直ちに停止する。
    - branch または worktree を削除しない。
    - worktree 管理ファイルを再生成し、保持した worktree が unmerged、dirty、detached のいずれかとして見えるようにする。
    - blocker となった file と現在の状態を報告する。
9. Plugin version update またはその commit が失敗した場合:
    - 直ちに停止する。
    - branch または worktree を削除しない。
    - 報告前に worktree 管理ファイルを再生成する。
    - 失敗を報告し、rebase 済み worktree を follow-up 用に保持する。
10. finalize 成功後、ユーザーが古い merge 済み `codex/*` branch または残った task worktree の prune も依頼していた場合は、別の follow-up cleanup として `$astralrecord-prune-codex-worktrees` を実行する。

## 安全確認

次のいずれかに該当する場合は、Git state を変更する前に停止する。

- task worktree を Prepare するとき、メイン workspace が `develop` 上にない。
- task branch worktree ではなく `develop` から Finalize mode を起動している。
- 対象 task branch または worktree path がすでに存在し、再利用が明示されていない。
- merge 手順前の `develop` に未 commit 変更がある。
- 選択したファイルに無関係な作業が混在し、安全に分離できない。
- worktree が clean で、task branch に `develop` より先行する commit がない。
- rebase または merge で conflict が発生する。
- rebase 後の filebase 検証で ID 重複、解決できない変更 reference、無効な YAML/schema 内容、または曖昧な結果が見つかる。
- Plugin version update が必要だが、rebase 後に clean に完了できない。

## Worktree の規約

- メイン workspace: `E:\AstralRecord-Workspace`
- Task branch prefix: `codex/`
- 既定の task worktree root: `E:\AstralRecord-Worktrees\<task-slug>`
- Worktree 管理ファイル: `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md`

別の skill が task worktree 内で操作する必要がある場合は、workspace root prefix を置き換えて path を読み替える。

```text
E:\AstralRecord-Workspace\<relative-path>
-> E:\AstralRecord-Worktrees\<task-slug>\<relative-path>
```

## Commit 範囲のルール

- 依頼された task に属するファイルだけを commit する。
- local build output、IDE 設定、マシン固有 config、秘密情報、log、temp、無関係なユーザー変更を除外する。
- 依頼が skill の作成または更新の場合、`.codex/skills/` は commit 対象にできる。
- `00_docs/99_資料/レビュー結果/*.md` は、依頼された review または quality gate の検証済み正規成果物である場合に commit 対象にできる。
- 無関係なファイルを誤って stage した場合は `git restore --staged -- <path>` を使う。
- Plugin versioning が必要な場合、実装 commit と `pom.xml` の版番号 commit を別の範囲に保つ。

## Cleanup のルール

finalize 成功には cleanup を含める。次のすべてを満たす場合、`develop` merge 成功と同じ turn で task worktree と task branch を削除する。

- task branch の commit が成功している。
- `develop` への rebase が成功している。
- `develop` の fast-forward merge が成功している。
- ユーザーが branch または worktree の保持を明示していない。

完了した task worktree または merge 済み task branch が残っている間は、finalize を完全完了として報告しない。merge 成功後に cleanup が失敗した場合は、merge は成功、finalize は cleanup-blocked と報告し、削除が必要な正確な worktree と branch を含める。

次の場合は branch と worktree を保持する。

- rebase または merge conflict が発生した。
- 検証が失敗し、follow-up 編集が見込まれる。
- ユーザーが review または後続編集のため task workspace の保持を依頼した。
- rebase 後の Plugin version update を clean に完了できなかった。

この skill 自身の cleanup 範囲は現在の task branch/worktree までとする。複数 task にまたがる蓄積 cleanup には `$astralrecord-prune-codex-worktrees` を使う。

## Worktree 管理ファイルの内容

Prepare と Finalize の flow では、次を実行して `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成または更新する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

finalize が途中停止した場合も更新する。管理ファイルの目的は、worktree が残っている理由を説明できるようにすること。生成ファイルの `## 手動メモ` セクションは人間の判断欄として保持する。

管理ファイルに `DIRTY_WORKTREE`、`UNMERGED_WORKTREE`、`UNMERGED_BRANCH`、`DETACHED_WORKTREE`、`UNREGISTERED_PATH`、`NON_GIT_DIRECTORY` が表示される場合は、cleanup 完了とは言わず、それらを最終報告に含める。

## 使用例

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の変更用 task branch / worktree を作成し、branch 名と worktree パスを報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の現在の task worktree を finalize し、develop へ merge して、成功時は task branch / worktree を cleanup してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の task worktree を finalize し、merge 失敗時は branch / worktree を保持して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、並列実装後の E:\AstralRecord-Workspace\10_plugin\AstralRecord の task worktree を finalize し、develop へ rebase した後にだけプラグイン版番号を更新して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の task worktree を finalize し、その後に不要な codex/* branch / task worktree の掃除が必要なら $astralrecord-prune-codex-worktrees に引き継いでください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## Git結果
- <prepare / finalize の実施内容>

## Branch / Worktree
- `branch`: <branch-name>
- `worktree`: <absolute-path>

## Commit結果
- `<commit-hash>`: <要点> / 未実施

## Filebase再検証結果
- 対象: なし / <変更 category・path>
- YAML・schema: 成功 / 失敗 / 未実施
- 全体 ID 重複・変更参照: 成功 / 失敗 / 未実施

## バージョン更新結果
- 実施: はい / いいえ
- 内容: <旧版 -> 新版> / 不要 / 失敗

## Merge結果
- `rebase`: 成功 / 失敗 / 未実施
- `develop merge`: 成功 / 失敗 / 未実施

## Cleanup の結果
- `worktree`: 削除 / 保持
- `branch`: 削除 / 保持

## Worktree管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <category + branch/path>

## 残事項
- なし / <停止理由や手動対応事項>
```
