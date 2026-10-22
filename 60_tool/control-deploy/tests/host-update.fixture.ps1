#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('control-host-update-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$source = Join-Path $PSScriptRoot '..\Update-ControlHost.ps1'
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$null, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Host updater has parse errors.' }
$function = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-ControlHostUpdate' }, $true)
. ([scriptblock]::Create($function.Extent.Text))
$global:ControlHostFixtureCalls = [Collections.Generic.List[string]]::new()
$global:ControlHostFixturePreflights = [Collections.Generic.List[string]]::new()
$script:missingPackage = ''
$global:ControlHostFixtureFailure = ''
$global:ControlHostFixtureBadRegistration = ''
$global:ControlHostFixtureExitCode = 0
function Get-ControlAbsolutePath([string]$Path) { [IO.Path]::GetFullPath($Path) }
function Assert-ControlPackage([string]$Path, [string]$Component) {
    if ($Component -eq $script:missingPackage) { throw 'Incomplete fixture package.' }
    return $Path
}
function Test-ControlConfiguration([string]$Path, [string]$Component) { }
function Get-Service {
    param([string]$Name, $ErrorAction)
    $service = [pscustomobject]@{ Name = $Name }
    $service | Add-Member ScriptMethod WaitForStatus { param($Status, $Timeout) }
    return $service
}
@'
[CmdletBinding(SupportsShouldProcess)]
param([string]$Component,[string]$PackageDirectory,[string]$CliPath)
if ($Component -eq $global:ControlHostFixtureBadRegistration) { throw 'Unexpected fixture service identity.' }
if ($Component -eq $global:ControlHostFixtureFailure) { throw 'Running fixture server refused.' }
if ($Component -eq 'Runner' -and !$CliPath) { throw 'Runner CLI was not passed.' }
if ($WhatIfPreference) { $global:ControlHostFixturePreflights.Add($Component); return }
$global:ControlHostFixtureCalls.Add($Component)
$global:LASTEXITCODE = $global:ControlHostFixtureExitCode
'@ | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'Update-Control.ps1')

function Assert-Calls([string]$Expected) {
    if (($global:ControlHostFixtureCalls -join ',') -ne $Expected) { throw "Unexpected update calls: $($global:ControlHostFixtureCalls -join ',')" }
}
function Assert-Refusal([scriptblock]$Action,[string]$Message) {
    try { & $Action; throw 'Expected refusal.' }
    catch { if ($_.Exception.Message -ne $Message) { throw } }
}
try {
    foreach ($hostName in @('APP-01','MC-01','DEV-01')) {
        $global:ControlHostFixtureCalls.Clear()
        $global:ControlHostFixturePreflights.Clear()
        Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName $hostName -DeploymentDirectory $root
        Assert-Calls $(if ($hostName -eq 'APP-01') { 'Runner,Agent,Web' } else { 'Runner,Agent' })
        if (($global:ControlHostFixturePreflights -join ',') -ne ($global:ControlHostFixtureCalls -join ',')) { throw 'All service registrations must be checked before updates.' }
    }
    $global:ControlHostFixtureCalls.Clear()
    Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName APP-01 -DeploymentDirectory $root -WhatIf
    Assert-Calls ''
    $script:missingPackage = 'Web'
    Assert-Refusal { Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName APP-01 -DeploymentDirectory $root } 'Incomplete fixture package.'
    Assert-Calls ''
    $script:missingPackage = ''
    Assert-Refusal { Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName OTHER -DeploymentDirectory $root } 'この更新はAPP-01・MC-01・DEV-01用です。'
    Assert-Calls ''
    $global:ControlHostFixtureFailure = 'Runner'
    Assert-Refusal { Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName APP-01 -DeploymentDirectory $root } 'Running fixture server refused.'
    Assert-Calls ''
    $global:ControlHostFixtureFailure = ''
    $global:ControlHostFixtureBadRegistration = 'Agent'
    Assert-Refusal { Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName APP-01 -DeploymentDirectory $root } 'Unexpected fixture service identity.'
    Assert-Calls ''
    $global:ControlHostFixtureBadRegistration = ''
    $global:ControlHostFixtureExitCode = 5
    Assert-Refusal { Invoke-ControlHostUpdate -PackageDirectory $root -ComputerName APP-01 -DeploymentDirectory $root } 'Runner の更新が失敗しました。後続の更新を中断します。'
    Assert-Calls 'Runner'
    Write-Output 'Host update fixture passed: three hosts, WhatIf, package/host/Runner/registration refusals and failed update; no service changed.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $root).Path
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\control-host-update-'
    if (!$resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
