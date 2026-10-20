#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$HostReports,
    [Parameter(Mandatory)][hashtable]$ExpectedHosts,
    [string]$DataRoot=(Join-Path $env:ProgramData 'AstralRecordControl\Web'),
    [Parameter(Mandatory)][string]$ReportDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Assert-OperationHostReports([string[]]$Paths, [hashtable]$Hosts) {
    if ($Hosts.Count -eq 0 -or $Paths.Count -ne $Hosts.Count) { throw 'Exactly one report per expected game host is required.' }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($path in $Paths) {
        $report=Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
        $id=[Guid]::Empty
        $observed=[DateTimeOffset]::MinValue
        # ConvertFrom-Json may return DateTime. String conversion drops its UTC kind.
        $validTimestamp=$false
        if ($report -is [Collections.IDictionary] -and $report.Contains('collectedUtc')) {
            if ($report.collectedUtc -is [DateTime]) { $observed=[DateTimeOffset]::new($report.collectedUtc); $validTimestamp=$true }
            elseif ($report.collectedUtc -is [string]) { $validTimestamp=[DateTimeOffset]::TryParse($report.collectedUtc,[ref]$observed) }
        }
        if ($report -isnot [Collections.IDictionary] -or $report.schemaVersion -ne 1 -or
            $report.success -isnot [bool] -or !$report.success -or
            $report.processControlEnabled -isnot [bool] -or !$report.processControlEnabled -or
            !$Hosts.ContainsKey([string]$report.computerName) -or !$seen.Add([string]$report.computerName) -or
            ![Guid]::TryParse([string]$report.hostId,[ref]$id) -or $id -eq [Guid]::Empty -or
            $id -ne [Guid]$Hosts[[string]$report.computerName] -or
            $report.runnerStatus -ne 'Running' -or $report.agentStatus -ne 'Running' -or
            ($report.appliedInventoryRevision -isnot [long] -and $report.appliedInventoryRevision -isnot [int]) -or
            ($report.registeredServerCount -isnot [long] -and $report.registeredServerCount -isnot [int]) -or
            $report.appliedInventoryRevision -lt 1 -or $report.registeredServerCount -lt 1 -or
            [string]::IsNullOrWhiteSpace([string]$report.markerToolRoot) -or
            !$validTimestamp -or
            $observed -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or $observed -lt [DateTimeOffset]::UtcNow.AddHours(-24)) {
            throw 'A game host is not ready. Run its enable command again and copy the latest report.'
        }
    }
}

function Write-OperationWebConfig([string]$Path, [hashtable]$Configuration) {
    $temporary="$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    $backup="$Path.before-operations-$([Guid]::NewGuid().ToString('N')).bak"
    try {
        [IO.File]::WriteAllText($temporary,($Configuration | ConvertTo-Json -Depth 50),[Text.UTF8Encoding]::new($false))
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Path)
        [IO.File]::Replace($temporary,$Path,$backup)
        return $backup
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Get-OperationWebBinaryRoot($Service, [string]$Data) {
    if (!$Service) { throw 'Web service is not installed.' }
    $match=[regex]::Match([string]$Service.PathName,'^"([^"]+AstralRecordControl\.Web\.exe)"\s+--Control:DataRoot\s+"([^"]+)"\s+--contentRoot\s+"([^"]+)"\s*$')
    if (!$match.Success -or $Service.StartName -ine 'NT SERVICE\AstralRecordControl.Web' -or
        (Get-ControlAbsolutePath $match.Groups[2].Value) -ine $Data -or $Service.State -ne 'Running') { throw 'Unexpected Web service identity, data root or state.' }
    $binaryRoot=Get-ControlAbsolutePath ([IO.Path]::GetDirectoryName($match.Groups[1].Value))
    if ((Get-ControlAbsolutePath $match.Groups[3].Value) -ine $binaryRoot) { throw 'Web content root differs from its binary directory.' }
    return $binaryRoot
}

if (!$IsWindows) { throw 'Windows is required.' }
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
if (![Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run locally in an elevated administrator PowerShell.' }
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
$data=Get-ControlAbsolutePath $DataRoot
$reportRoot=Get-ControlAbsolutePath $ReportDirectory
if (!(Test-Path -LiteralPath $reportRoot -PathType Container)) { throw 'Report directory must already exist.' }
$configPath=Get-ControlAbsolutePath (Join-Path $data 'config.json')
$stage='Preflight'
$success=$false
$failureType=$null
$backup=$null
$lease=$null
$changed=$false
$webStopped=$false
try {
    Assert-OperationHostReports $HostReports $ExpectedHosts
    Test-ControlConfiguration $data Web
    $service=Get-CimInstance Win32_Service -Filter "Name='AstralRecordControl.Web'"
    $binaryRoot=Get-OperationWebBinaryRoot $service $data
    $null=Assert-ControlPackage $binaryRoot Web
    $configuration=Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json -AsHashtable
    $origin=[Uri]$configuration.Control.PublicOrigin
    $endpoint=[Uri]$configuration.Kestrel.Endpoints.Https.Url
    $configHash=(Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
    if (!$configuration.Control.ContainsKey('EnableProcessControl') -or !$configuration.Control.EnableProcessControl) {
        $stage='EnableWeb'
        $webStopped=$true
        Stop-Service -Name 'AstralRecordControl.Web'
        (Get-Service -Name 'AstralRecordControl.Web').WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))
        $lease=[IO.File]::Open((Get-ControlAbsolutePath (Join-Path $data 'web.lock')),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        if ((Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash -cne $configHash) { throw 'Configuration changed during preflight. Retry.' }
        $configuration.Control.EnableProcessControl=$true
        $backup=Write-OperationWebConfig $configPath $configuration
        $changed=$true
        Test-ControlConfiguration $data Web
        $lease.Dispose(); $lease=$null
        Start-Service -Name 'AstralRecordControl.Web'
        (Get-Service -Name 'AstralRecordControl.Web').WaitForStatus('Running',[TimeSpan]::FromSeconds(30))
        $webStopped=$false
    }
    $stage='VerifyHttps'
    $curl=Join-Path $env:SystemRoot 'System32\curl.exe'
    $port=if ($origin.IsDefaultPort) { 443 } else { $origin.Port }
    $connectTo='{0}:{1}:127.0.0.1:{2}' -f $origin.DnsSafeHost,$port,$endpoint.Port
    $healthy=$false
    for ($attempt=0;$attempt -lt 10;$attempt++) {
        $null=& $curl --noproxy '*' --connect-to $connectTo --max-time 5 --fail --silent ($origin.GetLeftPart([UriPartial]::Authority)+'/health/live') 2>$null
        if ($LASTEXITCODE -eq 0) { $healthy=$true; break }
        Start-Sleep -Seconds 1
    }
    if (!$healthy) { throw 'Local HTTPS health check failed.' }
    $stage='Ready'
    $success=$true
    Write-Host 'Basic process operations enabled. Confirm both hosts Online and applied revisions current in Control. Start Dev first, wait for startup, then use normal Stop.'
} catch {
    $failureType=$_.Exception.GetType().FullName
    # Restoring this flag does not touch game processes. Never stop a game Runner here.
    if ($changed -and $backup) {
        try {
            if ($lease) { $lease.Dispose(); $lease=$null }
            Stop-Service -Name 'AstralRecordControl.Web'
            (Get-Service -Name 'AstralRecordControl.Web').WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30))
            $lease=[IO.File]::Open((Join-Path $data 'web.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
            $original=Get-Content -Raw -Encoding UTF8 -LiteralPath $backup | ConvertFrom-Json -AsHashtable
            $null=Write-OperationWebConfig $configPath $original
            $lease.Dispose(); $lease=$null
            Start-Service -Name 'AstralRecordControl.Web'
            $webStopped=$false
        } catch { Write-Warning 'Web rollback was incomplete. Inspect the private configuration backup and service state.' }
    } elseif ($webStopped) {
        if ($lease) { $lease.Dispose(); $lease=$null }
        try {
            $current=Get-Service -Name 'AstralRecordControl.Web'
            if ($current.Status -eq 'StopPending') { $current.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(30)) }
            if ($current.Status -ne 'Running') { Start-Service -Name 'AstralRecordControl.Web' }
        } catch { Write-Warning 'Web could not be restarted. Inspect its service state.' }
    }
    throw "Control Web enable failed at $stage ($failureType). No game process was stopped."
} finally {
    if ($lease) { $lease.Dispose() }
    $report=@{schemaVersion=1;computerName=$env:COMPUTERNAME;collectedUtc=[DateTime]::UtcNow.ToString('o');success=$success;stage=$stage;failureType=$failureType}
    $reportPath=Join-Path $reportRoot ('Web-operations-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'.json')
    [IO.File]::WriteAllText($reportPath,($report | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Host "Report saved: $reportPath"
}
