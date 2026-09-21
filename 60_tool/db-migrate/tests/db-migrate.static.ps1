$ErrorActionPreference = "Stop"

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$toolProject = Join-Path $repoRoot "60_tool\db-migrate\DbMigrateTool.csproj"
$configPath = Join-Path $repoRoot "60_tool\db-migrate\db-migrate.config.json"
$historyConfigPath = Join-Path $repoRoot "60_tool\db-migrate\history-db-migrate.config.json"
$programPath = Join-Path $repoRoot "60_tool\db-migrate\Program.cs"
$deployPath = Join-Path $repoRoot "60_tool\deploy-debug\deploy-debug.ps1"

& dotnet run --project $toolProject -- --config $configPath --validate-only
if ($LASTEXITCODE -ne 0) {
    throw "db-migrate manifest validation failed."
}
& dotnet run --project $toolProject -- --config $historyConfigPath --validate-only
if ($LASTEXITCODE -ne 0) {
    throw "HistoryDB migration manifest validation failed."
}

$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$migrationRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $configPath) $config.migrationsRootPath))
$migrationFiles = @(Get-ChildItem -LiteralPath $migrationRoot -Filter "*.sql" -File)
$registeredFiles = @($config.migrations.fileName) + @($config.preExistingMigrationFileNames)
if ($migrationFiles.Count -ne $registeredFiles.Count) {
    throw "Every migration file must be accounted for by the manifest."
}
if (@($registeredFiles | Sort-Object -Unique).Count -ne $registeredFiles.Count) {
    throw "Migration manifest contains duplicate file names."
}

$historyConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $historyConfigPath | ConvertFrom-Json
$historyRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path $historyConfigPath) $historyConfig.migrationsRootPath))
$historyFiles = @(Get-ChildItem -LiteralPath $historyRoot -Filter "*.sql" -File)
if ($historyFiles.Count -ne 1 -or $historyFiles[0].Name -ne '20260921_player_activity.sql') {
    throw "HistoryDB migration directory must contain only the reviewed player activity migration."
}
$historyMigration = @($historyConfig.migrations | Where-Object { $_.id -eq '20260921_player_activity' })
$invalidHistoryManifest = $historyConfig.connectionStringName -ne 'History' -or $historyConfig.expectedDatabase -ne 'HistoryDB' -or $historyMigration.Count -ne 1 -or $historyMigration[0].additionalExpectations.Count -ne 7
if ($invalidHistoryManifest) {
    throw "HistoryDB player activity migration must use the History connection and validate all activity tables."
}

$receiptMigration = @($config.migrations | Where-Object { $_.id -eq "20260910_market_listing_create_receipt" })
if ($receiptMigration.Count -ne 1 -or $receiptMigration[0].fileName -ne "20260910_market_listing_create_receipt.sql" -or
    $receiptMigration[0].expectation.table -ne "market_listing_create_receipt") {
    throw "Listing creation receipt migration must be registered for execution and schema validation."
}

$program = Get-Content -Raw -Encoding UTF8 -LiteralPath $programPath
foreach ($requiredText in @(
    "EnsureMigrationHistoryTableAsync",
    "script_sha256",
    "Migration file is not registered in the manifest",
    "Unsupported connectionStringName",
    "Migration connection must target",
    "@LockTimeout = 120000",
    "Expected column definition does not match",
    "Expected index key order does not match"
)) {
    if ($program.IndexOf($requiredText, [StringComparison]::Ordinal) -lt 0) {
        throw "Required migration safety check is missing: $requiredText"
    }
}

$deploy = Get-Content -Raw -Encoding UTF8 -LiteralPath $deployPath
$migrationCall = $deploy.IndexOf("Invoke-DatabaseMigrations -MigrationConfig", [StringComparison]::Ordinal)
$historyMigrationCall = $deploy.IndexOf("Invoke-HistoryDatabaseMigrations -MigrationConfig", [StringComparison]::Ordinal)
$iisStop = $deploy.IndexOf('Invoke-IisReset -ComputerName $config.iis.host -Action "stop"', [StringComparison]::Ordinal)
$invalidMigrationOrder = $migrationCall -lt 0 -or $historyMigrationCall -lt 0 -or $iisStop -lt 0 -or $migrationCall -gt $historyMigrationCall -or $historyMigrationCall -gt $iisStop
if ($invalidMigrationOrder) {
    throw "Game and HistoryDB migrations must complete before IIS is stopped."
}

Write-Output "db-migrate static checks passed."
