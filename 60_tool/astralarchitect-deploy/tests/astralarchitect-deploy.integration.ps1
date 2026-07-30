[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Invoke-EntryPoint {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $true)]
        [bool]$ExpectSuccess
    )

    & $script:entryPoint @Arguments
    $exitCode = $LASTEXITCODE
    if ($ExpectSuccess -and $exitCode -ne 0) {
        throw "Entry point failed unexpectedly with exit code $exitCode."
    }
    if (-not $ExpectSuccess -and $exitCode -eq 0) {
        throw 'Entry point succeeded when a failure was expected.'
    }
}

function Assert-NoTransientArtifacts {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PluginsDirectory
    )

    foreach ($name in @(
        'AstralArchitect.jar.new',
        'AstralArchitect.jar.old',
        '.astralarchitect-deploy.lock')) {
        Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $PluginsDirectory $name))) `
            -Message "Transient deployment artifact remains: $name"
    }
}

$toolDirectory = Split-Path -Parent $PSScriptRoot
$sixtyToolDirectory = Split-Path -Parent $toolDirectory
$workspaceRoot = Split-Path -Parent $sixtyToolDirectory
$script:entryPoint = Join-Path $sixtyToolDirectory '09-astralarchitect-build-deploy.bat'
$projectDirectory = Join-Path $workspaceRoot '10_plugin\AstralArchitect'
$distDirectory = Join-Path $projectDirectory 'dist'
$configPath = Join-Path $toolDirectory 'astralarchitect-deploy.config.json'

Assert-True -Condition (Test-Path -LiteralPath $script:entryPoint -PathType Leaf) `
    -Message "Entry point does not exist: $script:entryPoint"
Assert-True -Condition (Test-Path -LiteralPath $configPath -PathType Leaf) `
    -Message "Config does not exist: $configPath"

$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json
Assert-True -Condition (-not [string]::IsNullOrWhiteSpace([string]$config.pluginsDirectory)) `
    -Message 'Config pluginsDirectory is empty.'

$systemTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$testRoot = Join-Path $systemTemp ("AstralArchitectDeployTest-" + [System.Guid]::NewGuid().ToString('N'))
$resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($systemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Test root escaped the system temporary directory: $resolvedTestRoot"
}

$pluginsDirectory = Join-Path $resolvedTestRoot 'plugins'
$pluginDataDirectory = Join-Path $pluginsDirectory 'AstralArchitect'
$ticketDirectory = Join-Path $pluginDataDirectory 'tickets\fixture'
$markerPath = Join-Path $ticketDirectory 'marker.bin'
$destinationJar = Join-Path $pluginsDirectory 'AstralArchitect.jar'
$stagingJar = Join-Path $pluginsDirectory 'AstralArchitect.jar.new'
$backupJar = Join-Path $pluginsDirectory 'AstralArchitect.jar.old'
$deploymentLockPath = Join-Path $pluginsDirectory '.astralarchitect-deploy.lock'
$buildLockPath = Join-Path $distDirectory '.astralarchitect-build.lock'

New-Item -ItemType Directory -Path $ticketDirectory -Force | Out-Null
[System.IO.File]::WriteAllBytes($markerPath, [byte[]](1, 3, 3, 7))
$markerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $markerPath).Hash

try {
    Invoke-EntryPoint -Arguments @('-PluginsDirectory', 'relative\plugins') -ExpectSuccess $false
    Invoke-EntryPoint -Arguments @('-PluginsDirectory', ([System.IO.Path]::GetPathRoot($resolvedTestRoot))) `
        -ExpectSuccess $false
    Invoke-EntryPoint -Arguments @('-PluginsDirectory', $resolvedTestRoot) -ExpectSuccess $false

    Invoke-EntryPoint -Arguments @('-BuildOnly') -ExpectSuccess $true
    Assert-True -Condition (-not (Test-Path -LiteralPath $destinationJar)) `
        -Message 'BuildOnly unexpectedly deployed a JAR.'

    Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $true
    Assert-True -Condition (Test-Path -LiteralPath $destinationJar -PathType Leaf) `
        -Message 'Initial deployment did not create AstralArchitect.jar.'
    $builtJars = @(Get-ChildItem -LiteralPath $distDirectory -Filter 'AstralArchitect-*.jar' -File)
    Assert-True -Condition ($builtJars.Count -eq 1) -Message 'Expected one built AstralArchitect JAR.'
    Assert-True -Condition (
        (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash -eq
        (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJars[0].FullName).Hash) `
        -Message 'Initial deployed JAR does not match the built JAR.'
    Assert-NoTransientArtifacts -PluginsDirectory $pluginsDirectory
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $markerPath).Hash -eq $markerHash) `
        -Message 'Initial deployment changed plugin ticket data.'
    Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $pluginDataDirectory 'tools'))) `
        -Message 'Initial deployment copied a server-side CLI.'

    # Simulate an interrupted transaction after the old JAR was moved aside,
    # plus a stale staging file left by another interrupted attempt.
    Move-Item -LiteralPath $destinationJar -Destination $backupJar
    [System.IO.File]::WriteAllBytes($stagingJar, [byte[]](9, 9, 9))
    Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $true
    Assert-True -Condition (Test-Path -LiteralPath $destinationJar -PathType Leaf) `
        -Message 'Interrupted-state recovery did not publish AstralArchitect.jar.'
    $builtJars = @(Get-ChildItem -LiteralPath $distDirectory -Filter 'AstralArchitect-*.jar' -File)
    Assert-True -Condition ($builtJars.Count -eq 1) -Message 'Expected one rebuilt AstralArchitect JAR.'
    Assert-True -Condition (
        (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash -eq
        (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJars[0].FullName).Hash) `
        -Message 'Recovered deployed JAR does not match the rebuilt JAR.'
    Assert-NoTransientArtifacts -PluginsDirectory $pluginsDirectory
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $markerPath).Hash -eq $markerHash) `
        -Message 'Recovery deployment changed plugin ticket data.'

    # A malformed backup must be rejected before the deployed JAR is changed.
    $deployedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash
    New-Item -ItemType Directory -Path $backupJar | Out-Null
    Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $false
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash -eq $deployedHash) `
        -Message 'Malformed backup validation changed the deployed JAR.'
    Remove-Item -LiteralPath $backupJar

    # Inject a failure after the live JAR has been moved to .old. The previous
    # JAR must be restored, both locks must be released, and a normal retry must
    # safely remove the stale staging file and complete the deployment.
    [System.IO.File]::WriteAllBytes($destinationJar, [byte[]](4, 2, 4, 2, 1))
    $preFailureHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash
    $previousFailpointValue = [System.Environment]::GetEnvironmentVariable(
        'ASTRALARCHITECT_DEPLOY_TEST_FAIL_AFTER_BACKUP',
        [System.EnvironmentVariableTarget]::Process)
    try {
        [System.Environment]::SetEnvironmentVariable(
            'ASTRALARCHITECT_DEPLOY_TEST_FAIL_AFTER_BACKUP',
            '1',
            [System.EnvironmentVariableTarget]::Process)
        Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $false
    }
    finally {
        [System.Environment]::SetEnvironmentVariable(
            'ASTRALARCHITECT_DEPLOY_TEST_FAIL_AFTER_BACKUP',
            $previousFailpointValue,
            [System.EnvironmentVariableTarget]::Process)
    }

    Assert-True -Condition (Test-Path -LiteralPath $destinationJar -PathType Leaf) `
        -Message 'Injected publish failure did not restore AstralArchitect.jar.'
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash -eq $preFailureHash) `
        -Message 'Injected publish failure did not restore the previous JAR bytes.'
    Assert-True -Condition (-not (Test-Path -LiteralPath $backupJar)) `
        -Message 'Injected publish failure left the backup JAR behind.'
    Assert-True -Condition (Test-Path -LiteralPath $stagingJar -PathType Leaf) `
        -Message 'Injected publish failure did not leave the expected retryable staging JAR.'
    Assert-True -Condition (-not (Test-Path -LiteralPath $deploymentLockPath)) `
        -Message 'Injected publish failure left the deployment lock behind.'
    Assert-True -Condition (-not (Test-Path -LiteralPath $buildLockPath)) `
        -Message 'Injected publish failure left the build lock behind.'
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $markerPath).Hash -eq $markerHash) `
        -Message 'Injected publish failure changed plugin ticket data.'

    foreach ($lockPath in @($deploymentLockPath, $buildLockPath)) {
        $lockProbe = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::OpenOrCreate,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None)
        $lockProbe.Dispose()
        Remove-Item -LiteralPath $lockPath -Force
    }

    Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $true
    Assert-NoTransientArtifacts -PluginsDirectory $pluginsDirectory
    $builtJars = @(Get-ChildItem -LiteralPath $distDirectory -Filter 'AstralArchitect-*.jar' -File)
    Assert-True -Condition ($builtJars.Count -eq 1) -Message 'Expected one rebuilt AstralArchitect JAR after retry.'
    Assert-True -Condition (
        (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationJar).Hash -eq
        (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJars[0].FullName).Hash) `
        -Message 'Retried deployed JAR does not match the rebuilt JAR.'
    Assert-True -Condition ((Get-FileHash -Algorithm SHA256 -LiteralPath $markerPath).Hash -eq $markerHash) `
        -Message 'Retry after injected failure changed plugin ticket data.'

    # An already-held deployment lock must reject another process before build.
    $deploymentLock = [System.IO.File]::Open(
        $deploymentLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    try {
        Invoke-EntryPoint -Arguments @('-PluginsDirectory', $pluginsDirectory) -ExpectSuccess $false
    }
    finally {
        $deploymentLock.Dispose()
        Remove-Item -LiteralPath $deploymentLockPath -Force
    }

    # BuildOnly uses the same workspace-local build lock.
    New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null
    $buildLock = [System.IO.File]::Open(
        $buildLockPath,
        [System.IO.FileMode]::OpenOrCreate,
        [System.IO.FileAccess]::ReadWrite,
        [System.IO.FileShare]::None)
    try {
        Invoke-EntryPoint -Arguments @('-BuildOnly') -ExpectSuccess $false
    }
    finally {
        $buildLock.Dispose()
        Remove-Item -LiteralPath $buildLockPath -Force
    }

    Write-Host 'AstralArchitect deploy integration tests passed.'
}
finally {
    $resolvedCleanupTarget = [System.IO.Path]::GetFullPath($resolvedTestRoot)
    $cleanupLeaf = Split-Path -Leaf $resolvedCleanupTarget
    if ($resolvedCleanupTarget.StartsWith($systemTemp, [System.StringComparison]::OrdinalIgnoreCase) -and
        $cleanupLeaf.StartsWith('AstralArchitectDeployTest-', [System.StringComparison]::Ordinal)) {
        if (Test-Path -LiteralPath $resolvedCleanupTarget) {
            Remove-Item -LiteralPath $resolvedCleanupTarget -Recurse -Force
        }
    }
    else {
        Write-Warning "Refused to remove unexpected test path: $resolvedCleanupTarget"
    }
}
