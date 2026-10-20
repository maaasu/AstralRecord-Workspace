#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Join-Path ([IO.Path]::GetTempPath()) ('ar tool config ' + [guid]::NewGuid().ToString('N'))
$toolRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
New-Item -ItemType Directory -Path $root | Out-Null
Start-Transcript -LiteralPath (Join-Path $root 'verification.log') | Out-Null
$previousPath = $env:PATH
$previousCapture = $env:AR_TOOL_CONFIG_CAPTURE
function Assert($Condition, $Message) { if (!$Condition) { throw $Message } }
function Invoke-TestBat([string]$Path, [string]$Arguments = '') {
    $start = [Diagnostics.ProcessStartInfo]::new($env:ComSpec)
    $start.Arguments = '/d /c ""' + $Path + '" ' + $Arguments + ' <NUL"'
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    Assert ($process.ExitCode -eq 0) ($stdout.Result + $stderr.Result)
    $process.Dispose()
    return Get-Content -Raw -Encoding UTF8 -LiteralPath $env:AR_TOOL_CONFIG_CAPTURE
}
try {
    # Fake external tools: no build, DB connection, or deployment can run in this fixture.
    $fakeBin = Join-Path $root 'fake bin'
    New-Item -ItemType Directory -Path $fakeBin | Out-Null
    foreach ($name in @('dotnet', 'powershell')) {
        [IO.File]::WriteAllText((Join-Path $fakeBin "$name.cmd"), "@echo off`r`n> `"%AR_TOOL_CONFIG_CAPTURE%`" echo %*`r`nexit /b 0`r`n")
    }
    $env:PATH = $fakeBin + ';' + $env:PATH
    $env:AR_TOOL_CONFIG_CAPTURE = Join-Path $root 'captured.txt'
    foreach ($entry in @(
        @('04-db-rebuild.bat', 'db-rebuild', 'db-rebuild'),
        @('11-db-reset-except-release-notes.bat', 'db-reset-except-release-notes', 'db-reset-except-release-notes'),
        @('13-db-migrate.bat', 'db-migrate', 'db-migrate'),
        @('14-management-db-migrate.bat', 'management-db-migrate', 'management-db-migrate'),
        @('15-history-db-migrate.bat', 'db-migrate', 'history-db-migrate')
    )) {
        $bat = Join-Path $root $entry[0]
        Copy-Item -LiteralPath (Join-Path $toolRoot $entry[0]) -Destination $bat
        $directory = Join-Path $root $entry[1]
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $config = Join-Path $directory ($entry[2] + '.config.json')
        $local = Join-Path $directory ($entry[2] + '.local.json')
        [IO.File]::WriteAllText($config, '{}')
        $arguments = Invoke-TestBat $bat
        Assert ($arguments.Contains('--config "' + $config + '"')) 'Default config was not selected.'
        [IO.File]::WriteAllText($local, '{}')
        $arguments = Invoke-TestBat $bat
        Assert ($arguments.Contains('--config "' + $local + '"')) 'Local config was not selected.'
        $custom = Join-Path $root 'explicit config.json'
        $arguments = Invoke-TestBat $bat ('--config "' + $custom + '"')
        Assert ($arguments.TrimEnd().EndsWith('--config "' + $custom + '"')) 'Explicit config must remain last for the DB parser.'
    }
    $releaseBat = Join-Path $root '10-release-management-deploy.bat'
    Copy-Item -LiteralPath (Join-Path $toolRoot '10-release-management-deploy.bat') -Destination $releaseBat
    $arguments = Invoke-TestBat $releaseBat '-PreflightOnly'
    Assert ($arguments.Contains('invoke-powershell7.ps1"') -and $arguments.Contains('deploy-debug.ps1" -ReleaseManagementOnly') -and $arguments.Contains('-PreflightOnly')) '10 must run the backend on PowerShell 7.'
    $localRelease = Join-Path $root 'deploy-debug/deploy-debug.local.json'
    New-Item -ItemType Directory -Path (Split-Path $localRelease) -Force | Out-Null
    [IO.File]::WriteAllText($localRelease, '{}')
    $arguments = Invoke-TestBat $releaseBat '-PreflightOnly'
    Assert ($arguments.Contains('-ConfigPath "' + $localRelease + '"')) '10 must select local config.'
    $customRelease = Join-Path $root 'explicit release.json'
    $arguments = Invoke-TestBat $releaseBat ('-PreflightOnly -ConfigPath "' + $customRelease + '"')
    Assert ($arguments.Contains('-ConfigPath "' + $customRelease + '"') -and !$arguments.Contains($localRelease)) '10 explicit config must replace the default argument.'
    $env:PATH = $previousPath

    # Exercise real AstralArchitect config selection, failing path validation before Maven/build.
    $fixtureTools = Join-Path $root 'workspace/60_tool/astralarchitect-deploy'
    $project = Join-Path $root 'workspace/10_plugin/AstralArchitect'
    New-Item -ItemType Directory -Path $fixtureTools, $project -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $project 'pom.xml'), '<project/>')
    $script = Join-Path $fixtureTools 'astralarchitect-deploy.ps1'
    Copy-Item -LiteralPath (Join-Path $toolRoot 'astralarchitect-deploy/astralarchitect-deploy.ps1') -Destination $script
    [IO.File]::WriteAllText((Join-Path $fixtureTools 'astralarchitect-deploy.config.json'), '{"pluginsDirectory":"default-relative/plugins"}')
    foreach ($case in @(
        @{Arguments=@();Expected='default-relative'},
        @{Arguments=@();Expected='local-relative';Local=$true},
        @{Arguments=@('-ConfigPath',(Join-Path $root 'custom.json'));Expected='explicit-relative'},
        @{Arguments=@('-PluginsDirectory','override-relative/plugins');Expected='override-relative'}
    )) {
        if ($case.ContainsKey('Local')) { [IO.File]::WriteAllText((Join-Path $fixtureTools 'astralarchitect-deploy.local.json'), '{"pluginsDirectory":"local-relative/plugins"}') }
        [IO.File]::WriteAllText((Join-Path $root 'custom.json'), '{"pluginsDirectory":"explicit-relative/plugins"}')
        $childArguments = @('-NoProfile', '-File', $script) + $case.Arguments
        $output = & pwsh @childArguments 2>&1 | Out-String
        Assert ($LASTEXITCODE -ne 0 -and $output.Contains($case.Expected)) 'AstralArchitect config precedence failed.'
    }
    Write-Host "PASS: 5 DB entry defaults/local/explicit config, 10 bootstrap/config selection, 09 precedence. Evidence: $root"
} finally {
    $env:PATH = $previousPath
    $env:AR_TOOL_CONFIG_CAPTURE = $previousCapture
    Stop-Transcript | Out-Null
}
