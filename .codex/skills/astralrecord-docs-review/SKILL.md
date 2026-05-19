---
name: astralrecord-docs-review
description: Review AstralRecord design documents without reading source code. Use when asked to review docs, design specs, feature design documents, Markdown design files, Obsidian-style docs, or docs under 00_docs such as 10_プラグイン設計書; checks design consistency, unsuitable logic, missing intent, unresolved decisions, cross-document contradictions, naming/format rules, and review reporting.
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
3. Run `scripts/docs_structure_audit.py <absolute-docs-path>` when reviewing Markdown docs. Use its output as evidence for format and structure findings, not as the whole review.
4. Read the minimum related design docs needed to understand the feature intent and cross-document contracts. Follow Wiki links to docs when they define terms, models, methods, flows, dependencies, or unresolved items.
5. Review for design defects:
   - Contradictions between overview, model, use case, method spec, integration flow, operation/logging, and unresolved issues.
   - Logic that is underspecified, impossible to implement as written, unsafe for operation, or inconsistent with its stated responsibility.
   - Missing preconditions, failure behavior, ownership boundaries, dependencies, data lifecycle, state transitions, idempotency, concurrency, rollback, or observability where the design implies them.
   - Format and naming violations against documented docs rules.
6. Separate "findings" from "questions". Do not label missing designer intent as a defect unless the current docs already require that intent to be defined.

## Report Format

Write the review in Japanese. Start with findings, ordered by severity.

For each finding include:

- Severity: `重大`, `高`, `中`, or `低`.
- Location: file path and line when available.
- Problem: what is contradictory, unsafe, unclear, or rule-breaking.
- Impact: why this matters as design.
- Suggested fix: the smallest docs/design change that would resolve it.

After findings, include:

- `確認した範囲`: docs read and whether the audit script was run.
- `未確認/質問`: designer intent or external information needed before judging.
- `ソース参照`: always state `ソースコードは参照していません。`

If there are no findings, say so explicitly and still list residual risks or questions.

## Extension Points

Keep domain rules in one-level reference files:

- `references/plugin-design-docs.md` for `10_プラグイン設計書`.
- Add `references/api-design-docs.md` for future API design docs.
- Add `references/web-design-docs.md` for future Web design docs.

When adding a new domain reference, include path detection, required root files, document categories, review focus, and format rules. Do not copy large docs into the skill; point the workflow at the actual docs tree.
