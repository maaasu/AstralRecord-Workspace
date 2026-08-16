---
name: astralrecord-docs-review
description: AstralRecord の設計書をソースコードを読まずにレビューし、指摘がある場合のみ固定書式のレビュー記録を専用 task worktree 内へ安全に保存する。設計整合性、不適切なロジック、意図不足、未決事項、文書間矛盾、命名・フォーマットルールの確認や docs-only 変更後の独立レビューで使う。可能な場合は読み取り専用サブエージェントを活用する。
---

# AstralRecord Docs Review

## Core Rule

Review design documents only. Do not open or infer from source code, implementation files, database schema files, generated assets, or runtime outputs. Treat implementation paths written in docs as scope labels only. Create the canonical review record only when at least one finding exists. If there are no findings, do not create a review record; report that no record was created and include any unresolved questions in the review result.

If a judgment depends on the designer's intent, gather intent from docs first: root README, feature overview, use cases, model definitions, flows, operation notes, planned specifications, unresolved issues, and related feature docs. If the intent is still unclear, report it as a question or assumption instead of forcing a defect.

Resolve the selected repository root, then read `<repo-root>\.codex\skills\_shared\review-record-format.md` completely before reviewing. Its storage, filename, body schema, state, and validation rules are mandatory.

## Git Preflight

Complete this before creating the review record.

1. Resolve the selected checkout with `git rev-parse --show-toplevel` and inspect `git status --short --branch`.
2. If already inside a dedicated non-`develop` task worktree, set that root as `<task-root>`.
3. If the selected checkout is the main `develop` workspace, inspect whether uncommitted changes overlap the review target. If they do, stop before writing and require those changes to be moved to a task worktree; never review a stale HEAD copy as though it contains the dirty diff. Otherwise invoke `$astralrecord-git-worktree-develop` in Prepare mode to create `codex/review-<slug>`, remap the target path into that worktree, and use the new root as `<task-root>`.
4. When findings require a record, save only to `<task-root>\00_docs\99_資料\レビュー結果`; never write to the literal main-workspace path from a task worktree.
5. For a review-only request in an existing task worktree and a created record, invoke `$astralrecord-commit-current-diff` and commit only the validated record. If no record was created because there were no findings, there is no record commit. Do not finalize an existing implementation worktree unless requested.
6. If this skill created a review-only worktree and a record was created, invoke `$astralrecord-git-worktree-develop` in Finalize mode directly after validation; Finalize owns staging and committing the record. If no record was created, report that no record was needed. If blocked, retain the branch/worktree and report it. Never pre-commit and then call Finalize, and never fall back to writing on `develop`.

## Workflow

1. Complete Git Preflight and define `<task-root>` before any file write.
2. Identify the target design area from the remapped absolute path.
   - `00_docs/10_Plugin設計書`: read `references/plugin-design-docs.md`.
   - Future API/Web docs: use this generic workflow, then look for an added reference file such as `references/api-design-docs.md` or `references/web-design-docs.md`. If no domain reference exists, review only general design quality and documented local rules.
3. Read documented rules before judging: the docs root README, target feature overview, and any local rule files inside the target docs tree.
4. Run `<task-root>\.codex\skills\astralrecord-docs-review\scripts\docs_structure_audit.py <absolute-docs-path>` only when reviewing `00_docs/10_Plugin設計書`. Use its output as evidence for format and structure findings, not as the whole review. For other docs areas, write `未実行（理由: docs_structure_audit.py は 10_Plugin設計書 専用）` in the checked-scope section unless a matching domain audit script has been added.
5. Read the minimum related design docs needed to understand the feature intent and cross-document contracts. Follow Wiki links to docs when they define terms, models, methods, flows, dependencies, or unresolved items.
6. Review for design defects:
   - Contradictions between overview, model, use case, method spec, integration flow, operation/logging, and unresolved issues.
   - Logic that is underspecified, impossible to implement as written, unsafe for operation, or inconsistent with its stated responsibility.
   - Missing preconditions, failure behavior, ownership boundaries, dependencies, data lifecycle, state transitions, idempotency, concurrency, rollback, or observability where the design implies them.
   - Format and naming violations against documented docs rules.
7. Separate "findings" from "questions". Do not label missing designer intent as a defect unless the current docs already require that intent to be defined.
8. For a non-trivial scope and when sub-agents are available, delegate at least one independent read-only pass. For cross-feature, operational-risk, or data-lifecycle work, use a second specialist with a distinct concern. Give raw target docs and local rules, not expected findings. The coordinator de-duplicates evidence and remains the only canonical record writer.
9. Resolve questions that can be answered from the reviewed documents during the review. Only leave `## 未確認/質問` entries for decisions or facts that cannot be confirmed from the allowed review sources.
10. If a finding-specific question is needed, put it under `## 未確認/質問` and reference the finding from `関連指摘`.
    - If no findings exist, do not create a record or run record validation; include unresolved questions in the review result instead.
11. When at least one finding exists, create exactly one canonical record using the shared format. Use the allowed docs-review types below, start every new finding with `修正状態: 未修正`, and do not add extra headings. When no findings exist, do not create a record.
    - Round 1 creates the record only when it has findings and returns its absolute path to the coordinator.
    - Round 2 must receive that canonical record path as input when one exists, update the same file, preserve existing IDs/text/timestamp/target, and append only new sequential findings. It must not create a second record or an empty record.
12. Validate the saved file with `<task-root>\.codex\skills\_shared\scripts\validate_review_record.py`. Correct it until validation passes.
13. Complete the review-only commit/finalize behavior from Git Preflight. In an integrated implementation workflow, leave commit/finalize ownership to the coordinator.

## Report Format

Write the review in Japanese. When a record exists, emit its validated body without restructuring it and use the canonical body and exact section order from the shared format. When no findings exist, report that no record was created and include unresolved questions in the review result. Severity is exactly `[高]`, `[中]`, `[低]`, or `[情報]`.

The allowed `種別` values for docs review are:

`矛盾` | `不適切なロジック` | `不足` | `未確定事項` | `形式/命名` | `運用リスク`

Set `修正可否: 自動修正可` only when `$astralrecord-docs-fix` can edit the docs without inventing intent. Always include `修正対象候補`, `確信度`, and `修正状態` in the shared field order. For `確認した範囲`, use `読んだソース: なし（設計書レビューのため）`.

## Review Result File

When at least one finding exists, save exactly one Markdown record under `<task-root>\00_docs\99_資料\レビュー結果`. If the review has no findings, save no record. Report unresolved questions instead of creating a finding-free record. The shared format is the sole authority for filename, metadata, fields, empty values, state transitions, and validation. Do not use `E:\AstralRecord-Workspace` as a literal destination when `<task-root>` is another worktree.

## Extension Points

Keep domain rules in one-level reference files:

- `references/plugin-design-docs.md` for `10_Plugin設計書`.
- Add `references/api-design-docs.md` for future API design docs.
- Add `references/web-design-docs.md` for future Web design docs.

When adding a new domain reference, include path detection, required root files, document categories, review focus, and format rules. Do not copy large docs into the skill; point the workflow at the actual docs tree.
