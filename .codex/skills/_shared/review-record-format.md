# レビュー記録の形式

この file は `$astralrecord-code-review` と `$astralrecord-docs-review` が作成し、対応する fix skill が更新するレビュー記録の唯一の正本である。

## 保存場所と Git contract

1. 記録を作成する前に `git rev-parse --show-toplevel` で `<task-root>` を解決する。
2. メインの `develop` checkout にレビュー記録を書き込まない。選択した root が `develop` 上の `E:\AstralRecord-Workspace` なら、先に専用の `codex/review-*` worktree を Prepare する。
3. 1件以上の指摘を引き渡す必要がある場合、記録は `<task-root>\00_docs\99_資料\レビュー結果` にだけ保存する。指摘がないレビューでは記録を作成しない。
4. 実装 workflow では、実装 task worktree を作成した場合、その中に記録を保持する。
5. 新しく準備した review worktree で standalone review を行う場合、記録があれば `$astralrecord-git-worktree-develop` の Finalize に検証済み記録の stage と commit を任せ、merge 前に完了させる。既存 task worktree では、その記録だけを明示 path で commit し、finalize の所有権は変更しない。
6. 記録の所有者は1人の正規 writer とする。読み取り専用 reviewer は候補指摘を返してよいが、同じ Markdown file を並列編集してはならない。

## ファイル名

次の形式のいずれか1つだけを使う。

```text
(<fixed-count>／<finding-count>) yy-MM-dd HH：mm：ss <skill-name>.md
[完了] yy-MM-dd HH：mm：ss <skill-name>.md
```

- `<skill-name>` は `code-review` または `docs-review` のいずれかとする。
- prefix の後と skill name の前には ASCII space を1つ使う。
- filename では全角 `：` と `／` を使う。
- fix skill が file name を変更するときも timestamp と skill name を保持する。
- 少なくとも1件の指摘が `修正状態: 未修正` の間は count prefix を使う。
- すべての指摘が `修正済み` で `未確認/質問` が `なし。` の場合だけ `[完了]` を使う。
- 完了 prefix と count prefix を組み合わせない。

## 正規本文

次の見出し、metadata label、field name、順序を正確に使う。summary 節を追加せず、見出し名を変えず、空の節を省略せず、`/` を別の文字へ変更しない。

```markdown
# AstralRecord レビュー記録
- フォーマット版: `1`
- 使用スキル: `code-review` | `docs-review`
- 対象パス: `<stable workspace-relative review target path>`
- 作成日時: `yyyy-MM-ddTHH:mm:ss+09:00`
- 完了状態: `未完了` | `完了`
- 指摘修正数 / 指摘数: `<fixed-count> / <finding-count>`

## 指摘一覧

### AR-CODE-001 [高] <短い指摘タイトル>
- 種別: `<allowed type from the selected review skill>`
- 対象: `<path>:<line>` | `<path>`
- 関連箇所: `<path>:<line>` | `なし`
- 根拠: <根拠>
- 問題: <問題>
- 影響: <影響>
- 修正方針: <最小修正方針>
- 修正対象候補: `<path>` | `複数` | `未確定`
- 修正可否: `自動修正可` | `要確認` | `設計判断待ち`
- 確信度: `高` | `中` | `低`
- 修正状態: `未修正` | `修正済み`

## 未確認/質問

### Q-CODE-001
- 関連指摘: `AR-CODE-001` | `なし`
- 確認事項: <確認事項>
- 判断が必要な理由: <理由>
- 確認結果: `未確認` | <confirmed answer or adopted decision>
- 確認状態: `未確認` | `確認済み`

## 修正スキル入力サマリ
- 自動修正候補: `AR-CODE-001`, `AR-CODE-003` | `なし`
- 要確認: `AR-CODE-002`, `Q-CODE-001` | `なし`
- 推奨修正順: `AR-CODE-001` -> `AR-CODE-003` | `なし`
- 対象範囲: `<review target path>`

## 確認した範囲
- 対象領域: <project or docs area>
- 読んだルール/設計書: <paths> | `なし`
- 読んだソース: <paths or globs> | `なし（設計書レビューのため）`
- 実行した検査: <commands/results> | `未実行（理由: ...）`

## 対象外
- <intentionally excluded scope and reason> | `なし`
```

docs review では `AR-CODE-*` / `Q-CODE-*` を `AR-DOC-*` / `Q-DOC-*` に置き換える。
記録に指摘がある場合、指摘または質問がない節には、見出しの次の行に `指摘なし。` または `なし。` だけを記載する。その他の節はすべて残す。指摘のないレビューでは記録を作成せず、未解決の質問を別途報告する。

## 状態ルール

- 指摘 ID と質問 ID は `001` から始め、記録内で連番にする。
- 新しい指摘は必ず `修正状態: 未修正` で開始する。
- `指摘修正数` は状態が `修正済み` の指摘数と一致させる。
- 新しい質問は `確認結果: 未確認` と `確認状態: 未確認` で開始する。確認済みの質問も安定した ID とともに記録へ残し、`確認結果` に提示・採用した回答、`確認状態` に `確認済み` を記載する。
- 未解決の質問がある場合、すべての指摘を修正済みにしても `完了状態: 未完了` と count filename prefix を維持する。
- fix skill は既存の指摘本文を削除、要約、並べ替え、番号変更してはならない。
- summary には未解決の項目だけを含める。修正済み ID は3つの summary list すべてから削除する。
- worktree cleanup で記録が古くならないよう、repository の対象は安定した workspace 相対 path で保存する。repository 外の対象だけ絶対 path を使う。
- 修正と re-review の間も `対象パス`、`使用スキル`、`作成日時` を保持する。
- re-review では既存の state を更新し、新しい指摘だけを次の ID で追加する。
- 重要度の値は `[高]`、`[中]`、`[低]`、`[情報]` のいずれかとする。

## 必須ツール

記録を作成または更新した後に検証する。

```powershell
python <task-root>\.codex\skills\_shared\scripts\validate_review_record.py <record-path>
```

記録の metadata や filename を手動で書き換えず、updater で修正済み ID を更新する。

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed AR-CODE-001 AR-CODE-003
```

確認済みの質問ごとに一度 `--resolve-question 'Q-CODE-001=<confirmed answer>'` を追加する。

検証が失敗した場合は、記録の作成・更新を完了として報告しない。
