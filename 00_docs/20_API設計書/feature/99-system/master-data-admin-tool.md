# Master data admin tool

## Purpose

This contract allows an operator to edit filebase YAML through the authenticated API, seed the edited files into MasterDataDB, and reload the plugin without restarting the Minecraft server. SkillTree node and structure JSON are edited separately with `60_tool/skilltree-editor/`; the YAML file API and MasterDataDB Seeder do not accept those JSON files.

## API

All endpoints require `X-Api-Key`.

| Method | Path | Description |
|---|---|---|
| GET | `/api/master-data/files?directory={directory}` | List `.yml` files |
| GET | `/api/master-data/files/{relativePath}` | Read a YAML file |
| PUT | `/api/master-data/files/{relativePath}` | Create or replace a YAML file; body is `{ "content": "..." }` |
| DELETE | `/api/master-data/files/{relativePath}` | Delete a YAML file |
| POST | `/api/master-data/seed?mode=diff` | Synchronize filebase to MasterDataDB |

The file path must remain below `FileDatabase:RootPath`, must not contain `..`, and must target a `.yml` file. The bundled `tools/master-data-admin.ps1` wraps these calls.

## Runtime flow

1. Update one or more YAML files with the admin tool. For SkillTree, save validated JSON with `60_tool/skilltree-editor/` instead.
2. Run `POST /api/master-data/seed?mode=diff` when YAML-backed MasterDataDB entries changed. SkillTree-only changes do not require seeding.
3. Run `/masterdata reload` in Minecraft with permission level 99.

The plugin reloads API/filebase-backed master caches, including SkillTree node/structure JSON, while preserving player state, inventory state, and runtime world instances. SkillTree `unlockedNodeIds` remain in the `account-skilltree` API / DB and are not replaced by master-data reload.
