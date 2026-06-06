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

    [switch]$UseLiveServerClone,

    [string]$LiveServerSourceRoot = "",

    [switch]$RefreshLiveServerClone,

    [switch]$SkipBuild,

    [switch]$NoStart,

    [switch]$Background
)

$ErrorActionPreference = "Stop"

$pluginRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $PSScriptRoot "dev-server.config.json"
$distDir = Join-Path $pluginRoot "dist"

function Write-Info {
    param([string]$Message)
    Write-Host "[AstralRecord dev-server] $Message"
}

function Load-DevServerConfig {
    if (-not (Test-Path -LiteralPath $configPath)) {
        return $null
    }

    return Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
}

function Get-AbsolutePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [switch]$MustExist
    )

    if ($MustExist) {
        return (Resolve-Path -LiteralPath $Path).ProviderPath
    }

    if (Test-Path -LiteralPath $Path) {
        return (Resolve-Path -LiteralPath $Path).ProviderPath
    }

    return [System.IO.Path]::GetFullPath($Path)
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

function Write-StandaloneServerFiles {
    param(
        [Parameter(Mandatory = $true)]
        [string]$EulaPath,

        [Parameter(Mandatory = $true)]
        [string]$ServerPropertiesPath
    )

    Set-Content -Path $EulaPath -Value "eula=true" -Encoding ascii

    if (-not (Test-Path -LiteralPath $ServerPropertiesPath)) {
        @(
            "motd=AstralRecord Dev Server"
            "online-mode=false"
            "spawn-protection=0"
            "enable-command-block=true"
            "difficulty=easy"
        ) | Set-Content -Path $ServerPropertiesPath -Encoding ascii
    }
}

function Sync-LiveServerClone {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourceRoot,

        [Parameter(Mandatory = $true)]
        [string]$TargetRoot,

        [string[]]$ExcludedDirectories,

        [string[]]$ExcludedFiles
    )

    $resolvedSourceRoot = Get-AbsolutePath -Path $SourceRoot -MustExist
    $resolvedTargetRoot = Get-AbsolutePath -Path $TargetRoot

    if ($resolvedSourceRoot.TrimEnd('\') -eq $resolvedTargetRoot.TrimEnd('\')) {
        throw "Target root must be different from the live server source root."
    }

    New-Item -ItemType Directory -Force -Path $resolvedTargetRoot | Out-Null

    $robocopyArgs = @(
        $resolvedSourceRoot,
        $resolvedTargetRoot,
        "/MIR",
        "/FFT",
        "/R:1",
        "/W:1",
        "/NFL",
        "/NDL",
        "/NJH",
        "/NJS",
        "/NP"
    )

    if ($ExcludedDirectories -and $ExcludedDirectories.Count -gt 0) {
        $robocopyArgs += "/XD"
        $robocopyArgs += $ExcludedDirectories
    }

    if ($ExcludedFiles -and $ExcludedFiles.Count -gt 0) {
        $robocopyArgs += "/XF"
        $robocopyArgs += $ExcludedFiles
    }

    Write-Info "Cloning live server package from $resolvedSourceRoot"
    & robocopy @robocopyArgs | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "robocopy failed while cloning the live server package. Exit code: $LASTEXITCODE"
    }
}

function Resolve-ServerJarPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$TargetRoot,

        [switch]$AllowDownloadFallback,

        [string]$DownloadedServerJarPath
    )

    $serverJarCandidate = Join-Path $TargetRoot "server.jar"
    if (Test-Path -LiteralPath $serverJarCandidate) {
        return $serverJarCandidate
    }

    $rootJar = Get-ChildItem -Path $TargetRoot -Filter "*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(purpur|paper|spigot).+\.jar$' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1

    if ($null -ne $rootJar) {
        return $rootJar.FullName
    }

    if ($AllowDownloadFallback -and -not [string]::IsNullOrWhiteSpace($DownloadedServerJarPath)) {
        return $DownloadedServerJarPath
    }

    throw "No server jar was found under $TargetRoot."
}

$config = Load-DevServerConfig
$configuredCloneRoot = $null
$configuredLiveSourceRoot = $null
$configuredVelocityEnabled = $false
$configuredExcludedDirectories = @(".git", ".idea", "cache", "logs", "crash-reports")
$configuredExcludedFiles = @("session.lock")

if ($null -ne $config -and $null -ne $config.integration) {
    $configuredCloneRoot = $config.integration.defaultCloneRoot
    $configuredLiveSourceRoot = $config.integration.liveServerSourceRoot
    $configuredVelocityEnabled = [bool]$config.integration.velocityEnabled
    if ($config.integration.copyExcludedDirectories) {
        $configuredExcludedDirectories = @($config.integration.copyExcludedDirectories)
    }
    if ($config.integration.copyExcludedFiles) {
        $configuredExcludedFiles = @($config.integration.copyExcludedFiles)
    }
}

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    if ($UseLiveServerClone -and -not [string]::IsNullOrWhiteSpace($configuredCloneRoot)) {
        $ServerRoot = $configuredCloneRoot
    } else {
        $ServerRoot = Join-Path $pluginRoot ".dev-server\$($ServerType.ToLowerInvariant())-$MinecraftVersion"
    }
}

if ([string]::IsNullOrWhiteSpace($LiveServerSourceRoot) -and -not [string]::IsNullOrWhiteSpace($configuredLiveSourceRoot)) {
    $LiveServerSourceRoot = $configuredLiveSourceRoot
}

$resolvedServerRoot = Get-AbsolutePath -Path $ServerRoot
$pluginsDir = Join-Path $resolvedServerRoot "plugins"
$logsDir = Join-Path $resolvedServerRoot "logs"
$downloadedServerJarPath = Join-Path $resolvedServerRoot "server.jar"
$eulaPath = Join-Path $resolvedServerRoot "eula.txt"
$serverPropertiesPath = Join-Path $resolvedServerRoot "server.properties"

Build-PluginJar

if ($UseLiveServerClone) {
    if ([string]::IsNullOrWhiteSpace($LiveServerSourceRoot)) {
        throw "Live server source root is required for -UseLiveServerClone."
    }

    if ($RefreshLiveServerClone -or -not (Test-Path -LiteralPath $resolvedServerRoot)) {
        Sync-LiveServerClone `
            -SourceRoot $LiveServerSourceRoot `
            -TargetRoot $resolvedServerRoot `
            -ExcludedDirectories $configuredExcludedDirectories `
            -ExcludedFiles $configuredExcludedFiles
    } else {
        Write-Info "Using existing live server clone at $resolvedServerRoot"
    }

    New-Item -ItemType Directory -Force -Path $pluginsDir, $logsDir | Out-Null
    if ($configuredVelocityEnabled) {
        Write-Info "Velocity profile is expected and preserved from the cloned live server configuration."
    }
} else {
    New-Item -ItemType Directory -Force -Path $resolvedServerRoot, $pluginsDir, $logsDir | Out-Null

    if (-not (Test-Path -LiteralPath $downloadedServerJarPath)) {
        $downloadUrl = if ($ServerType -eq "Purpur") {
            Get-PurpurDownloadUrl -Version $MinecraftVersion
        } else {
            Get-PaperDownloadUrl -Version $MinecraftVersion
        }

        Write-Info "Downloading $ServerType $MinecraftVersion"
        Invoke-WebRequest -Uri $downloadUrl -OutFile $downloadedServerJarPath
    } else {
        Write-Info "server.jar already exists: $downloadedServerJarPath"
    }

    Write-StandaloneServerFiles -EulaPath $eulaPath -ServerPropertiesPath $serverPropertiesPath
}

$pluginJar = Get-LatestPluginJar
$pluginNamePrefix = [System.IO.Path]::GetFileNameWithoutExtension($pluginJar) -replace '-\d.*$',''
Get-ChildItem -Path $pluginsDir -Filter "$pluginNamePrefix*.jar" -File -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
$paperRemappedDir = Join-Path $pluginsDir ".paper-remapped"
if (Test-Path -LiteralPath $paperRemappedDir) {
    Get-ChildItem -Path $paperRemappedDir -Filter "$pluginNamePrefix*.jar" -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
}
$copiedPluginJar = Join-Path $pluginsDir (Split-Path -Leaf $pluginJar)
Copy-Item -LiteralPath $pluginJar -Destination $copiedPluginJar -Force
Write-Info "Copied plugin jar to $copiedPluginJar"

if ($NoStart) {
    Write-Info "Prepared server only. Start skipped because -NoStart was specified."
    exit 0
}

$serverJarPath = Resolve-ServerJarPath `
    -TargetRoot $resolvedServerRoot `
    -AllowDownloadFallback:(-not $UseLiveServerClone) `
    -DownloadedServerJarPath $downloadedServerJarPath

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
