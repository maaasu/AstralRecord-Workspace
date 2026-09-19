---
name: astralrecord-git-worktree-develop
description: AstralRecord ワークスペースの Git 運用専用スキル。ブランチ／Git 作業ツリーの作成、作業ツリーのコミット、develop へのリベース／早送りマージ、成功時のブランチ／作業ツリー整理、作業ツリー管理コンテンツ更新が明示された場合に使う。実装修正や ファイルベース 作成を含む通常依頼は、まず統合入口 `$astralrecord-code-version-commit-develop` を優先し、このスキルはその準備／完了処理の下位手順として使う。プラグイン変更時の `pom.xml` 版番号更新は、完了処理で最新 develop へリベースした後だけ行う。
---

# AstralRecord Git 作業ツリー運用

## 基本ルール

`develop` に直接作業を実装しない。作業ごとに作業ブランチと専用 git 作業ツリーを作成し、そこで作業する。範囲を限定したコミットと未コミット変更を残さないリベースが完了してから `develop` にマージする。

作業が `10_plugin/AstralRecord` 配下のプラグイン成果物を変更する場合、並列実装段階では `pom.xml` を更新しない。まず作業ブランチを最新のローカル `develop` にリベースし、その後、最終マージの直前にリベース済み作業ツリー内だけで `$astralrecord-plugin-version` を実行する。

コミットメッセージの形式は `E:\AstralRecord-Workspace\COMMIT_RULES.md` を正本とする。

作業ツリー管理ファイルと状態分類は `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を正本として扱う。

## 対応モード

このスキルは2つのモードに対応する。

- 準備モード: ローカル `develop` から作業ブランチと専用作業ツリーを作成し、後続作業用にブランチ名と作業ツリーのパスを報告する。
- 完了処理モード: 既存の作業ツリーで、要求された未コミットの作業ファイルがあれば確認してコミットする。未コミットファイルがなく未コミット変更のないブランチが `develop` より先行する作業コミットを持つ場合はそれを受け入れる。その後リベースし、必要な場合だけプラグイン版番号更新を実行し、安全なら早送りマージと後片付けを行う。

このスキルは一度に1つの依頼済み作業ブランチ/作業ツリーを管理する。すでにマージ済みの `codex/*` ブランチの履歴的な後片付け、古い作業ツリーメタデータ、現在の完了処理対象外に残る作業ツリーは `$astralrecord-prune-codex-worktrees` に引き継ぐ。

依頼が曖昧な場合は、文言からモードを判断する。

- `prepare`、`start`、`create branch`、`create worktree` → 準備モード
- `commit`、`merge`、`finalize`、`close task`、`cleanup` → 完了処理モード

## 手順

1. `E:\AstralRecord-Workspace\AGENTS.md` を読む。
2. `E:\AstralRecord-Workspace\COMMIT_RULES.md` を読む。
3. `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` を読む。
4. リポジトリ状態を確認する。
   - `git status --short --branch`
   - `git worktree list`
5. 作業名を決める。
   - 依頼内容から安定した短い作業名を作ることを優先する。
   - 小文字 ASCII、数字、ハイフンだけを使う。
   - 既定のブランチ形式は `codex/<task-slug>` とする。
6. 準備モードの場合:
   - メインワークスペースのブランチは `develop` でなければならない。
   - 作業ブランチは現在のローカル `develop` HEAD を基点にする。別の基点ブランチを暗黙にプル、フェッチ、切り替えしない。
   - 既定の作業ツリールートは `E:\AstralRecord-Worktrees\<task-slug>` とする。
   - ブランチまたは作業ツリーがすでに存在する場合は、ユーザーが再利用を明示していない限り停止して報告する。
   - ブランチと作業ツリーを作成する。
   - `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を `--write-management` で再生成し、正確なブランチ名と作業ツリーのパスを報告する。
7. 完了処理モードの場合:
   - 現在の作業ツリーが `develop` ではなく専用作業ブランチ上にあることを確認する。
    - 次を実行する。
     - `git status --porcelain=v1 -uall`
     - `git diff --stat`
      - 対象を絞った `git diff -- <path>`
    - コミット状態を決める。
      - 要求された未コミットの作業ファイルがある場合は、下記のステージ/確認/コミット手順に従う。
      - 作業ツリーが変更のない状態の場合は、`develop..HEAD` に少なくとも1つの作業コミットがあることを必須とする。`git log --oneline develop..HEAD`、`git diff --stat develop...HEAD`、`git diff --name-status develop...HEAD`、対象を絞ったコミット済み差分を確認する。先行するすべてのコミットと変更パスが要求された作業に属する場合だけ続行する。新しいコミットは省略してリベースに進む。
      - 未コミット変更のないブランチに無関係、説明不能、または複数範囲の先行コミットがある場合は、リベース/マージ前に停止し、明示的な分離または承認のため作業ツリーを保持する。
      - 作業ツリーが変更のない状態で `develop..HEAD` が空の場合は完了処理するものがないため停止する。
    - 要求された未コミットの作業ファイルがある場合だけ次を行う。
      - `python <worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\commit_candidate_audit.py <worktree-root>` を実行する。
      - 要求された作業ファイルだけを明示的なパスでステージする。レビューだけの作業では、検証済み Markdown 記録を `00_docs/99_資料/レビュー結果` 配下へステージしてよい。`git add .` や `git add -A` は使わない。
      - `git diff --cached --stat` と `git diff --cached --check` を実行する。
      - `python <worktree-root>\.codex\skills\astralrecord-git-worktree-develop\scripts\staged_mojibake_check.py <worktree-root>` を実行する。
      - `COMMIT_RULES.md` に従う日本語要約で要求された作業差分をコミットする。
    - 作業ブランチをローカル `develop` にリベースする。
    - リベースにより、レビュー済みパス、影響する呼び出し箇所、契約、テスト、リソースと交差する取り込み元変更が入った場合は、マージ前に停止し、リベース後のツリーに対して対象検証とレビュー再確認を再実行する。正規記録がある場合は同じ記録を更新して再検証する。再確認に指摘がなければ記録は不要とする。統合品質ゲートの停止基準を適用する。新しい指摘に修正が必要なら品質ゲートに戻り、失敗または未解決停止要因があれば停止して作業ツリーを保持する。
    - リベース後の再確認またはその修正確認工程が正規記録や作業ファイルを変更した場合は、作業ツリー内の分類ツールを実行し、明示したパスだけをステージし、ステージ済み差分/確認/文字化け検証を再実行する。その後、ファイルベース 検証、版番号更新、マージの前にリベース後品質用の範囲限定コミットを別に作る。更新を未コミットのままマージや後片付けを行わない。
    - リベース済みブランチが `40_filebase` の YAML を変更する場合は、マージ前にリベース後の ファイルベース 検証を行う。
      - 変更された各 YAML を、適用可能なスキーマまたはリポジトリで利用できる YAML 検証コマンドでもう一度解析する。
      - リベース後のツリーの全 `40_filebase/**/*.yml` を走査し、Git が競合と報告しない別ファイル間で導入された重複を含め、マスタ ID の重複を確認する。
      - 作業が導入または変更したすべての参照をリベース後のツリーに対して解決する。対象カテゴリに該当するアイテム、スキル、バフ、モブ、ドロップ報酬、ショップ、スポナー、ワールド参照を含める。
      - 変更 YAML を `00_docs/50_Filebase設計書/作成時チェックリスト.md` と関連カテゴリスキーマに照らして再読する。
      - 検証が失敗した場合、または重複/参照の結果を安全に解決できない場合はブランチ/作業ツリーを保持して停止する。先にマージして後で修正しない。
    - リベース後、ブランチがプラグイン成果物を実質的に変更するか確認する。
      - `10_plugin/AstralRecord/src/` 配下のプラグインソース。
      - `plugin.yml`、`config.yml`、メッセージリソース、ログリソースなどのプラグインリソース。
      - `10_plugin/AstralRecord/` 配下のプラグインビルドファイル。
    - リベース済みブランチにプラグイン成果物の変更が残る場合は、そのリベース済み作業ツリーで `$astralrecord-plugin-version` を起動し、`10_plugin/AstralRecord/pom.xml` の版番号コミットを別の範囲限定コミットとして作成する。
    - リベースが成功し、必要な版番号コミットも完了した場合は、作業ブランチを `develop` に早送りマージする。
    - 早送りマージが成功した後は、ユーザーが保持を明示していない限り、完了報告前に必ず作業ツリーを削除し作業ブランチを削除する。完了済み作業ツリーを後の後片付けに残さない。
    - 成功、失敗、意図した保持のいずれの場合も、`--write-management` で `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を再生成する。
8. マージまたはリベース競合が発生した場合:
    - 直ちに停止する。
    - ブランチまたは作業ツリーを削除しない。
    - 作業ツリー管理ファイルを再生成し、保持した作業ツリーが未マージ、未コミット変更あり、HEAD がブランチから分離した状態 のいずれかとして見えるようにする。
    - 停止要因となったファイルと現在の状態を報告する。
9. プラグイン版番号更新またはそのコミットが失敗した場合:
    - 直ちに停止する。
    - ブランチまたは作業ツリーを削除しない。
    - 報告前に作業ツリー管理ファイルを再生成する。
    - 失敗を報告し、リベース済み作業ツリーを後続対応用に保持する。
10. 完了処理成功後、ユーザーが古いマージ済み `codex/*` ブランチまたは残った作業ツリーの整理も依頼していた場合は、別の後続対応後片付けとして `$astralrecord-prune-codex-worktrees` を実行する。

## 安全確認

次のいずれかに該当する場合は、Git 状態を変更する前に停止する。

- 作業ツリーを準備するとき、メインワークスペースが `develop` 上にない。
- 作業ブランチ作業ツリーではなく `develop` から完了処理モードを起動している。
- 対象作業ブランチまたは作業ツリーのパスがすでに存在し、再利用が明示されていない。
- マージ手順前の `develop` に未コミット変更がある。
- 選択したファイルに無関係な作業が混在し、安全に分離できない。
- 作業ツリーが変更のない状態で、作業ブランチに `develop` より先行するコミットがない。
- リベースまたはマージで競合が発生する。
- リベース後の ファイルベース 検証で ID 重複、解決できない変更参照、無効な YAML/スキーマ内容、または曖昧な結果が見つかる。
- プラグイン版番号更新が必要だが、リベース後に未コミット変更を残さず完了できない。

## 作業ツリーの規約

- メインワークスペース: `E:\AstralRecord-Workspace`
- 作業ブランチ接頭辞: `codex/`
- 既定の作業ツリールート: `E:\AstralRecord-Worktrees\<task-slug>`
- 作業ツリー管理ファイル: `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md`

別のスキルが作業ツリー内で操作する必要がある場合は、ワークスペースルート接頭辞を置き換えてパスを読み替える。

```text
E:\AstralRecord-Workspace\<relative-path>
-> E:\AstralRecord-Worktrees\<task-slug>\<relative-path>
```

## コミット範囲のルール

- 依頼された作業に属するファイルだけをコミットする。
- ローカルビルド出力、IDE 設定、マシン固有設定、秘密情報、ログ、一時ファイル、無関係なユーザー変更を除外する。
- 依頼がスキルの作成または更新の場合、`.codex/skills/` はコミット対象にできる。
- `00_docs/99_資料/レビュー結果/*.md` は、依頼されたレビューまたは品質ゲートの検証済み正規成果物である場合にコミット対象にできる。
- 無関係なファイルを誤ってステージした場合は `git restore --staged -- <path>` を使う。
- プラグイン版番号更新が必要な場合、実装コミットと `pom.xml` の版番号コミットを別の範囲に保つ。

## 整理のルール

完了処理成功には後片付けを含める。次のすべてを満たす場合、`develop` マージ成功と同じ応答で作業ツリーと作業ブランチを削除する。

- 作業ブランチのコミットが成功している。
- `develop` へのリベースが成功している。
- `develop` の早送りマージが成功している。
- ユーザーがブランチまたは作業ツリーの保持を明示していない。

完了した作業ツリーまたはマージ済み作業ブランチが残っている間は、完了処理を完全完了として報告しない。マージ成功後に後片付けが失敗した場合は、マージは成功、完了処理は後片付けが未完了と報告し、削除が必要な正確な作業ツリーとブランチを含める。

次の場合はブランチと作業ツリーを保持する。

- リベースまたはマージ競合が発生した。
- 検証が失敗し、後続対応編集が見込まれる。
- ユーザーがレビューまたは後続編集のため作業ワークスペースの保持を依頼した。
- リベース後のプラグイン版番号更新を未コミット変更を残さず完了できなかった。

このスキル自身の後片付け範囲は現在の作業ブランチ/作業ツリーまでとする。複数作業にまたがる蓄積後片付けには `$astralrecord-prune-codex-worktrees` を使う。

## 作業ツリー管理ファイルの内容

準備と完了処理の流れでは、次を実行して `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` を作成または更新する。

```powershell
python E:\AstralRecord-Workspace\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py --repo E:\AstralRecord-Workspace --worktree-root E:\AstralRecord-Worktrees --write-management
```

完了処理が途中停止した場合も更新する。管理ファイルの目的は、作業ツリーが残っている理由を説明できるようにすること。生成ファイルの `## 手動メモ` セクションは人間の判断欄として保持する。

管理ファイルに `DIRTY_WORKTREE`、`UNMERGED_WORKTREE`、`UNMERGED_BRANCH`、`DETACHED_WORKTREE`、`UNREGISTERED_PATH`、`NON_GIT_DIRECTORY` が表示される場合は、後片付け完了とは言わず、それらを最終報告に含める。

## 使用例

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の変更用作業ブランチ / 作業ツリーを作成し、ブランチ名と作業ツリーパスを報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の現在の作業ツリーを完了処理し、develop へマージして、成功時は作業ブランチ / 作業ツリーを後片付けしてください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の作業ツリーを完了処理し、マージ失敗時はブランチ / 作業ツリーを保持して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、並列実装後の E:\AstralRecord-Workspace\10_plugin\AstralRecord の作業ツリーを完了処理し、develop へリベースした後にだけプラグイン版番号を更新して結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、E:\AstralRecord-Workspace\.codex\skills の現在の作業ツリーを完了処理し、その後に不要な codex/* ブランチ / 作業ツリーの掃除が必要なら $astralrecord-prune-codex-worktrees に引き継いでください。
```

## 報告形式

結果は日本語で記載する。

```markdown
## Git結果
- <準備 / 完了処理の実施内容>

## ブランチ / 作業ツリー
- `branch`: <branch-name>
- `worktree`: <absolute-path>

## コミット結果
- `<commit-hash>`: <要点> / 未実施

## Filebase再検証結果
- 対象: なし / <変更カテゴリ・パス>
- YAML・スキーマ: 成功 / 失敗 / 未実施
- 全体 ID 重複・変更参照: 成功 / 失敗 / 未実施

## バージョン更新結果
- 実施: はい / いいえ
- 内容: <旧版 -> 新版> / 不要 / 失敗

## マージ結果
- `rebase`: 成功 / 失敗 / 未実施
- `develop merge`: 成功 / 失敗 / 未実施

## 後片付けの結果
- `worktree`: 削除 / 保持
- `branch`: 削除 / 保持

## 作業ツリー管理
- `management_file`: E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md
- 更新: はい / いいえ
- 残った確認項目: なし / <カテゴリ + ブランチ/パス>

## 残事項
- なし / <停止理由や手動対応事項>
```
