---
name: astralrecord-master-data-author
description: AstralRecord の filebase マスタデータ作成 worker。準備済み task worktree の中で、50_Filebase設計書、対象 feature、モチーフ・進行度方針、既存 YAML スキーマを読み、item/equipment/material/consumable/class/skill/mob/spawner/world/loot/shop などの 40_filebase 定義を本番向けに整合させて追加・拡張する。通常依頼で worktree 作成や commit / develop 反映も必要になり得る場合は、統合入口 `$astralrecord-code-version-commit-develop` を優先する。
---

# AstralRecord Master Data Author

## Core Rule

Create production-oriented filebase master data from the design sources, not from isolated imagination. Keep individual-world ideas in the target YAML or explicitly supplied world context, and use stable motif/progression comments instead of change-prone planning notes.

## Required Context

1. Read `E:\AstralRecord-Workspace\AGENTS.md`.
2. Read `E:\AstralRecord-Workspace\40_filebase\AGENTS.md`.
3. Read the filebase design documents:
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\README.md`
   - the requested category's `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\feature\<category>.md`
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\モチーフ選定ガイド.md`
   - `E:\AstralRecord-Workspace\00_docs\50_Filebase設計書\作成時チェックリスト.md`
4. Read `references/filebase-target-map.md` to choose target directories and schema files.
5. Read each target YAML schema before editing or adding a master file.
6. Inspect nearby existing YAML files for local formatting, reference style, ID style, rarity naming, and value scale.

## Icon Selection Rule

- 新規の item または skill を定義するときは、既存コンテンツで使用済みの `icon` を確認し、原則として再利用しない。
- ただし、モチーフや用途から特定の `icon` が明らかに適切で、別の `icon` に置き換える方が不自然な場合は、既存コンテンツで使用済みでも再利用してよい。ノクスリンゴに `apple` を使うケースが該当する。
- 単なる見た目の近さや実装都合だけでは例外にしない。例外を適用した場合は、採用理由を作業報告へ記載する。

## Parallel Package Rule

When multiple workers create filebase masters in parallel, divide the work by the smallest coherent playable package, not by individual YAML files or by technical layers that depend on each other.

- Give each package its own task branch and dedicated worktree. Never let parallel writers share a writable worktree, Git index, or checked-out branch.
- Before editing, record the package name, owned paths, reserved IDs or ID prefixes, shared-file owner, dependencies on other packages, and intended finalize order.
- Prefer independent area / combat / economy packages whose references can be validated inside one worktree. Avoid splitting a mob, its material, and its loot chain across workers unless the dependency and merge order are explicit.
- Do not edit a shared registry, schema, design document, or common YAML from multiple packages. Assign one owner or defer that edit to a later integration task.
- Treat duplicate IDs as a semantic conflict even when Git reports a clean merge. After rebasing onto the latest `develop`, re-scan all `40_filebase/**/*.yml` for duplicate master IDs and revalidate every reference introduced or changed by the package before merge.

## Workflow

1. Classify the requested content by target group:
   - Area package: world, enemy mob, mob spawner, material, equipment, consumable, loot pool/table, and shop when needed.
   - Combat package: class, skill, buff, equipment, enemy, loot.
   - Economy package: material, consumable, equipment, recipe, shop, loot.
2. Define the smallest coherent set of new masters. Prefer a complete playable loop over a large disconnected list.
3. Check IDs before editing:
   - Search all `40_filebase/**/*.yml` for the candidate `id`.
   - Use lowercase snake_case IDs.
   - Prefix only when it improves ownership or category clarity. Do not infer an area motif that the user or target YAML does not provide.
4. Add YAML files in the schema-defined directories. Keep one logical master per file, using the existing `v1.<id>.yml` naming style where the directory already uses it.
5. Keep references resolvable:
   - Use `ref: item:<id>`, `ref: skill:<id>`, `ref: buff:<id>`, `ref: mob:<id>`, and loot references according to the target schema.
   - If a new mob drops a new material, create the material first.
   - If a loot table references a new pool, create the pool first.
6. Add the stable `motif` and relative `progression` design comments defined by the Filebase README, then derive values from the category role, progression, rarity, and acquisition difficulty.
7. Re-read all changed YAML and `作成時チェックリスト.md` before reporting.
8. For a parallel package, include the owned paths, reserved IDs, dependencies, shared-file decisions, and intended finalize order in the report so the later finalizer can revalidate them.

## Quality Bar

- Every created master must have a concrete gameplay purpose.
- Player-facing strings in master data must be written in Japanese for Japanese MMORPG users. This includes `name`, `displayName`, `title`, `label`, `description`, `lore`, mail `body`, NPC interaction `message`, shop names, quest text, and other text that can be shown or sent to players.
- Keep technical IDs, Bukkit Material names, enum values, reference prefixes, tags, and implementation identifiers in the existing English / uppercase formats required by the schema and plugin.
- Names and lore should follow the motif supplied by the user or recorded in the target YAML without adding an unsupported world setting.
- Avoid debug names such as `test`, `sample`, `lab`, or `starter` for production additions unless the user explicitly requests a starter bundle.
- Do not alter plugin/API/web/resourcepack files unless the user requests implementation support or the schema requires a resource reference to exist.
- If a required implementation feature is missing, document the limitation in the report rather than inventing unsupported YAML.

## Report Format

Write the result in Japanese.

```markdown
## 作成結果
- <追加した playable loop / master group の概要>

## 並列所有情報
- package: <単独作業なら不要 / package 名>
- owned paths / reserved IDs: <対象>
- dependencies / finalize order: <なし / 内容>

## 追加・変更ファイル
- `<path>`: <内容>

## 参照整合
- <主要 ref と確認結果>

## 検証
- `<command or manual check>`: 成功 / 失敗 / 未実行（理由）

## 残事項
- なし / <次に作るとよい master や要確認事項>
```
