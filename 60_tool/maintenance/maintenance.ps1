#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Workflow','Plan','Deploy','MigratePreview','MigrateCommit','Restore')]
    [string]$Phase = 'Workflow',
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'maintenance.local.json'),
    [string]$RunDirectory,
    [switch]$ServersStopped,
    [switch]$AdmissionClosed
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Distribution.ps1')
$locks = [Collections.Generic.List[IDisposable]]::new()
try {
    if (!(Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'Create maintenance.local.json from maintenance.example.json and configure paths first.' }
    $config = Get-Content -Raw -Encoding utf8 -LiteralPath $ConfigPath | ConvertFrom-Json -AsHashtable
    if ($config.schemaVersion -ne 1) { throw 'Unsupported schemaVersion.' }
    if ($config.migration.enabled -isnot [bool]) { throw 'Migration enabled must be boolean.' }
    if ($config.migration.enabled) {
        . (Join-Path $PSScriptRoot 'SkillTreeMigration.ps1')
        $normalizedMigration = ConvertTo-SkillTreeMigrationConfig $config.migration
    }
    if ($Phase -eq 'Workflow') {
        if (!$config.ContainsKey('workflow') -or !$config.migration.enabled) { throw 'Configure workflow and enable migration before using the guided maintenance workflow.' }
        . (Join-Path $PSScriptRoot 'UpdateWorkflow.ps1')
        $ConfigPath=(Resolve-Path -LiteralPath $ConfigPath).Path
        $workflow=$config.workflow
        if (!$workflow.runRoot) { $workflow.runRoot=Join-Path ([IO.Path]::GetDirectoryName($ConfigPath)) 'runs' }
        $workflow.runRoot=Get-MaintenanceAbsolutePath $workflow.runRoot
        if ($RunDirectory) { throw 'Workflow manages its own run directory. Use explicit phases only for advanced recovery.' }
        $plan=@(Get-MaintenancePlan $config)
        $roots=@($config.servers | Where-Object enabled | ForEach-Object { Get-MaintenanceAbsolutePath $_.rootPath })
        $roots+=@($plan | ForEach-Object { $_.root; if ($_.directory) { $_.source } else { [IO.Path]::GetDirectoryName($_.source) } })
        $fingerprint=Get-SkillTreeMigrationSha256 ((Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash + '|Channels')
        $entryScript=$PSCommandPath
        $pwsh=(Get-Process -Id $PID).Path
        $deployAction={
            param($RunDirectory)
            & $pwsh -NoProfile -File $entryScript -Phase Deploy -ConfigPath $ConfigPath -RunDirectory $RunDirectory -ServersStopped 2>&1 |
                Tee-Object -FilePath (Join-Path $RunDirectory 'distribution.log') | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "Distribution failed. Keep servers stopped and inspect $RunDirectory. Use Restore before retrying an incomplete deployment." }
        }
        Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $config.migration -ConfigurationFingerprint $fingerprint `
            -ServerRoots $roots -DeployAction $deployAction -ServersStopped:$ServersStopped -AdmissionClosed:$AdmissionClosed -Label 'Channels'
        exit 0
    }
    if ($Phase -eq 'Plan') {
        $plan = @(Get-MaintenancePlan $config)
        foreach ($op in $plan) { Write-Host "$($op.kind): $($op.source) -> $($op.destination)" }
        Write-Host "Distribution targets: $($plan.Count)"
        Write-Host "Migration enabled: $($config.migration.enabled); scope: $($config.migration.scope)"
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
    if ($Phase -in @('MigratePreview','MigrateCommit') -and !$AdmissionClosed) { throw 'Keep players offline and admission closed, then specify -AdmissionClosed.' }
    if (!$RunDirectory) { throw '-RunDirectory is required; reuse it for the same release and migration retries.' }
    $RunDirectory = Get-MaintenanceAbsolutePath $RunDirectory
    # Keep run outputs separate from every configured server, including migration-only runs.
    foreach ($server in $config.servers) {
        if ($server.enabled) {
            $root = Get-MaintenanceAbsolutePath $server.rootPath
            if (Test-MaintenanceOverlap $RunDirectory $root) { throw 'Run directory overlaps a server root.' }
        }
    }
    if ($Phase -eq 'Restore' -and !(Test-Path -LiteralPath $RunDirectory -PathType Container)) { throw 'Restore requires an existing run.' }
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    $locks.Add([IO.File]::Open((Join-Path $RunDirectory 'run.lock'), 'OpenOrCreate', 'ReadWrite', 'None'))
    $configDigest = (Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash
    $configRecordPath = Join-Path $RunDirectory 'config-digest.json'
    if (Test-Path -LiteralPath $configRecordPath) {
        $record = Get-Content -Raw -Encoding utf8 -LiteralPath $configRecordPath | ConvertFrom-Json
        if ($record.sha256 -ne $configDigest) { throw 'Configuration changed within this run. Restore the original configuration before retrying.' }
    } else {
        Write-MaintenanceJson @{ sha256=$configDigest } $configRecordPath
    }
    if ($Phase -in @('Deploy','Restore')) {
        # A server lock coordinates separate configs/run directories using this tool.
        $roots = if ($Phase -eq 'Deploy') {
            $plan = @(Get-MaintenancePlan $config)
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
        }
    }
    switch ($Phase) {
        Deploy {
            Invoke-MaintenanceDeploy $config $RunDirectory
            Write-Host 'Distribution complete. Keep admission closed. Finish API master updates before startup and migration.'
        }
        Restore {
            Restore-MaintenanceDeployment $RunDirectory
            Write-Host 'Files restored. Backups and displaced artifacts were retained. Check server state before opening admission.'
        }
        default {
            if (!$config.migration.enabled) { throw 'Migration is disabled in this configuration.' }
            $deployment = Join-Path $RunDirectory 'deployment.json'
            if (Test-Path -LiteralPath $deployment) {
                $journal = Get-Content -Raw -Encoding utf8 -LiteralPath $deployment | ConvertFrom-Json -AsHashtable
                if ($journal.status -ne 'Deployed') { throw 'Distribution is incomplete or restored; migration is blocked.' }
            }
            if ($Phase -eq 'MigrateCommit') {
                Write-MaintenanceJson @{ startedAtUtc=[DateTime]::UtcNow.ToString('o') } (Join-Path $RunDirectory 'migration-commit-started.json')
            }
            . (Join-Path $PSScriptRoot 'SkillTreeMigration.ps1')
            Invoke-SkillTreeMigration -Config $config.migration -RunDirectory $RunDirectory -Commit:($Phase -eq 'MigrateCommit')
        }
    }
    Write-Host "Run records: $RunDirectory"
} catch {
    # Do not print exception bodies from HTTP calls; migration credentials may be present there.
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
} finally {
    foreach ($lock in $locks) { $lock.Dispose() }
}
