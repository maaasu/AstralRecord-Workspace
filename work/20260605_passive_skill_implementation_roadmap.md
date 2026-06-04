# Passive Skill Implementation Roadmap

## Scope
- `10_plugin/AstralRecord`
- `40_filebase/30.features.skill`
- `40_filebase/35.features.skilltree`
- related plugin design docs

## Implementation Steps
1. Add skill kind metadata to plugin-side skill definitions and executor implementations.
2. Add passive lifecycle foundation with `onActivate` / `onDeactivate` / `tick`.
3. Expand owned-skill resolution to include class, equipment, rune, set effect, and skilltree node skills.
4. Enforce active/passive slot restrictions and passive bind-required visibility rules in Skill Bind GUI.
5. Add master-data support for passive bind requirement in skill YAML schema and runtime loading.
6. Extend skilltree node master-data to support multiple `skillIds` and direct `statuses`.
7. Reflect unlocked skilltree node skills/statuses into player ownership and status calculation.
8. Show skill information on skilltree nodes.
9. Update plugin/filebase design docs and add TODO for a future owned-skills list GUI.
10. Run targeted verification, then version bump and commit if scope is clean.

## Decisions Fixed In This Task
- Passive bind requirement is configured in master data only.
- Skilltree nodes allow `skillIds[]`, `statuses[]`, and mixed definitions.
- Non-bind passive skills are hidden from the skill bind GUI list.
- Passive foundation includes `onActivate` / `onDeactivate` / `tick`.
- Ownership includes class, equipment, rune, set effect, and skilltree-derived skills.
