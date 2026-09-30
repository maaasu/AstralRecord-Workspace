#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-update-entry-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
function Write-TestFile($Path,$Content) { New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null; [IO.File]::WriteAllText($Path,$Content) }
try {
    $toolsRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    foreach ($relative in @('deploy-debug/dev-update.ps1','deploy-debug/PluginTestSelection.ps1','maintenance/maintenance.ps1','maintenance/Distribution.ps1','maintenance/Diagnostics.ps1','maintenance/DeploymentSelection.ps1','maintenance/SkillTreeMigration.ps1')) {
        $destination=Join-Path $fixture $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $toolsRoot $relative) -Destination $destination
    }
    # Isolate external build and workflow transport. Test real entry routing and real file distribution.
    Write-TestFile (Join-Path $fixture 'deploy-debug/deploy-debug.ps1') @'
param([string]$ConfigPath,[switch]$PluginOnly,[switch]$MasterDataOnly,[switch]$ReleaseManagementOnly,[switch]$PreflightOnly,[string]$TestMode,[string]$Tests,[string]$WorkflowRunDirectory,[string]$WorkflowFingerprint)
. (Join-Path $PSScriptRoot 'PluginTestSelection.ps1')
if ($WorkflowFingerprint) {
    $selection=Resolve-PluginTestSelection -TestMode $TestMode -Tests $Tests -PluginOnly:$PluginOnly
    $mode=if ($PluginOnly) { 'PluginOnly' } elseif ($MasterDataOnly) { 'MasterDataOnly' } else { 'Full' }
    if ((Get-DevDeploymentFingerprint -ConfigPath $ConfigPath -Mode $mode -TestSelection $selection) -cne $WorkflowFingerprint) { throw 'Entry/backend test fingerprints differ.' }
}
@{pluginOnly=[bool]$PluginOnly;masterDataOnly=[bool]$MasterDataOnly;release=[bool]$ReleaseManagementOnly;preflight=[bool]$PreflightOnly;testMode=$TestMode;tests=$Tests;fingerprint=$WorkflowFingerprint} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $ConfigPath) 'backend-result.json')
'@
    Write-TestFile (Join-Path $fixture 'maintenance/UpdateWorkflow.ps1') @'
function Invoke-UpdateWorkflow($WorkflowConfig,$MigrationConfig,$ConfigurationFingerprint,$ServerRoots,$DeployAction,[switch]$ServersStopped,[switch]$AdmissionClosed,$Label,$Recovery,[switch]$RecoveryChecked,$PrepareRunAction,$RestoreAction,[switch]$ServerAlreadyRunning,[switch]$AutomaticWritersStopped,[switch]$AllowSameSessionPublication) {
    $run=Join-Path $WorkflowConfig.runRoot ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    @{label=$Label;servers=$MigrationConfig.serverIds;seed=$WorkflowConfig.seedMasterData;roots=$ServerRoots} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $WorkflowConfig.runRoot 'entry-result.json')
    if ($PrepareRunAction) { $null=& $PrepareRunAction $run }
    & $DeployAction $run
    if (!(Test-Path -LiteralPath (Join-Path $run 'deploy-action-success.json')) -and !(Test-Path -LiteralPath (Join-Path $run 'deployment.json'))) { throw 'Deployment completion was not journaled.' }
}
'@
    $dev=Join-Path $fixture 'dev'; $channel=Join-Path $fixture 'channel'
    Write-TestFile "$dev/plugins/AstralRecord.jar" 'new jar'
    Write-TestFile "$channel/plugins/AstralRecord.jar" 'old jar'
    Write-TestFile "$fixture/source/config.yml" 'fixture'
    $migration=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='TEST_API';migrationKeyEnvironmentVariable='TEST_MIG';serverIds=@('dev')}
    $config=@{devWorkflow=@{runRoot="$fixture/dev-runs";startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$true;migration=$migration};plugin=@{enabled=$true;deployPath="$dev/plugins"};fileDatabase=@{enabled=$true;sourcePath="$fixture/source";deployPath="$fixture/filebase"};api=@{enabled=$false};web=@{enabled=$false}}
    $configPath=Join-Path $fixture 'dev.json'
    $config | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath
    $entry=Join-Path $fixture 'deploy-debug/dev-update.ps1'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan
    Assert ($LASTEXITCODE -eq 0 -and !(Test-Path "$fixture/backend-result.json")) 'Plan must not run backend.'
    foreach ($mode in @('Full','PluginOnly','MasterDataOnly')) {
        $arguments=@('-NoProfile','-File',$entry,'-ConfigPath',$configPath,'-ServersStopped','-AdmissionClosed')
        if ($mode -ne 'Full') { $arguments+='-'+$mode }
        & pwsh @arguments
        Assert ($LASTEXITCODE -eq 0) "Dev routing failed: $mode"
        $result=Get-Content -Raw -Encoding UTF8 -LiteralPath "$fixture/backend-result.json" | ConvertFrom-Json
        Assert ($result.pluginOnly -eq ($mode -eq 'PluginOnly') -and $result.masterDataOnly -eq ($mode -eq 'MasterDataOnly')) 'Wrong backend flags.'
        $expectedTestMode=if ($mode -eq 'PluginOnly') { 'Skip' } elseif ($mode -eq 'Full') { 'All' } else { '' }
        Assert ($result.testMode -ceq $expectedTestMode) 'Default test mode changed.'
        $workflow=Get-Content -Raw -LiteralPath "$fixture/dev-runs/entry-result.json" | ConvertFrom-Json
        Assert ($workflow.servers[0] -eq 'dev' -and $workflow.label -eq 'Dev') 'Dev target server changed.'
        Assert ($workflow.seed -eq ($mode -ne 'PluginOnly')) 'Wrong seed selection.'
    }
    foreach ($case in @(
        @{Flags=@('-TestMode','Skip');Mode='Skip';Tests=''},
        @{Flags=@('-PluginOnly','-TestMode','All');Mode='All';Tests=''},
        @{Flags=@('-TestMode','Selected','-Tests','ClassRepositoryTest, SkillPermissionServiceTest');Mode='Selected';Tests='ClassRepositoryTest,SkillPermissionServiceTest'},
        @{Flags=@('-PluginOnly','-TestMode','Selected','-Tests','*RepositoryTest');Mode='Selected';Tests='*RepositoryTest'}
    )) {
        & pwsh -NoProfile -File $entry -ConfigPath $configPath -ServersStopped -AdmissionClosed @($case.Flags)
        Assert ($LASTEXITCODE -eq 0) 'Explicit test mode routing failed.'
        $result=Get-Content -Raw -Encoding UTF8 -LiteralPath "$fixture/backend-result.json" | ConvertFrom-Json
        Assert ($result.testMode -ceq $case.Mode -and $result.tests -ceq $case.Tests) 'Test selection was not forwarded correctly.'
    }
    $planOutput=@(& pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan -TestMode Selected -Tests '*RepositoryTest')
    Assert ($LASTEXITCODE -eq 0 -and ($planOutput -join "`n") -match 'Selected tests: \*RepositoryTest') 'Plan must describe selected tests.'
    foreach ($flags in @(
        @('-TestMode','Selected'), @('-Tests','ClassRepositoryTest'),
        @('-TestMode','All','-Tests',''), @('-TestMode','Skip','-Tests',''),
        @('-TestMode','Skip','-Tests','ClassRepositoryTest'), @('-TestMode','Selected','-Tests','ClassRepositoryTest,'),
        @('-MasterDataOnly','-TestMode','All'), @('-ReleaseManagementOnly','-TestMode','All'),
        @('-PreflightOnly','-TestMode','Skip')
    )) {
        Remove-Item -LiteralPath "$fixture/backend-result.json" -ErrorAction SilentlyContinue
        & pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan @flags
        Assert ($LASTEXITCODE -ne 0 -and !(Test-Path "$fixture/backend-result.json")) 'Invalid test selection must fail before backend execution.'
    }
    $config.plugin.enabled=$false
    $config | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan -TestMode All
    Assert ($LASTEXITCODE -ne 0) 'Explicit tests with disabled Plugin must be rejected.'
    $config.plugin.enabled=$true
    $config.devWorkflow.migration.scope='AllCandidates'
    $config | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan
    Assert ($LASTEXITCODE -eq 0) 'Legacy scope must not enable bulk migration or block patch publication.'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -ReleaseManagementOnly -PreflightOnly
    Assert ($LASTEXITCODE -eq 0) 'Release preflight must bypass Dev workflow.'
    $result=Get-Content -Raw -LiteralPath "$fixture/backend-result.json" | ConvertFrom-Json
    Assert ($result.release -and $result.preflight) 'Release flags were lost.'
    $channelConfig=@{schemaVersion=1;workflow=@{runRoot="$fixture/channel-runs";startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$true};migration=$migration;servers=@(@{id='dev';enabled=$true;rootPath=$dev},@{id='channel';enabled=$true;rootPath=$channel});distributions=@(@{enabled=$true;kind='Jar';source='dev';relativePath='plugins/AstralRecord.jar';targets=@('channel')})}
    $channelPath=Join-Path $fixture 'channel.json'
    $channelConfig | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $channelPath
    & pwsh -NoProfile -File "$fixture/maintenance/maintenance.ps1" -ConfigPath $channelPath -ServersStopped -AdmissionClosed -WorldCopy Skip -NetworkPlugins Skip
    Assert ($LASTEXITCODE -eq 0) 'Default maintenance phase must execute workflow.'
    Assert ((Get-Content -Raw -LiteralPath "$channel/plugins/AstralRecord.jar") -eq 'new jar') 'Workflow did not run real distribution.'
    Write-Host "PASS: Dev/test modes, selection validation, cross-process fingerprints, startup routing without account settings, channel workflow, release preflight. Evidence: $fixture"
} finally { Stop-Transcript | Out-Null }
