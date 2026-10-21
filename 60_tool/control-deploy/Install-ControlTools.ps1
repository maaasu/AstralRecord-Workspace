#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [Parameter(Mandatory)][string]$PackageDirectory,
    [Parameter(Mandatory)][string]$ToolRoot,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedManifestSha256
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-LocalToolsPath([string]$Path) {
    if (![IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'LocalAbsolutePathRequired' }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($full.Length -le 3) { throw 'DriveRootRefused' }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'ReparsePathRefused' }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
    return $full
}

function Assert-SeparateToolsPaths([string]$First, [string]$Second) {
    $a = (Assert-LocalToolsPath $First) + '\'
    $b = (Assert-LocalToolsPath $Second) + '\'
    if ($a.StartsWith($b, [StringComparison]::OrdinalIgnoreCase) -or
        $b.StartsWith($a, [StringComparison]::OrdinalIgnoreCase)) { throw 'PackageAndToolRootOverlap' }
}

function ConvertTo-ToolsRelativePath([string]$ManifestKey) {
    $prefix = 'Tools/60_tool/'
    if (!$ManifestKey.StartsWith($prefix, [StringComparison]::Ordinal)) { throw 'InvalidToolManifestPrefix' }
    $relative = $ManifestKey.Substring($prefix.Length)
    if (!$relative -or $relative.Contains('\') -or $relative.Contains('//') -or
        $relative -match '[\x00-\x1f<>:"|?*]') { throw 'UnsafeToolManifestPath' }
    foreach ($segment in $relative.Split('/')) {
        if (!$segment -or $segment -in @('.', '..') -or $segment.EndsWith('.') -or $segment.EndsWith(' ') -or
            $segment -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$') { throw 'UnsafeToolManifestPath' }
    }
    if ($relative -match '(?i)(^|/)(\.git|credentials|keys|99\.work)(/|$)' -or
        $relative -match '(?i)(^|/)(\.env(?:\..*)?|token\.bin|.*\.local\..*|.*\.secret\..*|.*secrets.*\.json|.*\.(?:key|pfx|p12|pem|jks))$') {
        throw 'PrivateToolFileRefused'
    }
    return $relative.Replace('/', '\')
}

function Assert-ToolsMarker([string]$Root, [string]$MarkerPath) {
    $marker = Assert-LocalToolsPath $MarkerPath
    if (!(Test-Path -LiteralPath $marker -PathType Leaf)) { throw 'ManagedMarkerRequired' }
    try { $value = Get-Content -Raw -Encoding UTF8 -LiteralPath $marker | ConvertFrom-Json -AsHashtable }
    catch { throw 'InvalidManagedMarker' }
    if ($value -isnot [Collections.IDictionary] -or $value['schemaVersion'] -ne 1 -or
        !($value['toolRoot'] -is [string]) -or
        (Assert-LocalToolsPath $value['toolRoot']) -ine $Root) { throw 'ManagedMarkerRootMismatch' }
    return (Get-FileHash -LiteralPath $marker -Algorithm SHA256).Hash
}

function Get-ControlToolsPlan([string]$Package, [string]$Root, [string]$ExpectedHash, [string]$MarkerPath) {
    $packageRoot = Assert-LocalToolsPath $Package
    $toolRoot = Assert-LocalToolsPath $Root
    Assert-SeparateToolsPaths $packageRoot $toolRoot
    if (!(Test-Path -LiteralPath $packageRoot -PathType Container) -or
        !(Test-Path -LiteralPath $toolRoot -PathType Container)) { throw 'PackageOrToolRootMissing' }
    $markerHash = Assert-ToolsMarker $toolRoot $MarkerPath
    $manifestPath = Assert-LocalToolsPath (Join-Path $packageRoot 'files.sha256.json')
    if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
        (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -ine $ExpectedHash) { throw 'ReleaseManifestHashMismatch' }
    try { $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json -AsHashtable }
    catch { throw 'InvalidReleaseManifest' }
    if ($manifest -isnot [Collections.IDictionary]) { throw 'InvalidReleaseManifest' }
    $sourceRoot = Assert-LocalToolsPath (Join-Path $packageRoot 'Tools\60_tool')
    if (!(Test-Path -LiteralPath $sourceRoot -PathType Container)) { throw 'ToolPackageMissing' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $entries = [Collections.Generic.List[object]]::new()
    foreach ($key in $manifest.Keys) {
        if (!$key.StartsWith('Tools/60_tool/', [StringComparison]::Ordinal)) { continue }
        $relative = ConvertTo-ToolsRelativePath $key
        if (!$seen.Add($relative)) { throw 'DuplicateToolManifestPath' }
        $hash = [string]$manifest[$key]
        if ($hash -cnotmatch '^[A-Fa-f0-9]{64}$') { throw 'InvalidToolManifestHash' }
        $source = Assert-LocalToolsPath (Join-Path $sourceRoot $relative)
        $target = Assert-LocalToolsPath (Join-Path $toolRoot $relative)
        if (!(Test-Path -LiteralPath $source -PathType Leaf) -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ine $hash) { throw 'ToolPackageHashMismatch' }
        if ((Test-Path -LiteralPath $target) -and !(Test-Path -LiteralPath $target -PathType Leaf)) { throw 'ToolTargetNotFile' }
        $originalHash = if (Test-Path -LiteralPath $target -PathType Leaf) { (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash } else { $null }
        $entries.Add([pscustomobject]@{Relative=$relative;Source=$source;Target=$target;
            NewHash=$hash.ToUpperInvariant();OldHash=$originalHash;OldAcl=if($originalHash){Get-Acl -LiteralPath $target}else{$null}})
    }
    if ($entries.Count -eq 0) { throw 'EmptyToolPackage' }
    foreach ($item in Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'ToolPackageReparsePoint' }
        if ($item.PSIsContainer) { continue }
        $relative = $item.FullName.Substring($sourceRoot.Length + 1)
        if (!$seen.Contains($relative)) { throw 'UnlistedToolPackageFile' }
    }
    $required = @('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    foreach ($file in $required) {
        if (!$seen.Contains($file) -or !(Test-Path -LiteralPath (Join-Path $toolRoot $file) -PathType Leaf)) { throw 'ManagedEntryMissing' }
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile((Join-Path $sourceRoot $file), [ref]$null, [ref]$errors) | Out-Null
        if ($errors.Count) { throw 'ManagedEntrySyntaxError' }
    }
    return [pscustomobject]@{Package=$packageRoot;Root=$toolRoot;ManifestHash=$ExpectedHash.ToUpperInvariant();
        MarkerPath=$MarkerPath;MarkerHash=$markerHash;Entries=@($entries.ToArray() | Sort-Object Relative)}
}

function Assert-ToolsPlanStillValid($Plan) {
    if ((Assert-ToolsMarker $Plan.Root $Plan.MarkerPath) -cne $Plan.MarkerHash) { throw 'ManagedMarkerChanged' }
    $manifest = Join-Path $Plan.Package 'files.sha256.json'
    if ((Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash -cne $Plan.ManifestHash) { throw 'ReleaseManifestChanged' }
}

function Protect-ToolsBackup([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleAll($rule) | Out-Null }
    $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $identity = [Security.Principal.SecurityIdentifier]::new($sid)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity,
            [Security.AccessControl.FileSystemRights]::FullControl,$inheritance,
            [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
    if (!(Get-Acl -LiteralPath $Path).AreAccessRulesProtected) { throw 'ToolBackupAclNotProtected' }
}

function Invoke-ControlToolsInstall($Plan) {
    $changes = @($Plan.Entries | Where-Object { $_.OldHash -cne $_.NewHash })
    if (!$changes.Count) { return [pscustomobject]@{Changed=0;Backup=$null;Status='UpToDate'} }
    $lockPath = Join-Path $Plan.Root '.control-tools-update.lock'
    $lock = $null
    $backup = Join-Path ([IO.Path]::GetDirectoryName($Plan.Root)) ([IO.Path]::GetFileName($Plan.Root) + '.control-tools-backup-' + [Guid]::NewGuid().ToString('N'))
    $touched = [Collections.Generic.List[object]]::new()
    $createdDirectories = [Collections.Generic.List[string]]::new()
    try {
        Assert-ToolsPlanStillValid $Plan
        $lock = [IO.FileStream]::new($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None,4096,[IO.FileOptions]::DeleteOnClose)
        $backup = Assert-LocalToolsPath $backup
        New-Item -ItemType Directory -Path $backup -ErrorAction Stop | Out-Null
        Protect-ToolsBackup $backup
        foreach ($entry in $changes) {
            if (!$entry.OldHash) { continue }
            $destination = Assert-LocalToolsPath (Join-Path $backup $entry.Relative)
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
            if ((Get-FileHash -LiteralPath $entry.Target -Algorithm SHA256).Hash -cne $entry.OldHash) { throw 'ToolTargetChanged' }
            [IO.File]::Copy($entry.Target,$destination,$false)
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne $entry.OldHash) { throw 'ToolBackupHashMismatch' }
        }
        @($changes | ForEach-Object { @{relative=$_.Relative;oldHash=$_.OldHash;newHash=$_.NewHash} }) |
            ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $backup 'changes.json')
        foreach ($entry in $changes) {
            Assert-ToolsPlanStillValid $Plan
            $source = Assert-LocalToolsPath $entry.Source
            $target = Assert-LocalToolsPath $entry.Target
            if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -cne $entry.NewHash) { throw 'ToolPackageChanged' }
            if ($entry.OldHash) {
                if (!(Test-Path -LiteralPath $target -PathType Leaf) -or
                    (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -cne $entry.OldHash) { throw 'ToolTargetChanged' }
            } elseif (Test-Path -LiteralPath $target) { throw 'ToolTargetAppeared' }
            $parent = [IO.Path]::GetDirectoryName($target)
            if (!(Test-Path -LiteralPath $parent -PathType Container)) {
                $missing = [Collections.Generic.List[string]]::new()
                for ($cursor=$parent; !(Test-Path -LiteralPath $cursor -PathType Container); $cursor=[IO.Path]::GetDirectoryName($cursor)) { $missing.Add($cursor) }
                for ($index=$missing.Count-1; $index -ge 0; $index--) {
                    [IO.Directory]::CreateDirectory($missing[$index]) | Out-Null
                    $createdDirectories.Add($missing[$index])
                }
            }
            $null = Assert-LocalToolsPath $parent
            $temporary = $target + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
            $replaceBackup = $target + '.' + [Guid]::NewGuid().ToString('N') + '.previous'
            try {
                [IO.File]::Copy($source,$temporary,$false)
                if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -cne $entry.NewHash) { throw 'ToolStageHashMismatch' }
                if ($entry.OldAcl) { Set-Acl -LiteralPath $temporary -AclObject $entry.OldAcl }
                $touched.Add($entry)
                if ($entry.OldHash) { [IO.File]::Replace($temporary,$target,$replaceBackup,$false) }
                else { [IO.File]::Move($temporary,$target) }
                if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -cne $entry.NewHash) { throw 'ToolInstalledHashMismatch' }
            } finally {
                if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
                if (Test-Path -LiteralPath $replaceBackup) { Remove-Item -LiteralPath $replaceBackup -Force }
            }
        }
        Assert-ToolsPlanStillValid $Plan
        return [pscustomobject]@{Changed=$changes.Count;Backup=$backup;Status='Installed'}
    } catch {
        $original = $_
        $rollbackErrors = [Collections.Generic.List[string]]::new()
        for ($index=$touched.Count-1; $index -ge 0; $index--) {
            $entry = $touched[$index]
            try {
                $target = Assert-LocalToolsPath $entry.Target
                if ($entry.OldHash) {
                    $saved = Assert-LocalToolsPath (Join-Path $backup $entry.Relative)
                    if ((Get-FileHash -LiteralPath $saved -Algorithm SHA256).Hash -cne $entry.OldHash) { throw 'ToolBackupChanged' }
                    $restore = $target + '.' + [Guid]::NewGuid().ToString('N') + '.restore'
                    $rollbackBackup = $target + '.' + [Guid]::NewGuid().ToString('N') + '.failed'
                    try {
                        [IO.File]::Copy($saved,$restore,$false)
                        Set-Acl -LiteralPath $restore -AclObject $entry.OldAcl
                        if (Test-Path -LiteralPath $target -PathType Leaf) { [IO.File]::Replace($restore,$target,$rollbackBackup,$false) }
                        elseif (!(Test-Path -LiteralPath $target)) { [IO.File]::Move($restore,$target) }
                        else { throw 'ToolRollbackTargetNotFile' }
                    } finally {
                        if (Test-Path -LiteralPath $restore) { Remove-Item -LiteralPath $restore -Force }
                        if (Test-Path -LiteralPath $rollbackBackup) { Remove-Item -LiteralPath $rollbackBackup -Force }
                    }
                    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -cne $entry.OldHash) { throw 'ToolRollbackHashMismatch' }
                } else {
                    if (Test-Path -LiteralPath $target -PathType Leaf) {
                        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -cne $entry.NewHash) { throw 'ToolRollbackTargetChanged' }
                        Remove-Item -LiteralPath $target -Force
                    } elseif (Test-Path -LiteralPath $target) { throw 'ToolRollbackTargetNotFile' }
                }
            } catch { $rollbackErrors.Add($entry.Relative + ': ' + $_.Exception.Message) }
        }
        for ($index=$createdDirectories.Count-1; $index -ge 0; $index--) {
            try {
                if (!(Get-ChildItem -LiteralPath $createdDirectories[$index] -Force)) {
                    Remove-Item -LiteralPath $createdDirectories[$index] -Force
                }
            } catch { $rollbackErrors.Add('Directory cleanup: ' + $_.Exception.Message) }
        }
        if ($rollbackErrors.Count) { throw "ToolUpdateRollbackIncomplete: $($rollbackErrors -join '; '). Initial failure: $($original.Exception.Message)" }
        throw "ToolUpdateRolledBack: $($original.Exception.Message)"
    } finally {
        if ($lock) { $lock.Dispose() }
    }
}

if (!$IsWindows) { throw 'WindowsRequired' }
$markerPath = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
$plan = Get-ControlToolsPlan $PackageDirectory $ToolRoot $ExpectedManifestSha256 $markerPath
$changes = @($plan.Entries | Where-Object { $_.OldHash -cne $_.NewHash }).Count
if (!$changes) { Write-Output 'Control Tools are already current.'; return }
if (!$PSCmdlet.ShouldProcess($plan.Root,"Update $changes sealed Tool files without changing the management marker")) { return }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (![Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'AdministratorRequired' }
$result = Invoke-ControlToolsInstall $plan
Write-Output "Control Tools update complete: $($result.Changed) files. Backup: $($result.Backup)"
