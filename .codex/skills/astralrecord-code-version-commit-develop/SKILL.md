---
name: astralrecord-code-version-commit-develop
description: AstralRecord で未コミット差分が発生する実装・設計書反映・本番向け filebase 作成に優先して使う品質ゲート付き統合入口。task branch / worktree を作り、実装後に独立レビュー、ビルド警告の確認・対処、レビュー記録検証、自動修正、再検証、独立再レビューを行ってから commit / develop rebase / fast-forward merge / cleanup へ進む。サブエージェントが利用可能なら実装者・reviewer・fixerを分離し、プラグイン版番号は最新版 develop へ rebase 済みの finalize 時だけ更新する。
---

# AstralRecord Code Version Commit Develop

## Core Rule

Do not redefine implementation, review, fix, plugin versioning, or git workflow details in this skill. Use the existing skills as the source of truth and connect them through the mandatory quality gate.

1. First, use `$astralrecord-git-worktree-develop` to prepare a task branch and worktree from local `develop`.
2. Run the appropriate worker skill inside that prepared worktree:
   - `$astralrecord-code-fix` when the request supplies an `AR-CODE-*` review record to fix.
   - `$astralrecord-docs-fix` when the request supplies an `AR-DOC-*` review record to fix.
   - `$astralrecord-skill-author` for a Minecraft in-game class skill addition or change, including skill gem, shop, administrator availability, combat balance, or visual effects.
   - `$astralrecord-code` for plugin, API, web, docs-linked implementation, database docs, resourcepack, or mixed implementation tasks.
   - `$astralrecord-master-data-author` for production-oriented filebase master creation under `40_filebase`.
   - `$skill-creator` for skill definitions, references, scripts, or `agents/openai.yaml` under `.codex/skills`.
3. After the worker verifies its changes, run the matching independent review/fix cycle before finalize:
   - design-doc-only diff outside `00_docs/40_Database設計書` -> `$astralrecord-docs-review` then `$astralrecord-docs-fix`
   - code, configuration, database/schema docs, filebase, resourcepack, workspace skills/tools, or mixed diff -> `$astralrecord-code-review` then `$astralrecord-code-fix`
4. Do not run `$astralrecord-plugin-version` during the implementation or review/fix phase. Plugin versioning belongs to the rebased finalize step handled by `$astralrecord-git-worktree-develop`.
5. If the user wants a serial end-to-end run, finalize only after the quality gate passes.
6. If the user wants parallel execution or delayed merge, complete the quality gate first, then stop with a clean, committed or explicitly coordinator-owned task state for later finalize.
7. Keep the commit scope limited to implementation, directly synchronized docs, a canonical review record when findings exist, review-driven fixes, and the later version-update file.
8. Keep the commit message format from `E:\AstralRecord-Workspace\COMMIT_RULES.md`.
9. If the user also wants accumulated merged `codex/*` residues cleaned after finalize, delegate that last step to `$astralrecord-prune-codex-worktrees` instead of extending `$astralrecord-git-worktree-develop` beyond the current task.
10. Use `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md` for worktree management content. Ensure the management snapshot is refreshed whenever this integrated flow leaves a worktree for later finalize.

## Sub-agent Policy

Use collaboration tools when they are available; do not merely mention delegation in prompts.

- The coordinator owns Git operations, worktree selection, canonical record path, quality-gate decisions, and final integration.
- Use one implementation writer at a time. Never let multiple agents edit the shared task worktree concurrently.
- When collaboration tools are available, the first full reviewer must not be the implementation writer. The reviewer edits no source and is the sole writer of the canonical review record for that round.
- A fixer must be distinct from the reviewer when agent capacity permits and may edit only findings accepted by the fix skill.
- When collaboration tools are available, the second full reviewer must be independent of the fixer; prefer an agent that has not seen the intended fix beyond the raw diff and review record.
- For non-trivial multi-file or cross-project work, add one or two read-only specialists for rules/design and tests/security/concurrency/data integrity. Specialists return evidence to the canonical reviewer and never edit the record.
- A trivial one-file typo or metadata-only change may use one independent reviewer. If collaboration tools are unavailable, the coordinator may execute the same roles sequentially after rebuilding context from the raw diff and rules; report that independence was unavailable and that the sequential fallback was used.
- Do not leak an expected finding or intended answer into reviewer prompts. Pass raw diffs, target paths, tests, and authoritative rules.

## Quality Gate

The quality gate is mandatory for feature creation and behavior changes.

Choose one tier before implementation:

- Standard gate: feature/behavior changes, executable scripts, schemas/data contracts, workspace skill logic, multi-file work, or any security/concurrency/data-integrity impact. Run two full review rounds and the applicable fix passes below.
- Light gate: one-file typo, comment, display metadata, or similarly non-behavioral edit. Run one independent review when collaboration tools are available, otherwise use the documented sequential fallback. If it finds an issue, fix it and run one targeted confirmation; otherwise Round 2 is not required. Record the reason for choosing this tier.
- Review-fix entry: when the request starts from an existing canonical review record, treat that record as Round 1, run the matching fix worker, verify, and continue at Round 2. Do not create a replacement record.

### Build Warning Gate

For every Standard gate, treat build warnings as verification output, not as successful-build noise. Apply this gate whenever a meaningful build, compile, test, or static-analysis command exists for the touched project.

1. Capture the complete output of the selected verification command, including standard error, and inspect warnings as well as the exit status.
2. Identify each warning as task-originated, pre-existing, or external/toolchain-originated. Do not assume that a successful exit status means the check passed.
3. Fix every task-originated warning that is reasonably actionable, then rerun the same verification command and re-inspect its warnings.
4. For a warning classified as pre-existing, verify the same command against the current local `develop` when practical. For an external/toolchain warning, record the source and why the task cannot resolve it. Include the warning text or an unambiguous summary, classification, and verification command in the quality-gate report or canonical record.
5. Do not finalize when a new warning remains unexplained, a task-originated warning remains without an explicit user-approved deferral, or warning output could not be captured and inspected. If the project has no meaningful command, record that fact and use the strongest available static check instead.

Light-gate changes do not require a full build solely for this rule, unless their touched project rules already require one. If a build or static check is run for a Light gate, still inspect and report any warnings it emits.

1. Run the worker's verification before review, including the Build Warning Gate for a Standard gate. Independently inspect the complete task diff against `astralrecord-code/references/plugin-code.md` "Plugin Test Traceability Gate"; when any listed test source, Plugin POM, allowed design input, or test-policy path changes, run `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` from `<task-root>` regardless of which worker ran or whether test source changed. This common gate cannot be replaced by `mvn verify`.
2. Review Round 1:
   - use an independent reviewer when collaboration tools are available, otherwise use the documented sequential fallback;
   - review the exact task diff plus impacted call sites, tests, resources, and design contracts;
   - when findings exist, save one canonical record under `<task-root>\00_docs\99_資料\レビュー結果`;
   - when no findings exist, do not create a record; report unresolved questions separately;
   - validate the record with `.codex\skills\_shared\scripts\validate_review_record.py` only when one exists.
3. Fix Pass 1:
    - if a canonical record exists and `自動修正可` findings exist, run the matching fix skill with a single writer;
    - the fix skill runs the updater exactly once and returns the new canonical record path; the coordinator must not run the updater again;
    - rerun targeted build/test/static checks after the fix skill returns and reapply the Build Warning Gate to their complete output;
    - do not resolve `要確認` or `設計判断待ち` by assumption.
4. Review Round 2:
   - use a reviewer independent of the fixer when collaboration tools are available, otherwise use the documented sequential fallback;
   - if a canonical record exists, verify previous findings against the current diff and append only genuinely new findings with sequential IDs;
   - preserve the same canonical record, original timestamp, target path, and existing finding text;
   - create a record only if this round produces a finding; otherwise leave no record;
   - recalculate the record's states/summary and validate it again when one exists.
5. If Round 2 finds an automatically fixable regression, allow Fix Pass 2 followed by one targeted confirmation limited to those IDs. Reapply the Build Warning Gate when that confirmation runs. Do not run more than two full reviews, two fix passes, and one targeted confirmation.
6. Pass only when verification succeeds, the applicable Build Warning Gate passes, any existing record validates, no `自動修正可` finding remains unresolved, and no `[高]` or `[中]` finding is waiting for confirmation/design judgment.
7. `[低]` or `[情報]` findings that require user/design judgment may remain only when their non-blocking rationale is recorded. Report them explicitly.
8. Stop before finalize and retain the branch/worktree when verification fails, existing-record validation fails, a blocking finding remains, the loop cap is reached, or a user/design decision is required.

## Parallel Filebase Flow

For parallel `40_filebase` creation, this integrated skill handles one coherent package per invocation.

1. Group related masters into an independently verifiable area / combat / economy package instead of creating one worktree per YAML file.
2. Before Prepare, define the package name, owned paths, reserved IDs or ID prefixes, shared-file owner, dependencies, and intended finalize order.
3. Create one task branch and dedicated worktree for that package. Branches without separate worktrees do not isolate parallel writers in the same workspace.
4. Complete implementation, the Quality Gate, and a scoped task commit, then preserve the package worktree and report its ownership information for later finalize.
5. Finalize packages one at a time in dependency order. Each finalize must rebase onto the current local `develop` and complete the post-rebase filebase validation required by `$astralrecord-git-worktree-develop` before merge.
6. If worktrees are intentionally avoided, parallel tasks may only produce read-only YAML proposals and ID/reference manifests. A single integration task may then use `$astralrecord-master-data-create-direct` to apply and commit them serially on `develop`.

## Workflow

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\.codex\skills\astralrecord-git-worktree-develop\references\worktree-management.md`.
3. Identify the target project and choose the worker skill:
   - existing `AR-CODE-*` review record -> `$astralrecord-code-fix`
   - existing `AR-DOC-*` review record -> `$astralrecord-docs-fix`
   - Minecraft in-game class skill addition or change -> `$astralrecord-skill-author`
   - `40_filebase` master creation -> `$astralrecord-master-data-author`
   - `.codex/skills` creation or update -> `$skill-creator`
   - other implementation tasks -> `$astralrecord-code`
4. For parallel `40_filebase` work, define and report the coherent package ownership fields from `Parallel Filebase Flow` before creating the worktree.
5. Invoke `$astralrecord-git-worktree-develop` in Prepare mode and create a task branch / worktree for the request. The prepare step must refresh `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md`.
   - Review-fix exception: if the canonical record exists only inside a registered, unmerged non-`develop` task worktree, verify that branch/worktree belongs to the review task and reuse it instead of preparing a second branch. If the record is tracked on `develop`, prepare normally and remap it. Never edit a record in one worktree while applying fixes in another.
6. Map the requested absolute path from `E:\AstralRecord-Workspace\...` to the returned worktree root and invoke the selected worker there with one writer. Finish implementation or master creation, docs sync, and worker verification.
7. Inspect the changed files and select the review/fix pair from Core Rule.
8. Execute the selected quality-gate tier and Sub-agent Policy completely. For review-fix entry, remap a record path under `E:\AstralRecord-Workspace` to the same relative path under the task worktree before invoking the fixer. Keep all review records under the task worktree root, never the literal main workspace.
9. Inspect the final diff and verification results after review-driven fixes.
10. Determine execution style:
   - Serial single-task flow: invoke `$astralrecord-git-worktree-develop` in Finalize mode only after the quality gate passes.
   - Parallel or delayed merge: invoke `$astralrecord-commit-current-diff` for the quality-gated task files, refresh management, and report the clean branch as finalize-ready. Later Finalize accepts this precommitted state.
11. Do not invoke `$astralrecord-plugin-version` before the rebase. If plugin files changed, `$astralrecord-git-worktree-develop` decides and runs it during finalize.
12. If Prepare, quality gate, or Finalize stops, retain the task worktree and report the exact blocker. Refresh the management snapshot whenever a branch/worktree remains.
13. If finalize succeeds and the user requested broader cleanup, run `$astralrecord-prune-codex-worktrees` as the final optional maintenance step.

## Version Update Decision

When the implementation materially changes the plugin deliverable, the version step must run during finalize after rebasing to latest local `develop`:

- Plugin source under `10_plugin/AstralRecord/src/`
- Plugin resources such as `plugin.yml`, `config.yml`, message resources, logger resources
- Plugin build files such as `10_plugin/AstralRecord/pom.xml`

Skip the version step when the implementation affects only:

- API, Web, docs-only, database docs, filebase, or resourcepack
- Skill files under `.codex/skills` with no plugin code/resource change
- Pure review-result bookkeeping unrelated to the plugin binary

If both plugin and non-plugin projects changed in one task, run the plugin version step once during finalize and keep the `pom.xml` update scoped to its own commit when possible.

## Delegation Prompt Pattern

Use prompts equivalent to the following:

```text
$astralrecord-git-worktree-develop を使って、<absolute-path> 用の task branch / worktree を作成し、branch 名と worktree パスを報告してください。
```

```text
$astralrecord-code を使って、<worktree-absolute-path> に対して <implementation task> を行い、結果を報告してください。
```

```text
$astralrecord-git-worktree-develop を使って、<worktree-absolute-path> の現在の task worktree を finalize し、develop へ merge して、成功時は task branch / worktree を cleanup してください。
```

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の不要な codex/* branch と task worktree を dry-run 監査し、必要なら execute で掃除してください。
```

When the user did not provide a path but the project is still clear, keep the project context explicit in all steps.

For delayed merge after parallel work, use a prompt equivalent to:

```text
$astralrecord-git-worktree-develop を使って、並列実装後の <worktree-absolute-path> の task worktree を finalize し、rebase 後もプラグイン成果物に変更がある場合だけプラグイン版番号を更新して結果を報告してください。
```

For a parallel or delayed stop, refresh management content before reporting:

```text
$astralrecord-prune-codex-worktrees を使って、E:\AstralRecord-Workspace の worktree 管理コンテンツを dry-run で更新し、今回残す branch/worktree が確認項目として見えることを報告してください。
```

## Report Format

Write the final result in Japanese and merge all executed steps into one report.

- `実装結果`: 実行した worker skill の要点
- `品質ゲート`: review rounds、fix passes、verification、review record path（指摘なしなら記録なし）、残存 findings
- `サブエージェント利用`: implementer / reviewer / fixer / specialist の役割と結果。未使用なら理由
- `Branch / Worktree`: 準備した branch 名と worktree パス
- `並列所有情報`: filebase parallel package の owned paths、reserved IDs、dependencies、finalize order。単独作業なら不要
- `バージョン更新結果`: finalize 実施時のみ要点。未実施なら理由を明記
- `Git結果`: `$astralrecord-git-worktree-develop` の要点
- `Worktree管理`: `E:\AstralRecord-Worktrees\WORKTREE_MANAGEMENT.md` の更新有無と残った確認項目
- `次のアクション`: finalize 済み / parallel 実装完了のため finalize 待ち
- `未対応事項`: ブランチ競合、未実施テスト、競合解決待ちなど

## Example

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の依頼されたプラグイン挙動を task worktree 上で実装し、finalize 時のプラグイン版番号更新を含めて develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\.codex\skills の依頼された skill 変更を task worktree 上で実装し、プラグイン未変更なら版番号更新を行わず develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\40_filebase の最初のオーバーワールド向け本番マスタを task worktree 上で作成し、develop へ merge してください。
```

```text
$astralrecord-code-version-commit-develop を使って、E:\AstralRecord-Workspace\10_plugin\AstralRecord の依頼されたプラグイン挙動を専用 task worktree で実装し、並列実行のため finalize 前で停止して、後続 merge 用の branch 名と worktree パスを報告してください。
```
