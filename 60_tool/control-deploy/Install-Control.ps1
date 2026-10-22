#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][ValidateSet('Web','Agent','Runner')][string]$Component,
    [Parameter(Mandatory)][string]$PackageDirectory,
    [string]$CliPackageDirectory = '',
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'AstralRecordControl'),
    [string]$DataRoot = '',
    [switch]$Start,
    [switch]$KeepManual,
    [switch]$PreflightOnly,
    [switch]$EnableManagedOperations
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
if (-not $IsWindows) { throw 'Windows services are required.' }
if ($EnableManagedOperations -and $Component -ne 'Runner') { throw 'Managed operations attestation is installed with Runner on each game PC.' }
if ($KeepManual -and $Start) { throw 'KeepManual cannot be combined with Start.' }
$source = Assert-ControlPackage $PackageDirectory $Component
$binaryRoot = Get-ControlAbsolutePath (Join-Path $InstallRoot $Component)
if (-not $DataRoot) { $DataRoot = Join-Path $env:ProgramData "AstralRecordControl\$Component" }
$data = Get-ControlAbsolutePath $DataRoot
Assert-ControlSeparatePaths $binaryRoot $data
Assert-ControlSeparatePaths $source $binaryRoot
Assert-ControlSeparatePaths $source $data
$serviceName = "AstralRecordControl.$Component"
if ($PreflightOnly) { Test-ControlConfiguration $data $Component; Write-Output 'Configuration preflight passed.'; return }
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Service installation requires an elevated OS administrator.' }
$existing = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($existing) { throw 'Service already exists. Use Update-Control.ps1.' }
if ($Component -eq 'Agent' -and -not (Get-Service -Name 'AstralRecordControl.Runner' -ErrorAction SilentlyContinue)) {
    throw 'Register AstralRecordControl.Runner before Agent.'
}
if (-not $PSCmdlet.ShouldProcess($serviceName, 'Stage binaries, create service and restrict ACLs')) { return }
if ($Component -eq 'Agent') {
    if (!$CliPackageDirectory) { $CliPackageDirectory=Join-Path ([IO.Path]::GetDirectoryName($source)) 'Cli' }
    & (Join-Path $PSScriptRoot 'Install-ControlCli.ps1') -PackageDirectory $CliPackageDirectory
}
New-Item -ItemType Directory -Path $binaryRoot -Force | Out-Null
New-Item -ItemType Directory -Path $data -Force | Out-Null
$release = Join-Path $binaryRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $release | Out-Null
Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $release -Recurse
$exe = Join-Path $release "$serviceName.exe"
$arguments = if ($Component -eq 'Web') { "--Control:DataRoot `"$data`" --contentRoot `"$release`"" } else { "--data-root `"$data`"" }
$binPath = "`"$exe`" $arguments"
& sc.exe create $serviceName 'binPath=' $binPath 'start=' 'demand' 'obj=' "NT SERVICE\$serviceName"
if ($LASTEXITCODE -ne 0) { throw 'Service creation failed; staged binaries retained.' }
& sc.exe sidtype $serviceName unrestricted
if ($LASTEXITCODE -ne 0) { throw 'Service SID configuration failed.' }
$sid = [Security.Principal.NTAccount]::new("NT SERVICE\$serviceName").Translate([Security.Principal.SecurityIdentifier]).Value
Set-ControlAcl $binaryRoot $sid -ReadOnly
Set-ControlAcl $data $sid
if ($EnableManagedOperations) {
    $marker=Enable-ControlManagedOperations (Join-Path $PSScriptRoot '..')
    Write-Output "Managed tool attestation: $marker"
}
if (-not $KeepManual) {
    try { Set-ControlServiceStartup $Component }
    catch {
        & sc.exe config $serviceName 'start=' 'demand' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Service startup setup failed and $serviceName could not be returned to Manual: $($_.Exception.Message)" }
        throw
    }
}
Write-Output "Registered stopped service $serviceName. Service SID: $sid"
Write-Output "Persistent data: $data. Complete config/owner/token and certificate ACL before starting."
if ($Start) { Test-ControlConfiguration $data $Component; Start-Service -Name $serviceName }
