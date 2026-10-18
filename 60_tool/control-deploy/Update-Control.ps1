#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidateSet('Web','Agent')][string]$Component,
    [Parameter(Mandatory)][string]$PackageDirectory,
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'AstralRecordControl'),
    [string]$DataRoot = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
$source = Assert-ControlPackage $PackageDirectory $Component
$binaryRoot = Get-ControlAbsolutePath (Join-Path $InstallRoot $Component)
if (-not $DataRoot) { $DataRoot = Join-Path $env:ProgramData "AstralRecordControl\$Component" }
$data = Get-ControlAbsolutePath $DataRoot
Assert-ControlSeparatePaths $binaryRoot $data
Assert-ControlSeparatePaths $source $binaryRoot
Assert-ControlSeparatePaths $source $data
Test-ControlConfiguration $data $Component
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Service updates require an elevated OS administrator.' }
$name = "AstralRecordControl.$Component"
$service = Get-Service -Name $name
$registered = Get-CimInstance Win32_Service -Filter "Name='$name'"
if ($registered.StartName -ne "NT SERVICE\$name") { throw 'Unexpected service identity.' }
$expected = [regex]::Escape($binaryRoot + '\')
if ($registered.PathName -notmatch ('^"' + $expected)) { throw 'Service binary path is outside the managed installation.' }
if (-not $PSCmdlet.ShouldProcess($name, 'Stop only this service, snapshot data and switch to new binaries')) { return }
$sid = [Security.Principal.NTAccount]::new("NT SERVICE\$name").Translate([Security.Principal.SecurityIdentifier]).Value
$release = Join-Path $binaryRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $release | Out-Null
Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $release -Recurse
Set-ControlAcl $release $sid -ReadOnly
Stop-Service -Name $name
$service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
$lockFile = if ($Component -eq 'Web') { 'web.lock' } else { 'service.lock' }
$lease = [IO.File]::Open((Join-Path $data $lockFile), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    $snapshot = Join-Path $data ('updates\' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $snapshot -Force | Out-Null
    foreach ($file in Get-ChildItem -LiteralPath $data -File -Force) {
        if ($file.Name -match '\.db(?:-wal|-shm)?$') { Copy-Item -LiteralPath $file.FullName -Destination $snapshot }
    }
    $registered.PathName | Set-Content -Encoding utf8 -LiteralPath (Join-Path $snapshot 'previous-service-path.txt')
    $exe = Join-Path $release "$name.exe"
    $arguments = if ($Component -eq 'Web') { "--Control:DataRoot `"$data`" --contentRoot `"$release`"" } else { "--data-root `"$data`"" }
    & sc.exe config $name 'binPath=' "`"$exe`" $arguments"
    if ($LASTEXITCODE -ne 0) { throw 'Binary switch failed; service stays stopped.' }
} finally { $lease.Dispose() }
Start-Service -Name $name
Write-Output "Updated $name. Previous binaries and offline database snapshot retained. Verify health/Agent reconciliation."
