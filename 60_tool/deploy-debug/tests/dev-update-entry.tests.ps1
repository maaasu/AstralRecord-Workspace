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
    foreach ($relative in @('deploy-debug/dev-update.ps1','maintenance/maintenance.ps1','maintenance/Distribution.ps1','maintenance/SkillTreeMigration.ps1')) {
        $destination=Join-Path $fixture $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $toolsRoot $relative) -Destination $destination
    }
    # Isolate external build and workflow transport. Test real entry routing and real file distribution.
    Write-TestFile (Join-Path $fixture 'deploy-debug/deploy-debug.ps1') @'
param([string]$ConfigPath,[switch]$PluginOnly,[switch]$MasterDataOnly,[switch]$ReleaseManagementOnly,[switch]$PreflightOnly)
@{pluginOnly=[bool]$PluginOnly;masterDataOnly=[bool]$MasterDataOnly;release=[bool]$ReleaseManagementOnly;preflight=[bool]$PreflightOnly} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $ConfigPath) 'backend-result.json')
'@
    Write-TestFile (Join-Path $fixture 'maintenance/UpdateWorkflow.ps1') @'
function Invoke-UpdateWorkflow($WorkflowConfig,$MigrationConfig,$ConfigurationFingerprint,$ServerRoots,$DeployAction,[switch]$ServersStopped,[switch]$AdmissionClosed,$Label) {
    $run=Join-Path $WorkflowConfig.runRoot ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    @{label=$Label;scope=$MigrationConfig.scope;seed=$WorkflowConfig.seedMasterData;roots=$ServerRoots} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $WorkflowConfig.runRoot 'entry-result.json')
    & $DeployAction $run
    if (!(Test-Path -LiteralPath (Join-Path $run 'deploy-action-success.json')) -and !(Test-Path -LiteralPath (Join-Path $run 'deployment.json'))) { throw 'Deployment completion was not journaled.' }
}
'@
    $dev=Join-Path $fixture 'dev'; $channel=Join-Path $fixture 'channel'
    Write-TestFile "$dev/plugins/AstralRecord.jar" 'new jar'
    Write-TestFile "$channel/plugins/AstralRecord.jar" 'old jar'
    Write-TestFile "$fixture/source/config.yml" 'fixture'
    $migration=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='TEST_API';migrationKeyEnvironmentVariable='TEST_MIG';serverIds=@('dev');scope='ExplicitAccounts';accountIds=@([guid]::NewGuid().ToString())}
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
        $result=Get-Content -Raw -LiteralPath "$fixture/backend-result.json" | ConvertFrom-Json
        Assert ($result.pluginOnly -eq ($mode -eq 'PluginOnly') -and $result.masterDataOnly -eq ($mode -eq 'MasterDataOnly')) 'Wrong backend flags.'
        $workflow=Get-Content -Raw -LiteralPath "$fixture/dev-runs/entry-result.json" | ConvertFrom-Json
        Assert ($workflow.scope -eq 'ExplicitAccounts' -and $workflow.label -eq 'Dev') 'Dev target scope changed.'
        Assert ($workflow.seed -eq ($mode -ne 'PluginOnly')) 'Wrong seed selection.'
    }
    $config.devWorkflow.migration.scope='AllCandidates'
    $config | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $configPath
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -Plan
    Assert ($LASTEXITCODE -ne 0) 'Dev AllCandidates must be rejected.'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -ReleaseManagementOnly -PreflightOnly
    Assert ($LASTEXITCODE -eq 0) 'Release preflight must bypass Dev workflow.'
    $result=Get-Content -Raw -LiteralPath "$fixture/backend-result.json" | ConvertFrom-Json
    Assert ($result.release -and $result.preflight) 'Release flags were lost.'
    $channelConfig=@{schemaVersion=1;workflow=@{runRoot="$fixture/channel-runs";startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$true};migration=$migration;servers=@(@{id='dev';enabled=$true;rootPath=$dev},@{id='channel';enabled=$true;rootPath=$channel});distributions=@(@{enabled=$true;kind='Jar';source='dev';relativePath='plugins/AstralRecord.jar';targets=@('channel')})}
    $channelPath=Join-Path $fixture 'channel.json'
    $channelConfig | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $channelPath
    & pwsh -NoProfile -File "$fixture/maintenance/maintenance.ps1" -ConfigPath $channelPath -ServersStopped -AdmissionClosed
    Assert ($LASTEXITCODE -eq 0) 'Default maintenance phase must execute workflow.'
    Assert ((Get-Content -Raw -LiteralPath "$channel/plugins/AstralRecord.jar") -eq 'new jar') 'Workflow did not run real distribution.'
    Write-Host "PASS: Dev modes, startup workflow routing, migration scope, channel default workflow, release preflight. Evidence: $fixture"
} finally { Stop-Transcript | Out-Null }
