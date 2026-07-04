# Filebase Target Map

Use this map after reading the master design documents. Always read the target schema before editing.

## Core Files

| Purpose | Directory | Schema |
|:--|:--|:--|
| Shared status names | `40_filebase/00.meta` | `40_filebase/00.meta/StatusType.md` |
| Items common fields | `40_filebase/10.features.item` | `40_filebase/10.features.item/item.YAMLスキーマ定義.md` |
| Equipment | `40_filebase/10.features.item/equipment` | `40_filebase/10.features.item/equipment/_equipment.YAMLスキーマ定義.md` |
| Materials | `40_filebase/10.features.item/material` | `40_filebase/10.features.item/material/_material.YAMLスキーマ定義.md` |
| Consumables | `40_filebase/10.features.item/consumable` | `40_filebase/10.features.item/consumable/_consumable.YAMLスキーマ定義.md` |
| Classes | `40_filebase/20.features.class` | `40_filebase/20.features.class/class.YAMLスキーマ定義.md` |
| Skills | `40_filebase/30.features.skill` | `40_filebase/30.features.skill/skill.YAMLスキーマ定義.md` |
| Skill trees | `40_filebase/35.features.skilltree` | `40_filebase/35.features.skilltree/skilltree.YAMLスキーマ定義.md` |
| Enemies | `40_filebase/40.features.mob/enemy` | `40_filebase/40.features.mob/mob.YAMLスキーマ定義.md`, `40_filebase/40.features.mob/enemy/enemy.YAMLスキーマ定義.md` |
| Mob spawners | `40_filebase/41.features.mob.spawner` | `40_filebase/41.features.mob.spawner/spawner.YAMLスキーマ定義.md` |
| Shops | `40_filebase/45.features.shop` | `40_filebase/45.features.shop/shop.YAMLスキーマ定義.md` |
| Worlds | `40_filebase/60.features.world` | `40_filebase/60.features.world/world.YAMLスキーマ定義.md` |
| Buffs | `40_filebase/70.shared.buff` | `40_filebase/70.shared.buff/buff.YAMLスキーマ定義.md` |
| Loot pools | `40_filebase/80.shared.loot/pool` | `40_filebase/80.shared.loot/pool/pool.YAMLスキーマ定義.md` |
| Loot tables | `40_filebase/80.shared.loot/table` | `40_filebase/80.shared.loot/table/table.YAMLスキーマ定義.md` |

## First Overworld Creation Order

1. Materials and consumables.
2. Initial equipment.
3. Enemy mobs.
4. Loot pools.
5. Loot tables.
6. Mob spawner.
7. World or shop updates only when the user asks for placement/availability.

## ID Guidance

- Use lowercase snake_case.
- Prefer production names over debug names.
- Area prefixes are useful for related batches, for example `skygrass_`, `astral_field_`, `cloudroot_`, or `novice_`.
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
