#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '16-maintenance/Distribution.ps1')
. (Join-Path $PSScriptRoot '16-maintenance/WorkflowRunRoot.ps1')

$toolRoot = Get-MaintenanceAbsolutePath $PSScriptRoot
$directories = [ordered]@{
    'deploy-debug' = '01-deploy-debug'
    'db-rebuild' = '04-db-rebuild'
    'status-catalog-codegen' = '07-generate-status-types'
    'tag-catalog-codegen' = '08-generate-tag-types'
    'astralarchitect-deploy' = '09-astralarchitect-build-deploy'
    'db-reset-except-release-notes' = '11-db-reset-except-release-notes'
    'network-plugin-build' = '12-build-network-plugins'
    'db-migrate' = '13-db-migrate'
    'management-db-migrate' = '14-management-db-migrate'
    'maintenance' = '16-maintenance'
    'player-patch-migration' = '17-player-patch-migration'
}

function Convert-WorkspaceToolPath([string]$Value) {
    $normalized = $Value.Replace('\', '/')
    $absoluteToolPrefix = $toolRoot.Replace('\', '/') + '/'
    $prefix = if ($normalized.StartsWith($absoluteToolPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        $absoluteToolPrefix
    } elseif ($normalized.StartsWith('60_tool/', [StringComparison]::OrdinalIgnoreCase)) { '60_tool/' }
    if (!$prefix) { return $Value }

    $relative = $normalized.Substring($prefix.Length)
    $directory, $rest = $relative -split '/', 2
    $knownToolPath = switch ($directory) {
        'db-migrate' { $rest -match '^(DbMigrateTool\.csproj|(history-)?db-migrate\.(config|local)\.json)$' }
        'management-db-migrate' { $rest -match '^(ManagementDbMigrateTool\.csproj|management-db-migrate\.(config|local)\.json)$' }
        'network-plugin-build' { $rest -match '^output(/|$)' }
        default { $false }
    }
    if (!$knownToolPath) { return $Value }
    $replacement = if ($directory -ieq 'db-migrate' -and $rest -match '^history-db-migrate\.(config|local)\.json$') {
        '15-history-db-migrate'
    } else { $directories[$directory] }
    $updated = $prefix + $replacement + '/' + $rest
    if ($Value.Contains('\')) { return $updated.Replace('/', '\') }
    return $updated
}

function Update-WorkspaceToolPaths($Settings) {
    $updated = $false
    if ($Settings -is [System.Collections.IDictionary]) {
        foreach ($name in @($Settings.Keys)) {
            # Keep the registered root and derive its relative path only when the bound path moves.
            if ($name -in @('accessRoot','relativePath')) { continue }
            $value = $Settings[$name]
            if ($value -is [string]) {
                $mapped = Convert-WorkspaceToolPath $value
                if ($mapped -cne $value) { $Settings[$name] = $mapped; $updated = $true }
            } elseif (Update-WorkspaceToolPaths $value) { $updated = $true }
        }
    } elseif ($Settings -is [array]) {
        foreach ($item in $Settings) {
            if (Update-WorkspaceToolPaths $item) { $updated = $true }
        }
    }
    return $updated
}

$moves = [Collections.Generic.List[object]]::new()
function Add-LayoutMove([string]$Old, [string]$New, [switch]$Configuration) {
    $source = Get-MaintenanceAbsolutePath (Join-Path $toolRoot $Old)
    $destination = Get-MaintenanceAbsolutePath (Join-Path $toolRoot $New)
    if (!(Test-Path -LiteralPath $source)) { return }
    $toolPrefix = $toolRoot + [IO.Path]::DirectorySeparatorChar
    if (!$source.StartsWith($toolPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        !$destination.StartsWith($toolPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Tool migration path is outside 60_tool.' }

    $text = $null
    $archive = $null
    if ($Configuration) {
        $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath $source | ConvertFrom-Json -AsHashtable
        $bindings = if ($settings['controlGate'] -and $settings.controlGate['bindings']) {
            @($settings.controlGate.bindings['paths']) | Where-Object { $_ -and $_['path'] -and $_['accessRoot'] }
        }
        $movedBindings = @($bindings | Where-Object { (Convert-WorkspaceToolPath $_.path) -cne $_.path })
        if (Update-WorkspaceToolPaths $settings) {
            foreach ($binding in $movedBindings) {
                $relativePath = [IO.Path]::GetRelativePath($binding.accessRoot, $binding.path).Replace('\', '/')
                if ($relativePath -in @('.', '..') -or $relativePath.StartsWith('../') -or [IO.Path]::IsPathRooted($relativePath)) {
                    throw "Moving the configured tool path would escape its Control accessRoot: $($binding.path)"
                }
                $binding.relativePath = $relativePath
            }
            $text = ($settings | ConvertTo-Json -Depth 100) + [Environment]::NewLine
            $archive = Get-MaintenanceAbsolutePath (Join-Path (Split-Path -Parent $destination) "legacy-settings/$([IO.Path]::GetFileName($source))")
            if (Test-Path -LiteralPath $archive) { throw "Archive destination already exists: $archive" }
        }
    }
    if ((Test-Path -LiteralPath $destination) -and
        (!$text -or ![IO.File]::Exists($destination) -or [IO.File]::ReadAllText($destination) -cne $text)) {
        throw "Migration destination already exists: $destination"
    }
    $moves.Add([pscustomobject]@{ Source=$source; Destination=$destination; Text=$text; Archive=$archive })
}

foreach ($pair in @(
    @{ Directory='deploy-debug'; Configuration='deploy-debug.local.json'; Workflow='devWorkflow' },
    @{ Directory='maintenance'; Configuration='maintenance.local.json'; Workflow='workflow' }
)) {
    $oldDirectory = Join-Path $toolRoot $pair.Directory
    $oldRunRoot = Join-Path $oldDirectory 'runs'
    $checkRoots = @($oldRunRoot)
    $oldConfig = Join-Path $oldDirectory $pair.Configuration
    if (Test-Path -LiteralPath $oldConfig -PathType Leaf) {
        Assert-MaintenanceNoLinks $oldConfig
        $workflow = (Get-Content -Raw -Encoding UTF8 -LiteralPath $oldConfig | ConvertFrom-Json -AsHashtable)[$pair.Workflow]
        if ($workflow -and $workflow['runRoot']) { $checkRoots += $workflow.runRoot }
    }
    foreach ($runRoot in $checkRoots | Select-Object -Unique) {
        $pending = Get-PendingUpdateRun $runRoot
        if ($pending) { throw "Complete or release the existing workflow before moving its local settings: $($pending.Directory)" }
        $lockPath = Join-Path $runRoot 'workflow.lock'
        if (Test-Path -LiteralPath $lockPath -PathType Leaf) {
            Assert-MaintenanceNoLinks $lockPath
            [IO.File]::Open($lockPath, 'Open', 'ReadWrite', 'None').Dispose()
        }
    }
    Add-LayoutMove "$($pair.Directory)/runs" "$($directories[$pair.Directory])/legacy-runs"
}

foreach ($old in $directories.Keys) {
    $directory = Join-Path $toolRoot $old
    Assert-MaintenanceNoLinks $directory
    if (!(Test-Path -LiteralPath $directory -PathType Container)) { continue }
    foreach ($config in Get-ChildItem -LiteralPath $directory -File -Filter '*.local.json' -Force) {
        $new = if ($old -eq 'db-migrate' -and $config.Name -eq 'history-db-migrate.local.json') {
            '15-history-db-migrate'
        } else { $directories[$old] }
        Add-LayoutMove "$old/$($config.Name)" "$new/$($config.Name)" -Configuration
    }
    foreach ($artifact in @('bin', 'obj')) {
        Add-LayoutMove "$old/$artifact" "$($directories[$old])/legacy-build/$artifact"
    }
}
Add-LayoutMove 'network-plugin-build/output' '12-build-network-plugins/output'

foreach ($move in $moves) {
    if (!$PSCmdlet.ShouldProcess($move.Source, "Move local data to $($move.Destination)")) { continue }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $move.Destination)) | Out-Null
    if ($move.Archive) {
        [IO.Directory]::CreateDirectory((Split-Path -Parent $move.Archive)) | Out-Null
        if (![IO.File]::Exists($move.Destination)) {
            $temporary = "$($move.Destination).$([Guid]::NewGuid().ToString('N')).tmp"
            [IO.File]::WriteAllText($temporary, $move.Text)
            [IO.File]::Move($temporary, $move.Destination)
        }
        [IO.File]::Move($move.Source, $move.Archive)
    } elseif (Test-Path -LiteralPath $move.Source -PathType Container) {
        [IO.Directory]::Move($move.Source, $move.Destination)
    } else {
        [IO.File]::Move($move.Source, $move.Destination)
    }
    Write-Host "Moved $($move.Source) -> $($move.Destination)"
}

foreach ($old in $directories.Keys) {
    $directory = Get-MaintenanceAbsolutePath (Join-Path $toolRoot $old)
    if ((Test-Path -LiteralPath $directory -PathType Container) -and
        !(Get-ChildItem -LiteralPath $directory -Force | Select-Object -First 1) -and
        $PSCmdlet.ShouldProcess($directory, 'Remove empty legacy directory')) {
        [IO.Directory]::Delete($directory, $false)
    }
}
