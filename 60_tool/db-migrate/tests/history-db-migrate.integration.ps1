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
    & $sqlcmd -S $serverName -d $Database -E -C -b -l 60 -Q $CommandText | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "sqlcmd failed while executing a non-query." }
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

    Invoke-MigrationTool
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

    $config.expectedDatabase = "HistoryDB"
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath
    $ErrorActionPreference = "Continue"
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1 | Out-Null
    $wrongTargetExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($wrongTargetExit -eq 0) {
        throw "HistoryDB migration accepted a connection to an unexpected database."
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
