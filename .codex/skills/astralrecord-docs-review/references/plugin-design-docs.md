# Plugin Design Docs Reference

Use this reference for paths under `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書`.

## Required Context

Before reviewing a feature, read:

- `00_docs/10_Plugin設計書/README.md`.
- `00_docs/10_Plugin設計書/FEATURE_CATALOG.md` when implementation ownership matters.
- the target feature overview, such as `feature/01-user/01_0-概要.md`.
- model, use-case, method-contract, flow, operation, planned-specification, and unresolved-decision docs relevant to the reviewed behavior.

Follow both Wiki links and relative Markdown links when they define terms, models, methods, flows, dependencies, or unresolved items. Do not inspect implementation paths while running a docs-only review; paths in `FEATURE_CATALOG.md` are ownership labels only.

## Structure Rules To Check

Use the root design README as the source of truth. Current expected structure:

- Feature directory: `2-digit-number-feature-name`, for example `01-user`.
- Required feature entry point: `<feature-number>_0-概要.md` directly under the feature directory.
- No feature-level `<feature-number>_README.md`.
- Optional categories:
  - `1`: model definitions.
  - `2`: use cases.
  - `3`: method specifications and processing contracts.
  - `4`: integration flows.
  - `5`: exceptions, logs, and operations.
  - `6`: development and extension guides.
  - `8`: accepted but unimplemented specifications.
  - `9`: unresolved decisions only.
- Markdown file name: `<feature-number>_<category-number>-<meaningful-name>.md`.
- A category with one Markdown file is flattened into the feature directory.
- A category directory is used only when it contains multiple Markdown files.
- H1 matches the file name without `.md`.
- File names are unique across the Plugin design-doc tree and do not use brackets or spaces.
- Wiki links and relative Markdown links are both valid. A pathless Wiki link must resolve uniquely.
- Empty category directories, empty unresolved-decision docs, and detail-number names such as `.00` or `.01` are not valid.

## Feature Overview Rules

The feature overview is the navigation and responsibility entry point. Review whether it contains the information needed for the feature rather than requiring fixed headings:

- purpose, responsibilities, and non-goals.
- major boundaries and invariants.
- dependent features and cross-feature contracts.
- related data, settings, and authoritative sources.
- links to the design docs needed to understand the feature.
- feature-specific change impact only when it adds value beyond the root rules.

Implementation ownership paths belong in `FEATURE_CATALOG.md`, not in a duplicated per-feature table of contents.

## Method Contract Rules To Check

For category `3` docs:

- Focus on externally meaningful or cross-feature contracts, not a complete method inventory.
- Require the inputs, outputs, preconditions, rejection conditions, important decisions, delegation, state changes, persistence/thread boundaries, and failure behavior that are relevant to the documented contract.
- Class names, physical method names, and event names are optional. When present, they must be internally consistent with the design being reviewed, but a clear table or grouped contract is not defective merely because fixed labels are absent.
- Logical names should remain understandable Japanese noun phrases where practical.
- Cross-file references may use Wiki links or relative Markdown links.
- Logs/messages should identify the ID, level/type, trigger, arguments, meaning, and operational response as needed. Do not require a full message template when a properties file is the documented source of truth.

## Integration Flow Rules To Check

- Require an integration-flow document only when the behavior crosses components or cannot be understood from local contracts alone.
- Mermaid is required only when a diagram materially clarifies participants, branching, asynchronous work, compensation, or state transitions.
- A simple flow may use prose or a table.
- When a diagram is present, check that its labels and sequence agree with the surrounding design.

## Design Review Focus

Prioritize design-level issues:

- Does the flow match the method contracts and the feature overview?
- Are model fields sufficient for the stated use cases and lifecycle?
- Are responsibilities divided cleanly between event, command, service, repository, cache/session, task, adapter/listener, and operation docs?
- Are cross-feature calls explicit enough to know ownership and dependency direction?
- Are failure paths, null/not-found behavior, retries, logging, player-facing messages, and operational response documented where needed?
- Are state transitions clear for login/logout, cache/session, save timing, cooldowns, buffs/status effects, item ownership, loot grants, and other gameplay lifecycles?
- Are current behavior, accepted future work, and unresolved decisions clearly separated?
- Does documentation avoid duplicating authoritative implementation paths and message templates?

## When Intent Is Missing

Gather intent from the overview, use cases, flow diagrams, category `8`, category `9`, and related feature docs. If the intended behavior still cannot be determined, report it as `未確認/質問` with the exact decision needed rather than forcing a defect.
