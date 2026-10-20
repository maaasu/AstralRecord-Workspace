#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param([Parameter(Mandatory)][string]$PackageDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
if (!$IsWindows) { throw 'The Control CLI requires Windows.' }
$source=Assert-ControlPackage $PackageDirectory 'Cli'
$base=Get-ControlAbsolutePath (Join-Path $env:LOCALAPPDATA 'AstralRecordControl\Cli')
$destination=Join-Path $base 'bin'
Assert-ControlSeparatePaths $source $base
if (Test-Path -LiteralPath $destination) { Assert-ControlPackage $destination 'Cli' | Out-Null }
if (!$PSCmdlet.ShouldProcess($destination,'Install the Control CLI for this Windows user, keeping the saved login')) { return }
New-Item -ItemType Directory -Path $base -Force | Out-Null
$stage=Join-Path $base ('stage-'+[Guid]::NewGuid().ToString('N'))
$backup=Join-Path $base ('bin.before-'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[Guid]::NewGuid().ToString('N'))
$movedOld=$false
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $stage -Recurse
    Assert-ControlPackage $stage 'Cli' | Out-Null
    if (Test-Path -LiteralPath $destination) { [IO.Directory]::Move($destination,$backup); $movedOld=$true }
    [IO.Directory]::Move($stage,$destination)
} catch {
    if ($movedOld -and !(Test-Path -LiteralPath $destination)) { [IO.Directory]::Move($backup,$destination) }
    throw
} finally {
    if (Test-Path -LiteralPath $stage) {
        $resolved=Get-ControlAbsolutePath $stage
        if ([IO.Path]::GetDirectoryName($resolved) -ine $base -or [IO.Path]::GetFileName($resolved) -notmatch '^stage-[a-f0-9]{32}$') { throw 'Unexpected CLI staging path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
Write-Output (Join-Path $destination 'AstralRecordControl.Cli.exe')
Write-Output 'CLI installation complete. The existing saved login was kept.'
