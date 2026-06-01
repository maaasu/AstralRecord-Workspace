# DB Rebuild Tool

`E:\AstralRecord-Workspace\60_tool\DbRebuildTool` contains a .NET console tool that drops and recreates `AstralRecord`, `MasterDataDB`, and `HistoryDB`.

## Config

- Default source config: `\\192.168.0.88\server\AstralRecordApi\appsettings.json`
- Override file: [db-rebuild.config.json](/E:/AstralRecord-Workspace/60_tool/DbRebuildTool/db-rebuild.config.json:1)
- Override keys: `connectionStrings.*`, `fileDatabase.rootPath`, `masterData.systemUserId`
- When `seedMasterData` is `true`, the tool reuses the API `MasterDataSeeder` after recreating `MasterDataDB`
- `fileDatabase.rootPath` is optional even when the source API settings point to a server-local path. If that path is not accessible from the machine running the tool, the tool falls back to the workspace `40_filebase` directory when it can discover it.

## Run

```powershell
dotnet run --project E:\AstralRecord-Workspace\60_tool\DbRebuildTool\DbRebuildTool.csproj -- --yes
```

Batch file:

```bat
E:\AstralRecord-Workspace\60_tool\DbRebuildTool\db-rebuild.bat
```

Run without confirmation:

```bat
E:\AstralRecord-Workspace\60_tool\DbRebuildTool\db-rebuild.bat --yes
```

Use another config file:

```powershell
dotnet run --project E:\AstralRecord-Workspace\60_tool\DbRebuildTool\DbRebuildTool.csproj -- --config E:\path\to\db-rebuild.config.json --yes
```

Batch file with another config:

```bat
E:\AstralRecord-Workspace\60_tool\DbRebuildTool\db-rebuild.bat --config E:\path\to\db-rebuild.config.json
```

## Notes

- Without `--yes`, the tool asks for `REBUILD` confirmation.
- Each database is recreated with `DbContext.Database.EnsureCreatedAsync()`.
- Existing data is not preserved.
