#requires -Version 7.0
[CmdletBinding()]
param([string]$Dotnet = 'dotnet', [string]$OutputDirectory = (Join-Path $PSScriptRoot 'publish'))
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$solutionRoot = Join-Path $repo '30_web\AstralRecordControl'
$output = Get-ControlAbsolutePath $OutputDirectory
Assert-ControlSeparatePaths $output (Join-Path $env:ProgramData 'AstralRecordControl')
if (Test-Path -LiteralPath $output) { throw 'Use a new empty output directory for each publish.' }
New-Item -ItemType Directory -Path $output | Out-Null
Push-Location $solutionRoot
try {
    & $Dotnet test 'AstralRecordControl.slnx' -c Release --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Control tests failed.' }
    foreach ($component in @('Web', 'Agent', 'Runner', 'Cli')) {
        $destination = Join-Path $output $component
        & $Dotnet publish "src/AstralRecordControl.$component/AstralRecordControl.$component.csproj" -c Release -r win-x64 --self-contained true -o $destination --nologo
        if ($LASTEXITCODE -ne 0) { throw "Publish failed: $component" }
        @{ component=$component; phase='P3'; protocolVersion=1; createdAtUtc=[DateTimeOffset]::UtcNow.ToString('O') } | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath (Join-Path $destination 'control-package.json')
        Assert-ControlPackage $destination $component | Out-Null
    }
} finally { Pop-Location }
Write-Output "Control P3 package: $output"
