[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$PluginsDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-ChildPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Root,
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $childPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $childPath.StartsWith($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Deployment path is outside its expected root: $childPath"
    }
}

function Assert-RegularFileOrMissing {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $item = Get-Item -Force -LiteralPath $Path
    $isReparsePoint = ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
    if (-not ($item -is [System.IO.FileInfo]) -or $isReparsePoint) {
        throw "$Label must be a regular non-reparse file or be absent: $Path"
    }
}

function Assert-ReadableRegularFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    Assert-RegularFileOrMissing -Path $Path -Label $Label
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label does not exist: $Path"
    }

    $stream = $null
    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read)
    }
    finally {
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Resolve-PluginsDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if ($Path -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') {
        throw "PluginsDirectory must be an absolute drive or UNC path: $Path"
    }

    $resolvedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    $rootPath = [System.IO.Path]::GetPathRoot($resolvedPath).TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar)
    if ([string]::Equals($resolvedPath, $rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PluginsDirectory must not be a filesystem or share root: $resolvedPath"
    }

    $leafName = Split-Path -Leaf $resolvedPath
    if (-not [string]::Equals($leafName, 'plugins', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "PluginsDirectory must point to a directory named plugins: $resolvedPath"
    }
    if (-not (Test-Path -LiteralPath $resolvedPath -PathType Container)) {
        throw "Server plugins directory does not exist: $resolvedPath"
    }

    return $resolvedPath
}

function Enter-ExclusiveFileLock {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    Assert-RegularFileOrMissing -Path $Path -Label $Label
    try {
        return [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
    }
    catch [System.IO.IOException] {
        throw "$Label is already held by another build/deployment process: $Path"
    }
}

function Exit-ExclusiveFileLock {
    param(
        [System.IO.FileStream]$Lock,
        [string]$Path
    )

    if ($null -eq $Lock) {
        return
    }
    $Lock.Dispose()
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }

    try {
        Assert-RegularFileOrMissing -Path $Path -Label 'Lock file'
        if (Test-Path -LiteralPath $Path -PathType Leaf) {
            Remove-Item -LiteralPath $Path -Force
        }
    }
    catch {
        Write-Warning "Lock file could not be removed: $Path"
    }
}

$toolDirectory = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $toolDirectory
$projectDirectory = Join-Path $workspaceRoot '10_plugin\AstralArchitect'
$distDirectory = Join-Path $projectDirectory 'dist'
$configPath = Join-Path $PSScriptRoot 'astralarchitect-deploy.config.json'

if (-not (Test-Path -LiteralPath $projectDirectory -PathType Container)) {
    throw "AstralArchitect project directory does not exist: $projectDirectory"
}
if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory 'pom.xml') -PathType Leaf)) {
    throw "AstralArchitect pom.xml does not exist under: $projectDirectory"
}

$resolvedPluginsDirectory = $null
$destinationJar = $null
$jarStaging = $null
$jarBackup = $null
$deploymentLockPath = $null
$publishFailpointEnabled = [string]::Equals(
    $env:ASTRALARCHITECT_DEPLOY_TEST_FAIL_AFTER_BACKUP,
    '1',
    [System.StringComparison]::Ordinal)
if (-not $BuildOnly) {
    if ([string]::IsNullOrWhiteSpace($PluginsDirectory)) {
        if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
            throw "Deployment config does not exist: $configPath"
        }
        $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
        $PluginsDirectory = [string]$config.pluginsDirectory
    }
    if ([string]::IsNullOrWhiteSpace($PluginsDirectory)) {
        throw 'PluginsDirectory is not configured. Set it in astralarchitect-deploy.config.json or pass -PluginsDirectory.'
    }

    $resolvedPluginsDirectory = Resolve-PluginsDirectory -Path $PluginsDirectory
    $destinationJar = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar'
    $jarStaging = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar.new'
    $jarBackup = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar.old'
    $deploymentLockPath = Join-Path $resolvedPluginsDirectory '.astralarchitect-deploy.lock'

    foreach ($path in @($destinationJar, $jarStaging, $jarBackup, $deploymentLockPath)) {
        Assert-ChildPath -Root $resolvedPluginsDirectory -Path $path
    }
    Assert-RegularFileOrMissing -Path $destinationJar -Label 'Deployed JAR'
    Assert-RegularFileOrMissing -Path $jarStaging -Label 'Staged JAR'
    Assert-RegularFileOrMissing -Path $jarBackup -Label 'Backup JAR'

    if ($publishFailpointEnabled) {
        $systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        $pluginsPath = $resolvedPluginsDirectory.TrimEnd(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $pluginsPath.StartsWith($systemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'The deployment publish failpoint is permitted only under the system temporary directory.'
        }
    }
}

New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null
$buildLockPath = Join-Path $distDirectory '.astralarchitect-build.lock'
Assert-ChildPath -Root $projectDirectory -Path $buildLockPath

$deploymentLock = $null
$buildLock = $null
try {
    if (-not $BuildOnly) {
        $deploymentLock = Enter-ExclusiveFileLock -Path $deploymentLockPath -Label 'AstralArchitect deployment lock'
    }
    $buildLock = Enter-ExclusiveFileLock -Path $buildLockPath -Label 'AstralArchitect build lock'

    Push-Location $projectDirectory
    try {
        & mvn clean package
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $jars = @(Get-ChildItem -LiteralPath $distDirectory -Filter 'AstralArchitect-*.jar' -File)
    if ($jars.Count -ne 1) {
        throw "Expected exactly one AstralArchitect JAR in $distDirectory, found $($jars.Count)."
    }
    Assert-ReadableRegularFile -Path $jars[0].FullName -Label 'Built JAR'

    Write-Host "Built: $($jars[0].FullName)"
    if ($BuildOnly) {
        Write-Host 'BuildOnly was specified. Deployment was skipped.'
    }
    else {
        # Re-check every live path after the potentially long build and before
        # changing the server directory.
        Assert-RegularFileOrMissing -Path $destinationJar -Label 'Deployed JAR'
        Assert-RegularFileOrMissing -Path $jarStaging -Label 'Staged JAR'
        Assert-RegularFileOrMissing -Path $jarBackup -Label 'Backup JAR'

        # If only .old survived, the previous process stopped after moving the
        # deployed JAR aside. If both files survived, the destination was
        # already published atomically and remains authoritative.
        if (Test-Path -LiteralPath $jarBackup) {
            Assert-ReadableRegularFile -Path $jarBackup -Label 'Backup JAR'
            if (Test-Path -LiteralPath $destinationJar) {
                Assert-ReadableRegularFile -Path $destinationJar -Label 'Deployed JAR'
                Remove-Item -LiteralPath $jarBackup -Force
            }
            else {
                Move-Item -LiteralPath $jarBackup -Destination $destinationJar
            }
        }
        if (Test-Path -LiteralPath $jarStaging) {
            Remove-Item -LiteralPath $jarStaging -Force
        }

        Copy-Item -LiteralPath $jars[0].FullName -Destination $jarStaging
        Assert-ReadableRegularFile -Path $jarStaging -Label 'Staged JAR'
        $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jars[0].FullName).Hash
        $stagedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jarStaging).Hash
        if (-not [string]::Equals($builtHash, $stagedHash, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $jarStaging -Force
            throw 'Staged JAR hash does not match the built JAR.'
        }

        $hadJar = Test-Path -LiteralPath $destinationJar
        try {
            if ($hadJar) {
                Move-Item -LiteralPath $destinationJar -Destination $jarBackup
            }
            if ($publishFailpointEnabled) {
                throw 'Injected deployment failure after moving the previous JAR to backup.'
            }
            Move-Item -LiteralPath $jarStaging -Destination $destinationJar
        }
        catch {
            if (Test-Path -LiteralPath $jarBackup) {
                Assert-ReadableRegularFile -Path $jarBackup -Label 'Backup JAR'
                if (Test-Path -LiteralPath $destinationJar) {
                    Assert-RegularFileOrMissing -Path $destinationJar -Label 'Partially published JAR'
                    Remove-Item -LiteralPath $destinationJar -Force
                }
                Move-Item -LiteralPath $jarBackup -Destination $destinationJar
            }
            elseif (-not $hadJar -and (Test-Path -LiteralPath $destinationJar)) {
                Assert-RegularFileOrMissing -Path $destinationJar -Label 'Partially published JAR'
                Remove-Item -LiteralPath $destinationJar -Force
            }
            throw
        }

        if (Test-Path -LiteralPath $jarBackup) {
            try {
                Assert-RegularFileOrMissing -Path $jarBackup -Label 'Backup JAR'
                Remove-Item -LiteralPath $jarBackup -Force
            }
            catch {
                Write-Warning "Old JAR backup could not be removed: $jarBackup"
            }
        }

        Write-Host "Plugin deployed: $destinationJar"
        Write-Host 'Restart the Minecraft server to load the new JAR.'
    }
}
finally {
    Exit-ExclusiveFileLock -Lock $buildLock -Path $buildLockPath
    Exit-ExclusiveFileLock -Lock $deploymentLock -Path $deploymentLockPath
}
