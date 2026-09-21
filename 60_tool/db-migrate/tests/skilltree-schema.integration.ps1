param(
    [string]$RepoRoot = 'E:\AstralRecord-Worktrees\skill-tree-safe-web',
    [string]$SqlInstance = 'localhost\SQLEXPRESS'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = [IO.Path]::GetFullPath($RepoRoot)
$toolProject = Join-Path $RepoRoot '60_tool\db-migrate\DbMigrateTool.csproj'
$sourceConfigPath = Join-Path $RepoRoot '60_tool\db-migrate\db-migrate.config.json'
$migrationId = '20260921_skilltree_safe_editor'
$databaseName = 'AstralRecordSkillTreeSchemaTest_' + [Guid]::NewGuid().ToString('N')
$databaseCreated = $false
$tempConfigPath = Join-Path ([IO.Path]::GetTempPath()) "$databaseName.json"
$transcriptPath = Join-Path ([IO.Path]::GetTempPath()) "$databaseName.log"
$databaseConnectionString = "Server=$SqlInstance;Database=$databaseName;Integrated Security=True;TrustServerCertificate=True;Connection Timeout=60;"
$masterConnectionString = "Server=$SqlInstance;Database=master;Integrated Security=True;TrustServerCertificate=True;Connection Timeout=60;"
$sqlcmd = (Get-Command sqlcmd -ErrorAction Stop).Source

function Assert-TemporaryDatabaseName {
    param([string]$Name)
    if ($Name -notmatch '^AstralRecordSkillTreeSchemaTest_[0-9a-f]{32}$') {
        throw "Unsafe integration-test database name: $Name"
    }
}

function Invoke-DbNonQuery {
    param([string]$Database, [string]$CommandText)
    & $sqlcmd -S $SqlInstance -d $Database -E -C -b -l 60 -Q $CommandText 2>&1 | Write-Output
    if ($LASTEXITCODE -ne 0) {
        throw "sqlcmd failed for database '$Database'."
    }
}

function Invoke-DbScalar {
    param([string]$Database, [string]$CommandText)
    $result = & $sqlcmd -S $SqlInstance -d $Database -E -C -b -l 60 -h -1 -W -Q $CommandText 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "sqlcmd scalar query failed for database '$Database'."
    }
    return ($result | Select-Object -First 1).ToString().Trim()
}

function Invoke-MigrationRunner {
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1 | Write-Output
    if ($LASTEXITCODE -ne 0) {
        throw "db-migrate runner failed with exit code $LASTEXITCODE."
    }
}

function Assert-ScalarEqual {
    param([string]$Name, [int]$Actual, [int]$Expected)
    if ($Actual -ne $Expected) {
        throw "$Name expected $Expected but was $Actual."
    }
}

Start-Transcript -LiteralPath $transcriptPath -Force | Out-Null
try {
    if (-not (Test-Path -LiteralPath $toolProject) -or -not (Test-Path -LiteralPath $sourceConfigPath)) {
        throw "RepoRoot does not contain the db-migrate runner: $RepoRoot"
    }
    Assert-TemporaryDatabaseName $databaseName
    Write-Output "Skill-tree migration integration log: $transcriptPath"

    Invoke-DbNonQuery -Database 'master' -CommandText "CREATE DATABASE [$databaseName];"
    $databaseCreated = $true
    Invoke-DbNonQuery -Database $databaseName -CommandText @"
CREATE TABLE dbo.account (
    uuid UNIQUEIDENTIFIER NOT NULL,
    level INT NOT NULL,
    CONSTRAINT PK_account PRIMARY KEY CLUSTERED (uuid)
);
CREATE TABLE dbo.market_listing (
    listing_id UNIQUEIDENTIFIER NOT NULL,
    CONSTRAINT PK_market_listing PRIMARY KEY CLUSTERED (listing_id)
);
CREATE TABLE dbo.account_skilltree_state (
    account_skilltree_state_id UNIQUEIDENTIFIER NOT NULL,
    account_id UNIQUEIDENTIFIER NOT NULL,
    version INT NOT NULL,
    CONSTRAINT PK_account_skilltree_state PRIMARY KEY CLUSTERED (account_skilltree_state_id)
);
INSERT INTO dbo.account (uuid, level) VALUES ('11111111-1111-1111-1111-111111111111', 1);
INSERT INTO dbo.account_skilltree_state (account_skilltree_state_id, account_id, version)
VALUES ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 7);
"@

    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceConfigPath | ConvertFrom-Json
    $migration = @($config.migrations | Where-Object { $_.id -eq $migrationId })
    if ($migration.Count -ne 1 -or $migration[0].additionalExpectations.Count -lt 4) {
        throw "The target runner manifest is not ready for the skill-tree multi-table migration test."
    }
    $config.sourceApiAppsettingsPath = $null
    $config.connectionStrings.sqlServer = $databaseConnectionString
    $config.migrationsRootPath = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $sourceConfigPath) $config.migrationsRootPath))
    $config | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath

    & dotnet build $toolProject --nologo 2>&1 | Write-Output
    if ($LASTEXITCODE -ne 0) {
        throw "db-migrate runner build failed with exit code $LASTEXITCODE."
    }

    Invoke-MigrationRunner
    Assert-ScalarEqual -Name 'migration history count' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'$migrationId';")) -Expected 1
    Assert-ScalarEqual -Name 'sentinel legacy state preservation' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM dbo.account_skilltree_state WHERE account_skilltree_state_id = '22222222-2222-2222-2222-222222222222' AND account_id = '11111111-1111-1111-1111-111111111111' AND version = 7 AND definition_generation_id IS NULL;")) -Expected 1
    Assert-ScalarEqual -Name 'skill-tree runtime table count' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM sys.tables WHERE schema_id = SCHEMA_ID(N'dbo') AND name IN (N'skilltree_definition_generation', N'skilltree_server_runtime', N'skilltree_server_player_view', N'skilltree_operation', N'skilltree_migration_operation');")) -Expected 5
    Assert-ScalarEqual -Name 'skill-tree primary key count' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM sys.key_constraints WHERE [type] = N'PK' AND name IN (N'PK_skilltree_definition_generation', N'PK_skilltree_server_runtime', N'PK_skilltree_server_player_view', N'PK_skilltree_operation');")) -Expected 4
    Assert-ScalarEqual -Name 'skill-tree required constraints' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM sys.check_constraints WHERE name IN (N'CK_skilltree_definition_generation_id', N'CK_skilltree_definition_generation_snapshot_json', N'CK_skilltree_server_player_view_version', N'CK_skilltree_server_player_view_json', N'CK_skilltree_operation_version', N'CK_skilltree_operation_action');")) -Expected 6

    Invoke-MigrationRunner
    Assert-ScalarEqual -Name 'idempotent migration history count' -Actual ([int](Invoke-DbScalar -Database $databaseName -CommandText "SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id = N'$migrationId';")) -Expected 1

    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $tempConfigPath | ConvertFrom-Json
    $migration = @($config.migrations | Where-Object { $_.id -eq $migrationId })[0]
    $migration.additionalExpectations[0].columns += [pscustomobject]@{
        name = 'missing_skilltree_schema_column'
        sqlType = 'int'
        maxLengthBytes = 4
        isNullable = $false
    }
    $config | ConvertTo-Json -Depth 30 | Set-Content -Encoding UTF8 -LiteralPath $tempConfigPath
    $ErrorActionPreference = 'Continue'
    & dotnet run --no-build --project $toolProject -- --config $tempConfigPath 2>&1 | Write-Output
    $missingAdditionalExpectationExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($missingAdditionalExpectationExit -eq 0) {
        throw 'Runner accepted a missing column in an additional expectation after migration history was recorded.'
    }

    Write-Output 'PASS: skill-tree migration applies once, preserves legacy state, validates all tables, and rejects a missing additional-expectation column.'
}
finally {
    $ErrorActionPreference = 'Continue'
    Remove-Item -LiteralPath $tempConfigPath -Force -ErrorAction SilentlyContinue
    try {
        if ($databaseCreated) {
            Assert-TemporaryDatabaseName $databaseName
            Invoke-DbNonQuery -Database 'master' -CommandText "IF DB_ID(N'$databaseName') IS NOT NULL BEGIN ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName]; END;"
        }
    }
    finally {
        Stop-Transcript | Out-Null
    }
}
