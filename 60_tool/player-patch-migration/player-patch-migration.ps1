#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Preview','Commit')][string]$Phase='Preview',
    [string]$ConfigPath=(Join-Path $PSScriptRoot 'player-patch-migration.local.json'),
    [string]$RunDirectory,
    [switch]$AdmissionClosed
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../maintenance/Distribution.ps1')
. (Join-Path $PSScriptRoot '../maintenance/SkillTreeMigration.ps1')
$runLock=$null
$maintenanceDiagnosticFile=$null
try {
    if (!$AdmissionClosed) { throw 'Keep players offline and admission closed, then specify -AdmissionClosed. The target servers must remain running and ready.' }
    if (!$RunDirectory) { throw '-RunDirectory is required. Reuse the same directory for Preview, Commit, and retries.' }
    if (!(Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'Create player-patch-migration.local.json from the example, or pass an existing maintenance/deploy-debug config with -ConfigPath.' }
    $ConfigPath=(Resolve-Path -LiteralPath $ConfigPath).Path
    $config=Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json -AsHashtable
    if ($config.ContainsKey('devWorkflow')) { $migration=$config.devWorkflow.migration }
    else {
        if (!$config.ContainsKey('schemaVersion') -or $config.schemaVersion -ne 1) { throw 'Unsupported schemaVersion.' }
        $migration=$config.migration
    }
    $normalized=ConvertTo-SkillTreeMigrationConfig $migration
    if (!$normalized.Enabled) { throw 'Migration is disabled in this configuration.' }
    $RunDirectory=Get-MaintenanceAbsolutePath $RunDirectory
    $protectedRoots=@()
    if ($config.ContainsKey('servers')) {
        $protectedRoots+=@($config.servers | Where-Object enabled | ForEach-Object { Get-MaintenanceAbsolutePath $_.rootPath })
        Assert-MaintenanceNetworkRunBoundary $config $RunDirectory
    }
    foreach ($name in @('plugin','api','web','fileDatabase')) {
        if ($config.ContainsKey($name) -and $config[$name].enabled) {
            $protectedRoots+=Get-MaintenanceAbsolutePath $config[$name].deployPath
        }
    }
    if ($config.ContainsKey('fileDatabase')) { $protectedRoots+=Get-MaintenanceAbsolutePath $config.fileDatabase.sourcePath }
    foreach ($root in $protectedRoots) {
        if (Test-MaintenanceOverlap $RunDirectory $root) { throw 'Run directory overlaps server, source, or deployment data.' }
    }
    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    $runLock=[IO.File]::Open((Join-Path $RunDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None')
    $maintenanceDiagnosticFile=New-MaintenanceDiagnosticLog $RunDirectory 'PlayerPatchMigration'
    Write-MaintenanceDiagnostic 'player-migration.begin' @{phase=$Phase}
    $digest=(Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash
    $digestPath=Join-Path $RunDirectory 'config-digest.json'
    if (Test-Path -LiteralPath $digestPath) {
        $saved=Get-Content -Raw -Encoding UTF8 -LiteralPath $digestPath | ConvertFrom-Json
        if ($saved.sha256 -cne $digest) { throw 'Configuration changed within this run. Restore the original configuration before retrying.' }
    } else { Write-MaintenanceJson @{sha256=$digest} $digestPath }
    $deploymentPath=Join-Path $RunDirectory 'deployment.json'
    if (Test-Path -LiteralPath $deploymentPath) {
        $deployment=Get-Content -Raw -Encoding UTF8 -LiteralPath $deploymentPath | ConvertFrom-Json
        if ($deployment.status -cne 'Deployed') { throw 'Distribution is incomplete or restored; migration is blocked.' }
    }
    if ($Phase -eq 'Commit') {
        $markerPath=Join-Path $RunDirectory 'migration-commit-started.json'
        if (!(Test-Path -LiteralPath $markerPath)) { Write-MaintenanceJson @{startedAtUtc=[DateTime]::UtcNow.ToString('o')} $markerPath }
        Write-Host 'プレイヤーの一括更新を実行します。完了までログアウトと入場制限を維持してください。'
    } else { Write-Host 'プレイヤー一括更新の事前確認を行います。プレイヤーデータは書き換えません。' }
    $result=Invoke-SkillTreeMigration -Config $migration -RunDirectory $RunDirectory -Commit:($Phase -eq 'Commit')
    $result
    Write-Host "Run records: $RunDirectory"
    Write-MaintenanceDiagnostic 'player-migration.completed' @{phase=$Phase;status=$result.Status}
} catch {
    Write-MaintenanceDiagnostic 'player-migration.failed' -Failure $_
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
} finally {
    if ($runLock) { $runLock.Dispose() }
}
