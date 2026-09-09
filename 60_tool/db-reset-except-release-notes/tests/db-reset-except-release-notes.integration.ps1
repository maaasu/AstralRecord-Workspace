$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
$projectPath = Join-Path $repoRoot "60_tool\db-reset-except-release-notes\DbResetExceptReleaseNotesTool.csproj"
$toolConfigPath = Join-Path $repoRoot "60_tool\db-reset-except-release-notes\db-reset-except-release-notes.config.json"
$toolConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $toolConfigPath | ConvertFrom-Json
$sourceSettingsPath = [Environment]::ExpandEnvironmentVariables([string]$toolConfig.sourceApiAppsettingsPath)
$sourceSettings = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceSettingsPath | ConvertFrom-Json
$testSuffix = "_Integration_$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
$astralRecordName = "AstralRecord$testSuffix"
$masterDataName = "MasterDataDB$testSuffix"
$historyName = "HistoryDB$testSuffix"
$backupPrefix = "${astralRecordName}_ReleaseNotesBackup_"
$offlineRoot = Join-Path ([System.IO.Path]::GetTempPath()) "AstralRecordDbReset$testSuffix"
$offlineMarkerPath = Join-Path $offlineRoot "app_offline.htm"
$resetLockResource = "AstralRecord:DbReset:$astralRecordName"

function New-DatabaseConnection {
    param(
        [Parameter(Mandatory = $true)][string]$ConnectionString,
        [Parameter(Mandatory = $true)][string]$DatabaseName
    )

    $builder = [System.Data.SqlClient.SqlConnectionStringBuilder]::new($ConnectionString)
    $builder["Initial Catalog"] = $DatabaseName
    return [System.Data.SqlClient.SqlConnection]::new($builder.ConnectionString)
}

function Invoke-NonQuery {
    param(
        [Parameter(Mandatory = $true)][string]$ConnectionString,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$Sql,
        [hashtable]$Parameters = @{}
    )

    $connection = New-DatabaseConnection -ConnectionString $ConnectionString -DatabaseName $DatabaseName
    try {
        $connection.Open()
        $command = $connection.CreateCommand()
        $command.CommandText = $Sql
        $command.CommandTimeout = 120
        foreach ($entry in $Parameters.GetEnumerator()) {
            $null = $command.Parameters.AddWithValue($entry.Key, $entry.Value)
        }
        $null = $command.ExecuteNonQuery()
    }
    finally {
        if ($null -ne $command) { $command.Dispose() }
        $connection.Dispose()
    }
}

function Invoke-Scalar {
    param(
        [Parameter(Mandatory = $true)][string]$ConnectionString,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$Sql,
        [hashtable]$Parameters = @{}
    )

    $connection = New-DatabaseConnection -ConnectionString $ConnectionString -DatabaseName $DatabaseName
    try {
        $connection.Open()
        $command = $connection.CreateCommand()
        $command.CommandText = $Sql
        $command.CommandTimeout = 120
        foreach ($entry in $Parameters.GetEnumerator()) {
            $null = $command.Parameters.AddWithValue($entry.Key, $entry.Value)
        }
        return $command.ExecuteScalar()
    }
    finally {
        if ($null -ne $command) { $command.Dispose() }
        $connection.Dispose()
    }
}

function Invoke-Tool {
    param(
        [string[]]$AdditionalArguments = @(),
        [Parameter(Mandatory = $true)][bool]$ExpectSuccess
    )

    & dotnet run --project $projectPath --configuration Release -- `
        --integration-test-suffix $testSuffix --integration-app-root $offlineRoot --yes @AdditionalArguments
    $exitCode = $LASTEXITCODE
    if ($ExpectSuccess -and $exitCode -ne 0) {
        throw "DB reset tool failed unexpectedly with exit code $exitCode."
    }
    if (-not $ExpectSuccess -and $exitCode -eq 0) {
        throw "DB reset tool succeeded although an injected failure was expected."
    }
}

function Assert-ResetLockExclusion {
    $connection = New-DatabaseConnection `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName "master"
    try {
        $connection.Open()
        $command = $connection.CreateCommand()
        $command.CommandText = @"
DECLARE @result int;
EXEC @result = [sys].[sp_getapplock]
    @Resource = @resource,
    @LockMode = N'Exclusive',
    @LockOwner = N'Session',
    @LockTimeout = 0,
    @DbPrincipal = N'public';
SELECT @result;
"@
        $null = $command.Parameters.AddWithValue("@resource", $resetLockResource)
        $lockResult = [int]$command.ExecuteScalar()
        if ($lockResult -lt 0) {
            throw "Failed to acquire the integration reset lock. Result=$lockResult"
        }

        Invoke-Tool -ExpectSuccess $false
        Assert-Equal $false (Test-Path -LiteralPath $offlineMarkerPath) "marker blocked by reset lock"
    }
    finally {
        if ($null -ne $command) { $command.Dispose() }
        if ($connection.State -eq [System.Data.ConnectionState]::Open) {
            $releaseCommand = $connection.CreateCommand()
            try {
                $releaseCommand.CommandText = @"
DECLARE @result int;
EXEC @result = [sys].[sp_releaseapplock]
    @Resource = @resource,
    @LockOwner = N'Session',
    @DbPrincipal = N'public';
SELECT @result;
"@
                $null = $releaseCommand.Parameters.AddWithValue("@resource", $resetLockResource)
                $null = $releaseCommand.ExecuteScalar()
            }
            finally {
                $releaseCommand.Dispose()
            }
        }
        $connection.Dispose()
    }
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Expected -ne $Actual) {
        throw "$Label mismatch. Expected=$Expected Actual=$Actual"
    }
}

function Get-PendingBackupNames {
    $connection = New-DatabaseConnection `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName "master"
    try {
        $connection.Open()
        $command = $connection.CreateCommand()
        $command.CommandText = @"
SELECT [name]
FROM [sys].[databases]
WHERE LEFT([name], LEN(@prefix)) = @prefix
ORDER BY [name];
"@
        $null = $command.Parameters.AddWithValue("@prefix", $backupPrefix)
        $reader = $command.ExecuteReader()
        $names = [System.Collections.Generic.List[string]]::new()
        while ($reader.Read()) { $names.Add($reader.GetString(0)) }
        $reader.Dispose()
        $command.Dispose()
        return $names.ToArray()
    }
    finally {
        $connection.Dispose()
    }
}

function Remove-TestDatabases {
    $targets = @(
        @{ ConnectionString = [string]$sourceSettings.ConnectionStrings.SqlServer; Name = $astralRecordName },
        @{ ConnectionString = [string]$sourceSettings.ConnectionStrings.MasterData; Name = $masterDataName },
        @{ ConnectionString = [string]$sourceSettings.ConnectionStrings.History; Name = $historyName }
    )

    foreach ($target in $targets) {
        Invoke-NonQuery -ConnectionString $target.ConnectionString -DatabaseName "master" -Sql @"
IF DB_ID(@databaseName) IS NOT NULL
BEGIN
    DECLARE @statement nvarchar(max);
    IF DATABASEPROPERTYEX(@databaseName, N'Status') = N'OFFLINE'
    BEGIN
        SET @statement = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET ONLINE';
        EXEC(@statement);
    END;
    SET @statement = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE';
    EXEC(@statement);
    SET @statement = N'DROP DATABASE ' + QUOTENAME(@databaseName);
    EXEC(@statement);
END;
"@ -Parameters @{ "@databaseName" = $target.Name }
    }

    foreach ($backupName in @(Get-PendingBackupNames)) {
        Invoke-NonQuery `
            -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
            -DatabaseName "master" `
            -Sql @"
DECLARE @statement nvarchar(max);
SET @statement = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE';
EXEC(@statement);
SET @statement = N'DROP DATABASE ' + QUOTENAME(@databaseName);
EXEC(@statement);
"@ `
            -Parameters @{ "@databaseName" = $backupName }
    }
}

try {
    Remove-TestDatabases
    $null = New-Item -ItemType Directory -Path $offlineRoot -Force

    Write-Host "Testing reset execution lock and foreign marker ownership..."
    Assert-ResetLockExclusion
    $foreignMarker = "foreign application maintenance"
    Set-Content -LiteralPath $offlineMarkerPath -Value $foreignMarker -Encoding UTF8 -NoNewline
    Invoke-Tool -ExpectSuccess $false
    Assert-Equal $foreignMarker (Get-Content -Raw -Encoding UTF8 -LiteralPath $offlineMarkerPath) "foreign marker preservation"
    Remove-Item -LiteralPath $offlineMarkerPath -Force

    Write-Host "Testing recovery when no release note backup was needed..."
    Invoke-Tool -AdditionalArguments @("--resume-operation", "00000000000000000000000000000000") -ExpectSuccess $false
    Assert-Equal $false (Test-Path -LiteralPath $offlineMarkerPath) "unowned resume marker rejection"
    Invoke-Tool -AdditionalArguments @("--fail-after-astral-record-rebuild") -ExpectSuccess $false
    Assert-Equal 0 (@(Get-PendingBackupNames).Count) "no-data backup count"
    Assert-Equal $true (Test-Path -LiteralPath $offlineMarkerPath) "no-backup failure marker retention"
    $noBackupMarker = Get-Content -Raw -Encoding UTF8 -LiteralPath $offlineMarkerPath
    if ($noBackupMarker -notmatch "maintenance:(?<operationId>[0-9a-f]{32})") {
        throw "No-backup failure marker does not include a maintenance operation ID."
    }
    $noBackupOperationId = $Matches.operationId
    Invoke-Tool -AdditionalArguments @("--resume-operation", $noBackupOperationId) -ExpectSuccess $true
    Assert-Equal $false (Test-Path -LiteralPath $offlineMarkerPath) "no-backup recovery marker cleanup"
    Assert-Equal "MULTI_USER" ([string](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName "master" `
        -Sql "SELECT [user_access_desc] FROM [sys].[databases] WHERE [name] = @name;" `
        -Parameters @{ "@name" = $astralRecordName })) "no-backup recovery access mode"

    $releaseNoteId = [Guid]::NewGuid()
    $outboxId = [Guid]::NewGuid()
    Invoke-NonQuery `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql @"
CREATE TABLE [dbo].[integration_marker] ([value] int NOT NULL);
INSERT INTO [dbo].[integration_marker] ([value]) VALUES (1);

INSERT INTO [dbo].[release_note] (
    [release_note_id], [slug], [version], [title], [summary], [release_url], [source_path],
    [content_sha256], [published_at_utc], [is_published], [notify_discord], [created_at_utc], [updated_at_utc])
VALUES (
    @releaseNoteId, N'integration-release', N'1.0.0', N'Integration Release', N'preserved',
    N'https://example.invalid/releases/integration', N'integration.md', REPLICATE(N'A', 64),
    SYSUTCDATETIME(), 1, 1, SYSUTCDATETIME(), SYSUTCDATETIME());

INSERT INTO [dbo].[release_notification_outbox] (
    [outbox_id], [release_note_id], [channel], [status], [attempt_count], [next_attempt_at_utc],
    [lease_until_utc], [lease_token], [sent_at_utc], [discord_message_id], [last_error],
    [created_at_utc], [updated_at_utc])
VALUES (
    @outboxId, @releaseNoteId, N'DISCORD', 2, 3, SYSUTCDATETIME(), NULL, NULL,
    SYSUTCDATETIME(), N'integration-message-id', NULL, SYSUTCDATETIME(), SYSUTCDATETIME());
"@ `
        -Parameters @{ "@releaseNoteId" = $releaseNoteId; "@outboxId" = $outboxId }

    Write-Host "Testing backup, rebuild, restore, and cleanup..."
    Invoke-Tool -ExpectSuccess $true
    Assert-Equal $false (Test-Path -LiteralPath $offlineMarkerPath) "successful rebuild marker cleanup"

    Assert-Equal 1 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT COUNT(*) FROM [dbo].[release_note] WHERE [release_note_id] = @id;" `
        -Parameters @{ "@id" = $releaseNoteId })) "release_note row count"
    Assert-Equal 1 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT COUNT(*) FROM [dbo].[release_notification_outbox] WHERE [outbox_id] = @id AND [discord_message_id] = N'integration-message-id';" `
        -Parameters @{ "@id" = $outboxId })) "release_notification_outbox row count"
    Assert-Equal 0 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT CASE WHEN OBJECT_ID(N'[dbo].[integration_marker]', N'U') IS NULL THEN 0 ELSE 1 END;")) "non-preserved marker"
    Assert-Equal 4 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT COUNT(*) FROM [sys].[columns] WHERE [object_id] = OBJECT_ID(N'[dbo].[market_listing]') AND [name] IN (N'cancel_idempotency_key', N'cancel_request_hash', N'cancel_response_json', N'cancel_completed_at');")) "market cancel receipt columns"
    Assert-Equal 0 (@(Get-PendingBackupNames).Count) "completed backup cleanup"

    Write-Host "Testing retained backup and recovery mode..."
    Invoke-Tool -AdditionalArguments @("--fail-after-astral-record-rebuild") -ExpectSuccess $false
    Assert-Equal "OFFLINE" ([string](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName "master" `
        -Sql "SELECT [state_desc] FROM [sys].[databases] WHERE [name] = @name;" `
        -Parameters @{ "@name" = $astralRecordName })) "failed rebuild isolation"
    $pendingBackups = @(Get-PendingBackupNames)
    Assert-Equal 1 $pendingBackups.Count "retained backup count"
    Assert-Equal $true (Test-Path -LiteralPath $offlineMarkerPath) "failed rebuild marker retention"
    $retainedMarker = Get-Content -Raw -Encoding UTF8 -LiteralPath $offlineMarkerPath
    if ($retainedMarker -notmatch "maintenance:(?<operationId>[0-9a-f]{32})") {
        throw "Retained marker does not include a maintenance operation ID."
    }
    if (-not $pendingBackups[0].EndsWith($Matches.operationId, [StringComparison]::Ordinal)) {
        throw "Retained marker operation ID does not match the backup database name."
    }

    Invoke-Tool -AdditionalArguments @("--restore-backup", $pendingBackups[0]) -ExpectSuccess $true
    Assert-Equal $false (Test-Path -LiteralPath $offlineMarkerPath) "recovery marker cleanup"
    Assert-Equal 1 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT COUNT(*) FROM [dbo].[release_note] WHERE [release_note_id] = @id;" `
        -Parameters @{ "@id" = $releaseNoteId })) "recovered release_note row count"
    Assert-Equal 1 ([int](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName $astralRecordName `
        -Sql "SELECT COUNT(*) FROM [dbo].[release_notification_outbox] WHERE [outbox_id] = @id;" `
        -Parameters @{ "@id" = $outboxId })) "recovered outbox row count"
    Assert-Equal 0 (@(Get-PendingBackupNames).Count) "recovery backup cleanup"
    Assert-Equal "MULTI_USER" ([string](Invoke-Scalar `
        -ConnectionString ([string]$sourceSettings.ConnectionStrings.SqlServer) `
        -DatabaseName "master" `
        -Sql "SELECT [user_access_desc] FROM [sys].[databases] WHERE [name] = @name;" `
        -Parameters @{ "@name" = $astralRecordName })) "successful rebuild access mode"

    Write-Host "DB reset integration test passed."
}
finally {
    Remove-TestDatabases
    if (Test-Path -LiteralPath $offlineRoot) {
        Remove-Item -LiteralPath $offlineRoot -Recurse -Force
    }
}
