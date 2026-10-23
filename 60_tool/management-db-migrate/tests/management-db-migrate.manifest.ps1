param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))))

$ErrorActionPreference = 'Stop'
$project = Join-Path $RepoRoot '60_tool/management-db-migrate/ManagementDbMigrateTool.csproj'
$sourceConfig = Join-Path $RepoRoot '60_tool/management-db-migrate/management-db-migrate.config.json'
$sourceMigrations = Join-Path $RepoRoot '00_docs/40_Database設計書/table-definitions/ManagementDB/migrations'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('AR_ManagementDbMigrate_Manifest_' + [Guid]::NewGuid().ToString('N'))

function Invoke-Validation([string]$ConfigPath, [int]$ExpectedExitCode, [string]$ExpectedOutput) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & dotnet run --no-build --project $project -- --config $ConfigPath --validate-only 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousErrorActionPreference }
    $text = $output -join [Environment]::NewLine
    if ($exitCode -ne $ExpectedExitCode -or $text -notmatch [Regex]::Escape($ExpectedOutput)) {
        throw "Unexpected manifest validation result (exit=$exitCode): $text"
    }
    Write-Output $text
}

try {
    [void][IO.Directory]::CreateDirectory($temporaryRoot)
    $migrationsRoot = Join-Path $temporaryRoot 'migrations'
    Copy-Item -LiteralPath $sourceMigrations -Destination $migrationsRoot -Recurse
    $configPath = Join-Path $temporaryRoot 'config.json'
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $sourceConfig | ConvertFrom-Json
    $config.sourceApiAppsettingsPath = $null
    $config.migrationsRootPath = $migrationsRoot
    $config | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $configPath
    Invoke-Validation $configPath 0 '5 migration(s)'

    foreach ($fileName in @('20261020_unregistered.sql', '20261020-unregistered.sql')) {
        $unregistered = Join-Path $migrationsRoot $fileName
        Set-Content -Encoding UTF8 -LiteralPath $unregistered -Value 'SELECT 1;'
        Invoke-Validation $configPath 1 "Management migration file is not registered: $fileName"
        Remove-Item -LiteralPath $unregistered
    }

    $defense = Join-Path $migrationsRoot '20261019_defense.sql'
    Add-Content -Encoding UTF8 -LiteralPath $defense -Value '-- tampered'
    Invoke-Validation $configPath 1 'script hash does not match the reviewed 20261019_defense.sql'
    Write-Output 'management-db-migrate manifest checks passed.'
}
finally {
    if ((Test-Path -LiteralPath $temporaryRoot) -and [IO.Path]::GetFileName($temporaryRoot) -match '^AR_ManagementDbMigrate_Manifest_[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
