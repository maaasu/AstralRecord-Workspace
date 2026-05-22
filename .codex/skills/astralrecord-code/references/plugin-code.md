# Plugin Code

Use this reference for implementation under `E:\AstralRecord-Workspace\10_plugin\AstralRecord`.

## Required Reads

1. `E:\AstralRecord-Workspace\AGENTS.md`.
2. `E:\AstralRecord-Workspace\10_plugin\AstralRecord\README.md`.
3. Relevant helper prompts only:
   - `.agents/prompts/logger.md` for logs, `LogId`, or logger properties.
   - `.agents/prompts/player_msg.md` for player-facing messages, `MsgId`, or message properties.
   - `.agents/prompts/database.md` for DB contracts, schemas, filebase-dependent implementation, or API/Database/Filebase coordination.
   - `.agents/prompts/docs.md` only when the user also asks to edit plugin docs.

## Migrated `/code` Checklist

After applying README rules, verify:

1. The feature is contained under `feature/<feature>/` unless README defines a shared/core placement.
2. Game logic is not placed in `infrastructure/`.
3. `core/` contains only command/event registration entry points and other README-approved bootstrapping.
4. Bukkit/Paper thread constraints are respected; do not call main-thread-only API from async work.
5. Player handling uses `AstPlayer` where appropriate; avoid passing `org.bukkit.entity.Player` through domain logic unnecessarily.
6. DB access is contained in the repository layer.
7. Values already represented by enums/constants are not hard-coded as strings.
8. Logs and player messages are not written inline; read `logger.md` or `player_msg.md` when touching them.
9. Public externally-called methods include Japanese JavaDoc/KDoc covering arguments, return value, exceptions, and preconditions.

## Language Selection

- Match the language of the existing file first.
- For a new file, follow the local directory's existing style.
- Use README rules for Java/Kotlin decisions.

## Custom Instruction Examples

For direct requests such as `表示アイテムを apple から iron_ingot に変更`:

1. Search for both the old value and nearby feature terminology.
2. Prefer enum/material/constants/resource definitions over string replacement.
3. Update tests, filebase references, messages, and docs only when the changed contract requires it or the user asks.
4. Run a targeted compile or test and inspect the diff for accidental broad replacements.
