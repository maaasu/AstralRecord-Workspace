param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))))

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)) {
    throw 'ASTRALRECORD_SQLSERVER_TEST_CONNECTION is required. Only an isolated temporary database is modified.'
}

$databaseName = 'AR_ManagementDbMigrate_Test_' + [Guid]::NewGuid().ToString('N')
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) $databaseName
$connectionBuilder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)
$connectionBuilder['Initial Catalog'] = 'master'
$serverConnection = [System.Data.SqlClient.SqlConnection]::new($connectionBuilder.ConnectionString)
$databaseCreated = $false

function Invoke-Sql([string]$Database, [string]$Sql, [switch]$Scalar) {
    $builder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)
    $builder['Initial Catalog'] = $Database
    $connection = [System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
    try {
        $connection.Open()
        $command = $connection.CreateCommand(); $command.CommandText = $Sql; $command.CommandTimeout = 30
        try { if ($Scalar) { return $command.ExecuteScalar() }; [void]$command.ExecuteNonQuery() } finally { $command.Dispose() }
    } finally { $connection.Dispose() }
}

try {
    [void][IO.Directory]::CreateDirectory($temporaryRoot)
    $serverConnection.Open()
    $create = $serverConnection.CreateCommand(); $create.CommandText = "CREATE DATABASE [$databaseName]"; [void]$create.ExecuteNonQuery(); $create.Dispose(); $databaseCreated = $true
    Invoke-Sql $databaseName 'CREATE TABLE dbo.player(player_uuid uniqueidentifier NOT NULL PRIMARY KEY); CREATE TABLE dbo.schema_migration(migration_id nvarchar(150) NOT NULL PRIMARY KEY, applied_at datetime2(3) NOT NULL DEFAULT SYSUTCDATETIME());'

    $databaseConnectionBuilder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)
$databaseConnectionBuilder['Initial Catalog'] = $databaseName
    $configPath = Join-Path $temporaryRoot 'management-db-migrate.config.json'
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RepoRoot '60_tool\management-db-migrate\management-db-migrate.config.json') | ConvertFrom-Json
    $config.sourceApiAppsettingsPath = $null
    $config | Add-Member -NotePropertyName expectedDatabase -NotePropertyValue $databaseName
    $config.connectionStrings.management = $databaseConnectionBuilder.ConnectionString
    $config.migrationsRootPath = Join-Path $RepoRoot '00_docs\40_Database設計書\table-definitions\ManagementDB\migrations'
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $configPath

    $project = Join-Path $RepoRoot '60_tool\management-db-migrate\ManagementDbMigrateTool.csproj'
    & dotnet run --no-build --project $project -- --config $configPath
    if ($LASTEXITCODE -ne 0) { throw 'Management migration runner failed on the isolated database.' }
    foreach ($sql in @(
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'web_credential'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'web_credential_login_attempt'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'web_trusted_browser'",
        "SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.web_credential') AND name=N'UX_web_credential_login_id'",
        "SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.web_trusted_browser') AND name=N'UX_web_trusted_browser_token_hash'",
        "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260917_web_credentials'",
        "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260920_trusted_admin_browser'",
        "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=N'20260926_donations'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'donation_request'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'donation_ledger'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'donation_grant'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'donation_notification'",
        "SELECT COUNT(*) FROM sys.tables WHERE name=N'donation_discord_link'",
        "SELECT COUNT(*) FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.donation_grant') AND name=N'UX_donation_grant_account_total'"
    )) {
        if ([int](Invoke-Sql $databaseName $sql -Scalar) -ne 1) { throw "Schema verification failed: $sql" }
    }
    & dotnet run --no-build --project $project -- --config $configPath
    if ($LASTEXITCODE -ne 0 -or
        [int](Invoke-Sql $databaseName "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id IN (N'20260917_web_credentials', N'20260920_trusted_admin_browser', N'20260926_donations')" -Scalar) -ne 3) { throw 'Management migration rerun was not idempotent.' }

    $negativeRoot = Join-Path $temporaryRoot 'altered'
    [void][IO.Directory]::CreateDirectory($negativeRoot)
    $sourceScript = Join-Path $RepoRoot '00_docs\40_Database設計書\table-definitions\ManagementDB\migrations\20260917_web_credentials.sql'
    foreach ($change in @(
        @{ Name='different-root'; Replacement="`n-- altered root" },
        @{ Name='destructive-dml'; Replacement="`nDELETE FROM dbo.player;" },
        @{ Name='inline-use'; Replacement="`nSELECT 1; USE [master]; USE [ManagementDB];" }
    )) {
        $alteredPath = Join-Path $negativeRoot '20260917_web_credentials.sql'
        (Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceScript) + $change.Replacement | Set-Content -Encoding UTF8 -LiteralPath $alteredPath
        $config.migrationsRootPath = $negativeRoot
        $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $configPath
        $previousErrorActionPreference = $ErrorActionPreference
        try { $ErrorActionPreference = 'Continue'; $output = & dotnet run --no-build --project $project -- --config $configPath --validate-only 2>&1; $exitCode = $LASTEXITCODE }
        finally { $ErrorActionPreference = $previousErrorActionPreference }
        if ($exitCode -eq 0 -or ($output -join [Environment]::NewLine) -notmatch 'script hash does not match') { throw "Altered migration was not rejected: $($change.Name)" }
    }
    Write-Output 'management-db-migrate integration checks passed.'
}
finally {
    if ($serverConnection.State -eq [System.Data.ConnectionState]::Open -and $databaseCreated) {
        $drop = $serverConnection.CreateCommand(); $drop.CommandText = "IF DB_ID(N'$databaseName') IS NOT NULL BEGIN ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName]; END;"; [void]$drop.ExecuteNonQuery(); $drop.Dispose()
    }
    $serverConnection.Dispose()
    if ((Test-Path -LiteralPath $temporaryRoot) -and [IO.Path]::GetFileName($temporaryRoot) -match '^AR_ManagementDbMigrate_Test_[0-9a-f]{32}$') { Remove-Item -LiteralPath $temporaryRoot -Recurse -Force }
}
