# Review Record Format

This file is the single source of truth for review records created by
`$astralrecord-code-review` and `$astralrecord-docs-review`, and updated by
their corresponding fix skills.

## Storage and Git contract

1. Resolve `<task-root>` with `git rev-parse --show-toplevel` before creating a record.
2. Never write a review record into the main `develop` checkout. If the selected root is
   `E:\AstralRecord-Workspace` on `develop`, prepare a dedicated `codex/review-*` worktree first.
3. When at least one finding needs to be handed off, store the record only at `<task-root>\00_docs\99_資料\レビュー結果`. If a review has no findings, do not create a record.
4. In an implementation workflow, keep the record in the implementation task worktree when one is created.
5. For a standalone review in a newly prepared review worktree, let `$astralrecord-git-worktree-develop`
   Finalize stage and commit the validated record before merge when one exists. In a pre-existing task
   worktree, commit only that record with an explicit path and leave finalize ownership unchanged.
6. One canonical writer owns the record. Read-only reviewers may return candidate findings,
   but must not edit the same Markdown file in parallel.

## File name

Use exactly one of these forms:

```text
(<fixed-count>／<finding-count>) yy-MM-dd HH：mm：ss <skill-name>.md
[完了] yy-MM-dd HH：mm：ss <skill-name>.md
```

- `<skill-name>` is exactly `code-review` or `docs-review`.
- Use one ASCII space after the prefix and one before the skill name.
- Use fullwidth `：` and `／` in the filename.
- Preserve the timestamp and skill name when a fix skill renames the file.
- Use the count prefix while at least one finding has `修正状態: 未修正`.
- Use `[完了]` only when every finding is `修正済み` and `未確認/質問` is `なし。`.
- Never combine the completion prefix and count prefix.

## Canonical body

Use the following headings, metadata labels, field names, and order exactly. Do not add a
summary section, rename a heading, omit an empty section, or change `/` to another character.

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

For docs reviews, replace `AR-CODE-*` / `Q-CODE-*` with `AR-DOC-*` / `Q-DOC-*`.
When a record has findings, a section with no findings or questions must contain exactly
`指摘なし。` or `なし。` on the line after its heading. Keep all other sections present. A
review with no findings must not create a record; report unresolved questions separately.

## State rules

- Start finding and question IDs at `001` and keep them sequential within the record.
- A new finding always starts with `修正状態: 未修正`.
- `指摘修正数` equals the number of findings whose state is `修正済み`.
- New questions start with `確認結果: 未確認` and `確認状態: 未確認`. Confirmed questions remain in the record with stable IDs, the supplied/adopted answer in `確認結果`, and `確認状態: 確認済み`.
- Open questions keep `完了状態: 未完了` and the count filename prefix even when all findings are fixed.
- A fix skill must not delete, summarize, reorder, or renumber existing finding content.
- The summary contains only unresolved items. Remove a fixed ID from all three summary lists.
- Store repository targets as stable workspace-relative paths so worktree cleanup does not stale the record. Use an absolute path only for a target outside the repository.
- Preserve `対象パス`, `使用スキル`, and `作成日時` during fixes and re-review.
- A re-review updates existing states and appends genuinely new findings using the next ID.
- Severity values are exactly `[高]`, `[中]`, `[低]`, or `[情報]`.

## Mandatory tools

Validate after creating or updating a record:

```powershell
python <task-root>\.codex\skills\_shared\scripts\validate_review_record.py <record-path>
```

Update fixed IDs with the updater instead of manually rewriting record metadata or filenames:

```powershell
python <task-root>\.codex\skills\_shared\scripts\update_review_record.py <record-path> --fixed AR-CODE-001 AR-CODE-003
```

Add `--resolve-question 'Q-CODE-001=<confirmed answer>'` once per confirmed question.

Do not report record creation/update as complete when validation fails.
