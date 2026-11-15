$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'deploy-debug.ps1'
$root = Join-Path ([IO.Path]::GetTempPath()) ('ar-management-deploy-gate-' + [Guid]::NewGuid().ToString('N'))
try {
    $bin = Join-Path $root 'bin'; $api = Join-Path $root 'api'; $web = Join-Path $root 'web'
    New-Item -ItemType Directory -Force -Path $bin, $api, $web | Out-Null
    Set-Content -LiteralPath (Join-Path $root 'api.csproj') -Value '<Project />' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root 'web.csproj') -Value '<Project />' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root 'management-fail.csproj') -Value '<Project />' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root 'history-ok.csproj') -Value '<Project />' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root 'management.json') -Value '{}' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $root 'history.json') -Value '{}' -Encoding UTF8
    Set-Content -LiteralPath (Join-Path $api 'token.txt') -Value 'test-token' -Encoding UTF8
    @{ ApiKey=@{ Key='test-api-key' }; SkillTreeRuntime=@{ Key='test-runtime-key'; MigrationKey='test-migration-key' } } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $api 'appsettings.json') -Encoding UTF8
    @{ AstralRecordApi=@{ BaseUrl='https://release-api.example.test:444'; ApiKey='test-api-key' }; ReleaseNotes=@{ SyncOnStartup=$true } } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $web 'appsettings.json') -Encoding UTF8
    @'
@echo off
echo %*>>"%AR_DEPLOY_GATE_LOG%"
echo %* | findstr /I "management-fail.csproj" >nul && exit /b 17
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $bin 'dotnet.cmd') -Encoding ASCII
    '@echo off' + [Environment]::NewLine + 'echo robocopy %*>>"%AR_DEPLOY_GATE_LOG%"' | Set-Content -LiteralPath (Join-Path $bin 'robocopy.cmd') -Encoding ASCII
    $config = @{ iis=@{enabled=$false}; api=@{enabled=$true;tokenFileName='token.txt';projectPath=(Join-Path $root 'api.csproj');buildOutputPath=(Join-Path $root 'api-out');deployPath=$api;backup=@{enabled=$false};preserveDirectories=@();preserveFilePatterns=@()}; web=@{enabled=$true;expectedApiBaseUrl='https://release-api.example.test:444';projectPath=(Join-Path $root 'web.csproj');buildOutputPath=(Join-Path $root 'web-out');deployPath=$web;backup=@{enabled=$false};preserveDirectories=@();preserveFilePatterns=@()}; plugin=@{enabled=$false}; fileDatabase=@{enabled=$false}; databaseMigrations=@{enabled=$false}; historyDatabaseMigrations=@{enabled=$true;projectPath=(Join-Path $root 'history-ok.csproj');configPath=(Join-Path $root 'history.json')}; managementDatabaseMigrations=@{enabled=$true;projectPath=(Join-Path $root 'management-fail.csproj');configPath=(Join-Path $root 'management.json')} }
    $configPath = Join-Path $root 'deploy.json'; $config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configPath -Encoding UTF8
    foreach ($case in @(@{ Name='default'; Arguments=@() }, @{ Name='release-management'; Arguments=@('-ReleaseManagementOnly') })) {
        $callsPath = Join-Path $root "$($case.Name)-calls.log"
        $previousPath = $env:Path; $env:Path = "$bin;$previousPath"; $env:AR_DEPLOY_GATE_LOG = $callsPath
        try {
            $previousErrorActionPreference = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ConfigPath $configPath @($case.Arguments) 2>&1 | Out-Null; $exitCode = $LASTEXITCODE }
            finally { $ErrorActionPreference = $previousErrorActionPreference }
        } finally { $env:Path = $previousPath; Remove-Item Env:AR_DEPLOY_GATE_LOG -ErrorAction SilentlyContinue }
        if ($exitCode -eq 0) { throw "$($case.Name) deployment should fail when ManagementDB migration fails." }
        if ((Test-Path (Join-Path $api 'app_offline.htm')) -or (Test-Path (Join-Path $web 'app_offline.htm'))) { throw "$($case.Name) migration failure created an offline marker." }
        $calls = Get-Content -Raw -LiteralPath $callsPath
        if ($calls -notmatch 'management-fail.csproj') { throw "$($case.Name) did not invoke the ManagementDB migration gate." }
        if ($calls -match 'robocopy') { throw "$($case.Name) deployment copied files after ManagementDB migration failure." }
    }
    Write-Output 'ManagementDB migration deployment gate integration test passed.'
} finally { if ((Test-Path -LiteralPath $root) -and [IO.Path]::GetFileName($root) -match '^ar-management-deploy-gate-[0-9a-f]{32}$') { Remove-Item -LiteralPath $root -Recurse -Force } }
