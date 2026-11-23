#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Workflow','Plan','Deploy','Restore')]
    [string]$Phase = 'Workflow',
    [string]$ConfigPath,
    [string]$RunDirectory,
    [ValidateSet('Ask','Include','Skip')][string]$WorldCopy = 'Ask',
    [ValidateSet('Ask','Include','Skip')][string]$NetworkPlugins = 'Ask',
    [ValidateSet('Auto','Restart','Restore')][string]$Recovery = 'Auto',
    [switch]$RecoveryChecked,
    [switch]$ServersStopped,
    [switch]$AdmissionClosed,
    [switch]$ListDistributionOptions,
    [string]$DistributionSelection,
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$DistributionConfigurationDigest,
    [ValidatePattern('^[a-f0-9]{64}$')][string]$ExpectedSelectionFingerprint
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Distribution.ps1')
. (Join-Path $PSScriptRoot 'DeploymentSelection.ps1')
. (Join-Path $PSScriptRoot 'WorkflowRunRoot.ps1')
$locks = [Collections.Generic.List[IDisposable]]::new()
$maintenanceDiagnosticFile = $null
$gateContext=$null
try {
    if (($Recovery -ne 'Auto' -or $RecoveryChecked) -and $Phase -ne 'Workflow') { throw '-Recoveryは通常Workflow専用です。詳細操作は-Phase Restoreを使用してください。' }
    $hasDetail = $PSBoundParameters.ContainsKey('DistributionSelection')
    if ($ListDistributionOptions -and ($Phase -ne 'Plan' -or $hasDetail -or $WorldCopy -ne 'Ask' -or $NetworkPlugins -ne 'Ask')) { throw '-ListDistributionOptions requires -Phase Plan without selection arguments.' }
    if (($PSBoundParameters.ContainsKey('WorldCopy') -or $PSBoundParameters.ContainsKey('NetworkPlugins') -or $hasDetail -or $DistributionConfigurationDigest -or $ExpectedSelectionFingerprint) -and $Phase -eq 'Restore') { throw 'Distribution selection is supported only for Workflow, Plan and Deploy.' }
    if (($DistributionConfigurationDigest -and !$hasDetail -and !$ExpectedSelectionFingerprint) -or
        ($ExpectedSelectionFingerprint -and ($Phase -ne 'Deploy' -or !$DistributionConfigurationDigest -or $hasDetail))) { throw 'Invalid detailed distribution selection arguments.' }
    if ($hasDetail -and $Phase -ne 'Plan' -and !$DistributionConfigurationDigest) {
        throw '詳細IDで実行するには、候補一覧のSHA-256を -DistributionConfigurationDigest に指定してください。'
    }
    $legacyConfig=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../maintenance/maintenance.local.json'))
    if (!$ConfigPath) {
        $ConfigPath=Join-Path $PSScriptRoot 'maintenance.local.json'
        if (!(Test-Path -LiteralPath $ConfigPath -PathType Leaf) -and (Test-Path -LiteralPath $legacyConfig -PathType Leaf)) {
            $ConfigPath=$legacyConfig
        }
    }
    if (!(Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'Create maintenance.local.json from maintenance.example.json and configure paths first.' }
    $ConfigPath=(Resolve-Path -LiteralPath $ConfigPath).Path
    $read = Read-MaintenanceConfiguration $ConfigPath
    $config = $read.config
    $configDigest = $read.digest
    if ($config.schemaVersion -ne 1) { throw 'Unsupported schemaVersion.' }
    if ($ListDistributionOptions) {
        foreach ($option in @(Get-MaintenanceDistributionOptions $config)) {
            Write-Host "$($option.id)  $($option.label)  $($option.source) → $($option.destination)"
        }
        Write-Host "配布設定の SHA-256: $configDigest"
        exit 0
    }
    if ($DistributionConfigurationDigest -and $DistributionConfigurationDigest -ine $configDigest) { throw '配布設定が変更されました。候補を取得し直してください。' }
    $distributionIds = $null
    if ($hasDetail) {
        if ([string]::IsNullOrWhiteSpace($DistributionSelection)) { throw '配布対象を1件以上選択してください。' }
        $distributionIds = @(ConvertTo-MaintenanceDistributionIds @($DistributionSelection.Split(',').Trim()))
    }
    $planChoice=if ($WorldCopy -eq 'Skip') { 'Skip' } else { 'Include' }
    $networkChoice=if ($NetworkPlugins -eq 'Skip') { 'Skip' } else { 'Include' }
    if ($hasDetail) { $null = Get-MaintenanceSelectedConfig $config $planChoice $networkChoice -DistributionIds $distributionIds }
    if ($config.migration.enabled -isnot [bool]) { throw 'Migration enabled must be boolean.' }
    if ($config.migration.enabled) {
        . (Join-Path $PSScriptRoot 'SkillTreeMigration.ps1')
        $normalizedMigration = ConvertTo-SkillTreeRuntimeConfig $config.migration
    }
    $configuredRunRoot=if ($config['workflow']) { $config.workflow['runRoot'] }
    if ($Phase -eq 'Workflow') {
        if (!$config.ContainsKey('workflow') -or !$config.migration.enabled) { throw 'Configure workflow and enable patch publication in migration before using the guided maintenance workflow.' }
        $workflow=$config.workflow
        $workflow['runRoot']=Resolve-MaintenanceWorkflowRunRoot -ToolRoot (Split-Path -Parent $PSScriptRoot) -ConfigPath $ConfigPath -ConfiguredRunRoot $configuredRunRoot
    }
    if ($ConfigPath -ieq $legacyConfig -and $config['networkPlugins'] -and !$config.networkPlugins['sourceDirectory']) {
        $legacyRunRoot=if ($configuredRunRoot) { $configuredRunRoot }
            else { Join-Path (Split-Path -Parent $legacyConfig) 'runs' }
        $pending=Get-PendingUpdateRun $legacyRunRoot
        $usesNetworkOutput=@($config.networkPlugins['destinations'] | Where-Object { $_ -and $_['deployDirectory'] }).Count -gt 0
        if ($usesNetworkOutput -and $Phase -eq 'Workflow' -and $configuredRunRoot -and !$pending) {
            throw 'Move the legacy maintenance.local.json to 16-maintenance using Move-LocalToolLayout.ps1, or set networkPlugins.sourceDirectory explicitly before starting a new workflow with a custom runRoot.'
        }
        if (!$usesNetworkOutput -or ($pending -and ($Phase -eq 'Plan' -or
            ($Phase -eq 'Workflow' -and $workflow.runRoot -ieq $pending.Root) -or
            ($RunDirectory -and [IO.Path]::GetFullPath($RunDirectory) -ieq $pending.Directory)))) {
            # Keep legacy Control reservations compatible when recovering or when no network artifact will be deployed.
            $config.networkPlugins['sourceDirectory']=Get-MaintenanceAbsolutePath (Join-Path $PSScriptRoot '../network-plugin-build/output')
        }
    }
    if ($Phase -ne 'Plan') {
        Import-Module (Join-Path $PSScriptRoot '../control-gate/Control.Gate.psm1') -Force
        $resume=$null
        if ($Phase -eq 'Workflow' -and (Get-ControlGateMode $config) -eq 'Managed') {
            $gateRunRoot=$workflow.runRoot
            $resume=Get-ControlGateResumeIdentity $gateRunRoot
        }
        $promptForDetails = $Phase -eq 'Workflow' -and !$hasDetail -and $WorldCopy -eq 'Ask' -and $NetworkPlugins -eq 'Ask' -and ![Console]::IsInputRedirected
        if ($Phase -eq 'Workflow' -and !$resume -and !(Get-PendingUpdateRun $workflow.runRoot)) {
            # A cancelled or invalid new selection should not create a Control reservation.
            if ($promptForDetails) { $distributionIds = @(Select-MaintenanceDistributionDetails $config) }
            if ($null -ne $distributionIds) {
                $null = Get-MaintenanceNonemptyPlan (Get-MaintenanceSelectedConfig $config $planChoice $networkChoice -DistributionIds $distributionIds)
            }
        }
        $resumeReference=if ($resume) { $resume.RunReference } elseif ($Phase -eq 'Workflow' -and (Get-ControlGateMode $config) -eq 'Managed') { New-ControlGateRunReference $gateRunRoot } else { $RunDirectory }
        $resumeWorkflowId=if ($resume) { $resume.WorkflowId } else { '' }
        # Server resource keys make Runner include the corresponding server root directories.
        $gatePaths=@()
        $gateServerIds=@($config.servers | Where-Object enabled | ForEach-Object { [string]$_.id })
        if ($config.migration.enabled) { $gateServerIds+=@($normalizedMigration.ServerIds) }
        if ($config.ContainsKey('networkPlugins') -and $config.networkPlugins) {
            foreach ($networkEntry in $config.networkPlugins.destinations) {
                if ([string]::IsNullOrWhiteSpace($networkEntry.deployDirectory)) { continue }
                $networkPath=Get-MaintenanceAbsolutePath $networkEntry.deployDirectory
                $gatePaths+=$networkPath
                if ((Get-ControlGateMode $config) -eq 'Managed') {
                    $networkBindings=@($config.controlGate.bindings.paths | Where-Object { [string]$_.path -ieq $networkPath })
                    if ($networkBindings.Count -ne 1 -or ![string]$networkBindings[0].runtimeServerId) { throw 'Managed network destination requires one path binding with runtimeServerId.' }
                    $gateServerIds+=[string]$networkBindings[0].runtimeServerId
                }
            }
            if ($config.networkPlugins['sourceDirectory']) { $gatePaths+=Get-MaintenanceAbsolutePath $config.networkPlugins['sourceDirectory'] }
            else { $gatePaths+=Get-MaintenanceAbsolutePath (Join-Path $PSScriptRoot '../12-build-network-plugins/output') }
        }
        $gateContext=Enter-ControlGate -Configuration $config -Operation '16' -RuntimeServerIds $gateServerIds `
            -AffectedPaths $gatePaths -RequiredState 'Stopped' -RunReference $resumeReference -WorkflowId $resumeWorkflowId `
            -ReservationId $(if ($resume) { $resume.ReservationId } else { '' }) -CompletedRun:([bool]($resume -and $resume.Completed)) `
            -InitialStep $(if ($resume) { 'Resume' } elseif ($Phase -eq 'Restore') { 'Restore' } else { 'Deploy' })
        if ($resume -and $resume.Completed) {
            $confirmed=Get-ControlGateResumeIdentity $gateRunRoot
            if (!$confirmed -or !$confirmed.Completed -or $confirmed.WorkflowId -cne $resume.WorkflowId -or
                $confirmed.ReservationId -cne $resume.ReservationId -or $confirmed.RunReference -cne $resume.RunReference) {
                throw 'Completed Control run changed during release recovery.'
            }
            Complete-ControlGate $gateContext $resume.RunReference
            Write-Host "Control release confirmed for completed run: $($resume.RunReference)"
            exit 0
        }
    }
    if ($Phase -eq 'Workflow') {
        . (Join-Path $PSScriptRoot 'UpdateWorkflow.ps1')
        if ($RunDirectory) { throw 'Workflow manages its own run directory. Use explicit phases only for advanced recovery.' }
        # All configured roots are protected; artifact checks run after selection.
        $networkRoots=@(Assert-MaintenanceNetworkRunBoundary $config $workflow.runRoot -PassThru)
        $roots=@($config.servers | Where-Object enabled | ForEach-Object { Get-MaintenanceAbsolutePath $_.rootPath })
        $roots+=$networkRoots
        $fingerprint=Get-SkillTreeMigrationSha256 ($configDigest + '|Channels')
        $entryScript=$PSCommandPath
        $pwsh=(Get-Process -Id $PID).Path
        $selection=@{choices=$null}
        $prepareRunAction={
            param($RunDirectory)
            $selectionLock=[IO.File]::Open((Join-Path $RunDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None')
            try {
                $selection.choices=Resolve-MaintenanceDeploymentSelection $RunDirectory $WorldCopy $NetworkPlugins -PromptForNewRun `
                    -ConfigurationDigest $configDigest -Config $config -DistributionIds $distributionIds -PromptForDetails:$promptForDetails -ValidatePlan
                Get-MaintenanceSelectionFingerprint $selection.choices
            } finally { $selectionLock.Dispose() }
        }
        $deployAction={
            param($RunDirectory)
            Assert-ControlGateStep $gateContext 'Deploy'
            Write-MaintenanceDiagnostic 'child.launch' @{phase='Deploy'}
            $details = @()
            if ($null -ne $selection.choices.distributionIds) {
                $details = @('-DistributionConfigurationDigest', $configDigest, '-ExpectedSelectionFingerprint', (Get-MaintenanceSelectionFingerprint $selection.choices))
            }
            & $pwsh -NoProfile -File $entryScript -Phase Deploy -ConfigPath $ConfigPath -RunDirectory $RunDirectory -WorldCopy $selection.choices.worldCopy -NetworkPlugins $selection.choices.networkPlugins -ServersStopped @details 2>&1 |
                Tee-Object -FilePath (Join-Path $RunDirectory 'distribution.log') -Append | Out-Host
            Write-MaintenanceDiagnostic 'child.exit' @{exitCode=$LASTEXITCODE}
            if ($LASTEXITCODE -ne 0) { throw "Distribution failed. Keep servers stopped and inspect $RunDirectory. Use Restore before retrying an incomplete deployment." }
        }
        $restoreAction={
            param($RunDirectory)
            Assert-ControlGateStep $gateContext 'Restore'
            & $pwsh -NoProfile -File $entryScript -Phase Restore -ConfigPath $ConfigPath -RunDirectory $RunDirectory -ServersStopped 2>&1 |
                Tee-Object -FilePath (Join-Path $RunDirectory 'recovery-restore.log') -Append | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "ファイル復旧に失敗しました。停止を維持し $RunDirectory の記録を確認してください。" }
        }
        $workflowResult=Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $config.migration -ConfigurationFingerprint $fingerprint `
            -ServerRoots $roots -DeployAction $deployAction -PrepareRunAction $prepareRunAction -ServersStopped:$ServersStopped -AdmissionClosed:$AdmissionClosed -Label 'Channels' `
            -RestoreAction $restoreAction -Recovery $Recovery -RecoveryChecked:$RecoveryChecked -GateContext $gateContext -PreferredRunDirectory $resumeReference
        if ($workflowResult.Status -cne 'COMPLETED') { throw 'Control gate retained: workflow did not reach completion.' }
        Complete-ControlGate $gateContext $workflowResult.RunDirectory
        exit 0
    }
    if ($Phase -eq 'Plan') {
        $config=Get-MaintenanceSelectedConfig $config $planChoice $networkChoice -DistributionIds $distributionIds
        Write-Host "World copy: $planChoice; Network plugins: $networkChoice (plan only)"
        $plan = @(Get-MaintenancePlan $config)
        foreach ($op in $plan) { Write-Host "$($op.kind): $($op.source) -> $($op.destination)" }
        Write-Host "Distribution targets: $($plan.Count)"
        Write-Host "Patch publication enabled: $($config.migration.enabled). Player data is updated at login; bulk migration is available only through 17-player-patch-migration.bat."
        if ($config.migration.enabled) {
            Write-Host "API endpoint: $($normalizedMigration.BaseUrl)"
            Write-Host "Private-IP-only TLS verification bypass: $($normalizedMigration.AllowPrivateApiInsecureTls)"
            if ($normalizedMigration.ApiSettingsPath) { Write-Host "Authentication source: API settings file $($normalizedMigration.ApiSettingsPath)" }
            else { Write-Host "Authentication source: environment variables $($normalizedMigration.ApiKeyEnvironmentVariable), $($normalizedMigration.MigrationKeyEnvironmentVariable)" }
        }
        Write-Host 'Plan only. No API calls, server writes, stop/start, or admission changes.'
        exit 0
    }
    if ($Phase -in @('Deploy','Restore') -and !$ServersStopped) { throw 'Stop affected source/destination servers and automatic writers first, then specify -ServersStopped.' }
    if (!$RunDirectory) { throw '-RunDirectory is required; reuse it for deployment recovery.' }
    $RunDirectory = Get-MaintenanceAbsolutePath $RunDirectory
    Assert-MaintenanceNetworkRunBoundary $config $RunDirectory
    # Keep run outputs separate from every configured server.
    foreach ($server in $config.servers) {
        if ($server.enabled) {
            $root = Get-MaintenanceAbsolutePath $server.rootPath
            if (Test-MaintenanceOverlap $RunDirectory $root) { throw 'Run directory overlaps a server root.' }
        }
    }
    if ($Phase -eq 'Restore' -and !(Test-Path -LiteralPath $RunDirectory -PathType Container)) { throw 'Restore requires an existing run.' }
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    $locks.Add([IO.File]::Open((Join-Path $RunDirectory 'run.lock'), 'OpenOrCreate', 'ReadWrite', 'None'))
    $maintenanceDiagnosticFile = New-MaintenanceDiagnosticLog $RunDirectory 'Entry'
    Write-MaintenanceDiagnostic 'entry.begin' @{phase=$Phase; powershell=$PSVersionTable.PSVersion.ToString()}
    $configRecordPath = Join-Path $RunDirectory 'config-digest.json'
    if (Test-Path -LiteralPath $configRecordPath) {
        $record = Get-Content -Raw -Encoding utf8 -LiteralPath $configRecordPath | ConvertFrom-Json
        if ($record.sha256 -ne $configDigest) { throw 'Configuration changed within this run. Restore the original configuration before retrying.' }
    }
    if ($Phase -eq 'Deploy') {
        $choices=Resolve-MaintenanceDeploymentSelection $RunDirectory $WorldCopy $NetworkPlugins -ConfigurationDigest $configDigest `
            -Config $config -DistributionIds $distributionIds -ExpectedFingerprint $ExpectedSelectionFingerprint -ValidatePlan
        $config=Get-MaintenanceSelectedConfig $config $choices.worldCopy $choices.networkPlugins -DistributionIds $choices.distributionIds
        $plan = @(Get-MaintenanceNonemptyPlan $config)
    }
    if (!(Test-Path -LiteralPath $configRecordPath)) { Write-MaintenanceJson @{ sha256=$configDigest } $configRecordPath }
    if ($Phase -in @('Deploy','Restore')) {
        # A server lock coordinates separate configs/run directories using this tool.
        $roots = if ($Phase -eq 'Deploy') {
            @($plan | ForEach-Object { $_.root }) + @($config.servers | Where-Object enabled | ForEach-Object { Get-MaintenanceAbsolutePath $_.rootPath })
        } else {
            $journal = Get-Content -Raw -Encoding utf8 -LiteralPath (Join-Path $RunDirectory 'deployment.json') | ConvertFrom-Json -AsHashtable
            @($journal.operations | ForEach-Object { Get-MaintenanceAbsolutePath $_.root })
        }
        foreach ($root in @($roots | Sort-Object -Unique)) {
            if (!(Test-Path -LiteralPath $root -PathType Container)) { throw "Missing server root: $root" }
            $lockPath = Resolve-MaintenanceChild $root '.astral-maintenance/distribution.lock'
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($lockPath)) -Force | Out-Null
            $locks.Add([IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None'))
            Write-MaintenanceDiagnostic 'server.lock.acquired' @{root=$root}
        }
    }
    switch ($Phase) {
        Deploy {
            Assert-ControlGateStep $gateContext 'Deploy'
            Invoke-MaintenanceDeploy $config $RunDirectory
            Write-Host 'Distribution complete. Keep admission closed. Finish API master updates before startup and patch publication.'
        }
        Restore {
            Assert-ControlGateStep $gateContext 'Restore'
            Restore-MaintenanceDeployment $RunDirectory
            Write-Host 'Files restored. Backups and displaced artifacts were retained. Check server state before opening admission.'
        }
    }
    Write-Host "Run records: $RunDirectory"
    Write-MaintenanceDiagnostic 'entry.completed' @{phase=$Phase}
    Complete-ControlGate $gateContext $RunDirectory
} catch {
    Write-MaintenanceDiagnostic 'entry.failed' -Failure $_
    # Do not print exception bodies from HTTP calls; migration credentials may be present there.
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
} finally {
    Write-MaintenanceDiagnostic 'entry.finally' @{phase=$Phase}
    foreach ($lock in $locks) { $lock.Dispose() }
}
