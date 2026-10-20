#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=Join-Path ([IO.Path]::GetTempPath()) ('control-cli-install-test-'+[Guid]::NewGuid().ToString('N'))
$prior=$env:LOCALAPPDATA
$installer=Join-Path $PSScriptRoot '..\Install-ControlCli.ps1'
function Assert-Throws([scriptblock]$Action) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$true }; if (!$failed) { throw 'Expected installer rejection.' } }
try {
    New-Item -ItemType Directory -Path $root | Out-Null
    $env:LOCALAPPDATA=Join-Path $root 'user'
    $source=Join-Path $root 'package'; New-Item -ItemType Directory -Path $source | Out-Null
    foreach ($name in @('AstralRecordControl.Cli.exe','AstralRecordControl.Cli.dll','AstralRecordControl.Cli.runtimeconfig.json')) { 'version1' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $source $name) }
    @{component='Cli';phase='P3';protocolVersion=1}|ConvertTo-Json|Set-Content -Encoding UTF8 -LiteralPath (Join-Path $source 'control-package.json')
    $base=Join-Path $env:LOCALAPPDATA 'AstralRecordControl\Cli'; $bin=Join-Path $base 'bin'
    & $installer -PackageDirectory $source -WhatIf | Out-Null
    if (Test-Path -LiteralPath $base) { throw 'WhatIf created the installation.' }
    & $installer -PackageDirectory $source | Out-Null
    'saved-login-fixture' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $base 'session.bin')
    $sessionHash=(Get-FileHash -LiteralPath (Join-Path $base 'session.bin')).Hash
    'version2' | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $source 'AstralRecordControl.Cli.exe')
    & $installer -PackageDirectory $source | Out-Null
    if ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $bin 'AstralRecordControl.Cli.exe')).Trim() -cne 'version2') { throw 'CLI was not updated.' }
    if ((Get-FileHash -LiteralPath (Join-Path $base 'session.bin')).Hash -cne $sessionHash) { throw 'Saved login changed during installation.' }
    if (@(Get-ChildItem -LiteralPath $base -Directory -Filter 'bin.before-*').Count -ne 1) { throw 'Previous CLI was not retained.' }
    Assert-Throws { & $installer -PackageDirectory $bin }
    Remove-Item -LiteralPath (Join-Path $source 'control-package.json')
    Assert-Throws { & $installer -PackageDirectory $source }
    if ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $bin 'AstralRecordControl.Cli.exe')).Trim() -cne 'version2') { throw 'Rejected package changed the CLI.' }
    Write-Output 'PASS: CLI installation/update, saved login preservation, prior binary backup, WhatIf and invalid package rejection.'
} finally {
    $env:LOCALAPPDATA=$prior
    $resolved=[IO.Path]::GetFullPath($root)
    if ([IO.Path]::GetDirectoryName($resolved) -ine [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') -or [IO.Path]::GetFileName($resolved) -notmatch '^control-cli-install-test-[a-f0-9]{32}$') { throw 'Unexpected fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
