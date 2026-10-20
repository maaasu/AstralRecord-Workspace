#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot '..\Enable-ControlGameOperations.ps1'
. $script -CliPath 'C:\test-cli.exe' -ReportDirectory $env:TEMP
Import-Module (Join-Path $PSScriptRoot '..\Control.Deployment.psm1') -Force

function Assert-Throws([scriptblock]$Body, [string]$Expected) {
    try { & $Body; throw "Expected rejection: $Expected" }
    catch { if ($_.Exception.Message -ne $Expected) { throw } }
}

$root = Join-Path $env:TEMP ('control-enable-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
try {
    $id = [Guid]::NewGuid().ToString('D')
    $snapshot = @{ runnerEpoch=[Guid]::NewGuid().ToString('D'); holds=@(); servers=@(@{ serverId=$id; processState='Stopped'; currentSessionId=$null }); appliedInventoryRevision=3 }
    if ((Assert-ControlGameStoppedSnapshot $snapshot) -ne 1) { throw 'Expected one registered server.' }
    $empty = $snapshot.Clone(); $empty.servers = @()
    Assert-Throws { Assert-ControlGameStoppedSnapshot $empty } 'EmptyRunnerInventory'
    $held = $snapshot.Clone(); $held.holds = @(@{ reservationId='x' })
    Assert-Throws { Assert-ControlGameStoppedSnapshot $held } 'Runner update requires no active holds.'
    $running = $snapshot.Clone(); $running.servers = @(@{serverId=$id;processState='Running';currentSessionId='x'})
    Assert-Throws { Assert-ControlGameStoppedSnapshot $running } 'Runner update requires every managed server to be fully stopped; Unmanaged is refused.'

    $safeConfig = '{"autoStart":false,"restartOnCrash":false}'
    $rows = @([pscustomobject]@{Id=$id;Config=$safeConfig;DesiredState='Stopped';NextAutoStartUtc=$null})
    Assert-ControlGamePersistedRows $rows $snapshot
    Assert-Throws { Assert-ControlGamePersistedRows @() $snapshot } 'PersistedInventoryMismatch'
    Assert-Throws { Assert-ControlGamePersistedRows @([pscustomobject]@{Id=$id;Config=$safeConfig;DesiredState='AutoStartPending';NextAutoStartUtc=$null}) $snapshot } 'PendingAutomaticStart'
    Assert-Throws { Assert-ControlGamePersistedRows @([pscustomobject]@{Id=$id;Config='{"autoStart":true,"restartOnCrash":false}';DesiredState='Stopped';NextAutoStartUtc=$null}) $snapshot } 'AutomaticStartConfigured'
    Assert-Throws { Assert-ControlGamePersistedRows @([pscustomobject]@{Id=$id;Config='{"autoStart":false,"restartOnCrash":true}';DesiredState='Stopped';NextAutoStartUtc=$null}) $snapshot } 'AutomaticStartConfigured'

    if ($env:CONTROL_RUNNER_TEST_PACKAGE) {
        $package = $env:CONTROL_RUNNER_TEST_PACKAGE
        $ledgerData = Join-Path $root 'ledger'
        New-Item -ItemType Directory -Path $ledgerData | Out-Null
        Add-Type -Path (Join-Path $package 'Microsoft.Data.Sqlite.dll')
        $connection = [Microsoft.Data.Sqlite.SqliteConnection]::new('Data Source=' + (Join-Path $ledgerData 'runner.db') + ';Pooling=False')
        try {
            $connection.Open()
            $command = $connection.CreateCommand()
            try {
                $command.CommandText = 'CREATE TABLE Servers(id TEXT, config TEXT, desiredState TEXT, nextAutoStartUtc TEXT)'
                $command.ExecuteNonQuery() | Out-Null
                $command.CommandText = 'INSERT INTO Servers VALUES ($id, $config, $state, NULL)'
                $command.Parameters.AddWithValue('$id', $id) | Out-Null
                $command.Parameters.AddWithValue('$config', $safeConfig) | Out-Null
                $command.Parameters.AddWithValue('$state', 'Stopped') | Out-Null
                $command.ExecuteNonQuery() | Out-Null
            } finally { $command.Dispose() }
        } finally { $connection.Dispose() }
        Assert-ControlGamePersistedState $package $ledgerData $snapshot
    }

    $toolRoot = Join-Path $root 'tools'
    New-Item -ItemType Directory -Path $toolRoot | Out-Null
    $files = @('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    $hashes = @{}
    foreach ($file in $files) {
        $path = Join-Path $toolRoot $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        'Write-Output test' | Set-Content -Encoding UTF8 -LiteralPath $path
        $hashes[$file] = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    $marker = Join-Path $root 'managed-operations.enabled'
    if ((Get-ControlGameMarkerAction $marker $toolRoot) -ne 'Create') { throw 'Marker create state expected.' }
    @{schemaVersion=1;toolRoot=$toolRoot;hashes=$hashes} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 -LiteralPath $marker
    if ((Get-ControlGameMarkerAction $marker $toolRoot) -ne 'Reuse') { throw 'Marker reuse state expected.' }
    'Write-Output changed' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $toolRoot $files[0])
    if ((Get-ControlGameMarkerAction $marker $toolRoot) -ne 'Reuse') { throw 'An ordinary tool update must reuse the installation.' }
    'function Broken {' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $toolRoot $files[0])
    Assert-Throws { Get-ControlGameMarkerAction $marker $toolRoot | Out-Null } 'ManagedToolSyntaxError'
    'Write-Output repaired' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $toolRoot $files[0])
    Assert-Throws { Get-ControlGameMarkerAction $marker (Join-Path $root 'other') | Out-Null } 'MarkerRootMismatch'

    $data = Join-Path $root 'runner-data'
    New-Item -ItemType Directory -Path $data | Out-Null
    $fixtureSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $directoryAcl=[Security.AccessControl.DirectorySecurity]::new()
    $directoryAcl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544',$fixtureSid) | Select-Object -Unique) {
        $directoryAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    }
    Set-Acl -LiteralPath $data -AclObject $directoryAcl
    $configPath = Join-Path $data 'config.json'
    '{"hostId":"test","enableProcessControl":false,"enableFileControl":false,"pipeName":"fixture"}' | Set-Content -Encoding UTF8 -LiteralPath $configPath
    if ((Get-Acl -LiteralPath $configPath).AreAccessRulesProtected) { throw 'Fixture must reproduce inherited configuration ACL.' }
    $inheritedSddl=(Get-Acl -LiteralPath $configPath).Sddl
    $backup=Set-ControlGameRunnerFlag $data $fixtureSid
    if ((Get-Acl -LiteralPath $configPath).Sddl -cne $inheritedSddl) { throw 'Inherited configuration ACL changed.' }
    Restore-ControlGameRunnerFlag $data $backup
    $acl = Get-Acl -LiteralPath $configPath
    $acl.SetAccessRuleProtection($true, $true)
    Set-Acl -LiteralPath $configPath -AclObject $acl
    $backup = Set-ControlGameRunnerFlag $data $fixtureSid
    $changed = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json -AsHashtable
    if ($changed.enableProcessControl -ne $true -or $changed.pipeName -ne 'fixture' -or !(Get-Acl -LiteralPath $configPath).AreAccessRulesProtected) { throw 'Runner flag update failed.' }
    Restore-ControlGameRunnerFlag $data $backup
    $restored = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json -AsHashtable
    if ($restored.enableProcessControl -ne $false -or $restored.pipeName -ne 'fixture') { throw 'Runner flag restore failed.' }

    # Model pending SCM states without querying or changing an actual service.
    function Get-Service { param([string]$Name) return $script:fakeService }
    try {
        foreach ($initial in @('StopPending','StartPending','Running','Stopped')) {
            $script:fakeService=[pscustomobject]@{Status=$initial;Waits=0}
            $script:fakeService | Add-Member ScriptMethod WaitForStatus { param($target,$timeout) $this.Status=$target; $this.Waits++ }
            $script:fakeService | Add-Member ScriptMethod Refresh { }
            $settled=Wait-ControlGameServiceSettled 'FixtureOnly'
            $expected=if ($initial -eq 'StopPending') {'Stopped'} elseif ($initial -eq 'StartPending') {'Running'} else {$initial}
            if ($settled -cne $expected) { throw 'Pending service state was not settled.' }
        }
    } finally { Remove-Item Function:\Get-Service }

    $report = @{schemaVersion=1;computerName='TESTHOST';hostId=$id;success=$true;processControlEnabled=$true;
        appliedInventoryRevision=3;registeredServerCount=1;markerToolRoot=$toolRoot;runnerStatus='Running';agentStatus='Running';
        collectedUtc='2026-10-20T00:00:00Z';stage='Complete';failureType=$null}
    Write-ControlGameReport $root $report
    $fixed = Join-Path $root 'TESTHOST-operations-enabled.json'
    if (!(Test-Path -LiteralPath $fixed) -or @((Get-ChildItem -LiteralPath $root -Filter 'TESTHOST-operations-enabled-*.json')).Count -ne 1) { throw 'Report files missing.' }
    $report.success=$false; $report.failureType='UnsafeRunnerSnapshot'; $report.stage='Preflight'
    Write-ControlGameReport $root $report
    $latest=Get-Content -Raw -Encoding UTF8 -LiteralPath $fixed | ConvertFrom-Json -AsHashtable
    if ($latest.success -ne $false -or $latest.failureType -ne 'UnsafeRunnerSnapshot' -or @((Get-ChildItem -LiteralPath $root -Filter 'TESTHOST-operations-enabled-*.json')).Count -ne 2) { throw 'Failure report did not replace latest success.' }
    Write-Output 'PASS: snapshot, persisted automation, marker, config backup/restore, and atomic report fixtures.'
} finally {
    $temporaryParent = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\','/')
    $target = [IO.Path]::GetFullPath($root)
    if ([IO.Path]::GetDirectoryName($target) -ine $temporaryParent -or [IO.Path]::GetFileName($target) -notlike 'control-enable-test-*') { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $target -Recurse -Force
}
