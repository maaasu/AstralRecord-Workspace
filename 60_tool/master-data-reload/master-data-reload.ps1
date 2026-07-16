[CmdletBinding()]
param(
    [ValidateSet('diff', 'rebuild')]
    [string]$Mode = 'diff'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path $scriptDir 'master-data-reload.config.json'

function Assert-PathExists {
    param([string]$Label, [string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function Invoke-FilebaseMirror {
    param($Config)

    Assert-PathExists -Label 'Filebase source path' -Path $Config.filebase.sourcePath
    if (-not (Test-Path -LiteralPath $Config.filebase.deployPath)) {
        New-Item -ItemType Directory -Path $Config.filebase.deployPath | Out-Null
    }

    $arguments = @(
        $Config.filebase.sourcePath,
        $Config.filebase.deployPath,
        '/MIR',
        '/R:2',
        '/W:2',
        '/NFL',
        '/NDL',
        '/NP',
        '/NJH',
        '/NJS'
    )

    if ($null -ne $Config.filebase.excludeDirectories) {
        $arguments += '/XD'
        $arguments += @($Config.filebase.excludeDirectories | ForEach-Object {
            Join-Path $Config.filebase.sourcePath $_
        })
    }

    Write-Host "==> Syncing filebase to $($Config.filebase.deployPath)" -ForegroundColor Cyan
    & robocopy @arguments
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy failed with exit code $LASTEXITCODE."
    }
}

function Invoke-MasterDataSeed {
    param($Config)

    $environmentVariable = [string]$Config.api.apiKeyEnvironmentVariable
    $apiKey = [Environment]::GetEnvironmentVariable($environmentVariable)
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw "API key environment variable is empty: $environmentVariable"
    }

    $uri = "$($Config.api.baseUrl.TrimEnd('/'))/api/master-data/seed?mode=$Mode"
    $headers = @{ 'X-Api-Key' = $apiKey }
    $previousCallback = [Net.ServicePointManager]::ServerCertificateValidationCallback

    try {
        if (-not [bool]$Config.api.verifySsl) {
            [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
        }

        Write-Host "==> Seeding MasterDataDB ($Mode)" -ForegroundColor Cyan
        $result = Invoke-RestMethod -Uri $uri -Method Post -Headers $headers -ContentType 'application/json'
        if ([string]$result.status -eq 'FAILED') {
            throw "MasterDataDB seed failed: $($result.errorMessage)"
        }

        Write-Host "Seed succeeded: files=$($result.fileCount), upserted=$($result.upsertedCount), deleted=$($result.deletedCount), skipped=$($result.skippedCount)" -ForegroundColor Green
    }
    finally {
        [Net.ServicePointManager]::ServerCertificateValidationCallback = $previousCallback
    }
}

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Config file not found: $configPath"
}
if (-not (Get-Command robocopy -ErrorAction SilentlyContinue)) {
    throw 'robocopy command was not found.'
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
Invoke-FilebaseMirror -Config $config
Invoke-MasterDataSeed -Config $config

Write-Host 'Filebase sync and API seed completed.' -ForegroundColor Green
