[CmdletBinding()]
param(
    [ValidateSet("Purpur", "Paper")]
    [string]$ServerType = "Purpur",

    [string]$MinecraftVersion = "1.21.11",

    [string]$JavaPath = "java",

    [int]$MinMemoryMb = 1024,

    [int]$MaxMemoryMb = 3072,

    [string]$ServerRoot = "",

    [string]$PluginJarPath = "",

    [switch]$SkipBuild,

    [switch]$NoStart,

    [switch]$Background
)

$ErrorActionPreference = "Stop"

$pluginRoot = Split-Path -Parent $PSScriptRoot
$defaultServerRoot = Join-Path $pluginRoot ".dev-server\$($ServerType.ToLowerInvariant())-$MinecraftVersion"
$resolvedServerRoot = if ([string]::IsNullOrWhiteSpace($ServerRoot)) { $defaultServerRoot } else { $ServerRoot }
$distDir = Join-Path $pluginRoot "dist"
$pluginsDir = Join-Path $resolvedServerRoot "plugins"
$logsDir = Join-Path $resolvedServerRoot "logs"
$serverJarPath = Join-Path $resolvedServerRoot "server.jar"
$eulaPath = Join-Path $resolvedServerRoot "eula.txt"
$serverPropertiesPath = Join-Path $resolvedServerRoot "server.properties"

function Write-Info {
    param([string]$Message)
    Write-Host "[AstralRecord dev-server] $Message"
}

function Get-PurpurDownloadUrl {
    param([string]$Version)
    return "https://api.purpurmc.org/v2/purpur/$Version/latest/download"
}

function Get-PaperDownloadUrl {
    param([string]$Version)

    $buildsUri = "https://api.papermc.io/v2/projects/paper/versions/$Version/builds"
    $buildsResponse = Invoke-RestMethod -Uri $buildsUri
    if (-not $buildsResponse.builds -or $buildsResponse.builds.Count -eq 0) {
        throw "Paper build could not be resolved for version $Version."
    }

    $latestBuild = ($buildsResponse.builds | Sort-Object build)[-1]
    $buildNumber = $latestBuild.build
    return "https://api.papermc.io/v2/projects/paper/versions/$Version/builds/$buildNumber/downloads/paper-$Version-$buildNumber.jar"
}

function Ensure-ServerJar {
    if (Test-Path -LiteralPath $serverJarPath) {
        Write-Info "server.jar already exists: $serverJarPath"
        return
    }

    $downloadUrl = if ($ServerType -eq "Purpur") {
        Get-PurpurDownloadUrl -Version $MinecraftVersion
    } else {
        Get-PaperDownloadUrl -Version $MinecraftVersion
    }

    Write-Info "Downloading $ServerType $MinecraftVersion"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $serverJarPath
}

function Build-PluginJar {
    if ($SkipBuild) {
        Write-Info "Skipping Maven build because -SkipBuild was specified."
        return
    }

    Write-Info "Building plugin jar with Maven"
    & mvn "-q" "-DskipTests" "package"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
}

function Get-LatestPluginJar {
    if (-not [string]::IsNullOrWhiteSpace($PluginJarPath)) {
        if (-not (Test-Path -LiteralPath $PluginJarPath)) {
            throw "Plugin jar was not found: $PluginJarPath"
        }
        return (Resolve-Path -LiteralPath $PluginJarPath).Path
    }

    if (-not (Test-Path -LiteralPath $distDir)) {
        if ($SkipBuild) {
            throw "dist directory was not found. Re-run without -SkipBuild or pass -PluginJarPath <jar>."
        }
        throw "dist directory was not found after the Maven build."
    }

    $candidate = Get-ChildItem -Path $distDir -Filter "AstralRecord-*.jar" -File -ErrorAction Stop |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if ($null -eq $candidate) {
        throw "No plugin jar was found in $distDir. Re-run without -SkipBuild or pass -PluginJarPath <jar>."
    }

    return $candidate.FullName
}

function Write-ServerFiles {
    Set-Content -Path $eulaPath -Value "eula=true" -Encoding ascii

    if (-not (Test-Path -LiteralPath $serverPropertiesPath)) {
        @(
            "motd=AstralRecord Dev Server"
            "online-mode=false"
            "spawn-protection=0"
            "enable-command-block=true"
            "difficulty=easy"
        ) | Set-Content -Path $serverPropertiesPath -Encoding ascii
    }
}

New-Item -ItemType Directory -Force -Path $resolvedServerRoot, $pluginsDir, $logsDir | Out-Null

Build-PluginJar
Ensure-ServerJar
Write-ServerFiles

$pluginJar = Get-LatestPluginJar
$copiedPluginJar = Join-Path $pluginsDir (Split-Path -Leaf $pluginJar)
Copy-Item -LiteralPath $pluginJar -Destination $copiedPluginJar -Force
Write-Info "Copied plugin jar to $copiedPluginJar"

if ($NoStart) {
    Write-Info "Prepared server only. Start skipped because -NoStart was specified."
    exit 0
}

$javaArgs = @(
    "-Xms${MinMemoryMb}M",
    "-Xmx${MaxMemoryMb}M",
    "-Dfile.encoding=UTF-8",
    "-jar",
    $serverJarPath,
    "nogui"
)

if ($Background) {
    $stdoutPath = Join-Path $logsDir "codex-dev-server.stdout.log"
    $stderrPath = Join-Path $logsDir "codex-dev-server.stderr.log"
    Write-Info "Starting server in background. Logs: $stdoutPath / $stderrPath"
    Start-Process -FilePath $JavaPath `
        -ArgumentList $javaArgs `
        -WorkingDirectory $resolvedServerRoot `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden
    exit 0
}

Write-Info "Starting server in foreground"
& $JavaPath @javaArgs
exit $LASTEXITCODE
