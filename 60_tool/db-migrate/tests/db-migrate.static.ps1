$ErrorActionPreference = "Stop"

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$toolProject = Join-Path $repoRoot "60_tool\db-migrate\DbMigrateTool.csproj"
$configPath = Join-Path $repoRoot "60_tool\db-migrate\db-migrate.config.json"
$programPath = Join-Path $repoRoot "60_tool\db-migrate\Program.cs"
$deployPath = Join-Path $repoRoot "60_tool\deploy-debug\deploy-debug.ps1"

& dotnet run --project $toolProject -- --config $configPath --validate-only
if ($LASTEXITCODE -ne 0) {
    throw "db-migrate manifest validation failed."
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

$program = Get-Content -Raw -Encoding UTF8 -LiteralPath $programPath
foreach ($requiredText in @(
    "EnsureMigrationHistoryTableAsync",
    "script_sha256",
    "Migration file is not registered in the manifest",
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
$iisStop = $deploy.IndexOf('Invoke-IisReset -ComputerName $config.iis.host -Action "stop"', [StringComparison]::Ordinal)
if ($migrationCall -lt 0 -or $iisStop -lt 0 -or $migrationCall -gt $iisStop) {
    throw "Database migration must complete before IIS is stopped."
}

Write-Output "db-migrate static checks passed."
