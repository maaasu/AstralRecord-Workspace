#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../ControlCutover.psm1') -Force

function Assert($Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Assert-Throws([scriptblock]$Action,[string]$Message) {
    $threw=$false
    try { & $Action } catch { $threw=$true }
    if (!$threw) { throw $Message }
}

$devRoot=Get-ControlCutoverRoot 'DEV-01'
Assert ($devRoot.id -eq 'f9c06531-2e7e-40f2-b03a-ee2ee9ebe5aa' -and $devRoot.path -eq 'C:\AstralRecord-Workspace') 'DEV source root changed.'
$appRoot=Get-ControlCutoverRoot 'APP-01'
Assert ($appRoot.id -eq '99a1c3c4-dafc-4fc8-9a8b-b3aad1c1ecee' -and $appRoot.path -eq 'C:\app-01') 'APP distribution root changed.'
$appDirectories=@(Get-ControlCutoverAppMetadataDirectories)
Assert ($appDirectories.Count -eq 5 -and $appDirectories[0] -eq 'C:\app-01' -and
    $appDirectories[1] -eq 'C:\app-01\AstralRecordApi' -and $appDirectories[2] -eq 'C:\app-01\AstralRecordWeb' -and
    $appDirectories[3] -eq 'C:\app-01\workspace' -and $appDirectories[4] -eq 'C:\app-01\workspace\40_filebase') 'APP metadata directory allowlist changed.'
$metadataRule=New-ControlCutoverMetadataReadRule 'S-1-5-80-456'
$read=[Security.AccessControl.FileSystemRights]::ReadAndExecute
Assert ($metadataRule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
    $metadataRule.InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]::None -and
    $metadataRule.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None -and
    ($metadataRule.FileSystemRights -band $read) -eq $read -and
    ($metadataRule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Write) -eq 0 -and
    ($metadataRule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Delete) -eq 0) 'APP metadata ACE must apply only RX to this directory.'
$servicePath='"C:\Program Files\AstralRecordControl\Agent\release\AstralRecordControl.Agent.exe" --data-root "C:\ProgramData\AstralRecordControl\Agent"'
$parsed=Get-ControlCutoverServicePaths $servicePath Agent
Assert ($parsed.binary -eq 'C:\Program Files\AstralRecordControl\Agent\release\AstralRecordControl.Agent.exe' -and
    $parsed.data -eq 'C:\ProgramData\AstralRecordControl\Agent') 'Installed service path with spaces was not parsed.'
Assert-Throws { Get-ControlCutoverServicePaths $servicePath Runner } 'Component mismatch must fail.'
Assert-Throws { Get-ControlCutoverServicePaths ($servicePath+' --extra') Agent } 'Unexpected service arguments must fail.'

$agent=@{hostId='0604b62f-c10b-46e8-a0cf-cca70aab6b5f';serverUrl='https://control.astralrecord.com';runnerPipeName='AstralRecordControl.Runner.v1';tokenFile='credentials/token.bin';enableFileControl=$false;enableSchedules=$false;
    localResources=@{roots=@(@{id='6a3ef4c4-386a-42ef-ac71-32b917de7023';path='C:\server\AstralRecord-Dev';backupOnly=$false});executables=@(@{id='01e00dff-0eb2-48b2-b6c3-59a8badc2315';path='C:\java.exe';kind='Java'})}}
$runner=@{hostId=$agent.hostId;agentSid='S-1-5-80-123';pipeName='AstralRecordControl.Runner.v1';enableProcessControl=$true;enableFileControl=$false;
    localResources=@{roots=@(@{id='6a3ef4c4-386a-42ef-ac71-32b917de7023';path='C:\server\AstralRecord-Dev';backupOnly=$false});executables=@(@{id='01e00dff-0eb2-48b2-b6c3-59a8badc2315';path='C:\java.exe';kind='Java'})}}
Assert-ControlCutoverBinding $agent $runner 'DEV-01' 'S-1-5-80-123'
$agent.localResources.roots[0].path='C:\server\Wrong'
$runner.localResources.roots[0].path='C:\server\Wrong'
Assert-Throws { Assert-ControlCutoverBinding $agent $runner 'DEV-01' 'S-1-5-80-123' } 'Wrong existing DEV root must fail.'
$agent.localResources.roots[0].path='C:\server\AstralRecord-Dev'
$runner.localResources.roots[0].path='C:\server\AstralRecord-Dev'
$agent=Add-ControlCutoverRoot $agent 'DEV-01'
$runner=Add-ControlCutoverRoot $runner 'DEV-01'
Assert-ControlCutoverBinding $agent $runner 'DEV-01' 'S-1-5-80-123'
Assert (@($agent.localResources.roots).Count -eq 2 -and @($runner.localResources.roots).Count -eq 2) 'DEV root was not added to both configs.'
$agent=Add-ControlCutoverRoot $agent 'DEV-01'
Assert (@($agent.localResources.roots).Count -eq 2) 'DEV root addition must be idempotent.'
$runner.localResources.roots[1].id='00000000-0000-0000-0000-000000000001'
Assert-Throws { Assert-ControlCutoverResourcesEqual $agent $runner } 'Mismatched resource GUID must fail.'

$appAgent=@{hostId='4ff81871-2069-4a9a-9c98-43421de3b7e8';serverUrl='https://control.astralrecord.com';runnerPipeName='AstralRecordControl.Runner.v1';tokenFile='credentials/token.bin';enableFileControl=$false;enableSchedules=$false;localResources=@{roots=@($appRoot);executables=@()}}
$appRunner=@{hostId=$appAgent.hostId;agentSid='S-1-5-80-456';pipeName='AstralRecordControl.Runner.v1';enableProcessControl=$false;enableFileControl=$false;localResources=@{roots=@($appRoot);executables=@()}}
Assert-ControlCutoverBinding $appAgent $appRunner 'APP-01' 'S-1-5-80-456'
$appAgent.localResources.roots[0].path='C:\app-01-wrong'
$appRunner.localResources.roots[0].path='C:\app-01-wrong'
Assert-Throws { Assert-ControlCutoverBinding $appAgent $appRunner 'APP-01' 'S-1-5-80-456' } 'Wrong APP root must fail.'
$appAgent.localResources.roots[0].path='C:\app-01'
$appRunner.localResources.roots[0].path='C:\app-01'
$appRunner.enableProcessControl=$true
Assert-Throws { Assert-ControlCutoverBinding $appAgent $appRunner 'APP-01' 'S-1-5-80-456' } 'APP process control must stay disabled.'

$snapshot=@{runnerEpoch=[Guid]::NewGuid().ToString('D');servers=@(@{serverId=[Guid]::NewGuid().ToString('D');processState='Stopped';currentSessionId=$null});holds=@()}
Assert-ControlCutoverSnapshot $snapshot
$snapshot.servers[0].processState='Running'
Assert-Throws { Assert-ControlCutoverSnapshot $snapshot } 'Running server must block resource configuration.'
$snapshot.servers[0].processState='Stopped';$snapshot.holds=@(@{reservationId=[Guid]::NewGuid().ToString('D')})
Assert-Throws { Assert-ControlCutoverSnapshot $snapshot } 'Active hold must block resource configuration.'

$entry=Join-Path $PSScriptRoot '../Update-ControlCutoverHost.ps1'
$errors=$null
[Management.Automation.Language.Parser]::ParseFile($entry,[ref]$null,[ref]$errors)|Out-Null
Assert (@($errors).Count -eq 0) 'Host entry has PowerShell syntax errors.'
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('astral-control-input-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $reader=Join-Path $fixture 'input-reader.ps1'
    [IO.File]::WriteAllText($reader,@'
[IO.File]::AppendAllText($env:CONTROL_ENROLL_FIXTURE_LOG,($args -join '|')+"`n")
exit 0
'@)
    $shim=Join-Path $fixture 'agent-fixture.cmd'
    [IO.File]::WriteAllLines($shim,@('@echo off',('"'+(Join-Path $PSHOME 'pwsh.exe')+'" -NoProfile -File "%~dp0input-reader.ps1" %*')),[Text.Encoding]::ASCII)
    $priorFixtureLog=$env:CONTROL_ENROLL_FIXTURE_LOG
    $env:CONTROL_ENROLL_FIXTURE_LOG=Join-Path $fixture 'commands.txt'
    Invoke-ControlCutoverAgentEnrollment $shim ([Guid]'4ff81871-2069-4a9a-9c98-43421de3b7e8')
    $commands=@(Get-Content -Encoding UTF8 -LiteralPath $env:CONTROL_ENROLL_FIXTURE_LOG)
    Assert ($commands.Count -eq 2 -and $commands[0] -ceq 'login|--server-url|https://control.astralrecord.com') 'Initial login must use the fixed Control origin.'
    Assert ($commands[1] -ceq 'agent|enroll|--host-id|4ff81871-2069-4a9a-9c98-43421de3b7e8|--expected-server-url|https://control.astralrecord.com|--start-agent|false') 'Enrollment must use the fixed Host and keep passive service startup order.'
} finally {
    if (Get-Variable priorFixtureLog -ErrorAction SilentlyContinue) { $env:CONTROL_ENROLL_FIXTURE_LOG=$priorFixtureLog }
    if ([IO.Path]::GetFullPath($fixture).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($fixture).StartsWith('astral-control-input-')) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
Write-Host 'PASS: fixed host roots, matching resources, APP directory-only limits, stopped snapshots, automatic enrollment, entry syntax.'
