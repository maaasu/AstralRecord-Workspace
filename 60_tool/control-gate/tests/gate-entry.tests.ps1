#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$toolRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Import-Module (Join-Path $toolRoot 'control-gate/Control.Gate.psm1') -Force
$temp=Join-Path ([IO.Path]::GetTempPath()) ('control-gate-test-' + [Guid]::NewGuid().ToString('N'))
$priorProgramData=$env:ProgramData; $priorLocalAppData=$env:LOCALAPPDATA
function Assert-Throws([scriptblock]$Action) {
    $failed=$false
    try { & $Action | Out-Null } catch { $failed=$true }
    if (!$failed) { throw 'Expected gate rejection.' }
}
try {
    New-Item -ItemType Directory -Path $temp | Out-Null
    $env:ProgramData=$temp; $env:LOCALAPPDATA=$temp
    $environmentId=[Guid]::NewGuid().ToString('D'); $serverId=[Guid]::NewGuid().ToString('D')
    $hostId=[Guid]::NewGuid().ToString('D'); $rootId=[Guid]::NewGuid().ToString('D')
    $path=Join-Path $temp 'server\plugins'
    $mock=Join-Path $temp 'mock-cli.ps1'
    @'
$ErrorActionPreference='Stop'
$command=($args[0..1] -join ' ')
$log=$env:MOCK_GATE_LOG
Add-Content -Encoding UTF8 -LiteralPath $log -Value ($args -join '|')
if ($env:MOCK_GATE_FAIL -eq $command) { $global:LASTEXITCODE=1; return }
if ($command -eq 'gate acquire') {
    $requestFile=$args[[array]::IndexOf($args,'--request-file')+1]
    $leaseFile=$args[[array]::IndexOf($args,'--lease-file')+1]
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath $requestFile | ConvertFrom-Json
    Copy-Item -LiteralPath $requestFile -Destination (Join-Path (Split-Path $log) 'request.json') -Force
    @{workflowId=$request.workflowId;reservationId=[Guid]::NewGuid().ToString('D');resourceKeys=$request.resourceKeys;fences=@();status='Prepared';adapterId=$request.adapterId;runReference=$request.runReference} |
        ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $leaseFile
}
$global:LASTEXITCODE=0
'@ | Set-Content -Encoding UTF8 -LiteralPath $mock
    $env:MOCK_GATE_LOG=Join-Path $temp 'commands.log'
    $config=@{controlGate=@{mode='Managed';cliPath=$mock;serverUrl='https://control.example.test';environmentId=$environmentId;bindings=@{
        servers=@(@{runtimeServerId='dev';serverId=$serverId})
        paths=@(@{path=$path;accessRoot=$temp;hostId=$hostId;managedRootId=$rootId;relativePath='server/plugins'})
    }}}
    Assert-Throws { Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' }
    $markerRoot=Join-Path $temp 'AstralRecordControl'; New-Item -ItemType Directory -Path $markerRoot | Out-Null
    $files=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    $hashes=@{}
    foreach ($file in $files) { $hashes[$file]=(Get-FileHash -LiteralPath (Join-Path $toolRoot $file) -Algorithm SHA256).Hash }
    @{schemaVersion=1;toolRoot=$toolRoot;hashes=$hashes} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled')
    Assert-Throws { Get-ControlGateMode @{} }
    $bad=$config.Clone(); $bad.controlGate=$config.controlGate.Clone(); $bad.controlGate.bindings=$config.controlGate.bindings.Clone()
    $bad.controlGate.bindings.paths=@()
    Assert-Throws { Enter-ControlGate $bad '01' @('dev') @($path) 'Stopped' }
    $context=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped'
    if (!$context.Owner -or !(Test-Path -LiteralPath $context.LeaseFile)) { throw 'Gate lease was not acquired.' }
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'request.json') | ConvertFrom-Json
    if ($request.adapterId -cne '01' -or $request.mode -cne 'Maintenance' -or $request.serverConditions[0].requiredState -cne 'Stopped' -or
        @($request.resourceKeys).Count -ne 3 -or ('environment:'+$environmentId) -cnotin @($request.resourceKeys) -or
        ('server:'+$serverId) -cnotin @($request.resourceKeys)) { throw 'Reservation did not cover Environment, Server and directory.' }
    $lines=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG)
    if (@($lines | Where-Object { $_ -like 'gate|acquire|*' }).Count -ne 1) { throw 'Expected one central acquire.' }
    $child=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped'
    if ($child.Owner) { throw 'Child reacquired a reservation.' }
    Assert-ControlGateStep $child 'Deploy'
    Complete-ControlGate $child 'run-1'
    if (!(Test-Path -LiteralPath $context.LeaseFile)) { throw 'Child removed the parent lease.' }
    Complete-ControlGate $context 'run-1'
    $lines=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG)
    if (@($lines | Where-Object { $_ -like 'gate|release|*' }).Count -ne 1) { throw 'Expected one release.' }
    $running=Enter-ControlGate $config '03' @('dev') @($path) 'Running' -InitialStep 'Seed'
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'request.json') | ConvertFrom-Json
    if ($request.adapterId -cne '03' -or $request.serverConditions[0].requiredState -cne 'Running') { throw '03 running reload gate condition is wrong.' }
    Complete-ControlGate $running 'run-03'
    $migration=Enter-ControlGate $config '17' @('dev') @() 'Running' -InitialStep 'Preview'
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'request.json') | ConvertFrom-Json
    if ($request.adapterId -cne '17' -or @($request.resourceKeys).Count -ne 2 -or $request.serverConditions[0].requiredState -cne 'Running') { throw '17 gate must reserve Environment and running Server only.' }
    Complete-ControlGate $migration 'run-17'
    $env:MOCK_GATE_FAIL='gate acquire'
    Assert-Throws { Enter-ControlGate $config '17' @('dev') @() 'Running' }
    Write-Output 'Control gate fixture passed (all reservations and paths mocked).'
} finally {
    $env:ProgramData=$priorProgramData; $env:LOCALAPPDATA=$priorLocalAppData
    Remove-Item Env:MOCK_GATE_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_GATE_FAIL -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE',$null,'Process')
    $resolved=[IO.Path]::GetFullPath($temp)
    $expected=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'control-gate-test-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
