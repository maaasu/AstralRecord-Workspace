#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ToolRoot = (Join-Path $PSScriptRoot '..'),
    [Parameter(Mandatory)][string]$CliPath,
    [string]$RunnerDataRoot = (Join-Path $env:ProgramData 'AstralRecordControl\Runner'),
    [string]$AgentDataRoot = (Join-Path $env:ProgramData 'AstralRecordControl\Agent'),
    [Parameter(Mandatory)][string]$ReportDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-ControlGameStoppedSnapshot($Snapshot) {
    Assert-ControlRunnerStoppedSnapshot $Snapshot
    if (@($Snapshot.servers).Count -lt 1) { throw 'EmptyRunnerInventory' }
    return @($Snapshot.servers).Count
}

function Get-ControlGameSnapshot([string]$Cli, [string]$PipeName) {
    if (![IO.Path]::IsPathFullyQualified($Cli) -or !(Test-Path -LiteralPath $Cli -PathType Leaf)) { throw 'InvalidCli' }
    Get-ControlAbsolutePath $Cli | Out-Null
    if ($PipeName -notmatch '^[A-Za-z0-9._-]+$') { throw 'InvalidPipe' }
    $lines = & $Cli runner check-update --pipe-name $PipeName
    if ($LASTEXITCODE -ne 0) { throw 'UnsafeRunnerSnapshot' }
    try { $snapshot = ($lines -join "`n") | ConvertFrom-Json -AsHashtable } catch { throw 'UnsafeRunnerSnapshot' }
    Assert-ControlGameStoppedSnapshot $snapshot | Out-Null
    return $snapshot
}

function Assert-ControlGameNoExternalJava {
    $processes = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")
    if ($processes.Count -ne 0) { throw 'ExternalJavaRunning' }
}

function Assert-ControlGameService([string]$Component, [string]$DataRoot) {
    $name = "AstralRecordControl.$Component"
    $service = Get-CimInstance Win32_Service -Filter "Name='$name'"
    if (!$service -or $service.StartName -ne "NT SERVICE\$name") { throw 'UnexpectedServiceIdentity' }
    $match = [regex]::Match([string]$service.PathName, ('^"([^\"]+' + [regex]::Escape($name) + '\.exe)"\s+--data-root\s+"([^\"]+)"$'))
    if (!$match.Success) { throw 'UnexpectedServicePath' }
    $package = Get-ControlAbsolutePath ([IO.Path]::GetDirectoryName($match.Groups[1].Value))
    if ((Get-ControlAbsolutePath $match.Groups[2].Value) -ine (Get-ControlAbsolutePath $DataRoot)) { throw 'UnexpectedDataRoot' }
    Assert-ControlPackage $package $Component | Out-Null
    $runtime = Get-Service -Name $name
    if ($runtime.Status -ne 'Running') { throw 'ServiceNotRunning' }
    return [pscustomobject]@{ Name=$name; Package=$package; Status=$runtime.Status; StartMode=$service.StartMode }
}

function Assert-ControlGamePersistedRows($Rows, $Snapshot) {
    $ids = @($Snapshot.servers | ForEach-Object { [string]$_.serverId })
    if ($Rows.Count -ne $ids.Count) { throw 'PersistedInventoryMismatch' }
    foreach ($row in $Rows) {
        if ($ids -notcontains [string]$row.Id -or $row.DesiredState -cne 'Stopped' -or $row.NextAutoStartUtc) { throw 'PendingAutomaticStart' }
        try { $config = [string]$row.Config | ConvertFrom-Json -AsHashtable } catch { throw 'InvalidPersistedConfig' }
        if ($config.autoStart -ne $false -or $config.restartOnCrash -ne $false) { throw 'AutomaticStartConfigured' }
    }
}

function Assert-ControlGamePersistedState([string]$RunnerPackage, [string]$DataRoot, $Snapshot) {
    $assembly = Join-Path $RunnerPackage 'Microsoft.Data.Sqlite.dll'
    $database = Join-Path $DataRoot 'runner.db'
    if (!(Test-Path -LiteralPath $assembly -PathType Leaf) -or !(Test-Path -LiteralPath $database -PathType Leaf)) { throw 'RunnerLedgerUnavailable' }
    try {
        Add-Type -Path $assembly -ErrorAction Stop
        $builder = [Microsoft.Data.Sqlite.SqliteConnectionStringBuilder]::new()
        $builder.DataSource = $database
        $builder.Mode = [Microsoft.Data.Sqlite.SqliteOpenMode]::ReadOnly
        $builder.Cache = [Microsoft.Data.Sqlite.SqliteCacheMode]::Private
        $builder.Pooling = $false
        $connection = [Microsoft.Data.Sqlite.SqliteConnection]::new($builder.ToString())
        try {
            $connection.Open()
            $command = $connection.CreateCommand()
            $command.CommandText = 'SELECT id, config, desiredState, nextAutoStartUtc FROM Servers'
            $rows = [Collections.Generic.List[object]]::new()
            $reader = $command.ExecuteReader()
            try {
                while ($reader.Read()) {
                    $rows.Add([pscustomobject]@{
                        Id=$reader.GetString(0); Config=$reader.GetString(1); DesiredState=$reader.GetString(2)
                        NextAutoStartUtc=if ($reader.IsDBNull(3)) { $null } else { $reader.GetString(3) }
                    })
                }
            } finally { $reader.Dispose(); $command.Dispose() }
            Assert-ControlGamePersistedRows @($rows.ToArray()) $Snapshot
        } finally { $connection.Dispose() }
    } catch {
        if ($_.Exception.Message -in @('PersistedInventoryMismatch','PendingAutomaticStart','InvalidPersistedConfig','AutomaticStartConfigured')) { throw }
        throw 'RunnerLedgerUnavailable'
    }
}

function Get-ControlGameMarkerAction([string]$MarkerPath, [string]$Root) {
    if (!(Test-Path -LiteralPath $MarkerPath)) { return 'Create' }
    if (!(Test-Path -LiteralPath $MarkerPath -PathType Leaf) -or ((Get-Item -LiteralPath $MarkerPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'InvalidMarker' }
    try { $marker = Get-Content -Raw -Encoding UTF8 -LiteralPath $MarkerPath | ConvertFrom-Json -AsHashtable } catch { throw 'InvalidMarker' }
    if ($marker.schemaVersion -ne 1 -or !$marker.toolRoot -or (Get-ControlAbsolutePath ([string]$marker.toolRoot)) -ine $Root) { throw 'MarkerRootMismatch' }
    $files = @('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    foreach ($file in $files) {
        $path = Join-Path $Root $file
        if (!(Test-Path -LiteralPath $path -PathType Leaf) -or ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'ManagedToolMissing' }
        if (!$marker.hashes.ContainsKey($file) -or $marker.hashes[$file] -cne (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash) { return 'Renew' }
    }
    if ($marker.hashes.Count -ne $files.Count) { return 'Renew' }
    return 'Reuse'
}

function Set-ControlGameRunnerFlag([string]$DataRoot) {
    $path = Join-Path $DataRoot 'config.json'
    $raw = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    $config = $raw | ConvertFrom-Json -AsHashtable
    if ($config.ContainsKey('enableProcessControl') -and $config.enableProcessControl -eq $true) { return $null }
    $backup = Join-Path $DataRoot ('config.before-process-control.' + [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '.' + [Guid]::NewGuid().ToString('N') + '.backup')
    $acl = Get-Acl -LiteralPath $path
    if (!$acl.AreAccessRulesProtected) { throw 'UnprotectedConfigAcl' }
    [IO.File]::Copy($path, $backup, $false)
    Set-Acl -LiteralPath $backup -AclObject $acl
    $config['enableProcessControl'] = $true
    $temporary = Join-Path $DataRoot ('.config.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, ($config | ConvertTo-Json -Depth 64), [Text.UTF8Encoding]::new($false))
        Set-Acl -LiteralPath $temporary -AclObject $acl
        [IO.File]::Move($temporary, $path, $true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
    return $backup
}

function Restore-ControlGameRunnerFlag([string]$DataRoot, [string]$Backup) {
    if (!$Backup) { return }
    $path = Join-Path $DataRoot 'config.json'
    $temporary = Join-Path $DataRoot ('.config-restore.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::Copy($Backup, $temporary, $false)
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Backup)
        [IO.File]::Move($temporary, $path, $true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Write-ControlGameReport([string]$Directory, [hashtable]$Report) {
    if (!(Test-Path -LiteralPath $Directory -PathType Container)) { throw 'ReportDirectoryMissing' }
    $computer = [string]$Report.computerName
    if ($computer -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'InvalidComputerName' }
    $json = $Report | ConvertTo-Json -Depth 5
    $stamp = [DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N')
    foreach ($name in @("$computer-operations-enabled-$stamp.json", "$computer-operations-enabled.json")) {
        $destination = Join-Path $Directory $name
        $temporary = Join-Path $Directory ('.' + [Guid]::NewGuid().ToString('N') + '.tmp')
        try {
            [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
            [IO.File]::Move($temporary, $destination, $true)
        } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
    }
}

function Invoke-ControlGameOperations([string]$ToolRoot, [string]$CliPath, [string]$RunnerDataRoot, [string]$AgentDataRoot, [string]$ReportDirectory) {
    $report = @{ schemaVersion=1; computerName=$env:COMPUTERNAME; hostId=$null; success=$false;
        processControlEnabled=$false; appliedInventoryRevision=$null; registeredServerCount=0; markerToolRoot=$null;
        runnerStatus='Unknown'; agentStatus='Unknown'; collectedUtc=$null; stage='Preflight'; failureType=$null }
    $runnerBackup = $null
    $agentWasStopped = $false
    $runnerWasStopped = $false
    $startupModesChanged = $false
    $runnerStartMode = $null
    $agentStartMode = $null
    $pipeName = $null
    $marker = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    try {
        if (!$IsWindows) { throw 'WindowsRequired' }
        Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        if (![Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'AdministratorRequired' }
        $root = Get-ControlAbsolutePath $ToolRoot
        $runnerData = Get-ControlAbsolutePath $RunnerDataRoot
        $agentData = Get-ControlAbsolutePath $AgentDataRoot
        $reports = Get-ControlAbsolutePath $ReportDirectory
        if (!(Test-Path -LiteralPath $reports -PathType Container)) { throw 'ReportDirectoryMissing' }
        Test-ControlConfiguration $runnerData Runner
        Test-ControlConfiguration $agentData Agent
        $runner = Assert-ControlGameService Runner $runnerData
        $agent = Assert-ControlGameService Agent $agentData
        $runnerStartMode = $runner.StartMode
        $agentStartMode = $agent.StartMode
        $runnerConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $runnerData 'config.json') | ConvertFrom-Json -AsHashtable
        $agentConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $agentData 'config.json') | ConvertFrom-Json -AsHashtable
        if ($runnerConfig.hostId -ne $agentConfig.hostId -or
            ($runnerConfig.ContainsKey('enableFileControl') -and $runnerConfig.enableFileControl -eq $true) -or
            ($agentConfig.ContainsKey('enableFileControl') -and $agentConfig.enableFileControl -eq $true) -or
            ($agentConfig.ContainsKey('enableSchedules') -and $agentConfig.enableSchedules -eq $true)) { throw 'UnsupportedHostConfiguration' }
        $agentSid = [Security.Principal.NTAccount]::new('NT SERVICE\AstralRecordControl.Agent').Translate([Security.Principal.SecurityIdentifier]).Value
        if ($runnerConfig.agentSid -cne $agentSid) { throw 'AgentSidMismatch' }
        $report.hostId = [string]$runnerConfig.hostId
        $pipeName = if ($runnerConfig.ContainsKey('pipeName') -and $runnerConfig.pipeName) { [string]$runnerConfig.pipeName } else { 'AstralRecordControl.Runner.v1' }
        if ($agentConfig.ContainsKey('runnerPipeName') -and $agentConfig.runnerPipeName -and $agentConfig.runnerPipeName -cne $pipeName) { throw 'PipeMismatch' }
        $report.markerToolRoot = $root
        $report.runnerStatus = 'Running'; $report.agentStatus = 'Running'
        Assert-ControlGameNoExternalJava
        $snapshot = Get-ControlGameSnapshot $CliPath $pipeName
        Assert-ControlGamePersistedState $runner.Package $runnerData $snapshot
        $report.appliedInventoryRevision = [long]$snapshot.appliedInventoryRevision
        $report.registeredServerCount = @($snapshot.servers).Count
        $markerAction = Get-ControlGameMarkerAction $marker $root
        $report.stage = 'AgentStopped'
        Stop-Service -Name $agent.Name
        $agentWasStopped = $true
        (Get-Service -Name $agent.Name).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        $report.agentStatus = 'Stopped'
        Assert-ControlGameNoExternalJava
        $snapshot = Get-ControlGameSnapshot $CliPath $pipeName
        Assert-ControlGamePersistedState $runner.Package $runnerData $snapshot
        if (@($snapshot.servers).Count -ne $report.registeredServerCount) { throw 'InventoryChanged' }
        $report.stage = 'RunnerConfigured'
        $runnerBackup = Set-ControlGameRunnerFlag $runnerData
        Test-ControlConfiguration $runnerData Runner
        if ($markerAction -eq 'Create') {
            & (Join-Path $PSScriptRoot 'Enable-ManagedOperations.ps1') -ToolRoot $root -RunnerDataRoot $runnerData | Out-Null
        } elseif ($markerAction -eq 'Renew') {
            & (Join-Path $PSScriptRoot 'Enable-ManagedOperations.ps1') -ToolRoot $root -RunnerDataRoot $runnerData -Renew -CliPath $CliPath | Out-Null
        }
        if ((Get-ControlGameMarkerAction $marker $root) -ne 'Reuse') { throw 'MarkerAttestationFailed' }
        $report.stage = 'RunnerRestart'
        Assert-ControlGameNoExternalJava
        $snapshot = Get-ControlGameSnapshot $CliPath $pipeName
        Assert-ControlGamePersistedState $runner.Package $runnerData $snapshot
        Stop-Service -Name $runner.Name
        (Get-Service -Name $runner.Name).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        $runnerWasStopped = $true
        Start-Service -Name $runner.Name
        (Get-Service -Name $runner.Name).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        $runnerWasStopped = $false
        $report.runnerStatus = 'Running'
        $snapshot = Get-ControlGameSnapshot $CliPath $pipeName
        Assert-ControlGamePersistedState $runner.Package $runnerData $snapshot
        Start-Service -Name $agent.Name
        (Get-Service -Name $agent.Name).WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
        $agentWasStopped = $false
        $report.agentStatus = 'Running'
        $report.stage = 'StartupMode'
        Set-Service -Name $runner.Name -StartupType Automatic
        $startupModesChanged = $true
        Set-Service -Name $agent.Name -StartupType Automatic
        $report.processControlEnabled = $true
        $report.appliedInventoryRevision = [long]$snapshot.appliedInventoryRevision
        $report.success = $true
        $report.stage = 'Complete'
    } catch {
        $known = @('WindowsRequired','AdministratorRequired','ReportDirectoryMissing','InvalidCli','InvalidPipe','UnsafeRunnerSnapshot','EmptyRunnerInventory',
            'ExternalJavaRunning','UnexpectedServiceIdentity','UnexpectedServicePath','UnexpectedDataRoot','ServiceNotRunning','PersistedInventoryMismatch',
            'PendingAutomaticStart','InvalidPersistedConfig','AutomaticStartConfigured','RunnerLedgerUnavailable','InvalidMarker','MarkerRootMismatch',
            'ManagedToolMissing','UnprotectedConfigAcl','UnsupportedHostConfiguration','AgentSidMismatch','PipeMismatch','InventoryChanged','MarkerAttestationFailed')
        $report.failureType = if ($_.Exception.Message -in $known) { $_.Exception.Message } else { 'OperationFailed' }
        if ($startupModesChanged) {
            try {
                $runnerStartup = if ($runnerStartMode -eq 'Auto') { 'Automatic' } else { [string]$runnerStartMode }
                $agentStartup = if ($agentStartMode -eq 'Auto') { 'Automatic' } else { [string]$agentStartMode }
                Set-Service -Name 'AstralRecordControl.Runner' -StartupType $runnerStartup
                Set-Service -Name 'AstralRecordControl.Agent' -StartupType $agentStartup
            } catch { $report.failureType = 'RecoveryIncomplete' }
        }
        # Never stop a running Runner during recovery. Restore only while it is already stopped or before a restart.
        if ($runnerBackup) {
            try {
                $currentRunner = (Get-Service -Name 'AstralRecordControl.Runner').Status
                if ($currentRunner -eq 'Stopped') {
                    Assert-ControlGameNoExternalJava
                    Restore-ControlGameRunnerFlag $RunnerDataRoot $runnerBackup
                    Start-Service -Name 'AstralRecordControl.Runner'
                    (Get-Service -Name 'AstralRecordControl.Runner').WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
                    $runnerWasStopped = $false
                } elseif ($currentRunner -eq 'Running' -and $report.stage -ne 'RunnerRestart' -and $report.stage -ne 'StartupMode') {
                    $safe = Get-ControlGameSnapshot $CliPath $pipeName
                    Assert-ControlGamePersistedState $runner.Package $RunnerDataRoot $safe
                    Restore-ControlGameRunnerFlag $RunnerDataRoot $runnerBackup
                }
            } catch { $report.failureType = 'RecoveryIncomplete' }
        }
        if ($agentWasStopped) {
            try {
                if ((Get-Service -Name 'AstralRecordControl.Runner').Status -ne 'Running') { throw 'RunnerUnavailable' }
                Start-Service -Name 'AstralRecordControl.Agent'
                (Get-Service -Name 'AstralRecordControl.Agent').WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
                $agentWasStopped = $false
            } catch { $report.failureType = 'RecoveryIncomplete' }
        }
    } finally {
        foreach ($item in @(@('Runner','runnerStatus'),@('Agent','agentStatus'))) {
            try { $report[$item[1]] = [string](Get-Service -Name "AstralRecordControl.$($item[0])").Status } catch { $report[$item[1]] = 'Unknown' }
        }
        try {
            $actualConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RunnerDataRoot 'config.json') | ConvertFrom-Json -AsHashtable
            $report.processControlEnabled = $actualConfig.enableProcessControl -eq $true
        } catch { $report.processControlEnabled = $false }
        $report.collectedUtc = [DateTimeOffset]::UtcNow.ToString('O')
        try { Write-ControlGameReport $ReportDirectory $report }
        catch {
            $report.success = $false; $report.failureType = 'ReportWriteFailed'
            try {
                $fixed = Join-Path $ReportDirectory ([string]$report.computerName + '-operations-enabled.json')
                $temporary = Join-Path $ReportDirectory ('.' + [Guid]::NewGuid().ToString('N') + '.tmp')
                [IO.File]::WriteAllText($temporary, ($report | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
                [IO.File]::Move($temporary, $fixed, $true)
            } catch { }
        }
    }
    if (!$report.success) { throw 'Control game operations were not enabled; inspect the non-secret report.' }
    Write-Output 'Control game operations enabled. Legacy tool entrances now require Managed bindings.'
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-ControlGameOperations $ToolRoot $CliPath $RunnerDataRoot $AgentDataRoot $ReportDirectory
}
