[CmdletBinding()]
param(
    [ValidateSet('All', 'Lobby', 'Proxy')]
    [string]$Target = 'All',
    [switch]$SkipTests,
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-PluginBuild {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectDirectory,
        [Parameter(Mandatory = $true)]
        [string]$ArtifactId
    )

    $pomPath = Join-Path $ProjectDirectory 'pom.xml'
    if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
        throw "$ArtifactId pom.xml does not exist: $pomPath"
    }

    $arguments = @('clean', 'package')
    if ($SkipTests) {
        $arguments += '-Dmaven.test.skip=true'
    }

    Write-Host "Building $ArtifactId..."
    Push-Location $ProjectDirectory
    try {
        & mvn @arguments | Out-Host
        $mavenExitCode = $LASTEXITCODE
        if ($mavenExitCode -ne 0) {
            throw "$ArtifactId Maven build failed with exit code $mavenExitCode."
        }
    }
    finally {
        Pop-Location
    }

    $targetDirectory = Join-Path $ProjectDirectory 'target'
    $jars = @(Get-ChildItem -LiteralPath $targetDirectory -Filter "$ArtifactId-*.jar" -File |
        Where-Object { -not $_.Name.StartsWith('original-', [System.StringComparison]::OrdinalIgnoreCase) })
    if ($jars.Count -ne 1) {
        throw "Expected exactly one $ArtifactId JAR in $targetDirectory, found $($jars.Count)."
    }

    return $jars[0].FullName
}

$toolDirectory = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $toolDirectory
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $resolvedOutputDirectory = Join-Path $PSScriptRoot 'output'
}
else {
    $resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
}

if (Test-Path -LiteralPath $resolvedOutputDirectory -PathType Leaf) {
    throw "OutputDirectory points to a file: $resolvedOutputDirectory"
}

$projects = @()
if ($Target -in @('All', 'Lobby')) {
    $projects += [PSCustomObject]@{
        ArtifactId = 'AstralRecordLobby'
        Directory = Join-Path $workspaceRoot '10_plugin\AstralRecordLobby'
        OutputName = 'AstralRecordLobby.jar'
    }
}
if ($Target -in @('All', 'Proxy')) {
    $projects += [PSCustomObject]@{
        ArtifactId = 'AstralRecordProxy'
        Directory = Join-Path $workspaceRoot '10_plugin\AstralRecordProxy'
        OutputName = 'AstralRecordProxy.jar'
    }
}

$builtArtifacts = @()
foreach ($project in $projects) {
    if (-not (Test-Path -LiteralPath $project.Directory -PathType Container)) {
        throw "$($project.ArtifactId) project directory does not exist: $($project.Directory)"
    }
    $builtArtifacts += [PSCustomObject]@{
        Source = Invoke-PluginBuild -ProjectDirectory $project.Directory -ArtifactId $project.ArtifactId
        OutputName = $project.OutputName
    }
}

New-Item -ItemType Directory -Path $resolvedOutputDirectory -Force | Out-Null
foreach ($artifact in $builtArtifacts) {
    $destination = Join-Path $resolvedOutputDirectory $artifact.OutputName
    Copy-Item -LiteralPath $artifact.Source -Destination $destination -Force
    Write-Host "Built JAR: $destination"
}

Write-Host 'Build only completed. No server deployment was performed.'
