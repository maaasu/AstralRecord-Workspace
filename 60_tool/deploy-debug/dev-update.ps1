#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [switch]$PluginOnly,
    [switch]$MasterDataOnly,
    [switch]$ReleaseManagementOnly,
    [switch]$PreflightOnly,
    [switch]$Plan,
    [switch]$ServersStopped,
    [switch]$AdmissionClosed
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
try {
    if (!$ConfigPath) {
        $localPath=Join-Path $PSScriptRoot 'deploy-debug.local.json'
        $ConfigPath=if (Test-Path -LiteralPath $localPath) { $localPath } else { Join-Path $PSScriptRoot 'deploy-debug.config.json' }
    }
    $ConfigPath=(Resolve-Path -LiteralPath $ConfigPath).Path
    $backend=Join-Path $PSScriptRoot 'deploy-debug.ps1'
    if ($ReleaseManagementOnly -or $PreflightOnly) {
        if ($MasterDataOnly -or $Plan -or $ServersStopped -or $AdmissionClosed) { throw 'Release management uses its existing preflight/deployment workflow.' }
        $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$backend,'-ConfigPath',$ConfigPath)
        if ($ReleaseManagementOnly) { $arguments+='-ReleaseManagementOnly' }
        if ($PreflightOnly) { $arguments+='-PreflightOnly' }
        if ($PluginOnly) { $arguments+='-PluginOnly' }
        & powershell.exe @arguments
        exit $LASTEXITCODE
    }
    if ($PluginOnly -and $MasterDataOnly) { throw 'Choose PluginOnly or MasterDataOnly, not both.' }
    . (Join-Path $PSScriptRoot '../maintenance/Distribution.ps1')
    . (Join-Path $PSScriptRoot '../maintenance/SkillTreeMigration.ps1')
    . (Join-Path $PSScriptRoot '../maintenance/UpdateWorkflow.ps1')
    $config=Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json -AsHashtable
    if (!$config.ContainsKey('devWorkflow')) { throw 'Add devWorkflow to the deployment config. See deploy-debug/README.md for one-time setup.' }
    $workflow=$config.devWorkflow
    $migration=$workflow.migration
    $normalized=ConvertTo-SkillTreeMigrationConfig $migration
    if (!$normalized.Enabled -or $normalized.Scope -ne 'ExplicitAccounts' -or $normalized.ServerIds.Count -ne 1) {
        throw 'Dev workflow requires enabled migration, one Dev serverId, and ExplicitAccounts. AllCandidates is not allowed.'
    }
    if (!$workflow.runRoot) { $workflow.runRoot=Join-Path ([IO.Path]::GetDirectoryName($ConfigPath)) 'runs' }
    $workflow.runRoot=Get-MaintenanceAbsolutePath $workflow.runRoot
    $mode=if ($PluginOnly) { 'PluginOnly' } elseif ($MasterDataOnly) { 'MasterDataOnly' } else { 'Full' }
    if ($MasterDataOnly -and !$config.fileDatabase.enabled) { throw 'MasterDataOnly requires fileDatabase.enabled=true.' }
    if ($PluginOnly -and !$config.plugin.enabled) { throw 'PluginOnly requires plugin.enabled=true.' }
    if ($mode -eq 'Full' -and !$config.plugin.enabled -and !$config.fileDatabase.enabled -and !$config.api.enabled -and !$config.web.enabled) { throw 'No enabled deployment component.' }
    # A pure JAR deployment does not reseed unchanged master data.
    if ($PluginOnly -or !$config.fileDatabase.enabled) { $workflow.seedMasterData=$false }
    $roots=@([IO.Path]::GetDirectoryName((Get-MaintenanceAbsolutePath $config.plugin.deployPath)))
    foreach ($name in @('api','web','fileDatabase')) {
        if ($config[$name].enabled) { $roots+=Get-MaintenanceAbsolutePath $config[$name].deployPath }
    }
    if (Test-Path -LiteralPath $config.fileDatabase.sourcePath) { $roots+=Get-MaintenanceAbsolutePath $config.fileDatabase.sourcePath }
    foreach ($root in $roots) { if (Test-MaintenanceOverlap $workflow.runRoot $root) { throw 'Workflow runRoot overlaps deployment/source data.' } }
    if ($Plan) {
        Write-Host "Dev update mode: $mode"
        Write-Host "Target runtime: $($normalized.ServerIds[0]); explicit accounts: $($normalized.AccountIds.Count); users (all characters): $($normalized.AccountUserIds.Count)"
        Write-Host "API endpoint: $($normalized.BaseUrl)"
        if ($normalized.ApiSettingsPath) { Write-Host "Authentication source: API settings file $($normalized.ApiSettingsPath)" }
        else { Write-Host "Authentication source: environment variables $($normalized.ApiKeyEnvironmentVariable), $($normalized.MigrationKeyEnvironmentVariable)" }
        Write-Host 'Stopped Dev -> build/copy -> optional seed -> prompt to start Dev -> wait for new runtime -> migrate -> complete.'
        Write-Host 'No builds, copies, or API requests performed.'
        exit 0
    }
    $fingerprint=Get-SkillTreeMigrationSha256 ((Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash + '|' + $mode)
    $deployAction={
        param($RunDirectory)
        $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$backend,'-ConfigPath',$ConfigPath)
        if ($PluginOnly) { $arguments+='-PluginOnly' }
        if ($MasterDataOnly) { $arguments+='-MasterDataOnly' }
        $log=Join-Path $RunDirectory 'dev-deploy.log'
        & powershell.exe @arguments 2>&1 | Tee-Object -FilePath $log | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Dev deployment failed. Keep Dev stopped. See $log" }
        Write-MaintenanceJson @{ completedAtUtc=[DateTime]::UtcNow.ToString('o') } (Join-Path $RunDirectory 'deploy-action-success.json')
    }
    Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint $fingerprint `
        -ServerRoots $roots -DeployAction $deployAction -ServersStopped:$ServersStopped -AdmissionClosed:$AdmissionClosed -Label 'Dev'
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
}
