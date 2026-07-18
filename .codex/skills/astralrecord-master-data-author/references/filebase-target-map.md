# Filebase Target Map

Use this map after reading the master design documents. Always read the target schema before editing.

## Core Files

| Purpose | Directory | Schema |
|:--|:--|:--|
| Shared status names | Plugin source | `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/model/StatusType.kt` |
| Items common fields | `40_filebase/10.features.item` | `40_filebase/10.features.item/item.YAMLスキーマ定義.md` |
| Equipment | `40_filebase/10.features.item/equipment` | `40_filebase/10.features.item/equipment/_equipment.YAMLスキーマ定義.md` |
| Materials | `40_filebase/10.features.item/material` | `40_filebase/10.features.item/material/_material.YAMLスキーマ定義.md` |
| Consumables | `40_filebase/10.features.item/consumable` | `40_filebase/10.features.item/consumable/_consumable.YAMLスキーマ定義.md` |
| Classes | `40_filebase/20.features.class` | `40_filebase/20.features.class/class.YAMLスキーマ定義.md` |
| Skills | `40_filebase/30.features.skill` | `40_filebase/30.features.skill/skill.YAMLスキーマ定義.md` |
| Skill trees | `40_filebase/35.features.skilltree` | `40_filebase/35.features.skilltree/skilltree.YAMLスキーマ定義.md` |
| Enemies | `40_filebase/40.features.mob/enemy` | `40_filebase/40.features.mob/mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/enemy/enemy.YAMLスキーマ定義.md` |
| Bosses | `40_filebase/40.features.mob/boss` | `40_filebase/40.features.mob/mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/boss/boss.YAMLスキーマ定義.md` |
| NPCs | `40_filebase/40.features.mob/npc` | `40_filebase/40.features.mob/mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/npc/npc.YAMLスキーマ定義.md` |
| Mob spawners | `40_filebase/41.features.mob.spawner` | `40_filebase/41.features.mob.spawner/spawner.YAMLスキーマ定義.md` |
| Gathering | `40_filebase/42.features.gathering` | `40_filebase/42.features.gathering/gathering.YAMLスキーマ定義.md` and subtype schema |
| Gathering spawners | `40_filebase/43.features.gathering.spawner` | `40_filebase/43.features.gathering.spawner/spawner.YAMLスキーマ定義.md` |
| Shops | `40_filebase/45.features.shop` | `40_filebase/45.features.shop/shop.YAMLスキーマ定義.md` |
| Quests | `40_filebase/47.features.quest` | `40_filebase/47.features.quest/quest.YAMLスキーマ定義.md` |
| Quest boards | `40_filebase/48.features.quest_board` | `40_filebase/48.features.quest_board/quest_board.YAMLスキーマ定義.md` |
| Worlds | `40_filebase/60.features.world` | `40_filebase/60.features.world/world.YAMLスキーマ定義.md` |
| Buffs | `40_filebase/70.shared.buff` | `40_filebase/70.shared.buff/buff.YAMLスキーマ定義.md` |
| Loot pools | `40_filebase/80.shared.loot/pool` | `40_filebase/80.shared.loot/pool/pool.YAMLスキーマ定義.md` |
| Loot tables | `40_filebase/80.shared.loot/table` | `40_filebase/80.shared.loot/table/table.YAMLスキーマ定義.md` |

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
