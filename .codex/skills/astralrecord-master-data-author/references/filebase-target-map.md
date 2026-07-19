# Filebase Target Map

Use this map after reading the master design documents. Always read the target schema before editing.

## Core Files

| Purpose | Directory | Schema |
|:--|:--|:--|
| Shared status names | Plugin source | `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/model/StatusType.kt` |
| Items common fields | `40_filebase/10.features.item` | `40_filebase/10.features.item/docs.item.YAMLスキーマ定義.md` |
| Equipment | `40_filebase/10.features.item/equipment` | `40_filebase/10.features.item/equipment/docs.equipment.YAMLスキーマ定義.md` |
| Materials | `40_filebase/10.features.item/material` | `40_filebase/10.features.item/material/docs.material.YAMLスキーマ定義.md` |
| Consumables | `40_filebase/10.features.item/consumable` | `40_filebase/10.features.item/consumable/docs.consumable.YAMLスキーマ定義.md` |
| Classes | `40_filebase/20.features.class` | `40_filebase/20.features.class/docs.class.YAMLスキーマ定義.md` |
| Skills | `40_filebase/30.features.skill` | `40_filebase/30.features.skill/docs.skill.YAMLスキーマ定義.md` |
| Skill trees | `40_filebase/35.features.skilltree` | `40_filebase/35.features.skilltree/docs.skilltree.YAMLスキーマ定義.md` |
| Enemies | `40_filebase/40.features.mob/enemy` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/enemy/docs.enemy.YAMLスキーマ定義.md` |
| Bosses | `40_filebase/40.features.mob/boss` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/boss/docs.boss.YAMLスキーマ定義.md` |
| NPCs | `40_filebase/40.features.mob/npc` | `40_filebase/40.features.mob/docs.mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/npc/docs.npc.YAMLスキーマ定義.md` |
| Mob spawners | `40_filebase/41.features.mob.spawner` | `40_filebase/41.features.mob.spawner/docs.spawner.YAMLスキーマ定義.md` |
| Gathering | `40_filebase/42.features.gathering` | `40_filebase/42.features.gathering/docs.gathering.YAMLスキーマ定義.md` and subtype schema |
| Gathering spawners | `40_filebase/43.features.gathering.spawner` | `40_filebase/43.features.gathering.spawner/docs.spawner.YAMLスキーマ定義.md` |
| Shops | `40_filebase/45.features.shop` | `40_filebase/45.features.shop/docs.shop.YAMLスキーマ定義.md` |
| Quests | `40_filebase/47.features.quest` | `40_filebase/47.features.quest/docs.quest.YAMLスキーマ定義.md` |
| Quest boards | `40_filebase/48.features.quest_board` | `40_filebase/48.features.quest_board/docs.quest_board.YAMLスキーマ定義.md` |
| Worlds | `40_filebase/60.features.world` | `40_filebase/60.features.world/docs.world.YAMLスキーマ定義.md` |
| Buffs | `40_filebase/70.shared.buff` | `40_filebase/70.shared.buff/docs.buff.YAMLスキーマ定義.md` |
| Loot pools | `40_filebase/80.shared.loot/pool` | `40_filebase/80.shared.loot/pool/docs.pool.YAMLスキーマ定義.md` |
| Loot tables | `40_filebase/80.shared.loot/table` | `40_filebase/80.shared.loot/table/docs.table.YAMLスキーマ定義.md` |

## ID Guidance

- Use lowercase snake_case.
- Prefer production names over debug names.
- Use an area or feature prefix only when the requested context defines it and the prefix improves ownership clarity.
- Keep item IDs stable and simple because loot, shop, recipe, equipment, and mob definitions will reference them.

## Reference Prefixes

| Target | Prefix |
|:--|:--|
| Item | `item:` |
| Skill | `skill:` |
| Buff | `buff:` |
| Mob | `mob:` |
| Class | `class:` |
| Recipe | `recipe:` |
| Rune | `rune:` |
| Set effect | `set:` |

Represent references using the style already used in the target schema or nearby files.
