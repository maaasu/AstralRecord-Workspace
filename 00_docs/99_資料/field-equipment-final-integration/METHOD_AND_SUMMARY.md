# Item purpose audit snapshot



- Generated date: 2026-10-14

- Repository: E:\AstralRecord-Worktrees\field-equipment-final-integration

- Category-bearing item masters: 514

- Items by category: {'bundle': 3, 'consumable': 60, 'currency': 14, 'equipment': 174, 'material': 159, 'orb': 38, 'pet': 3, 'pet_egg': 3, 'rune': 2, 'sigil': 58}

- Items with consumption references: 100; by category: {'currency': 2, 'material': 92, 'orb': 3, 'pet_egg': 3}

- Items without consumption references: 414; by category: {'bundle': 3, 'consumable': 60, 'currency': 12, 'equipment': 174, 'material': 67, 'orb': 35, 'pet': 3, 'rune': 2, 'sigil': 58}

- No-consumption items with saleValue > 0: 311; unSellable is not true for 308.

- No-consumption items with saleValue not positive or missing: 103.

- IDs 20a00400–20a00456: 57; shop listed 57; consumption references 0.

- Parse errors: 0; source literal-ID matches: 111 rows.



## Classification



Count only input/requirement paths as consumption: Shop requiredItems, recipe ingredients, skill learnRequiredItems/levelUpRequiredItems, equipment requiredMaterials including transcendence, pet hatchMaterials/breedMaterials/reviveMaterials, quest acceptRequirements.items, pet eggItemId mapping, and reviveOrbItemId. Drops, gathering, fishing, loot, mail/quest/dungeon rewards, and shop product listings are acquisition/output refs and are recorded separately.

saleValue > 0 is a separate configured generic economic value. Check unSellable before interpreting it as a player sale path. Equipment, consumable, orb, sigil, rune, currency, bundle, pet, and pet_egg can have their own behavior even without inbound consumption refs; the candidate CSV is a review list, not proof that an item has no function.

The literal source sweep only finds static eight-character IDs in source/config/resource files. Runtime-generated IDs, indirect or dynamic lookup, external server data, and deployed configuration are not covered. Findings describe current filebase references only.



## Reproduce



Run with -B -X utf8 and provide both required paths:

& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' -B -X utf8 'extract_unused_items.py' --repo 'E:\AstralRecord-Worktrees\field-equipment-final-integration' --output-dir 'E:\AstralRecord-Worktrees\field-equipment-final-integration\00_docs\99_資料\field-equipment-final-integration'
