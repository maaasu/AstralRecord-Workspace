$ErrorActionPreference = "Stop"

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$toolProject = Join-Path $repoRoot "60_tool\db-migrate\DbMigrateTool.csproj"
$sourceConfigPath = Join-Path $repoRoot "60_tool\db-migrate\db-migrate.config.json"
$sqlcmd = (Get-Command sqlcmd -ErrorAction Stop).Source

$serverName = "tcp:localhost,1433"
$serverConnectionString = "Server=$serverName;Database=master;Integrated Security=True;TrustServerCertificate=True;"
$databaseName = "AstralRecordDbMigrateTest_" + [Guid]::NewGuid().ToString("N")
$databaseCreated = $false
$databaseConnectionString = "Server=$serverName;Database=$databaseName;Integrated Security=True;TrustServerCertificate=True;Connection Timeout=60;"
$tempConfigPath = Join-Path ([IO.Path]::GetTempPath()) "$databaseName.json"

function Invoke-DbNonQuery {
    param([string]$ConnectionString, [string]$CommandText)
    $database = if ($ConnectionString -match "Database=([^;]+)") { $Matches[1] } else { "master" }
    & $sqlcmd -S $serverName -d $database -E -C -b -l 60 -Q $CommandText | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "sqlcmd failed while executing a non-query."
    }
}

function Invoke-DbScalar {
    param([string]$ConnectionString, [string]$CommandText)
    $database = if ($ConnectionString -match "Database=([^;]+)") { $Matches[1] } else { "master" }
    $result = & $sqlcmd -S $serverName -d $database -E -C -b -l 60 -h -1 -W -Q $CommandText
    if ($LASTEXITCODE -ne 0) {
        throw "sqlcmd failed while executing a scalar query."
    }
    return ($result | Select-Object -First 1).ToString().Trim()
}

function Invoke-MigrationTool {
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "db-migrate failed with exit code $LASTEXITCODE."
    }
}

try {
    Invoke-DbNonQuery -ConnectionString $serverConnectionString -CommandText "CREATE DATABASE [$databaseName];"
    $databaseCreated = $true
    # The production migrations extend an existing game database. Seed only the
    # parent keys required by the new receipt's foreign keys in this isolated DB.
    Invoke-DbNonQuery -ConnectionString $databaseConnectionString -CommandText @"
CREATE TABLE dbo.account (uuid UNIQUEIDENTIFIER NOT NULL PRIMARY KEY);
CREATE TABLE dbo.market_listing (listing_id UNIQUEIDENTIFIER NOT NULL PRIMARY KEY);
"@

    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceConfigPath | ConvertFrom-Json
    $config.sourceApiAppsettingsPath = $null
    $config.connectionStrings.sqlServer = $databaseConnectionString
    $config.migrationsRootPath = [IO.Path]::GetFullPath((Join-Path (Split-Path $sourceConfigPath) $config.migrationsRootPath))
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath

    Invoke-MigrationTool
    $tableCount = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM sys.tables WHERE name = N'account_learned_skill_operation';"
    $historyCount = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'20260905_account_learned_skill_operation';"
    if ([int]$tableCount -ne 1 -or [int]$historyCount -ne 1) {
        throw "Initial migration did not create the target schema and history row."
    }
    $receiptTableCount = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM sys.tables WHERE name = N'market_listing_create_receipt';"
    $receiptHistoryCount = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'20260910_market_listing_create_receipt';"
    if ([int]$receiptTableCount -ne 1 -or [int]$receiptHistoryCount -ne 1) {
        throw "Listing creation receipt migration did not create its schema and history row."
    }

    Invoke-MigrationTool
    $historyCountAfterRerun = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'20260905_account_learned_skill_operation';"
    if ([int]$historyCountAfterRerun -ne 1) {
        throw "Migration rerun changed the applied history."
    }
    $receiptHistoryAfterRerun = Invoke-DbScalar -ConnectionString $databaseConnectionString -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'20260910_market_listing_create_receipt';"
    if ([int]$receiptHistoryAfterRerun -ne 1) {
        throw "Listing creation receipt migration was not idempotent."
    }

    $config.migrations[0].expectation.columns[0].sqlType = "int"
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath
    $ErrorActionPreference = "Continue"
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1 | Out-Null
    $schemaMismatchExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($schemaMismatchExit -eq 0) {
        throw "Schema mismatch was not rejected."
    }

    Write-Output "db-migrate integration checks passed."
}
finally {
    Remove-Item -LiteralPath $tempConfigPath -Force -ErrorAction SilentlyContinue
    try {
        if ($databaseCreated) {
            Invoke-DbNonQuery -ConnectionString $serverConnectionString -CommandText "IF DB_ID(N'$databaseName') IS NOT NULL BEGIN ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName]; END;"
        }
    }
    catch {
        Write-Warning "Could not clean up local integration-test database ${databaseName}: $($_.Exception.Message)"
    }
}
