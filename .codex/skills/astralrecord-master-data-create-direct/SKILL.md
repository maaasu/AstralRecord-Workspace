---
name: astralrecord-master-data-create-direct
description: AstralRecord の 40_filebase に、ユーザーが指定した item・equipment・material・consumable・class・skill・mob・spawner・world・loot・shop などのマスターデータを単一ライターで高速に作成・更新し、対象ファイルだけを develop に直接コミットする。マスターデータ制作、filebase YAML の追加、指定 YAML の修正、ショップや装備などのマスター定義変更を直列実行するときに使用する。ワークツリー作成やソースコード実装を行わない。
---

# AstralRecord Master Data Create Direct

指定された `40_filebase` のマスターデータだけを、単一ライターとして現在の `develop` で作成・更新してコミットする。通常の実装作業で使う worktree 作成系スキルは呼び出さない。

## Scope

- 対象は `E:\AstralRecord-Workspace\40_filebase` 配下の YAML と、必要最小限のマスターデータ文書のみ。
- Plugin/API/Web/Resource Pack のソース変更、設計書の大規模修正、branch/worktree 操作は行わない。
- ユーザーが明示した対象パスと変更内容を優先し、不要なマスターや関連機能を増やさない。
- 作業完了時は、依頼に関係するファイルだけを stage して commit する。

## Concurrency rule

- このスキルは main workspace の作業ツリー、Git index、HEAD を占有する直列・単一ライター用とする。同じ `develop` で複数の書き込み task を同時実行しない。
- branch 名だけを分けても同じ作業ディレクトリは分離されない。複数 task が実際にファイルを編集する場合は `$astralrecord-code-version-commit-develop` で task ごとの branch / worktree を使う。
- worktree を使わず案出しを並列化する場合、並列 task はファイルを編集・stage・commit せず、読み取り専用で YAML 案、候補 ID、参照一覧を返す。1 つの統合 task だけがこのスキルで順次反映する。
- 別の direct writer が動作中と分かっている場合、または作業中に外部 task の差分・stage 状態が増減した場合は、編集や commit を続けず停止して報告する。

## Fast workflow

1. `git status --short --branch` と staged diff を確認し、現在の branch が `develop` であること、別の direct writer が動作中でないこと、既存差分を今回分と安全に分離できることを確認する。
2. 対象を確定する。ユーザーが絶対パスを指定した場合はその配下だけを対象にする。パスがない場合は `40_filebase` 内のカテゴリと ID を検索して候補を絞る。
3. `AGENTS.md`、`40_filebase\AGENTS.md`、`00_docs\50_Filebase設計書\README.md`、対象カテゴリの `feature\<category>.md`、YAML スキーマ、近隣の既存 YAML を読む。新規データでは `モチーフ選定ガイド.md` と `作成時チェックリスト.md` も読む。
4. 既存 ID を `40_filebase/**/*.yml` で検索する。新規 ID は既存規則に合わせた lowercase snake_case とし、参照先 ID・category・schemaVersion・ファイル名を既存例に合わせる。
5. 依頼された範囲だけを編集する。既存ファイルの記載順・コメント・フォーマットを保ち、参照は `ref: item:<id>` など対象スキーマの形式にする。未対応の実装機能を YAML だけで発明しない。
6. 変更後に YAML を再読込して、ID 重複・必須キー・参照先・slot/row/column など対象スキーマ固有の制約を確認する。可能なら Python の `yaml.safe_load`、必ず `git diff --check` を使う。
7. `git status --short --branch` と差分を再確認する。`git add .` / `git add -A` は使わず、依頼対象の絶対パスまたは明示的な相対パスだけを stage する。staged diff を確認してから commit する。

## Git rules

- develop で直接作業する。branch 作成、worktree 作成・切替、rebase、merge、cleanup は行わない。
- 既存の無関係な差分を消去・上書き・stash しない。対象が混在して安全に分離できない場合は、対象ファイルの差分を保ったまま停止して報告する。
- コミットメッセージは `E:\AstralRecord-Workspace\COMMIT_RULES.md` に従う。新規マスターは `feat: ...`、既存マスターの不具合・定義修正は `fix: ...` を基本とする。
- コミット後に `git status --short --branch` を確認し、残った差分が今回の依頼外であることを報告する。

## Quality bar

- プレイヤー表示文は日本語、技術 ID・enum・Bukkit Material 名は既存の英語形式にする。
- 新規マスターは具体的な gameplay purpose を持たせ、参照切れ・重複 ID・未定義カテゴリを残さない。
- 変更は最小限にし、設計書や実装を推測で同期しない。必要な制約が確認できない場合は作業を止めて報告する。

## Handoff conditions

次の場合はこのスキルの範囲外として、実装・worktree 用スキルを使う判断を報告する。

- Plugin/API/Web のコード変更が必要。
- マスターデータ以外の複数プロジェクト変更が必要。
- ユーザーが branch、worktree、develop への merge 方式を指定した。
- 複数の書き込み task を並列実行する必要がある。
