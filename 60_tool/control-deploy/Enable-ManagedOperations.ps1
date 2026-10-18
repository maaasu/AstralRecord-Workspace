#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$ToolRoot=(Join-Path $PSScriptRoot '..'),
    [switch]$Renew,
    [string]$CliPath='',
    [string]$RunnerDataRoot=(Join-Path $env:ProgramData 'AstralRecordControl\Runner')
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (!$IsWindows) { throw 'Windows is required.' }
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
if (![Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Managed operations attestation requires an elevated OS administrator.' }
$service=Get-CimInstance Win32_Service -Filter "Name='AstralRecordControl.Runner'"
if (!$service) { throw 'An installed Runner service is required.' }
if ($service.StartName -ne 'NT SERVICE\AstralRecordControl.Runner') { throw 'Unexpected Runner service identity.' }
$match=[regex]::Match([string]$service.PathName,'^"([^"]+AstralRecordControl\.Runner\.exe)"')
if (!$match.Success) { throw 'Runner service binary path is invalid.' }
$package=[IO.Path]::GetDirectoryName($match.Groups[1].Value)
Assert-ControlPackage $package Runner | Out-Null
$pipeName=''
if ($Renew) {
    $data=Get-ControlAbsolutePath $RunnerDataRoot
    $dataMatch=[regex]::Match([string]$service.PathName,'--data-root\s+"([^"]+)"')
    if (!$dataMatch.Success -or (Get-ControlAbsolutePath $dataMatch.Groups[1].Value) -ine $data) { throw 'Runner data root does not match the installed service.' }
    Test-ControlConfiguration $data Runner
    $config=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $data 'config.json') | ConvertFrom-Json -AsHashtable
    if ($config.enableProcessControl -ne $true) { throw 'Runner process control must be enabled before renewing managed operations.' }
    $pipeName=if ($config.pipeName) { [string]$config.pipeName } else { 'AstralRecordControl.Runner.v1' }
    Assert-ControlRunnerUpdateReady $CliPath $pipeName
}
if ($PSCmdlet.ShouldProcess($ToolRoot,'Attest installed P1 tool entries and atomically publish the managed operations marker')) {
    Enable-ControlManagedOperations $ToolRoot -Renew:$Renew -CliPath $CliPath -PipeName $pipeName
}
