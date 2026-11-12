[CmdletBinding(SupportsShouldProcess)]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([Environment]::MachineName -ine 'MC-01') {
    throw 'Run this script locally on MC-01.'
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell session on MC-01.'
}

$serviceName = 'AstralRecordControl.Agent'
if (-not $PSCmdlet.ShouldProcess($serviceName, 'Configure restart after failures (10, 30, then 60 seconds)')) { return }

& sc.exe failure $serviceName 'reset=' 86400 'actions=' 'restart/10000/restart/30000/restart/60000'
if ($LASTEXITCODE -ne 0) { throw "Could not configure recovery for $serviceName (sc.exe exit $LASTEXITCODE)." }

& sc.exe failureflag $serviceName 1
if ($LASTEXITCODE -ne 0) { throw "Restart actions were configured, but the failure flag could not be enabled (sc.exe exit $LASTEXITCODE)." }

& sc.exe qfailure $serviceName
if ($LASTEXITCODE -ne 0) { throw "Could not read back recovery actions for $serviceName (sc.exe exit $LASTEXITCODE)." }

& sc.exe qfailureflag $serviceName
if ($LASTEXITCODE -ne 0) { throw "Could not read back the failure flag for $serviceName (sc.exe exit $LASTEXITCODE)." }
