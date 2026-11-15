#requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../../control-gate/Control.Gate.psm1') -Force
$config=@{api=@{enabled=$true};fileDatabase=@{enabled=$true};controlGate=@{mode='Managed';sharedApiRuntimeServerIds=@('Dev','Build','Channel1','Channel2')}}
$runtimes=@('Dev')
$ids=@(Get-DeploymentGateRuntimeServerIds $config $runtimes)
if ($ids.Count -ne 1 -or $ids[0] -cne 'Dev') { throw '01 must not require peer servers to stop, even when API/Filebase are shared.' }
$ids=@(Get-DeploymentGateRuntimeServerIds $config $runtimes -MasterDataOnly)
if ($ids.Count -ne 4 -or 'Dev' -cnotin $ids -or 'Channel2' -cnotin $ids) { throw '03 shared maintenance scope changed.' }
$ids=@(Get-DeploymentGateRuntimeServerIds $config $runtimes -PluginOnly)
if ($ids.Count -ne 1 -or $ids[0] -cne 'Dev') { throw 'A plugin-only deployment unnecessarily reserves other servers.' }
$rejected=$false
try { $null=Get-DeploymentGateRuntimeServerIds $config $runtimes -MasterDataOnly -ServerAlreadyRunning } catch { $rejected=$true }
if (!$rejected) { throw 'A running Dev was accepted for a shared API master update.' }
$config.controlGate.sharedApiRuntimeServerIds=@('Channel1')
$rejected=$false
try { $null=Get-DeploymentGateRuntimeServerIds $config $runtimes } catch { $rejected=$true }
if (!$rejected) { throw 'A workflow target missing from the shared runtime set was accepted.' }
foreach ($invalid in @(@{ids=@()},@{ids=$null},@{ids=@('Dev')},@{ids=@('Dev','Dev')})) {
    $config.controlGate.sharedApiRuntimeServerIds=$invalid.ids
    $rejected=$false
    try { $null=Get-DeploymentGateRuntimeServerIds $config $runtimes } catch { $rejected=$true }
    if (!$rejected) { throw 'An empty, null, singleton, or duplicate shared runtime declaration was accepted.' }
}
$config.controlGate.mode='Legacy'
$ids=@(Get-DeploymentGateRuntimeServerIds $config $runtimes)
if ($ids.Count -ne 1 -or $ids[0] -cne 'Dev') { throw 'Legacy routing changed.' }
if ($runtimes.Count -ne 1 -or $runtimes[0] -cne 'Dev') { throw 'The publication target was mutated.' }
$config.controlGate=@{mode='Managed'}
$ids=@(Get-DeploymentGateRuntimeServerIds $config $runtimes)
if ($ids.Count -ne 1 -or $ids[0] -cne 'Dev') { throw 'A dedicated API configuration changed.' }
Write-Host 'PASS: 01 and 02 require Dev only; 03 shared maintenance, declaration validation, Legacy routing and publication targets remain unchanged.'
