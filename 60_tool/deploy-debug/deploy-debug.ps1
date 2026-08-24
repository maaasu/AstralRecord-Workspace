[CmdletBinding()]
param(
    [switch]$PluginOnly,
    [switch]$ReleaseManagementOnly,
    [switch]$PreflightOnly,
    [string]$ConfigPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path $scriptDir "deploy-debug.config.json"
}
$encodingNormalizerPath = Join-Path $scriptDir "normalize-source-encoding.ps1"
$script:iisResetCommand = $null

if ($PluginOnly -and $ReleaseManagementOnly) {
    throw "-PluginOnly and -ReleaseManagementOnly cannot be used together."
}

if ($PreflightOnly -and -not $ReleaseManagementOnly) {
    throw "-PreflightOnly requires -ReleaseManagementOnly."
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Test-CommandExists {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Resolve-IisResetCommand {
    param($IisConfig)

    if ($null -eq $IisConfig -or -not $IisConfig.enabled) {
        return $null
    }

    if (-not [string]::IsNullOrWhiteSpace($IisConfig.executablePath)) {
        if (-not (Test-Path -LiteralPath $IisConfig.executablePath)) {
            throw "Configured iisreset executable was not found: $($IisConfig.executablePath)"
        }

        return $IisConfig.executablePath
    }

    $command = Get-Command "iisreset" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $wellKnownPaths = @(
        "C:\Windows\System32\iisreset.exe",
        "C:\Windows\SysWOW64\iisreset.exe"
    )

    foreach ($path in $wellKnownPaths) {
        if (Test-Path -LiteralPath $path) {
            return $path
        }
    }

    throw "iisreset.exe was not found. Disable IIS control in deploy-debug.config.json or set iis.executablePath."
}

function Assert-PathExists {
    param(
        [string]$Label,
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function Invoke-RobocopyMirror {
    param(
        [string]$Source,
        [string]$Destination,
        [string[]]$ExcludeDirectories = @(),
        [string[]]$ExcludeFiles = @()
    )

    Assert-PathExists -Label "Source path" -Path $Source

    if (-not (Test-Path -LiteralPath $Destination)) {
        New-Item -ItemType Directory -Path $Destination | Out-Null
    }

    $arguments = @(
        $Source,
        $Destination,
        "/MIR",
        "/R:2",
        "/W:2",
        "/NFL",
        "/NDL",
        "/NP",
        "/NJH",
        "/NJS"
    )

    if ($ExcludeDirectories.Count -gt 0) {
        $arguments += "/XD"
        $arguments += $ExcludeDirectories
    }

    if ($ExcludeFiles.Count -gt 0) {
        $arguments += "/XF"
        $arguments += $ExcludeFiles
    }

    & robocopy @arguments
    $exitCode = $LASTEXITCODE

    if ($exitCode -ge 8) {
        throw "robocopy failed with exit code $exitCode. Source=$Source Destination=$Destination"
    }
}

function Backup-DeployTarget {
    param($Component)

    if (-not $Component.backup.enabled) {
        return
    }

    $deployPath = $Component.deployPath
    $backupPath = Join-Path $deployPath $Component.backup.folderName

    Assert-PathExists -Label "Deploy path" -Path $deployPath

    if (-not (Test-Path -LiteralPath $backupPath)) {
        New-Item -ItemType Directory -Path $backupPath | Out-Null
    }

    $excludeFiles = @()
    $excludeFileProperty = $Component.backup.PSObject.Properties['excludeFilePatterns']
    if ($null -ne $excludeFileProperty -and $null -ne $excludeFileProperty.Value) {
        $excludeFiles += @($excludeFileProperty.Value)
    }

    Write-Step "Backing up $deployPath to $backupPath"
    Invoke-RobocopyMirror -Source $deployPath -Destination $backupPath -ExcludeDirectories @($backupPath) -ExcludeFiles $excludeFiles
}

function Assert-TokenFileReady {
    param($Component)

    $tokenFileName = [string]$Component.tokenFileName
    if ([string]::IsNullOrWhiteSpace($tokenFileName) -or [IO.Path]::IsPathRooted($tokenFileName) -or $tokenFileName.Contains("..")) {
        throw "API tokenFileName must be a non-empty relative file name."
    }

    $tokenPath = Join-Path $Component.deployPath $tokenFileName
    Assert-PathExists -Label "Discord token file" -Path $tokenPath
    $token = Get-Content -LiteralPath $tokenPath -Raw -Encoding UTF8 -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Discord token file is empty: $tokenFileName"
    }
}

function Get-RequiredPropertyValue {
    param(
        [object]$Object,
        [string]$PropertyName,
        [string]$Label
    )

    if ($null -eq $Object) {
        throw "$Label is missing."
    }

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "$Label is missing."
    }

    return $property.Value
}

function Read-JsonFile {
    param(
        [string]$Label,
        [string]$Path
    )

    Assert-PathExists -Label $Label -Path $Path

    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "$Label is not valid JSON: $Path"
    }
}

function ConvertTo-AbsoluteHttpsUri {
    param(
        [string]$Value,
        [string]$Label
    )

    [Uri]$uri = $null
    if ([string]::IsNullOrWhiteSpace($Value) -or
        -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne [Uri]::UriSchemeHttps) {
        throw "$Label must be an absolute HTTPS URL."
    }

    return $uri
}

function Assert-ReleaseManagementReady {
    param(
        [object]$ApiComponent,
        [object]$WebComponent
    )

    Write-Step "Validating release management production settings"
    Assert-TokenFileReady -Component $ApiComponent

    $expectedBaseUrl = [string](Get-RequiredPropertyValue -Object $WebComponent -PropertyName "expectedApiBaseUrl" -Label "web.expectedApiBaseUrl")
    $expectedBaseUri = ConvertTo-AbsoluteHttpsUri -Value $expectedBaseUrl -Label "web.expectedApiBaseUrl"

    $apiSettingsPath = Join-Path $ApiComponent.deployPath "appsettings.json"
    $webSettingsPath = Join-Path $WebComponent.deployPath "appsettings.json"
    $apiSettings = Read-JsonFile -Label "API production appsettings.json" -Path $apiSettingsPath
    $webSettings = Read-JsonFile -Label "WEB production appsettings.json" -Path $webSettingsPath

    $apiKeySection = Get-RequiredPropertyValue -Object $apiSettings -PropertyName "ApiKey" -Label "API ApiKey section"
    $apiKey = [string](Get-RequiredPropertyValue -Object $apiKeySection -PropertyName "Key" -Label "API ApiKey:Key")
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw "API ApiKey:Key must not be empty."
    }

    $webApiSection = Get-RequiredPropertyValue -Object $webSettings -PropertyName "AstralRecordApi" -Label "WEB AstralRecordApi section"
    $actualBaseUrl = [string](Get-RequiredPropertyValue -Object $webApiSection -PropertyName "BaseUrl" -Label "WEB AstralRecordApi:BaseUrl")
    $actualBaseUri = ConvertTo-AbsoluteHttpsUri -Value $actualBaseUrl -Label "WEB AstralRecordApi:BaseUrl"
    if (-not [string]::Equals(
            $actualBaseUri.AbsoluteUri.TrimEnd('/'),
            $expectedBaseUri.AbsoluteUri.TrimEnd('/'),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "WEB AstralRecordApi:BaseUrl does not match the configured production API URL. Expected=$($expectedBaseUri.AbsoluteUri.TrimEnd('/')) Actual=$($actualBaseUri.AbsoluteUri.TrimEnd('/'))"
    }

    $webApiKey = [string](Get-RequiredPropertyValue -Object $webApiSection -PropertyName "ApiKey" -Label "WEB AstralRecordApi:ApiKey")
    if ([string]::IsNullOrWhiteSpace($webApiKey)) {
        throw "WEB AstralRecordApi:ApiKey must not be empty."
    }

    if ($apiKey -cne $webApiKey) {
        throw "API ApiKey:Key and WEB AstralRecordApi:ApiKey do not match."
    }

    $releaseNotesSection = Get-RequiredPropertyValue -Object $webSettings -PropertyName "ReleaseNotes" -Label "WEB ReleaseNotes section"
    $syncOnStartup = Get-RequiredPropertyValue -Object $releaseNotesSection -PropertyName "SyncOnStartup" -Label "WEB ReleaseNotes:SyncOnStartup"
    if ($syncOnStartup -isnot [bool] -or -not $syncOnStartup) {
        throw "WEB ReleaseNotes:SyncOnStartup must be true."
    }

    Write-Step "Release management production settings are ready"
}

function Enter-AppOffline {
    param($Component, [string]$Name)

    $offlinePath = Join-Path $Component.deployPath "app_offline.htm"
    $content = @"
<html>
<head><title>$Name deploying</title></head>
<body>$Name is being deployed.</body>
</html>
"@

    Write-Step "Putting $Name offline"
    Set-Content -LiteralPath $offlinePath -Value $content -Encoding UTF8
    Start-Sleep -Seconds 5

    return $offlinePath
}

function Exit-AppOffline {
    param([string]$Path)

    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path)) {
        Remove-Item -LiteralPath $Path -Force
    }
}

function Publish-DotNetProject {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name project" -Path $Component.projectPath

    Write-Step "Publishing $Name"
    & dotnet publish $Component.projectPath --configuration Release --output $Component.buildOutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed for $Name."
    }
}

function Build-Plugin {
    param(
        $Component,
        [switch]$SkipTests
    )

    Assert-PathExists -Label "Plugin project path" -Path $Component.projectPath
    Assert-PathExists -Label "Encoding normalizer script" -Path $encodingNormalizerPath

    Write-Step "Normalizing plugin source encodings (UTF-8 no BOM)"
    & $encodingNormalizerPath -RootPath (Join-Path $Component.projectPath "src\main\java")

    $mavenArguments = @("clean", "package")
    if ($SkipTests) {
        $mavenArguments = @("-Dmaven.test.skip=true") + $mavenArguments
        Write-Step "Building plugin without compiling or running tests"
    }
    else {
        Write-Step "Building plugin with tests"
    }

    Push-Location $Component.projectPath
    try {
        & mvn @mavenArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Plugin build failed: mvn $($mavenArguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Cleanup-PluginWorkspace {
    param($Component)

    Assert-PathExists -Label "Plugin project path" -Path $Component.projectPath

    $projectRoot = (Resolve-Path -LiteralPath $Component.projectPath).Path
    $cleanupTargets = @("target", "temp", "dist")

    Write-Step "Cleaning plugin workspace artifacts"
    foreach ($name in $cleanupTargets) {
        $candidate = Join-Path $projectRoot $name
        if (-not (Test-Path -LiteralPath $candidate)) {
            continue
        }

        $resolved = (Resolve-Path -LiteralPath $candidate).Path
        if (-not $resolved.StartsWith($projectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Cleanup target escaped project root: $resolved"
        }

        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

function Deploy-WebArtifact {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name build output" -Path $Component.buildOutputPath
    Assert-PathExists -Label "$Name deploy path" -Path $Component.deployPath

    Backup-DeployTarget -Component $Component
    $offlinePath = Enter-AppOffline -Component $Component -Name $Name

    try {
        $excludeDirectories = @()
        if ($Component.backup.enabled) {
            $excludeDirectories += (Join-Path $Component.deployPath $Component.backup.folderName)
        }

        if ($null -ne $Component.preserveDirectories) {
            foreach ($directoryName in @($Component.preserveDirectories)) {
                $excludeDirectories += (Join-Path $Component.deployPath $directoryName)
            }
        }

        $excludeFiles = @("app_offline.htm")
        if ($null -ne $Component.preserveFilePatterns) {
            $excludeFiles += @($Component.preserveFilePatterns)
        }

        Write-Step "Deploying $Name to $($Component.deployPath)"
        Invoke-RobocopyMirror -Source $Component.buildOutputPath -Destination $Component.deployPath -ExcludeDirectories $excludeDirectories -ExcludeFiles $excludeFiles
    }
    finally {
        Exit-AppOffline -Path $offlinePath
    }
}

function Deploy-FolderArtifact {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name source path" -Path $Component.sourcePath
    Assert-PathExists -Label "$Name deploy path" -Path $Component.deployPath

    Backup-DeployTarget -Component $Component

    $excludeDirectories = @()
    if ($Component.backup.enabled) {
        $excludeDirectories += (Join-Path $Component.deployPath $Component.backup.folderName)
    }

    if ($null -ne $Component.preserveDirectories) {
        foreach ($directoryName in @($Component.preserveDirectories)) {
            $excludeDirectories += (Join-Path $Component.deployPath $directoryName)
        }
    }

    $excludeFiles = @()
    if ($null -ne $Component.preserveFilePatterns) {
        $excludeFiles += @($Component.preserveFilePatterns)
    }

    Write-Step "Deploying $Name to $($Component.deployPath)"
    Invoke-RobocopyMirror -Source $Component.sourcePath -Destination $Component.deployPath -ExcludeDirectories $excludeDirectories -ExcludeFiles $excludeFiles
}

function Deploy-PluginArtifact {
    param($Component)

    Assert-PathExists -Label "Plugin build output" -Path $Component.buildOutputPath
    Assert-PathExists -Label "Plugin deploy path" -Path $Component.deployPath

    $artifact = Get-ChildItem -LiteralPath $Component.buildOutputPath -Filter $Component.artifactPattern -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $artifact) {
        throw "Plugin artifact not found in $($Component.buildOutputPath) with pattern $($Component.artifactPattern)."
    }

    $destinationFileName = if ([string]::IsNullOrWhiteSpace($Component.deployFileName)) {
        $artifact.Name
    } else {
        [string]$Component.deployFileName
    }

    $cleanupPatterns = @()
    if ($null -ne $Component.cleanupPatterns) {
        $cleanupPatterns += @($Component.cleanupPatterns)
    }
    else {
        $cleanupPatterns += @($Component.artifactPattern, $destinationFileName)
    }

    $cleanupPatterns += $artifact.Name
    $cleanupPatterns = $cleanupPatterns |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique

    foreach ($pattern in $cleanupPatterns) {
        Get-ChildItem -LiteralPath $Component.deployPath -Filter $pattern -File -ErrorAction SilentlyContinue |
            Remove-Item -Force
    }

    $destination = Join-Path $Component.deployPath $destinationFileName

    Write-Step "Copying plugin artifact to $destination"
    Copy-Item -LiteralPath $artifact.FullName -Destination $destination -Force

    $paperRemappedPath = Join-Path $Component.deployPath ".paper-remapped"
    if (-not (Test-Path -LiteralPath $paperRemappedPath)) {
        return
    }

    foreach ($pattern in $cleanupPatterns) {
        Get-ChildItem -LiteralPath $paperRemappedPath -Filter $pattern -File -ErrorAction SilentlyContinue |
            ForEach-Object {
                Write-Step "Removing stale paper remapped artifact $($_.FullName)"
                Remove-Item -LiteralPath $_.FullName -Force
            }
    }

    $unknownOriginPath = Join-Path $paperRemappedPath "unknown-origin"
    if (Test-Path -LiteralPath $unknownOriginPath) {
        foreach ($pattern in $cleanupPatterns) {
            Get-ChildItem -LiteralPath $unknownOriginPath -Filter $pattern -File -ErrorAction SilentlyContinue |
                ForEach-Object {
                    Write-Step "Removing stale paper remapped artifact $($_.FullName)"
                    Remove-Item -LiteralPath $_.FullName -Force
                }
        }
    }
}

function Invoke-IisReset {
    param(
        [string]$ComputerName,
        [ValidateSet("start", "stop", "restart")]
        [string]$Action
    )

    Write-Step "IIS $Action on $ComputerName"
    & $script:iisResetCommand $ComputerName "/$Action"
    if ($LASTEXITCODE -ne 0) {
        throw "iisreset /$Action failed for $ComputerName."
    }
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw "Config file not found: $ConfigPath"
}

$config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ($PluginOnly) {
    $config.api.enabled = $false
    $config.web.enabled = $false
    $config.fileDatabase.enabled = $false
}

if ($ReleaseManagementOnly) {
    $config.plugin.enabled = $false
    $config.fileDatabase.enabled = $false
    Assert-ReleaseManagementReady -ApiComponent $config.api -WebComponent $config.web
}

if ($PreflightOnly) {
    Write-Step "Release management preflight completed successfully"
    return
}

if (-not (Test-CommandExists -Name "dotnet")) {
    throw "dotnet command was not found."
}

if (-not $ReleaseManagementOnly -and -not (Test-CommandExists -Name "mvn")) {
    throw "mvn command was not found."
}

if (-not (Test-CommandExists -Name "robocopy")) {
    throw "robocopy command was not found."
}

$script:iisResetCommand = Resolve-IisResetCommand -IisConfig $config.iis

$iisStopped = $false

try {
    if ($config.api.enabled) {
        Publish-DotNetProject -Component $config.api -Name "API"
    }

    if ($config.web.enabled) {
        Publish-DotNetProject -Component $config.web -Name "WEB"
    }

    if ($config.plugin.enabled) {
        Build-Plugin -Component $config.plugin -SkipTests:$PluginOnly
    }

    if ($null -ne $script:iisResetCommand -and ($config.api.enabled -or $config.web.enabled)) {
        Invoke-IisReset -ComputerName $config.iis.host -Action "stop"
        $iisStopped = $true
    }

    if ($config.api.enabled) {
        Deploy-WebArtifact -Component $config.api -Name "API"
    }

    if ($config.web.enabled) {
        Deploy-WebArtifact -Component $config.web -Name "WEB"
    }

    if ($config.plugin.enabled) {
        Deploy-PluginArtifact -Component $config.plugin
        Cleanup-PluginWorkspace -Component $config.plugin
    }

    if ($config.fileDatabase.enabled) {
        Deploy-FolderArtifact -Component $config.fileDatabase -Name "FileDatabase"
    }
}
finally {
    if ($iisStopped) {
        try {
            Invoke-IisReset -ComputerName $config.iis.host -Action "start"
        }
        catch {
            Write-Error $_
            throw
        }
    }
}

Write-Step "Deployment completed successfully"
