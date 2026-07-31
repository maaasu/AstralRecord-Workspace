# Plugin Code

Use this reference for implementation under `E:\AstralRecord-Workspace\10_plugin\AstralRecord`.

## Required Reads

1. `E:\AstralRecord-Workspace\AGENTS.md`.
2. `E:\AstralRecord-Workspace\README.md` AstralRecord Plugin section.

## Migrated `/code` Checklist

After applying README rules, verify:

1. The feature is contained under `feature/<feature>/` unless README defines a shared/core placement.
2. Game logic is not placed in `infrastructure/`.
3. `core/` contains only command/event registration entry points and other README-approved bootstrapping.
4. Bukkit/Paper thread constraints are respected; do not call main-thread-only API from async work.
5. Player handling uses `AstPlayer` where appropriate; avoid passing `org.bukkit.entity.Player` through domain logic unnecessarily.
6. DB access is contained in the repository layer.
7. Values already represented by enums/constants are not hard-coded as strings.
8. Logs and player messages are not written inline; use the specialized rules below when touching them.
9. Public externally-called methods include Japanese JavaDoc/KDoc covering arguments, return value, exceptions, and preconditions.
10. Legacy color code handling must use the plugin shared definition `io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil`; do not use `org.bukkit.ChatColor` in plugin code.
11. GUI の共通挙動は各 GUI に重複実装せず shared 側へ寄せる。ホットバーの閉じるアイコン / インベントリ切替を使う GUI は `io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder` と `HotbarShortcutClickSupport` を使い、GUI ごとの個別 open/click/close 分岐を増やさない。

## Language Selection

- Match the language of the existing file first.
- For a new file, follow the local directory's existing style.
- Use README rules for Java/Kotlin decisions.

## Logs

Use these rules when adding or changing log messages, `LogId`, or `logger.properties`.

1. Do not write log text directly in code.
2. Use the existing logger wrapper and `LogId`.
3. Add or update `logger.properties` and the matching `LogId` together.
4. Call logs through the existing logger API.
5. Preserve `Throwable` when logging exceptions.
6. Avoid `printStackTrace()`-only handling and new IDs that duplicate an existing ID's meaning.
7. Before choosing a new ID, search `LogId.java`, `logger.properties`, and nearby call sites for an existing common definition with the same meaning.
8. For every reused or newly selected `LogId`, compare the property text with the actual operation and verify that formatter placeholders exactly match the non-`Throwable` arguments. A numerically valid ID with a different meaning is not reusable.
9. After any Plugin source/resource edit, run `python .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root <task-worktree>` before committing. Do not finish while it reports direct logger calls, human-readable fixed text hidden in changed `LogId` arguments, any log placeholder-count mismatch, duplicate resource keys, or ID/property drift.

## Player Messages

Use these rules when adding or changing player-facing messages, `MsgId`, or `player.properties`.

1. Do not write message text directly in code.
2. Route player notifications through `io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService`.
3. Update `player.properties` and `MsgId` together.
4. `AstPlayer.sendMessage(...)` is legacy compatibility only. Do not use it in new or modified code.
5. Do not call `Player#sendMessage(...)` directly for plugin-managed player messaging. Use `PlayerMessageService` so the common tag/prefix and chat routing rules stay consistent.
6. Do not pass string literals directly to `sendInfo`, `sendSuccess`, `sendError`, `sendMessage`, or new player-message helper methods unless the API is explicitly for managed chat formatting.
7. Check color codes, placeholders, and existing wording style.
8. Avoid changing an existing message's meaning without checking all call sites.
9. When a player-facing message includes filebase/master-data display strings such as `name`, `title`, `description`, or lore text, route the value through `PlayerMsgResource` / `PlayerMessageService` formatting or explicitly normalize it with `ColorCodeUtil`; raw `&` color codes from master data must never be displayed to players.
10. Before choosing a new player message ID, search `PlayerMsgId.java`, feature-specific `*MsgId`, and `player.properties`; update every authoritative enum and the property in the same patch.
11. Run the Plugin resource validation script from the Logs section after edits; it also rejects direct `sendMessage` calls, string literals passed to command message helpers, duplicate property keys, and player ID/property drift.

## Database, API, and Filebase Contracts

Use these rules when adding or changing plugin-side DB access, features that depend on DB contracts, schema-related work, or features that depend on file-based master data.

1. Check repository input/output models.
2. Check API contracts in `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\`.
3. Check SQL Server definitions under `E:\AstralRecord-Workspace\00_docs\40_Database設計書\`.
4. Check file-based master data and YAML schemas under `E:\AstralRecord-Workspace\40_filebase\`.
5. Before writing DB-schema-dependent code, verify that `00_docs\40_Database設計書` definitions and implementation agree.
6. Before writing filebase-dependent code, verify that `40_filebase` YAML and schema definitions agree.
7. If table or column changes are involved, check whether `00_docs\40_Database設計書` needs a matching update.
8. If file master structure changes are involved, check whether `40_filebase` needs a matching update.
9. Do not finish API and Plugin contract changes on only one side.
10. Avoid hard-coding DB names or YAML paths without checking Database/Filebase definitions.
11. For player-owned runtime state such as inventory and equipment durability, treat the Plugin-side loaded state as authoritative during gameplay. Avoid blocking API writes in combat or hot paths; durability and similarly low-criticality state should be marked dirty and flushed through the same save boundaries as player inventory (autosave, logout, plugin disable, or explicit save), prioritizing server performance over immediate API consistency.

## Plugin Docs

Use these rules only when the user asks to create or modify plugin design docs under `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\`.

1. Read `E:\AstralRecord-Workspace\00_docs\10_Plugin設計書\README.md`.
2. If a feature is identified, read its `NN_0-概要.md` entry point and `FEATURE_CATALOG.md` when implementation ownership matters.
3. Read corresponding implementation code to avoid speculative descriptions.
4. Use the root categories `0/1/2/3/4/5/6/8/9` only when the category has content; only category `0` is mandatory.
5. Name files `NN_<category>-<meaningful-name>.md`. Do not add `.00` / `.01` detail numbers.
6. Keep a single category document at the feature root. Create a category directory only when the category has multiple documents.
7. Split long files by coherent responsibility and use a meaningful name rather than a sequence number.
8. Put accepted but unimplemented specifications in category `8` and unresolved design decisions in category `9`; do not guess or mix the states.
9. Keep the feature overview and `FEATURE_CATALOG.md` aligned when responsibility or ownership changes.
10. Use either uniquely resolvable Wiki links or relative Markdown links according to the root docs rules.
11. Treat method docs as processing contracts, not a mandatory inventory of every physical method.
12. Do not duplicate full logger/player message text when a properties file is the authoritative source.

## Plugin Test Traceability Gate

Run `python .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py` from the repository root whenever the task diff adds, changes, renames, or deletes any of the following paths. This gate is mandatory even when no test source changed and the diff is design-doc-only or test-policy-only.

- `10_plugin/AstralRecord/src/test/**/*`
- `10_plugin/AstralRecord/pom.xml`
- `PLUGIN_GUIDE.md`
- `00_docs/10_Plugin設計書/**/*.md`
- `.codex/skills/astralrecord-plugin-test/**/*`
- `.codex/skills/astralrecord-code/SKILL.md`
- `.codex/skills/astralrecord-code/references/plugin-code.md`
- `.codex/skills/astralrecord-code-version-commit-develop/SKILL.md`
- `.codex/skills/astralrecord-docs-fix/SKILL.md`

Run the gate before the final Maven test run and before review handoff. Do not leave an untraced test method, disabled or conditionally skipped test, nonstandard Maven test source, compiler/Surefire-excluded test, Kotlin JUnit annotation alias, or ad-hoc `AdHoc*Test` / `*OneShotTest` source in the final diff. Do not substitute `mvn verify` for this command because the Plugin shade configuration writes to the main workspace distribution path.

## Custom Instruction Examples

For direct requests such as `表示アイテムを apple から iron_ingot に変更`:

1. Search for both the old value and nearby feature terminology.
2. Prefer enum/material/constants/resource definitions over string replacement.
3. Update tests, filebase references, messages, and docs only when the changed contract requires it or the user asks.
4. Run a targeted compile or test and inspect the diff for accidental broad replacements.

## Particle Rules

1. Particle rendering must go through io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService.
2. Do not call World#spawnParticle(...) or Player#spawnParticle(...) directly in feature code.
3. Shared particle species, aliases, and default visual parameters must be defined in io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions.
4. Do not duplicate Particle.valueOf(...) parsing in feature code; use the shared resolver.
5. Recurring particle tasks must have bounded cadence, point count, and viewer scans. Avoid per-tick or near-per-tick always-on effects unless there is an explicit profiling-backed reason.
6. When a recurring effect renders multiple points for the same center, batch nearby-viewer resolution through ParticleDisplayService instead of calling spawnForNearbyViewers once per point.
7. Skip recurring particle work for worlds or centers that have no possible viewers, and keep packet count proportional to visible players rather than loaded worlds.

## Player Teleport Rules

1. Player teleport behavior must preserve the player's yaw / pitch from immediately before teleporting.
2. New player teleport features must use `io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService` or an existing service method that delegates to it, such as `WorldService#teleportPlayerAsync(...)`.
3. Do not call `Player#teleport(...)` or `Player#teleportAsync(...)` directly for plugin-managed player movement unless the feature explicitly requires target-defined yaw / pitch and documents that exception.
4. Entity, display, packet, or visual-only movement is outside this rule and may keep using its existing movement API.
