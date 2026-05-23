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

## Player Messages

Use these rules when adding or changing player-facing messages, `MsgId`, or `player.properties`.

1. Do not write message text directly in code.
2. Route player notifications through the existing message management.
3. Update `player.properties` and `MsgId` together.
4. Call through `PlayerMsgResource` or `AstPlayer.sendMessage(...)`.
5. Do not pass string literals directly to `sendInfo`, `sendSuccess`, `sendError`, or `sendMessage`.
6. Check color codes, placeholders, and existing wording style.
7. Avoid changing an existing message's meaning without checking all call sites.

## Database, API, and Filebase Contracts

Use these rules when adding or changing plugin-side DB access, features that depend on DB contracts, schema-related work, or features that depend on file-based master data.

1. Check repository input/output models.
2. Check API contracts in `E:\AstralRecord-Workspace\00_docs\20_API設計書\feature\`.
3. Check SQL Server definitions under `E:\AstralRecord-Workspace\40_database\`.
4. Check file-based master data and YAML schemas under `E:\AstralRecord-Workspace\50_filebase\`.
5. Before writing DB-schema-dependent code, verify that `40_database` definitions and implementation agree.
6. Before writing filebase-dependent code, verify that `50_filebase` YAML and schema definitions agree.
7. If table or column changes are involved, check whether `40_database` needs a matching update.
8. If file master structure changes are involved, check whether `50_filebase` needs a matching update.
9. Do not finish API and Plugin contract changes on only one side.
10. Avoid hard-coding DB names or YAML paths without checking Database/Filebase definitions.

## Plugin Docs

Use these rules only when the user asks to create or modify plugin design docs under `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\`.

1. Read `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\README.md`.
2. If a feature is identified, read `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\<feature>\NN_README.md`.
3. Read corresponding implementation code to avoid speculative descriptions.
4. Prefer the root docs numbering categories `0/1/2/3/4/5/9`.
5. Keep categories directory-based and use `[機能番号] カテゴリ番号.詳細番号-名称.md` naming.
6. Put method specs under `3-メソッド仕様/` split by layer: event, service, command, repository.
7. Split long files by increasing detail numbers inside the category.
8. Put information that does not match implementation into `90-*` as unresolved instead of guessing.
9. Update the target feature README table of contents when editing feature docs.
10. Use Obsidian-style `[[参照]]` links.

## Custom Instruction Examples

For direct requests such as `表示アイテムを apple から iron_ingot に変更`:

1. Search for both the old value and nearby feature terminology.
2. Prefer enum/material/constants/resource definitions over string replacement.
3. Update tests, filebase references, messages, and docs only when the changed contract requires it or the user asks.
4. Run a targeted compile or test and inspect the diff for accidental broad replacements.
