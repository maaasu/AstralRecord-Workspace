#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot '..\Install-ControlTools.ps1'
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixture = Join-Path $tempParent ('control-tools-install-test-' + [Guid]::NewGuid().ToString('N'))
$priorProgramData = $env:ProgramData

function Assert-Throws([scriptblock]$Action, [string]$Expected) {
    try { & $Action | Out-Null; throw "Expected failure was accepted: $Expected" }
    catch {
        if ($_.Exception.Message -like 'Expected failure was accepted:*') { throw }
        if ($_.Exception.Message -notlike "*$Expected*") { throw "Expected $Expected, got $($_.Exception.Message)" }
    }
}
function Write-Text([string]$Path, [string]$Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path,$Value,[Text.UTF8Encoding]::new($false))
}
function Write-Manifest([string]$Root) {
    $source = Join-Path $Root 'Tools\60_tool'
    $entries = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse) {
        $relative = $file.FullName.Substring($source.Length + 1).Replace('\','/')
        $entries['Tools/60_tool/' + $relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $entries['Cli/ignored.dll'] = '0' * 64
    Write-Text (Join-Path $Root 'files.sha256.json') ($entries | ConvertTo-Json -Depth 4)
    return (Get-FileHash -LiteralPath (Join-Path $Root 'files.sha256.json') -Algorithm SHA256).Hash
}

try {
    [IO.Directory]::CreateDirectory($fixture) | Out-Null
    $env:ProgramData = Join-Path $fixture 'program-data'
    $package = Join-Path $fixture 'release'
    $source = Join-Path $package 'Tools\60_tool'
    $root = Join-Path $fixture 'installed\Tools\60_tool'
    [IO.Directory]::CreateDirectory($source) | Out-Null
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $marker = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    $required = @('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    foreach ($file in $required) {
        Write-Text (Join-Path $source $file) "Write-Output 'new $file'"
        Write-Text (Join-Path $root $file) "Write-Output 'old $file'"
    }
    Write-Text (Join-Path $source 'new-feature\entry.ps1') "Write-Output 'new feature'"
    Write-Text (Join-Path $root 'deployment.local.json') '{"privateFixture":"preserved"}'
    Write-Text $marker (@{schemaVersion=1;toolRoot=$root;hashes=@{}} | ConvertTo-Json -Depth 5)
    $markerHash = (Get-FileHash -LiteralPath $marker -Algorithm SHA256).Hash
    $localHash = (Get-FileHash -LiteralPath (Join-Path $root 'deployment.local.json') -Algorithm SHA256).Hash
    $manifestHash = Write-Manifest $package

    $tokens = $null; $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($installer,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'Installer parse failed.' }
    foreach ($function in $ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)) {
        Invoke-Expression $function.Extent.Text
    }
    # The installer runs elevated. This fixture runs as a standard user and keeps its backup readable for assertions/cleanup.
    function Protect-ToolsBackup([string]$Path) { $null = $Path }
    Assert-Throws { Get-ControlToolsPlan $package $root ('0'*64) $marker } 'ReleaseManifestHashMismatch'
    Assert-Throws { Get-ControlToolsPlan $package $source $manifestHash $marker } 'PackageAndToolRootOverlap'
    Assert-Throws { Assert-LocalToolsPath 'C:\' } 'DriveRootRefused'
    Assert-Throws { Assert-LocalToolsPath '\\server\share\tools' } 'LocalAbsolutePathRequired'
    $junction = Join-Path $fixture 'junction'
    New-Item -ItemType Junction -Path $junction -Target $root | Out-Null
    Assert-Throws { Assert-LocalToolsPath $junction } 'ReparsePathRefused'
    Remove-Item -LiteralPath $junction -Force
    Assert-Throws { ConvertTo-ToolsRelativePath 'Tools/60_tool/../secrets.json' } 'UnsafeToolManifestPath'
    Assert-Throws { ConvertTo-ToolsRelativePath 'Tools/60_tool/config.local.json' } 'PrivateToolFileRefused'
    $badMarker = Join-Path $fixture 'wrong-marker.json'
    Write-Text $badMarker (@{schemaVersion=1;toolRoot=(Join-Path $fixture 'other')} | ConvertTo-Json)
    Assert-Throws { Get-ControlToolsPlan $package $root $manifestHash $badMarker } 'ManagedMarkerRootMismatch'

    & $installer -PackageDirectory $package -ToolRoot $root -ExpectedManifestSha256 $manifestHash -WhatIf | Out-Null
    if ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root $required[0])) -notlike '*old*' -or
        (Test-Path -LiteralPath (Join-Path $root 'new-feature\entry.ps1'))) { throw 'WhatIf changed the Tool root.' }

    $plan = Get-ControlToolsPlan $package $root $manifestHash $marker
    $expectedCount = $required.Count + 1
    if (@($plan.Entries | Where-Object { $_.OldHash -cne $_.NewHash }).Count -ne $expectedCount) { throw 'Incorrect update set.' }
    $result = Invoke-ControlToolsInstall $plan
    if ($result.Status -cne 'Installed' -or $result.Changed -ne $expectedCount -or
        !(Test-Path -LiteralPath (Join-Path $root 'new-feature\entry.ps1') -PathType Leaf)) { throw 'Tool update did not complete.' }
    if ((Get-FileHash -LiteralPath $marker -Algorithm SHA256).Hash -cne $markerHash -or
        (Get-FileHash -LiteralPath (Join-Path $root 'deployment.local.json') -Algorithm SHA256).Hash -cne $localHash) { throw 'Marker or local override changed.' }
    $savedFiles = @(Get-ChildItem -LiteralPath $result.Backup -File -Recurse | Where-Object Name -ne 'changes.json')
    if ($savedFiles.Count -ne $required.Count) { throw 'Backup was not limited to replaced files.' }
    if ((Invoke-ControlToolsInstall (Get-ControlToolsPlan $package $root $manifestHash $marker)).Status -cne 'UpToDate') { throw 'Second run was not idempotent.' }

    # Change two targets back, then corrupt the later source after preflight. The first replacement must roll back.
    Write-Text (Join-Path $root $required[0]) "Write-Output 'old again'"
    Remove-Item -LiteralPath (Join-Path $root 'new-feature\entry.ps1')
    $plan = Get-ControlToolsPlan $package $root $manifestHash $marker
    $before = (Get-FileHash -LiteralPath (Join-Path $root $required[0]) -Algorithm SHA256).Hash
    Write-Text (Join-Path $source 'new-feature\entry.ps1') "Write-Output 'corrupted after plan'"
    Assert-Throws { Invoke-ControlToolsInstall $plan } 'ToolUpdateRolledBack'
    if ((Get-FileHash -LiteralPath (Join-Path $root $required[0]) -Algorithm SHA256).Hash -cne $before -or
        (Test-Path -LiteralPath (Join-Path $root 'new-feature\entry.ps1'))) { throw 'Rollback did not restore the target.' }
    if ((Get-FileHash -LiteralPath $marker -Algorithm SHA256).Hash -cne $markerHash -or
        (Get-FileHash -LiteralPath (Join-Path $root 'deployment.local.json') -Algorithm SHA256).Hash -cne $localHash) { throw 'Rollback changed protected extra files.' }
    Assert-Throws { Get-ControlToolsPlan $package $root $manifestHash $marker } 'ToolPackageHashMismatch'
    Write-Output 'PASS: pinned Tool manifest, root binding, WhatIf, limited backup, nonmirror install, idempotency and rollback.'
} finally {
    $env:ProgramData = $priorProgramData
    $resolved = [IO.Path]::GetFullPath($fixture)
    if ([IO.Path]::GetDirectoryName($resolved) -ine $tempParent -or
        [IO.Path]::GetFileName($resolved) -notmatch '^control-tools-install-test-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
