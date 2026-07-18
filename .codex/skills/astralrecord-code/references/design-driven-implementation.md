# Design-Driven Implementation

Use this reference when a user asks to implement from a design document, spec, docs feature folder, or path under `E:\AstralRecord-Workspace\00_docs`.

## Input Handling

1. Normalize all given paths to absolute paths.
2. Identify the implementation project implied by the docs path:
   - `00_docs/10_Plugin設計書`: usually `10_plugin/AstralRecord`.
   - API/Web docs, when added later: map to the matching project named by the docs tree or user request.
3. Read the docs root README, target feature README, and directly referenced docs that define behavior or contracts.
4. Treat implementation path names in docs as intended locations, but still verify the real project layout before editing.

## Extraction Checklist

Extract only what is needed for the requested implementation:

- feature responsibility and non-goals.
- commands, routes, screens, event handlers, scheduled tasks, or integration entry points.
- models, DTOs, entities, repositories, filebase keys, resource IDs, enums, constants, and item/material IDs.
- state transitions, persistence rules, idempotency, concurrency, rollback, and migration behavior.
- validation, permission/authentication, error handling, user messages, logs, and observability.
- tests or acceptance criteria written in the docs.
- unresolved items and design decisions not yet made.

## Decision Rules

- Implement explicit design decisions; do not fill unresolved decisions with guesses.
- If the user asks for a narrow change, implement only that slice even when the docs describe a larger feature.
- If docs and code disagree, inspect nearby history/patterns enough to avoid breaking existing behavior. Report material mismatches.
- If the design requires changes across Plugin/API/Web/Database/Filebase/Resourcepack, stage the reasoning by project and verify each boundary contract.
- Do not update design docs unless the user asked for docs changes or the implementation reveals a necessary documentation mismatch.

## Verification

Prefer project-local checks:

- Plugin: Maven compile/test or the narrow module command documented by the root `README.md` / `references/plugin-code.md`.
- API: `dotnet build` / targeted tests from the API project, following `references/api-code.md`.
- Web: `dotnet build` / page-level checks from the Web project.
- Database/Filebase/Resourcepack: run documented validators or syntax checks when available.

If a check is unavailable, explain the blocker and include a manual consistency check summary.
