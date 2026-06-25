---
name: astralrecord-docs-review
description: AstralRecord の設計書をソースコードを読まずにレビューする。設計仕様、feature 設計書、Markdown 設計ファイル、Obsidian 形式 docs、00_docs 配下の文書について、設計整合性・不適切なロジック・意図不足・未決事項・文書間矛盾・命名/フォーマットルールを確認したい場合に使う。
---

# AstralRecord Docs Review

## Core Rule

Review design documents only. Do not open or infer from source code, implementation files, database schema files, generated assets, or runtime outputs. Treat implementation paths written in docs as scope labels only.

If a judgment depends on the designer's intent, gather intent from docs first: root README, feature README, overview, use cases, model definitions, flows, operation notes, unresolved issues, and related feature docs referenced by Wiki links. If the intent is still unclear, report it as a question or assumption instead of forcing a defect.

## Workflow

1. Identify the target design area from the absolute path.
   - `00_docs/10_プラグイン設計書`: read `references/plugin-design-docs.md`.
   - Future API/Web docs: use this generic workflow, then look for an added reference file such as `references/api-design-docs.md` or `references/web-design-docs.md`. If no domain reference exists, review only general design quality and documented local rules.
2. Read documented rules before judging: the nearest docs README, feature README, and any local rule files inside the target docs tree.
3. Run `scripts/docs_structure_audit.py <absolute-docs-path>` only when reviewing `00_docs/10_プラグイン設計書`. Use its output as evidence for format and structure findings, not as the whole review. For other docs areas, write `未実行（理由: docs_structure_audit.py は 10_プラグイン設計書 専用）` in the checked-scope section unless a matching domain audit script has been added.
4. Read the minimum related design docs needed to understand the feature intent and cross-document contracts. Follow Wiki links to docs when they define terms, models, methods, flows, dependencies, or unresolved items.
5. Review for design defects:
   - Contradictions between overview, model, use case, method spec, integration flow, operation/logging, and unresolved issues.
   - Logic that is underspecified, impossible to implement as written, unsafe for operation, or inconsistent with its stated responsibility.
   - Missing preconditions, failure behavior, ownership boundaries, dependencies, data lifecycle, state transitions, idempotency, concurrency, rollback, or observability where the design implies them.
   - Format and naming violations against documented docs rules.
6. Separate "findings" from "questions". Do not label missing designer intent as a defect unless the current docs already require that intent to be defined.
7. Resolve questions that can be answered from the reviewed documents during the review. Only leave `## 未確認/質問` entries for decisions or facts that cannot be confirmed from the allowed review sources.
8. If a finding-specific question is needed (including questions tied to a specific `AR-DOC-*` finding), put it under `## 未確認/質問` and reference the finding from `関連指摘`. Do not leave questions inline only inside the finding body.

## Report Format

Write the review in Japanese. Start with findings, ordered by severity. Use this exact section order so `astralrecord-docs-fix` can use the result as input.

```markdown
## 指摘一覧

### AR-DOC-001 [高] <短い指摘タイトル>
- 種別: `矛盾` | `不適切なロジック` | `不足` | `未確定事項` | `形式/命名` | `運用リスク`
- 対象: `<absolute-or-workspace-relative-path>:<line>` または `<path>` when line is unavailable
- 関連箇所: `<path>:<line>` / `なし`
- 根拠: <設計書上の根拠。ソースコード根拠は使わない>
- 問題: <何が矛盾、不明確、危険、またはルール違反か>
- 影響: <設計としてなぜ困るか>
- 修正方針: <最小の docs/design 変更>
- 修正対象候補: `<path>` / `複数` / `未確定`
- 修正可否: `自動修正可` | `要確認` | `設計判断待ち`
```

Use sequential IDs starting at `AR-DOC-001`. Set `修正可否: 自動修正可` only when the report contains enough information to edit docs without inventing design intent. Use `要確認` or `設計判断待ち` when the fix requires a new product/design decision.

After findings, include these sections:

```markdown
## 未確認/質問

### Q-DOC-001
- 関連指摘: `AR-DOC-001` / `なし`
- 確認事項: <設計者に確認したいこと>
- 判断が必要な理由: <なぜレビューだけでは確定できないか>

## 修正スキル入力サマリ
- 自動修正候補: `AR-DOC-001`, `AR-DOC-003` / `なし`
- 要確認: `AR-DOC-002`, `Q-DOC-001` / `なし`
- 推奨修正順: `AR-DOC-001` -> `AR-DOC-003` / `なし`
- 対象範囲: `<review target path>`

## 確認した範囲
- 読んだ設計書: <paths>
- 実行した検査: `docs_structure_audit.py <path>` / `未実行（理由: ...）`

## ソース参照
ソースコードは参照していません。
```

If there are no findings, write `## 指摘一覧` followed by `指摘なし。` and still include residual questions, checked scope, and source-reference sections.

## Review Result File

Always save a Markdown copy of the review result under `E:\AstralRecord-Workspace\00_docs\99_資料\レビュー結果`.

Use this filename format:

```text
yy-MM-dd HH：mm：ss<skill-name-without-astralrecord-prefix>.md
```

When the result is not complete, prefix the filename with `(<fixed-count>／<finding-count>) `. When all findings are fixed, prefix the filename with `[完了] ` instead and update the metadata in the file to `完了状態: 完了`.
Use the skill name without the `astralrecord-` prefix in the filename and visible review metadata (for example, `docs-review` instead of `astralrecord-docs-review`). Windows file names cannot contain `:` or `/`, so use fullwidth `：` and `／` in the filename. For a new review result, set `<fixed-count>` to `0`.

The saved file must keep the normal report sections and include:

- the review target path.
- the skill name.
- `指摘修正数 / 指摘数`.
- each finding's `修正状態`.
- a `修正スキル入力サマリ` section that can be passed directly to `astralrecord-docs-fix`.

## Extension Points

Keep domain rules in one-level reference files:

- `references/plugin-design-docs.md` for `10_プラグイン設計書`.
- Add `references/api-design-docs.md` for future API design docs.
- Add `references/web-design-docs.md` for future Web design docs.

When adding a new domain reference, include path detection, required root files, document categories, review focus, and format rules. Do not copy large docs into the skill; point the workflow at the actual docs tree.
