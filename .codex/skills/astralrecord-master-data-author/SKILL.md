---
name: astralrecord-master-data-author
description: AstralRecord の filebase マスタデータを本番向けに追加・拡張する。ゲームコンセプト、ステータス設計、命名方針、初期オーバーワールド制作ブリーフ、既存 YAML スキーマを読み、item/equipment/material/consumable/class/skill/mob/spawner/world/loot/shop などの 40_filebase 定義を整合させて作成したい場合に使う。
---

# AstralRecord Master Data Author

## Core Rule

Create production-oriented filebase master data from the design sources, not from isolated imagination. Treat story and lore as light naming and motif guidance only; never force linear story progression, mandatory scenario viewing, or novel-like item definitions into master data.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\40_filebase\AGENTS.md`.
3. Read the master design documents:
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\00_README.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\01_ゲームコンセプト.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\02_ステータスとバランス方針.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\03_コンテンツ追加方針.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\04_命名規則と世界観メモ.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\05_初期オーバーワールド制作ブリーフ.md`
   - `E:\AstralRecord-Workspace\00_docs\99_資料\マスターデータ設計\06_AI追加チェックリスト.md`
4. Read `references/filebase-target-map.md` to choose target directories and schema files.
5. Read each target YAML schema before editing or adding a master file.
6. Inspect nearby existing YAML files for local formatting, reference style, ID style, rarity naming, and value scale.

## Workflow

1. Classify the requested content by target group:
   - First overworld package: world, enemy mob, mob spawner, material, equipment, consumable, loot pool/table, and starter shop when needed.
   - Combat package: class, skill, buff, equipment, enemy, loot.
   - Economy package: material, consumable, equipment, recipe, shop, loot.
2. Define the smallest coherent set of new masters. Prefer a complete playable loop over a large disconnected list.
3. Check IDs before editing:
   - Search all `40_filebase/**/*.yml` for the candidate `id`.
   - Use lowercase snake_case IDs.
   - Prefix only when it improves clarity, such as `astral_`, `sky_`, `field_`, `novice_`, or an area-specific prefix.
4. Add YAML files in the schema-defined directories. Keep one logical master per file, using the existing `v1.<id>.yml` naming style where the directory already uses it.
5. Keep references resolvable:
   - Use `ref: item:<id>`, `ref: skill:<id>`, `ref: buff:<id>`, `ref: mob:<id>`, and loot references according to the target schema.
   - If a new mob drops a new material, create the material first.
   - If a loot table references a new pool, create the pool first.
6. Balance for the requested level band using `02_ステータスとバランス方針.md`.
7. Re-read all changed YAML and the checklist before reporting.

## First Overworld Defaults

Use these defaults when the user asks for the first overworld and does not provide more detail:

- Level band: 1-8.
- Player baseline: solo play, optional duo, short sessions, low punishment.
- Area motif: sky-island frontier connected to a warm hub town.
- Enemy count: 3 normal enemy masters plus 1 spawner package before adding a boss.
- Item count: 4-6 materials/consumables and 6-10 starter equipment pieces.
- Reward loop: enemies drop local materials, materials feed shop/recipe/enhancement later, equipment is readable and modest.
- Rarity: mostly `COMMON`, a few `UNCOMMON`, no `RARE` or above unless the user explicitly asks.
- Story usage: use lore as short flavor only; do not create required scenario steps.

## Quality Bar

- Every created master must have a concrete gameplay purpose.
- Names and lore should feel consistent with AstralRecord's sky, record, town, exploration, and astral motifs without becoming ornate.
- Avoid debug names such as `test`, `sample`, `lab`, or `starter` for production additions unless the user explicitly requests a starter bundle.
- Do not alter plugin/API/web/resourcepack files unless the user requests implementation support or the schema requires a resource reference to exist.
- If a required implementation feature is missing, document the limitation in the report rather than inventing unsupported YAML.

## Report Format

Write the result in Japanese.

```markdown
## 作成結果
- <追加した playable loop / master group の概要>

## 追加・変更ファイル
- `<path>`: <内容>

## 参照整合
- <主要 ref と確認結果>

## 検証
- `<command or manual check>`: 成功 / 失敗 / 未実行（理由）

## 残事項
- なし / <次に作るとよい master や要確認事項>
```
