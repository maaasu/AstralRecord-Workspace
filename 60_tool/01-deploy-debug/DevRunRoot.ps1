function Resolve-DevRunRoot {
    param(
        [Parameter(Mandatory)][string]$ToolRoot,
        [Parameter(Mandatory)][string]$ConfigPath,
        [string]$ConfiguredRunRoot,
        [Parameter(Mandatory)][ValidateSet('Full','PluginOnly','MasterDataOnly')][string]$Mode
    )

    $toolDirectories = [ordered]@{
        Full = '01-deploy-debug'
        PluginOnly = '02-deploy-debug-plugin-only'
        MasterDataOnly = '03-master-data-reload'
    }
    $runRoots = [ordered]@{}
    if ($ConfiguredRunRoot) {
        $ConfiguredRunRoot = Get-MaintenanceAbsolutePath $ConfiguredRunRoot
        $previousRoots = @($ConfiguredRunRoot)
        foreach ($key in $toolDirectories.Keys) {
            $runRoots[$key] = Join-Path $ConfiguredRunRoot $toolDirectories[$key]
        }
    } else {
        $previousRoots = @(
            (Join-Path $ToolRoot 'deploy-debug/runs')
            (Join-Path (Split-Path -Parent $ConfigPath) 'runs')
        )
        foreach ($key in $toolDirectories.Keys) {
            $runRoots[$key] = Join-Path $ToolRoot (Join-Path $toolDirectories[$key] 'runs')
        }
    }
    $target = Get-MaintenanceAbsolutePath $runRoots[$Mode]
    # A run may still be pinned to its original absolute path by Control or its journal.
    $pending = @($runRoots.Values; $previousRoots) | Select-Object -Unique | ForEach-Object { Get-PendingUpdateRun $_ }
    if (@($pending).Count -gt 1) {
        throw "Multiple Dev update runs require recovery: $($pending.Directory -join ', ')"
    }
    if (!$pending) { return $target }
    $adapter = $toolDirectories[$Mode].Substring(0, 2)
    if (($pending.AdapterId -and $pending.AdapterId -cne $adapter) -or
        ($pending.Root -ine $target -and $pending.Root -iin $runRoots.Values)) {
        throw "The previous Dev update must be recovered using its original operation: $($pending.Directory)"
    }
    return $pending.Root
}
