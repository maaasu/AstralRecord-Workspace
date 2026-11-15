function Get-PendingUpdateRun {
    param([Parameter(Mandatory)][string]$RunRoot)

    $RunRoot = Get-MaintenanceAbsolutePath $RunRoot
    $activePath = Join-Path $RunRoot 'active-run.json'
    if (!(Test-Path -LiteralPath $activePath)) { return }
    Assert-MaintenanceNoLinks $activePath
    $active = Get-Content -Raw -Encoding UTF8 -LiteralPath $activePath | ConvertFrom-Json -AsHashtable
    if ($active['relativeRunDirectory'] -cnotmatch '^run-\d{8}T\d{6}Z-[a-f0-9]{32}$') {
        throw "Invalid active run reference: $activePath"
    }
    $run = Join-Path $RunRoot $active.relativeRunDirectory
    Assert-MaintenanceNoLinks $run
    if (!(Test-Path -LiteralPath $run -PathType Container)) { throw "Active run directory is missing: $run" }
    $identityPath = Join-Path $run 'control-gate-run.json'
    Assert-MaintenanceNoLinks $identityPath
    $identity = if (Test-Path -LiteralPath $identityPath -PathType Leaf) {
        Get-Content -Raw -Encoding UTF8 -LiteralPath $identityPath | ConvertFrom-Json -AsHashtable
    }
    $released = Join-Path $run 'control-gate-released.json'
    Assert-MaintenanceNoLinks $released
    if ($active['status'] -ceq 'Completed' -and (!$identity -or (Test-Path -LiteralPath $released -PathType Leaf))) { return }

    [pscustomobject]@{ Root = $RunRoot; Directory = $run; AdapterId = $(if ($identity) { $identity['adapterId'] }) }
}

function Resolve-MaintenanceWorkflowRunRoot {
    param(
        [Parameter(Mandatory)][string]$ToolRoot,
        [Parameter(Mandatory)][string]$ConfigPath,
        [string]$ConfiguredRunRoot
    )

    if ($ConfiguredRunRoot) { return Get-MaintenanceAbsolutePath $ConfiguredRunRoot }
    $defaultRoot = Join-Path $ToolRoot '16-maintenance/runs'
    $previousRoots = @(
        (Join-Path $ToolRoot 'maintenance/runs')
        (Join-Path (Split-Path -Parent $ConfigPath) 'runs')
    )
    $pending = @($defaultRoot; $previousRoots) | Select-Object -Unique | ForEach-Object { Get-PendingUpdateRun $_ }
    if (@($pending).Count -gt 1) {
        throw "Multiple maintenance runs require recovery: $($pending.Directory -join ', ')"
    }
    if ($pending) { return $pending.Root }
    return Get-MaintenanceAbsolutePath $defaultRoot
}
