[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) "deploy-debug.ps1"
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("astralrecord-release-preflight-" + [Guid]::NewGuid().ToString("N"))))
$expectedBaseUrl = "https://release-api.example.test:444"
$discordToken = "DISCORD_TOKEN_SENTINEL_7f95f4"
$sharedApiKey = "API_KEY_SENTINEL_2c18ab"
$differentApiKey = "DIFFERENT_API_KEY_SENTINEL_9e6430"
$sensitiveSentinels = @($discordToken, $sharedApiKey, $differentApiKey)

function Write-Utf8Json {
    param(
        [string]$Path,
        [object]$Value
    )

    $Value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function New-TestEnvironment {
    param([string]$Name)

    $root = Join-Path $temporaryRoot $Name
    $apiDeployPath = Join-Path $root "api"
    $webDeployPath = Join-Path $root "web"
    New-Item -ItemType Directory -Path $apiDeployPath, $webDeployPath -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $apiDeployPath "token.txt") -Value $discordToken -Encoding UTF8
    Write-Utf8Json -Path (Join-Path $apiDeployPath "appsettings.json") -Value @{
        ApiKey = @{ Key = $sharedApiKey }
    }
    Write-Utf8Json -Path (Join-Path $webDeployPath "appsettings.json") -Value @{
        AstralRecordApi = @{
            BaseUrl = $expectedBaseUrl
            ApiKey = $sharedApiKey
        }
        ReleaseNotes = @{ SyncOnStartup = $true }
    }

    $configPath = Join-Path $root "deploy-debug.config.json"
    Write-Utf8Json -Path $configPath -Value @{
        iis = @{ enabled = $false; host = "localhost"; executablePath = "" }
        api = @{
            enabled = $true
            tokenFileName = "token.txt"
            deployPath = $apiDeployPath
        }
        web = @{
            enabled = $true
            expectedApiBaseUrl = $expectedBaseUrl
            deployPath = $webDeployPath
        }
        plugin = @{ enabled = $false }
        fileDatabase = @{ enabled = $false }
    }

    return [pscustomobject]@{
        Root = $root
        ApiDeployPath = $apiDeployPath
        WebDeployPath = $webDeployPath
        ConfigPath = $configPath
    }
}

function Invoke-Preflight {
    param([string]$ConfigPath)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReleaseManagementOnly -PreflightOnly -ConfigPath $ConfigPath 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = ($output -join [Environment]::NewLine)
    }
}

function Assert-Succeeds {
    param(
        [string]$Name,
        [string]$ConfigPath
    )

    $result = Invoke-Preflight -ConfigPath $ConfigPath
    Assert-SecretNotExposed -Name $Name -Output $result.Output
    if ($result.ExitCode -ne 0) {
        throw "$Name should have succeeded. Output=$($result.Output)"
    }
}

function Assert-SecretNotExposed {
    param(
        [string]$Name,
        [string]$Output
    )

    foreach ($sentinel in $sensitiveSentinels) {
        if ($Output.IndexOf($sentinel, [StringComparison]::Ordinal) -ge 0) {
            throw "$Name exposed a sensitive sentinel in its output."
        }
    }
}

function Assert-FailsWith {
    param(
        [string]$Name,
        [string]$ConfigPath,
        [string]$ExpectedMessage
    )

    $result = Invoke-Preflight -ConfigPath $ConfigPath
    Assert-SecretNotExposed -Name $Name -Output $result.Output
    if ($result.ExitCode -eq 0) {
        throw "$Name should have failed."
    }

    if ($result.Output.IndexOf($ExpectedMessage, [StringComparison]::Ordinal) -lt 0) {
        throw "$Name did not report the expected message. Expected=$ExpectedMessage Output=$($result.Output)"
    }
}

try {
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

    $valid = New-TestEnvironment -Name "valid"
    Assert-Succeeds -Name "valid settings" -ConfigPath $valid.ConfigPath

    $wrongBaseUrl = New-TestEnvironment -Name "wrong-base-url"
    $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $wrongBaseUrl.WebDeployPath "appsettings.json") | ConvertFrom-Json
    $settings.AstralRecordApi.BaseUrl = "https://localhost:5001"
    Write-Utf8Json -Path (Join-Path $wrongBaseUrl.WebDeployPath "appsettings.json") -Value $settings
    Assert-FailsWith -Name "wrong API base URL" -ConfigPath $wrongBaseUrl.ConfigPath -ExpectedMessage "does not match the configured production API URL"

    $mismatchedApiKey = New-TestEnvironment -Name "mismatched-api-key"
    $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $mismatchedApiKey.WebDeployPath "appsettings.json") | ConvertFrom-Json
    $settings.AstralRecordApi.ApiKey = $differentApiKey
    Write-Utf8Json -Path (Join-Path $mismatchedApiKey.WebDeployPath "appsettings.json") -Value $settings
    Assert-FailsWith -Name "mismatched API key" -ConfigPath $mismatchedApiKey.ConfigPath -ExpectedMessage "do not match"

    $syncDisabled = New-TestEnvironment -Name "sync-disabled"
    $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $syncDisabled.WebDeployPath "appsettings.json") | ConvertFrom-Json
    $settings.ReleaseNotes.SyncOnStartup = $false
    Write-Utf8Json -Path (Join-Path $syncDisabled.WebDeployPath "appsettings.json") -Value $settings
    Assert-FailsWith -Name "disabled startup sync" -ConfigPath $syncDisabled.ConfigPath -ExpectedMessage "must be true"

    $emptyToken = New-TestEnvironment -Name "empty-token"
    Set-Content -LiteralPath (Join-Path $emptyToken.ApiDeployPath "token.txt") -Value "" -Encoding UTF8
    Assert-FailsWith -Name "empty Discord token" -ConfigPath $emptyToken.ConfigPath -ExpectedMessage "Discord token file is empty"

    Write-Host "All release management preflight integration tests passed."
}
finally {
    $systemTemporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ((Test-Path -LiteralPath $temporaryRoot) -and
        $temporaryRoot.StartsWith($systemTemporaryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
