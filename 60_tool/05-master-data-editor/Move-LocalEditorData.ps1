#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$toolRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$oldRoot = Join-Path $toolRoot 'skilltree-editor'
$newRoot = Join-Path $toolRoot '05-master-data-editor'
if ($PSScriptRoot -ine $newRoot) { throw 'Update the checkout to 05-master-data-editor before migrating local data.' }
if (!(Test-Path -LiteralPath $oldRoot)) { Write-Host 'Editor local data is already in the numbered layout.'; return }

function Assert-EditorLocalPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = $toolRoot + [IO.Path]::DirectorySeparatorChar
    if (!$full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Path escapes 60_tool: $full" }
    $cursor = $full
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -Force -LiteralPath $cursor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Links/junctions cannot be migrated: $cursor"
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}

Assert-EditorLocalPath $oldRoot
Assert-EditorLocalPath $newRoot
if (Test-Path -LiteralPath (Join-Path $oldRoot 'README.md')) {
    throw 'The old editor source is still present. Update the checkout before moving local data.'
}
# A worker outlives the app. Never move its status files while it can still write them.
$jobsRoot = Join-Path $oldRoot '.cache/tool-jobs'
if (Test-Path -LiteralPath $jobsRoot) {
    Assert-EditorLocalPath $jobsRoot
    foreach ($run in Get-ChildItem -Force -Directory -LiteralPath $jobsRoot) {
        Assert-EditorLocalPath $run.FullName
        $statePath = Join-Path $run.FullName 'state.json'
        if (!(Test-Path -LiteralPath $statePath)) { continue }
        Assert-EditorLocalPath $statePath
        $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json -AsHashtable
        if ($state['status'] -in @('starting', 'running', 'waitingForInput')) {
            throw "Finish the editor tool job before migrating: $($run.Name)"
        }
    }
}

$moves = [Collections.Generic.List[object]]::new()
$emptyDirectories = [Collections.Generic.List[string]]::new()
function Add-EditorMoves([string]$Source, [string]$Destination) {
    Assert-EditorLocalPath $Source
    Assert-EditorLocalPath $Destination
    $sourceItem = Get-Item -Force -LiteralPath $Source
    if ($sourceItem.PSIsContainer) {
        if ((Test-Path -LiteralPath $Destination) -and !(Test-Path -LiteralPath $Destination -PathType Container)) {
            throw "Migration destination already exists: $Destination"
        }
        if (!(Test-Path -LiteralPath $Destination)) {
            # PowerShell does not follow directory links without -FollowSymlink. Detect
            # them before moving an intact cache/build tree in one filesystem operation.
            $link = Get-ChildItem -Force -Recurse -LiteralPath $Source |
                Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } | Select-Object -First 1
            if ($link) { throw "Links/junctions cannot be migrated: $($link.FullName)" }
            $moves.Add([pscustomobject]@{ Source=$Source; Destination=$Destination; Directory=$true })
            return
        }
        $children = @(Get-ChildItem -Force -LiteralPath $Source)
        foreach ($child in $children) {
            Add-EditorMoves $child.FullName (Join-Path $Destination $child.Name)
        }
        $emptyDirectories.Add($Source)
    } else {
        if (Test-Path -LiteralPath $Destination) { throw "Migration destination already exists: $Destination" }
        $moves.Add([pscustomobject]@{ Source=$Source; Destination=$Destination; Directory=$false })
    }
}
Add-EditorMoves $oldRoot $newRoot

foreach ($move in $moves) {
    if ($PSCmdlet.ShouldProcess($move.Source, "Move local editor data to $($move.Destination)")) {
        [IO.Directory]::CreateDirectory((Split-Path -Parent $move.Destination)) | Out-Null
        if ($move.Directory) { [IO.Directory]::Move($move.Source, $move.Destination) }
        else { [IO.File]::Move($move.Source, $move.Destination) }
    }
}
foreach ($directory in $emptyDirectories) {
    Assert-EditorLocalPath $directory
    if (!(Get-ChildItem -Force -LiteralPath $directory | Select-Object -First 1) -and
        $PSCmdlet.ShouldProcess($directory, 'Remove empty old editor directory')) {
        [IO.Directory]::Delete($directory, $false)
    }
}
Write-Host "Editor local data destination: $newRoot"
