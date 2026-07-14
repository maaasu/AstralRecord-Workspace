# Master data admin tool

## Purpose

This contract allows an operator to edit filebase YAML through the authenticated API, seed the edited files into MasterDataDB, and reload the plugin without restarting the Minecraft server.

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

1. Update one or more YAML files with the admin tool.
2. Run `POST /api/master-data/seed?mode=diff`.
3. Run `/masterdata reload` in Minecraft with permission level 99.

The plugin reloads API/filebase-backed master caches while preserving player state, inventory state, and runtime world instances.
