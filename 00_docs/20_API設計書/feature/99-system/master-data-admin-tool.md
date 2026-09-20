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
3. Each Minecraft Plugin instance polls `GET /api/master-data/health` and reloads master data after it observes a new `SUCCEEDED` Seeder run.

The plugin reloads API/filebase-backed master caches, including SkillTree node/structure JSON, while preserving player state, inventory state, and runtime world instances. SkillTree unlocked nodes and their CP source class remain in the `account-skilltree` API / DB and are not replaced by master-data reload.

`/masterdata reload` with permission level 99 remains available for manual recovery and operational checks.

## Plugin polling

The Plugin uses `X-Api-Key` and the shared API transport settings to poll `/api/master-data/health` without blocking the Bukkit main thread. Polling is configured in the Plugin `config.yml`:

```yaml
masterData:
  autoReload:
    enabled: true
    pollIntervalSeconds: 30
```

- The first valid health response establishes a baseline and never starts a reload.
- A reload is eligible only when `lastSeedRunStatus` is `SUCCEEDED`, `lastSeedRunId` is non-null, and `lastSucceededAt` is present.
- A successful Seeder run is reloaded at most once by the automatic monitor. `FAILED` and `RUNNING` runs do not start a reload.
- A null `lastSeedRunId`, transport failure, non-success HTTP response, or invalid JSON is logged and retried on a later poll.
- The monitor delegates reload execution to the existing `AstralRecord.reloadMasterData()` concurrency boundary.
- If another reload is active or a newer successful Seeder run appears during reload, the monitor waits for completion, fetches the latest health again, and starts a follow-up reload when required.
- Disabling the Plugin cancels the periodic task and any in-flight health request. It does not remove the manual `/masterdata reload` command.
