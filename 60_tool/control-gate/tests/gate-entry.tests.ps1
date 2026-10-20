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
$leaseFile=$args[[array]::IndexOf($args,'--lease-file')+1]
if ($command -eq 'gate acquire') {
    $requestFile=$args[[array]::IndexOf($args,'--request-file')+1]
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath $requestFile | ConvertFrom-Json
    Copy-Item -LiteralPath $requestFile -Destination (Join-Path (Split-Path $log) 'request.json') -Force
    $lease=@{workflowId=$request.workflowId;reservationId=[Guid]::NewGuid().ToString('D');resourceKeys=$request.resourceKeys;fences=@(@{hostId=$env:MOCK_GATE_HOST;fence=1});status='Prepared';adapterId=$request.adapterId;runReference=$request.runReference}
    $central=Join-Path (Split-Path $log) ("central-$($lease.reservationId).json")
    $lease | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $leaseFile
    $lease | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $central
} elseif ($command -in @('gate inspect','gate release')) {
    $lease=Get-Content -Raw -Encoding UTF8 -LiteralPath $leaseFile | ConvertFrom-Json
    $central=Join-Path (Split-Path $log) ("central-$($lease.reservationId).json")
    $current=Get-Content -Raw -Encoding UTF8 -LiteralPath $central | ConvertFrom-Json
    if ($command -eq 'gate release') {
        $current.status='Released'
        $current | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $central
        if ($env:MOCK_GATE_RELEASE_RESPONSE_LOST -eq '1') { $global:LASTEXITCODE=1; return }
        $lease.status='Released'
        $lease | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $leaseFile
    } else { $current | ConvertTo-Json -Depth 8 }
}
$global:LASTEXITCODE=0
'@ | Set-Content -Encoding UTF8 -LiteralPath $mock
    $env:MOCK_GATE_LOG=Join-Path $temp 'commands.log'
    $env:MOCK_GATE_HOST=$hostId
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
    # A routine update must reuse the existing installation without reapproval of every script hash.
    $hashes['control-gate\Control.Gate.psm1']='0'*64
    @{schemaVersion=1;toolRoot=$toolRoot;hashes=$hashes} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled')
    if ((Get-ControlGateMode $config) -cne 'Managed') { throw 'A routine local tool update required reapproval.' }
    @{schemaVersion=1;toolRoot=(Join-Path $temp 'another-installation');hashes=$hashes} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled')
    Assert-Throws { Get-ControlGateMode $config }
    @{schemaVersion=1;toolRoot=$toolRoot;hashes=$hashes} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled')
    $bad=$config.Clone(); $bad.controlGate=$config.controlGate.Clone(); $bad.controlGate.bindings=$config.controlGate.bindings.Clone()
    $bad.controlGate.bindings.paths=@()
    Assert-Throws { Enter-ControlGate $bad '01' @('dev') @($path) 'Stopped' }
    $context=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference 'run-1'
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
    $running=Enter-ControlGate $config '03' @('dev') @($path) 'Running' -RunReference 'run-03' -InitialStep 'Seed'
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'request.json') | ConvertFrom-Json
    if ($request.adapterId -cne '03' -or $request.serverConditions[0].requiredState -cne 'Running') { throw '03 running reload gate condition is wrong.' }
    Complete-ControlGate $running 'run-03'
    $migration=Enter-ControlGate $config '17' @('dev') @() 'Running' -RunReference 'run-17' -InitialStep 'Preview'
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'request.json') | ConvertFrom-Json
    if ($request.adapterId -cne '17' -or @($request.resourceKeys).Count -ne 2 -or $request.serverConditions[0].requiredState -cne 'Running') { throw '17 gate must reserve Environment and running Server only.' }
    Complete-ControlGate $migration 'run-17'
    $runRoot=Join-Path $temp 'runs'; New-Item -ItemType Directory -Path $runRoot | Out-Null
    $run=New-ControlGateRunReference $runRoot
    $lost=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run
    New-Item -ItemType Directory -Path $run | Out-Null
    $savedLease=Get-Content -Raw -Encoding UTF8 -LiteralPath $lost.LeaseFile | ConvertFrom-Json
    @{schemaVersion=1;relativeRunDirectory=[IO.Path]::GetFileName($run);status='Completed'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $runRoot 'active-run.json')
    @{schemaVersion=1;status='Completed'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $run 'workflow-state.json')
    @{schemaVersion=1;workflowId=$savedLease.workflowId;reservationId=$savedLease.reservationId;adapterId='01'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $run 'control-gate-run.json')
    $env:MOCK_GATE_RELEASE_RESPONSE_LOST='1'
    Assert-Throws { Complete-ControlGate $lost $run }
    Remove-Item Env:MOCK_GATE_RELEASE_RESPONSE_LOST
    [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE',$null,'Process')
    $resume=Get-ControlGateResumeIdentity $runRoot
    if (!$resume.Completed -or $resume.WorkflowId -cne $savedLease.workflowId -or $resume.ReservationId -cne $savedLease.reservationId) { throw 'Completed run identity was lost.' }
    $before=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG | Where-Object { $_ -like 'gate|acquire|*' }).Count
    $recovered=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $resume.RunReference `
        -WorkflowId $resume.WorkflowId -ReservationId $resume.ReservationId -CompletedRun -InitialStep 'Resume'
    if (!$recovered.CompletedRun -or $recovered.RemoteStatus -cne 'Released') { throw 'Released reservation was not inspected.' }
    Complete-ControlGate $recovered $run
    if (!(Test-Path -LiteralPath (Join-Path $run 'control-gate-released.json'))) { throw 'Release marker was not recovered.' }
    $after=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG | Where-Object { $_ -like 'gate|acquire|*' }).Count
    if ($after -ne $before) { throw 'Release recovery reacquired a reservation.' }
    $centralPath=Join-Path $temp ("central-$($savedLease.reservationId).json")
    $central=Get-Content -Raw -Encoding UTF8 -LiteralPath $centralPath | ConvertFrom-Json
    $central.status='Releasing'
    $central | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $centralPath
    Remove-Item -LiteralPath (Join-Path $run 'control-gate-released.json')
    $releasing=Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run `
        -WorkflowId $resume.WorkflowId -ReservationId $resume.ReservationId -CompletedRun
    $releaseCount=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG | Where-Object { $_ -like 'gate|release|*' }).Count
    Complete-ControlGate $releasing $run
    if (!(Test-Path -LiteralPath (Join-Path $run 'control-gate-released.json')) -or
        @((Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG) | Where-Object { $_ -like 'gate|release|*' }).Count -ne ($releaseCount+1)) {
        throw 'Partially released reservation was not retried and confirmed.'
    }
    $central=Get-Content -Raw -Encoding UTF8 -LiteralPath $centralPath | ConvertFrom-Json
    $central.fences[0].fence=2
    $central | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $centralPath
    Assert-Throws { Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run `
        -WorkflowId $resume.WorkflowId -ReservationId $resume.ReservationId -CompletedRun }
    $central.fences[0].fence=1
    $central.resourceKeys=@($central.resourceKeys | Where-Object { $_ -ne ('server:'+$serverId) })
    $central | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $centralPath
    Assert-Throws { Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run `
        -WorkflowId $resume.WorkflowId -ReservationId $resume.ReservationId -CompletedRun }
    Assert-Throws { Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run `
        -WorkflowId $resume.WorkflowId -ReservationId ([Guid]::NewGuid().ToString('D')) -CompletedRun }
    Assert-Throws { Enter-ControlGate $config '01' @('dev') @($path) 'Stopped' -RunReference $run `
        -WorkflowId $resume.WorkflowId -ReservationId $resume.ReservationId }
    $env:MOCK_GATE_FAIL='gate acquire'
    Assert-Throws { Enter-ControlGate $config '17' @('dev') @() 'Running' }
    Write-Output 'Control gate fixture passed (all reservations and paths mocked).'
} finally {
    $env:ProgramData=$priorProgramData; $env:LOCALAPPDATA=$priorLocalAppData
    Remove-Item Env:MOCK_GATE_LOG -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_GATE_FAIL -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_GATE_HOST -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_GATE_RELEASE_RESPONSE_LOST -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE',$null,'Process')
    $resolved=[IO.Path]::GetFullPath($temp)
    $expected=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($expected,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'control-gate-test-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
