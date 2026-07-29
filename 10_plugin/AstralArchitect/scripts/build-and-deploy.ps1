[CmdletBinding()]
param(
    [switch]$BuildOnly,
    [string]$PluginsDirectory = '\\DEVICE_SERVER\server\CraftyController\crafty-__saas-windows-medium-amd64__-_03629d64\servers\5bf4f70b-2c02-4a6b-b23f-8453237d2d97\plugins'
)

$ErrorActionPreference = 'Stop'

$projectDirectory = Split-Path -Parent $PSScriptRoot
$distDirectory = Join-Path $projectDirectory 'dist'
$toolsSource = Join-Path $projectDirectory 'tools'

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

Write-Host "Built: $($jars[0].FullName)"
if ($BuildOnly) {
    Write-Host 'BuildOnly was specified. Deployment was skipped.'
    exit 0
}

$resolvedPluginsDirectory = [System.IO.Path]::GetFullPath($PluginsDirectory)
if (-not (Test-Path -LiteralPath $resolvedPluginsDirectory -PathType Container)) {
    throw "Server plugins directory does not exist: $resolvedPluginsDirectory"
}

$destinationJar = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar'
$jarStaging = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar.new'
$jarBackup = Join-Path $resolvedPluginsDirectory 'AstralArchitect.jar.old'
$pluginDataDirectory = Join-Path $resolvedPluginsDirectory 'AstralArchitect'
$toolsDestination = Join-Path $pluginDataDirectory 'tools'
$toolsStaging = Join-Path $pluginDataDirectory 'tools.new'
$toolsBackup = Join-Path $pluginDataDirectory 'tools.old'
New-Item -ItemType Directory -Path $pluginDataDirectory -Force | Out-Null

# A previous process may have stopped between moving the old artifact aside and
# publishing the staged artifact. A surviving .old is always the last complete
# version, so restore it before preparing this deployment.
if (Test-Path -LiteralPath $toolsBackup) {
    if (Test-Path -LiteralPath $toolsDestination) {
        Remove-Item -LiteralPath $toolsDestination -Recurse -Force
    }
    Move-Item -LiteralPath $toolsBackup -Destination $toolsDestination
}
if (Test-Path -LiteralPath $jarBackup) {
    if (Test-Path -LiteralPath $destinationJar) {
        Remove-Item -LiteralPath $destinationJar -Force
    }
    Move-Item -LiteralPath $jarBackup -Destination $destinationJar
}
if (Test-Path -LiteralPath $jarStaging) {
    Remove-Item -LiteralPath $jarStaging -Force
}
if (Test-Path -LiteralPath $toolsStaging) {
    Remove-Item -LiteralPath $toolsStaging -Recurse -Force
}

Copy-Item -LiteralPath $jars[0].FullName -Destination $jarStaging

if (Test-Path -LiteralPath $toolsSource -PathType Container) {
    New-Item -ItemType Directory -Path $toolsStaging | Out-Null
    $runtimeToolFiles = @(
        Get-ChildItem -LiteralPath (Join-Path $toolsSource 'astralarchitect_ticket') -Recurse -File -Filter '*.py'
        Get-Item -LiteralPath (Join-Path $toolsSource 'ticket_cli.py')
    )
    foreach ($toolFile in $runtimeToolFiles) {
        $relativePath = $toolFile.FullName.Substring($toolsSource.Length).TrimStart(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar)
        $destinationFile = Join-Path $toolsStaging $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destinationFile) -Force | Out-Null
        Copy-Item -LiteralPath $toolFile.FullName -Destination $destinationFile -Force
    }
}

$hadTools = Test-Path -LiteralPath $toolsDestination
$hadJar = Test-Path -LiteralPath $destinationJar
try {
    if (Test-Path -LiteralPath $toolsStaging) {
        if ($hadTools) {
            Move-Item -LiteralPath $toolsDestination -Destination $toolsBackup
        }
        Move-Item -LiteralPath $toolsStaging -Destination $toolsDestination
    }
    if ($hadJar) {
        Move-Item -LiteralPath $destinationJar -Destination $jarBackup
    }
    # Publish the JAR last so the next server restart never observes a new JAR
    # before its matching companion tools have been staged.
    Move-Item -LiteralPath $jarStaging -Destination $destinationJar
}
catch {
    if (Test-Path -LiteralPath $jarBackup) {
        if (Test-Path -LiteralPath $destinationJar) {
            Remove-Item -LiteralPath $destinationJar -Force
        }
        Move-Item -LiteralPath $jarBackup -Destination $destinationJar
    } elseif (-not $hadJar -and (Test-Path -LiteralPath $destinationJar)) {
        Remove-Item -LiteralPath $destinationJar -Force
    }
    if (Test-Path -LiteralPath $toolsBackup) {
        if (Test-Path -LiteralPath $toolsDestination) {
            Remove-Item -LiteralPath $toolsDestination -Recurse -Force
        }
        Move-Item -LiteralPath $toolsBackup -Destination $toolsDestination
    } elseif (-not $hadTools -and (Test-Path -LiteralPath $toolsDestination)) {
        Remove-Item -LiteralPath $toolsDestination -Recurse -Force
    }
    throw
}

if (Test-Path -LiteralPath $toolsBackup) {
    try {
        Remove-Item -LiteralPath $toolsBackup -Recurse -Force
    }
    catch {
        Write-Warning "Old tool backup could not be removed: $toolsBackup"
    }
}
if (Test-Path -LiteralPath $jarBackup) {
    try {
        Remove-Item -LiteralPath $jarBackup -Force
    }
    catch {
        Write-Warning "Old JAR backup could not be removed: $jarBackup"
    }
}
if (Test-Path -LiteralPath $toolsDestination) {
    Write-Host "Tools deployed: $toolsDestination"
}
Write-Host "Plugin deployed: $destinationJar"
Write-Host 'Restart the Minecraft server to load the new JAR.'
