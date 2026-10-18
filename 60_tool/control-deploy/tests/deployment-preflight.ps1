#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\Control.Deployment.psm1') -Force
$temp = Join-Path ([IO.Path]::GetTempPath()) ('control-deploy-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
function Assert-Throws([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Expected preflight rejection.' }
}
try {
    foreach ($script in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '..') -Recurse -File | Where-Object { $_.Extension -in '.ps1','.psm1' }) {
        $parseErrors = $null
        [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$parseErrors) | Out-Null
        if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
    }
    Assert-Throws { Get-ControlAbsolutePath '\\server\share' }
    Assert-Throws { Get-ControlAbsolutePath 'relative\path' }
    Assert-Throws { Get-ControlAbsolutePath 'C:\' }
    Assert-Throws { Assert-ControlSeparatePaths (Join-Path $temp 'data') (Join-Path $temp 'data\binary') }
    $package = Join-Path $temp 'package'
    New-Item -ItemType Directory -Path $package | Out-Null
    foreach ($name in 'AstralRecordControl.Web.exe','AstralRecordControl.Web.dll','AstralRecordControl.Web.runtimeconfig.json') { [IO.File]::WriteAllText((Join-Path $package $name), '{}') }
    @{component='Web';phase='P0';protocolVersion=1} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $package 'control-package.json') -Encoding utf8
    Assert-ControlPackage $package Web | Out-Null
    [IO.File]::WriteAllText((Join-Path $package 'control.db-wal'), 'sensitive-fixture')
    Assert-Throws { Assert-ControlPackage $package Web }
    Remove-Item -LiteralPath (Join-Path $package 'control.db-wal')
    $web = Join-Path $temp 'Web'
    New-Item -ItemType Directory -Path $web | Out-Null
    $configuration = @{Control=@{PublicOrigin='https://control.example.test'};Kestrel=@{Endpoints=@{Https=@{Url='https://0.0.0.0:5443';Certificate=@{Subject='control.example.test';Store='My';Location='LocalMachine'}}}}}
    $configuration | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $web 'config.json') -Encoding utf8
    Assert-Throws { Test-ControlConfiguration $web Web }
    [IO.File]::WriteAllText((Join-Path $web 'control.db'), '')
    Test-ControlConfiguration $web Web
    $configuration.Control.PublicOrigin = 'http://control.example.test'
    $configuration | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $web 'config.json') -Encoding utf8
    Assert-Throws { Test-ControlConfiguration $web Web }
    Write-Output 'Deployment preflight fixtures passed (no service registration or deployment).'
} finally {
    $resolved = [IO.Path]::GetFullPath($temp)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notlike 'control-deploy-test-*') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
