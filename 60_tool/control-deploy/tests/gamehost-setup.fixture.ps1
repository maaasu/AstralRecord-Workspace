#requires -Version 7.0
param(
    [string]$RealPackageDirectory = '',
    [string]$ExpectedManifestSha256 = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot '..\Initialize-ControlGameHost.ps1'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$fixture = Join-Path $tempRoot ("control-gamehost-fixture-" + [Guid]::NewGuid().ToString('N'))
$priorProgramData=$env:ProgramData
if (-not $fixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture path.' }

function Write-Json([string]$Path, $Value) {
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Expect-Failure([scriptblock]$Action, [string]$Label) {
    try { & $Action; throw "Expected failure was accepted: $Label" }
    catch {
        if ($_.Exception.Message -like "Expected failure was accepted:*") { throw }
    }
}

try {
    $release = Join-Path $fixture 'Release'
    $root = Join-Path $fixture 'game'
    $java = Join-Path $fixture 'java.exe'
    New-Item -ItemType Directory -Path $release, $root -Force | Out-Null
    $env:ProgramData=Join-Path $fixture 'program-data'
    Set-Content -LiteralPath $java -Value 'fixture only' -Encoding UTF8
    $files = @{}
    foreach ($component in @('Agent', 'Runner', 'Cli')) {
        $folder = Join-Path $release "Control\$component"
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        foreach ($name in @("AstralRecordControl.$component.exe", "AstralRecordControl.$component.dll", "AstralRecordControl.$component.runtimeconfig.json")) {
            Set-Content -LiteralPath (Join-Path $folder $name) -Value 'fixture only' -Encoding UTF8
        }
        Write-Json (Join-Path $folder 'control-package.json') @{ component = $component; phase = 'P3'; protocolVersion = 1 }
    }
    $toolFolder = Join-Path $release 'Tools\60_tool\control-deploy'
    New-Item -ItemType Directory -Path $toolFolder -Force | Out-Null
    foreach ($name in @('Install-Control.ps1', 'Install-ControlCli.ps1', 'Control.Deployment.psm1')) {
        Set-Content -LiteralPath (Join-Path $toolFolder $name) -Value '# fixture only' -Encoding UTF8
    }
    foreach ($file in Get-ChildItem -LiteralPath $release -Recurse -File) {
        $key = $file.FullName.Substring($release.Length + 1).Replace('\', '/')
        $files[$key] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    Write-Json (Join-Path $release 'release-manifest.json') @{ containsRuntimeConfiguration = $false; sha256 = $files }
    $seal = (Get-FileHash -LiteralPath (Join-Path $release 'release-manifest.json') -Algorithm SHA256).Hash
    $hostKey = $env:COMPUTERNAME.ToLowerInvariant()
    $hostId = [Guid]::NewGuid().ToString('D')
    $planPath = Join-Path $fixture 'plan.json'
    $receiptPath = Join-Path $fixture 'receipt.json'
    $origin = 'https://control.example.test:5443'
    $javaExecutableId = [Guid]::NewGuid().ToString('D')
    $velocityExecutableId = [Guid]::NewGuid().ToString('D')
    Write-Json $planPath @{
        schemaVersion = 1; publicOrigin = $origin
        hosts = @(@{ hostKey = $hostKey; displayName = 'fixture';
            managedRoots = @(@{ id = [Guid]::NewGuid().ToString('D'); path = $root });
            executables = @(
                @{ id = $javaExecutableId; path = $java; kind = 'Java' },
                @{ id = $velocityExecutableId; path = $java; kind = 'Velocity' }
            ) })
    }
    Write-Json $receiptPath @{
        schemaVersion = 1; publicOrigin = $origin
        hosts = @(@{ hostKey = $hostKey; hostId = $hostId })
    }
    $result = & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $release -ExpectedManifestSha256 $seal -PreflightOnly
    if ($result[-1] -notlike 'Fixture-safe preflight passed*') { throw 'Passive preflight did not complete.' }
    # An internally consistent seal must not hide a missing CLI installer until service setup starts.
    $installerKey='Tools/60_tool/control-deploy/Install-ControlCli.ps1'
    $missingInstaller=$files.Clone(); $missingInstaller.Remove($installerKey)
    Remove-Item -LiteralPath (Join-Path $release $installerKey)
    Write-Json (Join-Path $release 'release-manifest.json') @{ containsRuntimeConfiguration=$false;sha256=$missingInstaller }
    $missingSeal=(Get-FileHash -LiteralPath (Join-Path $release 'release-manifest.json') -Algorithm SHA256).Hash
    Expect-Failure { & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $release -ExpectedManifestSha256 $missingSeal -PreflightOnly } 'missing CLI installer'
    Set-Content -LiteralPath (Join-Path $release $installerKey) -Value '# fixture only' -Encoding UTF8
    Write-Json (Join-Path $release 'release-manifest.json') @{ containsRuntimeConfiguration=$false;sha256=$files }
    $seal=(Get-FileHash -LiteralPath (Join-Path $release 'release-manifest.json') -Algorithm SHA256).Hash
    if ($RealPackageDirectory) {
        if ($ExpectedManifestSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'A sealed package hash is required.' }
        $realResult = & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $RealPackageDirectory -ExpectedManifestSha256 $ExpectedManifestSha256 -PreflightOnly
        if ($realResult[-1] -notlike 'Fixture-safe preflight passed*') { throw 'Sealed package preflight did not complete.' }
    }
    Expect-Failure { & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $release -ExpectedManifestSha256 ('0' * 64) -PreflightOnly } 'manifest seal'
    $wrongReceipt = Get-Content -Raw -Encoding UTF8 -LiteralPath $receiptPath | ConvertFrom-Json -AsHashtable
    $wrongReceipt.publicOrigin = 'https://wrong.example.test'
    Write-Json $receiptPath $wrongReceipt
    Expect-Failure { & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $release -ExpectedManifestSha256 $seal -PreflightOnly } 'origin mismatch'
    $wrongReceipt.publicOrigin = $origin
    Write-Json $receiptPath $wrongReceipt
    Remove-Item -LiteralPath $java
    Expect-Failure { & $script -PlanPath $planPath -HostReceiptPath $receiptPath -PackageDirectory $release -ExpectedManifestSha256 $seal -PreflightOnly } 'missing Java'
    Set-Content -LiteralPath $java -Value 'fixture only' -Encoding UTF8

    $tokens = $null
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($script, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'Initializer PowerShell parse failed.' }
    foreach ($functionName in @('Assert-LocalPath', 'Assert-Guid', 'New-Resources', 'Stop-ServiceAndConfirm', 'Start-PassiveServices')) {
        $definition = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName }, $false) | Select-Object -First 1
        if ($null -eq $definition) { throw "Missing startup function: $functionName" }
        Invoke-Expression $definition.Extent.Text
    }
    $planHost = (Get-Content -Raw -Encoding UTF8 -LiteralPath $planPath | ConvertFrom-Json -AsHashtable).hosts[0]
    $resources = New-Resources $planHost
    if ($resources.executables.Count -ne 2 -or
        @($resources.executables | Where-Object { $_.id -eq $javaExecutableId -and $_.path -eq $java -and $_.kind -eq 'Java' }).Count -ne 1 -or
        @($resources.executables | Where-Object { $_.id -eq $velocityExecutableId -and $_.path -eq $java -and $_.kind -eq 'Velocity' }).Count -ne 1) {
        throw 'Java and Velocity resource IDs, paths, or kinds were not preserved.'
    }
    $runnerName = 'AstralRecordControl.Runner'
    $agentName = 'AstralRecordControl.Agent'
    $script:states = @{}
    $script:stopOrder = [Collections.Generic.List[string]]::new()
    $script:failAt = $agentName
    function Start-Service {
        param([string]$Name)
        $script:states[$Name] = if ($Name -eq $script:failAt) { 'StartPending' } else { 'Running' }
        if ($Name -eq $script:failAt) { throw 'Simulated SCM timeout after start request.' }
    }
    function Get-Service {
        param([string]$Name, $ErrorAction)
        return [pscustomobject]@{ Status = $script:states[$Name] }
    }
    function Stop-Service {
        param([string]$Name, $ErrorAction)
        $script:stopOrder.Add($Name)
        $script:states[$Name] = 'Stopped'
    }
    function Start-Sleep { param([int]$Milliseconds) }

    $script:states[$runnerName] = 'Stopped'
    $script:states[$agentName] = 'Stopped'
    Expect-Failure { Start-PassiveServices } 'Agent start timeout rollback'
    if ($script:states[$runnerName] -ne 'Stopped' -or $script:states[$agentName] -ne 'Stopped' -or
        ($script:stopOrder -join ',') -ne "$agentName,$runnerName") { throw 'Agent timeout rollback did not stop and confirm both requested services in reverse order.' }

    $script:states[$runnerName] = 'Stopped'
    $script:states[$agentName] = 'Stopped'
    $script:stopOrder.Clear()
    $script:failAt = $runnerName
    Expect-Failure { Start-PassiveServices } 'Runner start timeout rollback'
    if ($script:states[$runnerName] -ne 'Stopped' -or $script:stopOrder.Count -ne 1 -or
        $script:stopOrder[0] -ne $runnerName) { throw 'Runner timeout rollback did not stop and confirm the requested service.' }
    Write-Output 'Game host setup fixture passed: Java and Velocity preflight, three refusals, and two startup rollback cases.'
} finally {
    $env:ProgramData=$priorProgramData
    if ($fixture.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $fixture)) {
        Remove-Item -LiteralPath $fixture -Recurse -Force
    }
}
