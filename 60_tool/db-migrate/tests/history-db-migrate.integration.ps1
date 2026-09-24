$ErrorActionPreference = "Stop"

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$toolProject = Join-Path $repoRoot "60_tool\db-migrate\DbMigrateTool.csproj"
$sourceConfigPath = Join-Path $repoRoot "60_tool\db-migrate\history-db-migrate.config.json"
$sqlcmd = (Get-Command sqlcmd -ErrorAction Stop).Source

$serverName = "tcp:localhost,1433"
$serverConnectionString = "Server=$serverName;Database=master;Integrated Security=True;TrustServerCertificate=True;"
$databaseName = "HistoryDbMigrateTest_" + [Guid]::NewGuid().ToString("N")
$databaseConnectionString = "Server=$serverName;Database=$databaseName;Integrated Security=True;TrustServerCertificate=True;Connection Timeout=60;"
$tempConfigPath = Join-Path ([IO.Path]::GetTempPath()) "$databaseName.json"
$databaseCreated = $false

function Invoke-DbNonQuery {
    param([string]$Database, [string]$CommandText)
    # SqlClient/API と同じ QUOTED_IDENTIFIER ON で計算列への書き込みを検証する。
    $output = @(& $sqlcmd -S $serverName -d $Database -E -C -I -b -l 60 -Q $CommandText 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed while executing a non-query: $($output -join [Environment]::NewLine)" }
}

function Invoke-DbScalar {
    param([string]$CommandText)
    $result = & $sqlcmd -S $serverName -d $databaseName -E -C -b -l 60 -h -1 -W -Q $CommandText
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed while executing a scalar query." }
    return ($result | Select-Object -First 1).ToString().Trim()
}

function Invoke-MigrationTool {
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "HistoryDB db-migrate failed with exit code $LASTEXITCODE." }
}

try {
    Invoke-DbNonQuery -Database "master" -CommandText "CREATE DATABASE [$databaseName];"
    $databaseCreated = $true

    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceConfigPath | ConvertFrom-Json
    $config.sourceApiAppsettingsPath = $null
    $config.connectionStrings.history = $databaseConnectionString
    $config.expectedDatabase = $databaseName
    $config.migrationsRootPath = [IO.Path]::GetFullPath((Join-Path (Split-Path $sourceConfigPath) $config.migrationsRootPath))
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath

    # Upgrade an existing dungeon record, then ensure old API-shaped inserts still work.
    $initialMigrationPath = Join-Path $config.migrationsRootPath '20260921_player_activity.sql'
    & $sqlcmd -S $serverName -d $databaseName -E -C -b -l 60 -i $initialMigrationPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the pre-upgrade HistoryDB schema.' }
    Invoke-DbNonQuery -Database $databaseName -CommandText "INSERT dbo.dungeon_clear_activity(event_id,dungeon_id,dungeon_name,started_at,cleared_at) VALUES ('11111111-1111-1111-1111-111111111111',N'fixture',N'Fixture','2026-09-24T00:00:00.000','2026-09-24T00:01:00.125');"
    Invoke-MigrationTool
    $duration = [long](Invoke-DbScalar -CommandText "SELECT duration_milliseconds FROM dbo.dungeon_clear_activity WHERE event_id='11111111-1111-1111-1111-111111111111';")
    if ($duration -ne 60125) { throw 'Existing dungeon duration was not backfilled accurately.' }
    Invoke-DbNonQuery -Database $databaseName -CommandText "INSERT dbo.dungeon_clear_activity(event_id,dungeon_id,dungeon_name,started_at,cleared_at) VALUES ('22222222-2222-2222-2222-222222222222',N'fixture',N'Fixture','2026-09-24T00:00:00.000','2026-09-24T00:00:30.250');"
    $legacyDuration = [long](Invoke-DbScalar -CommandText "SELECT duration_milliseconds FROM dbo.dungeon_clear_activity WHERE event_id='22222222-2222-2222-2222-222222222222';")
    if ($legacyDuration -ne 30250) { throw 'Old API-shaped insert failed to compute duration.' }
    $bossTableCount = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM sys.tables WHERE name IN (N'boss_clear_activity', N'boss_clear_participant');")
    $bossMigrationCount = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260924_boss_activity_duration';")
    if ($bossTableCount -ne 2 -or $bossMigrationCount -ne 1) { throw 'Boss activity migration was not applied.' }
    Invoke-DbNonQuery -Database $databaseName -CommandText "INSERT dbo.boss_clear_activity(event_id,boss_id,boss_name,started_at,cleared_at) VALUES ('33333333-3333-3333-3333-333333333333',N'fixture-boss',N'Fixture Boss','2026-09-24T00:00:00.000','2026-09-24T00:00:12.345'); INSERT dbo.boss_clear_participant(event_id,account_id,user_uuid,mcid,account_name,damage_dealt,death_count) VALUES ('33333333-3333-3333-3333-333333333333',NEWID(),NEWID(),N'Fixture',N'Fixture',7.25,1);"
    $bossDuration = [long](Invoke-DbScalar -CommandText "SELECT duration_milliseconds FROM dbo.boss_clear_activity WHERE event_id='33333333-3333-3333-3333-333333333333';")
    if ($bossDuration -ne 12345) { throw 'Boss duration was not computed accurately on the migrated schema.' }
    $computedColumns = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM sys.computed_columns WHERE name=N'duration_milliseconds' AND is_persisted=1 AND is_nullable=0 AND object_id IN (OBJECT_ID(N'dbo.dungeon_clear_activity'), OBJECT_ID(N'dbo.boss_clear_activity'));")
    if ($computedColumns -ne 2) { throw 'Both duration columns must be persisted, non-null computed columns.' }
    $tableCount = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM sys.tables WHERE schema_id=SCHEMA_ID(N'dbo') AND name IN (N'player_activity_batch', N'player_ip_observation', N'player_trade_activity', N'player_trade_activity_item', N'dungeon_clear_activity', N'dungeon_clear_participant', N'mob_damage_summary', N'mob_player_death');")
    $historyCount = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260921_player_activity';")
    if ($tableCount -ne 8 -or $historyCount -ne 1) {
        throw "Initial HistoryDB migration did not create all activity tables and its migration history row."
    }

    Invoke-MigrationTool
    $historyCountAfterRerun = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260921_player_activity';")
    if ($historyCountAfterRerun -ne 1) {
        throw "HistoryDB migration rerun was not idempotent."
    }
    $bossMigrationAfterRerun = [int](Invoke-DbScalar -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260924_boss_activity_duration';")
    if ($bossMigrationAfterRerun -ne 1) { throw 'Boss migration rerun was not idempotent.' }

    $config.expectedDatabase = "HistoryDB"
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath
    $ErrorActionPreference = "Continue"
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1 | Out-Null
    $wrongTargetExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($wrongTargetExit -eq 0) {
        throw "HistoryDB migration accepted a connection to an unexpected database."
    }

    $config.expectedDatabase = $databaseName
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath
    Invoke-DbNonQuery -Database $databaseName -CommandText "DROP INDEX IX_player_ip_observation_ip_observed ON dbo.player_ip_observation;"
    $ErrorActionPreference = "Continue"
    $schemaValidationOutput = @(& dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1)
    $schemaValidationExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaValidationExit -eq 0) {
        throw "HistoryDB migration accepted a missing required index after its migration history was recorded."
    }
    if (($schemaValidationOutput -join [Environment]::NewLine) -notmatch 'Expected index was not found: IX_player_ip_observation_ip_observed') {
        throw "HistoryDB migration failure did not report the missing required index: $($schemaValidationOutput -join [Environment]::NewLine)"
    }

    Write-Output "HistoryDB migration integration checks passed."
}
finally {
    Remove-Item -LiteralPath $tempConfigPath -Force -ErrorAction SilentlyContinue
    try {
        if ($databaseCreated) {
            Invoke-DbNonQuery -Database "master" -CommandText "IF DB_ID(N'$databaseName') IS NOT NULL BEGIN ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName]; END;"
        }
    }
    catch {
        Write-Warning "Could not clean up local integration-test database ${databaseName}: $($_.Exception.Message)"
    }
}
