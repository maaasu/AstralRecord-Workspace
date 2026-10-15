$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$toolProject = Join-Path $repoRoot '60_tool\db-migrate\DbMigrateTool.csproj'
$configPath = Join-Path $repoRoot '60_tool\db-migrate\db-migrate.config.json'
$sourceConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
$migrationRoot = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $configPath) $sourceConfig.migrationsRootPath))
$sqlcmd = (Get-Command sqlcmd -ErrorAction Stop).Source
$serverName = 'tcp:localhost,1433'
$databaseName = 'AstralRecordEnchantLockTest_' + [Guid]::NewGuid().ToString('N')
$tempConfig = Join-Path ([IO.Path]::GetTempPath()) "$databaseName.json"
$created = $false

function Invoke-TestSql {
    param([string]$Database, [string]$Sql)
    $output = & $sqlcmd -S $serverName -d $Database -E -C -b -l 10 -h -1 -W -Q $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Test SQL failed: $output" }
    return ($output | Out-String).Trim()
}

try {
    Invoke-TestSql master "CREATE DATABASE [$databaseName];" | Out-Null
    $created = $true
    Invoke-TestSql $databaseName @'
CREATE TABLE dbo.equipment_instance_enchant (
 enchant_id UNIQUEIDENTIFIER NOT NULL,
 CONSTRAINT PK_equipment_instance_enchant PRIMARY KEY (enchant_id)
);
INSERT dbo.equipment_instance_enchant VALUES (NEWID());
'@ | Out-Null
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
    $config.sourceApiAppsettingsPath = $null
    $config.connectionStrings.sqlServer = "Server=$serverName;Database=$databaseName;Integrated Security=True;TrustServerCertificate=True;"
    $config.migrationsRootPath = $migrationRoot
    $config.migrations = @($config.migrations | Where-Object { $_.id -eq '20261013_equipment_enchant_lock' })
    if ($config.migrations.Count -ne 1) { throw 'Expected exactly one registered enchant lock migration.' }
    $config.preExistingMigrationFileNames = @(Get-ChildItem -LiteralPath $migrationRoot -Filter '*.sql' |
        Where-Object { $_.Name -ne '20261013_equipment_enchant_lock.sql' } | ForEach-Object { $_.Name })
    $config | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 -LiteralPath $tempConfig
    & dotnet run --project $toolProject -- --config $tempConfig --validate-only
    if ($LASTEXITCODE -ne 0) { throw 'Isolated migration manifest validation failed.' }
    & dotnet run --no-build --project $toolProject -- --config $tempConfig
    if ($LASTEXITCODE -ne 0) { throw 'First migration run failed.' }
    if ((Invoke-TestSql $databaseName 'SET NOCOUNT ON; SELECT CAST(is_locked AS INT) FROM dbo.equipment_instance_enchant;') -ne '0') {
        throw 'Migration must initialize every legacy row as unlocked.'
    }
    Invoke-TestSql $databaseName 'UPDATE dbo.equipment_instance_enchant SET is_locked=1;' | Out-Null
    & dotnet run --no-build --project $toolProject -- --config $tempConfig
    if ($LASTEXITCODE -ne 0) { throw 'Idempotent migration replay failed.' }
    if ((Invoke-TestSql $databaseName 'SET NOCOUNT ON; SELECT CAST(is_locked AS INT) FROM dbo.equipment_instance_enchant;') -ne '1') {
        throw 'Replay must preserve an existing lock.'
    }
    Invoke-TestSql $databaseName 'INSERT dbo.equipment_instance_enchant (enchant_id) VALUES (NEWID());' | Out-Null
    if ((Invoke-TestSql $databaseName 'SET NOCOUNT ON; SELECT COUNT(*) FROM dbo.equipment_instance_enchant WHERE is_locked=0;') -ne '1') {
        throw 'New rows must default to unlocked.'
    }
    Write-Output 'Enchant lock migration: manifest, legacy false, new-row default, replay and schema validation passed.'
} finally {
    if ($created) { Invoke-TestSql master "ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName];" | Out-Null }
    if (Test-Path -LiteralPath $tempConfig) { Remove-Item -LiteralPath $tempConfig }
}
