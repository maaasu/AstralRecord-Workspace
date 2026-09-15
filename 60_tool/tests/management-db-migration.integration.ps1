param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)) {
    throw 'ASTRALRECORD_SQLSERVER_TEST_CONNECTION is required. Only isolated temporary databases are modified.'
}
$suffix = [guid]::NewGuid().ToString('N')
$managementName = 'AR_ManagementDb_Test_' + $suffix
$legacyName = 'AR_WebSiteDb_Test_' + $suffix
$builder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new($env:ASTRALRECORD_SQLSERVER_TEST_CONNECTION)
$builder['Initial Catalog'] = 'master'
$connection = [System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
function Invoke-Sql([string]$Sql, [switch]$Scalar) {
    $command = $connection.CreateCommand()
    try {
        $command.CommandText = $Sql
        $command.CommandTimeout = 30
        if ($Scalar) { return $command.ExecuteScalar() }
        [void]$command.ExecuteNonQuery()
    } finally { $command.Dispose() }
}
function Invoke-TestScript([string]$Path) {
    $sql = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
    $sql = $sql.Replace('ManagementDB', $managementName).Replace('WebSiteDB', $legacyName)
    foreach ($batch in [regex]::Split($sql, '(?im)^\s*GO\s*$')) {
        if (-not [string]::IsNullOrWhiteSpace($batch)) { Invoke-Sql $batch }
    }
}
try {
    $connection.Open()
    Invoke-Sql "CREATE DATABASE [$legacyName];"
    Invoke-Sql "USE [$legacyName]; CREATE TABLE dbo.web_user(user_uuid uniqueidentifier PRIMARY KEY, mcid nvarchar(100), web_admin bit, first_login_at datetime2(3), last_login_at datetime2(3)); INSERT dbo.web_user VALUES ('11111111-1111-1111-1111-111111111111', N'PreservedPlayer', 1, '2026-01-01', '2026-09-15');"
    $schemaRoot = Join-Path $RepoRoot '00_docs/40_Database設計書/table-definitions/ManagementDB'
    Invoke-TestScript (Join-Path $schemaRoot 'init.sql')
    Invoke-TestScript (Join-Path $schemaRoot 'migrations/20260915-import-website.sql')
    $preserved = Invoke-Sql "SELECT COUNT(*) FROM dbo.player WHERE player_uuid='11111111-1111-1111-1111-111111111111' AND mcid=N'PreservedPlayer' AND web_admin=1 AND created_at='2026-01-01' AND updated_at='2026-09-15' AND first_web_login_at=created_at AND last_web_login_at=updated_at;" -Scalar
    if ($preserved -ne 1) { throw 'Legacy identity, permissions or timestamps were not preserved.' }
    Invoke-Sql "UPDATE dbo.player SET web_admin=0; INSERT dbo.player(player_uuid,mcid) VALUES('22222222-2222-2222-2222-222222222222',N'NotYetWebUser');"
    Invoke-TestScript (Join-Path $schemaRoot 'init.sql')
    Invoke-TestScript (Join-Path $schemaRoot 'migrations/20260915-import-website.sql')
    if ((Invoke-Sql 'SELECT COUNT(*) FROM dbo.player;' -Scalar) -ne 2) { throw 'Re-run changed player count.' }
    if ((Invoke-Sql 'SELECT COUNT(*) FROM dbo.player WHERE web_admin=1;' -Scalar) -ne 0) { throw 'Re-run restored stale permissions.' }
    if ((Invoke-Sql 'SELECT COUNT(*) FROM dbo.player WHERE first_web_login_at IS NULL AND last_web_login_at IS NULL;' -Scalar) -ne 1) { throw 'Non-Web identity was not preserved.' }
    if ((Invoke-Sql 'SELECT COUNT(*) FROM dbo.schema_migration;' -Scalar) -ne 1) { throw 'Migration ledger is not idempotent.' }
    Write-Output 'PASS: migration preserves identity/admin/timestamps; rerun preserves newer permissions and non-Web players.'
} finally {
    if ($connection.State -eq [System.Data.ConnectionState]::Open) {
        foreach ($databaseName in @($managementName, $legacyName)) {
            if ($databaseName -notmatch '^AR_(ManagementDb|WebSiteDb)_Test_[0-9a-f]{32}$') { throw 'Unsafe temporary DB cleanup target.' }
            Invoke-Sql "USE master; IF DB_ID(N'$databaseName') IS NOT NULL BEGIN ALTER DATABASE [$databaseName] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [$databaseName]; END;"
        }
    }
    $connection.Dispose()
}
