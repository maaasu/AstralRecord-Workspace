#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\Control.Deployment.psm1') -Force
$temp = Join-Path ([IO.Path]::GetTempPath()) ('control-deploy-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
function Assert-Throws([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected preflight rejection.' }
}
try {
    foreach ($script in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..') -Recurse -File | Where-Object { $_.Extension -in '.ps1','.psm1' }) {
        $parseErrors = $null
        [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$parseErrors) | Out-Null
        if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
    }
    Assert-Throws { Get-ControlAbsolutePath '\\server\share' }
    Assert-Throws { Get-ControlAbsolutePath '//server/share/control-data' }
    Assert-Throws { Get-ControlAbsolutePath 'relative\path' }
    Assert-Throws { Get-ControlAbsolutePath 'C:\' }
    Assert-Throws { Assert-ControlSeparatePaths (Join-Path $temp 'data') (Join-Path $temp 'data\binary') }
    $package = Join-Path $temp 'package'
    New-Item -ItemType Directory -Path $package | Out-Null
    foreach ($name in 'AstralRecordControl.Web.exe','AstralRecordControl.Web.dll','AstralRecordControl.Web.runtimeconfig.json') { [IO.File]::WriteAllText((Join-Path $package $name), '{}') }
    @{component='Web';phase='P1';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    Assert-ControlPackage $package Web | Out-Null
    @{component='Web';phase='P2';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    Assert-ControlPackage $package Web | Out-Null
    @{component='Web';phase='P3';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    Assert-ControlPackage $package Web | Out-Null
    @{component='Web';phase='P0';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    Assert-Throws { Assert-ControlPackage $package Web }
    @{component='Web';phase='P1';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    [IO.File]::WriteAllText((Join-Path $package 'control.db-wal'), 'sensitive-fixture')
    Assert-Throws { Assert-ControlPackage $package Web }
    Remove-Item -LiteralPath (Join-Path $package 'control.db-wal')
    $web = Join-Path $temp 'Web'
    New-Item -ItemType Directory -Path $web | Out-Null
    $configuration = @{Control=@{PublicOrigin='https://control.example.test'};Kestrel=@{Endpoints=@{Https=@{Url='https://0.0.0.0:5443';Certificate=@{Subject='control.example.test';Store='My';Location='LocalMachine'}}}}}
    $configuration | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $web 'config.json') -Encoding utf8
    Assert-Throws { Test-ControlConfiguration $web Web }
    Assert-ControlRunnerStoppedSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@();servers=@(@{serverId=[Guid]::NewGuid().ToString();processState='Stopped';currentSessionId=$null})}
    Assert-Throws { Assert-ControlRunnerStoppedSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@(@{reservationId=[Guid]::NewGuid().ToString()});servers=@()} }
    Assert-Throws { Assert-ControlRunnerStoppedSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@();servers=@(@{serverId=[Guid]::NewGuid().ToString();processState='Unmanaged';currentSessionId=$null})} }
    Assert-Throws { Assert-ControlRunnerStoppedSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@();servers=@(@{serverId=[Guid]::NewGuid().ToString();processState='Running';currentSessionId=[Guid]::NewGuid().ToString()})} }
    $mockCli=Join-Path $temp 'control-cli.cmd'
    function Set-MockRunnerSnapshot($snapshot) {
        $json=$snapshot | ConvertTo-Json -Depth 8 -Compress
        [IO.File]::WriteAllText($mockCli, "@echo off`r`necho $json`r`nexit /b 0`r`n")
    }
    Set-MockRunnerSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@();servers=@(@{serverId=[Guid]::NewGuid().ToString();processState='Stopped';currentSessionId=$null})}
    Assert-ControlRunnerUpdateReady $mockCli 'AstralRecordControl.Runner.v1'
    Assert-Throws { Assert-ControlRunnerUpdateReady $mockCli 'invalid\\pipe' }
    Set-MockRunnerSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@(@{reservationId=[Guid]::NewGuid().ToString()});servers=@()}
    Assert-Throws { Assert-ControlRunnerUpdateReady $mockCli 'AstralRecordControl.Runner.v1' }
    Set-MockRunnerSnapshot @{runnerEpoch=[Guid]::NewGuid().ToString();holds=@();servers=@(@{serverId=[Guid]::NewGuid().ToString();processState='Unmanaged';currentSessionId=$null})}
    Assert-Throws { Assert-ControlRunnerUpdateReady $mockCli 'AstralRecordControl.Runner.v1' }
    $toolRoot=Join-Path $temp 'tools'
    $entries=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    foreach ($entry in $entries) {
        $path=Join-Path $toolRoot $entry
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($path)) -Force | Out-Null
        [IO.File]::WriteAllText($path, '# test entry')
    }
    $savedProgramData=$env:ProgramData
    try {
        $env:ProgramData=$temp
        Assert-Throws { Enable-ControlManagedOperations $toolRoot -Renew -CliPath $mockCli -PipeName 'AstralRecordControl.Runner.v1' }
        $markerRoot=Join-Path $temp 'AstralRecordControl'
        New-Item -ItemType Directory -Path $markerRoot | Out-Null
        @{schemaVersion=1;toolRoot=(Join-Path $temp 'different-tools');hashes=@{}} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled') -Encoding utf8
        Assert-Throws { Enable-ControlManagedOperations $toolRoot -Renew -CliPath $mockCli -PipeName 'AstralRecordControl.Runner.v1' }
    } finally { $env:ProgramData=$savedProgramData }
    $agent = Join-Path $temp 'Agent'
    New-Item -ItemType Directory -Path (Join-Path $agent 'credentials') | Out-Null
    [IO.File]::WriteAllText((Join-Path $agent 'credentials\token.bin'), 'encrypted-fixture')
    foreach ($origin in 'https://control.example.test/path','https://user:secret@control.example.test','https://control.example.test?token=secret','https://control.example.test/#fragment') {
        @{hostId=[Guid]::NewGuid().ToString();serverUrl=$origin} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $agent 'config.json') -Encoding utf8
        Assert-Throws { Test-ControlConfiguration $agent Agent }
    }
    $runner = Join-Path $temp 'Runner'; New-Item -ItemType Directory -Path $runner | Out-Null
    @{hostId=[Guid]::NewGuid().ToString();agentSid='S-1-5-bad'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runner 'config.json') -Encoding utf8
    Assert-Throws { Test-ControlConfiguration $runner Runner }
    @{hostId=[Guid]::NewGuid().ToString();agentSid='S-1-5-80-1-2-3-4-5'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runner 'config.json') -Encoding utf8
    Test-ControlConfiguration $runner Runner
    [IO.File]::WriteAllText((Join-Path $web 'control.db'), '')
    Test-ControlConfiguration $web Web
    $configuration.Control.PublicOrigin = 'http://control.example.test'
    $configuration | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $web 'config.json') -Encoding utf8
    Assert-Throws { Test-ControlConfiguration $web Web }
    Write-Output 'Deployment preflight fixtures passed (no service registration or deployment).'
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'control-deploy-test-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
