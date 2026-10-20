#requires -Version 7.0
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ConfigPath,
    [Parameter(Mandatory)][string]$ManifestPath,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ManifestSha256,
    [ValidateSet('Preflight','Apply')][string]$Phase='Preflight',
    [switch]$AdmissionClosed,
    [switch]$AutomaticWritersStopped
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (!$IsWindows -or $env:COMPUTERNAME -ine 'APP-01') { throw 'Run locally on APP-01.' }
Import-Module (Join-Path $PSScriptRoot 'ApiFilebaseCutover.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '../control-gate/Control.Gate.psm1') -Force
$apiRoot='C:\app-01\AstralRecordApi'
$newRoot='C:\app-01\workspace\40_filebase'
$settingsPath=Join-Path $apiRoot 'appsettings.json'
$reportDirectory='C:\app-01\ControlDeployment\EnvironmentCutover'
$runId=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$privateRoot=Join-Path $env:ProgramData 'AstralRecordEnvironmentCutover'
$runDirectory=Join-Path $privateRoot $runId
$report=[ordered]@{schemaVersion=1;computer='APP-01';phase=$Phase;runId=$runId;success=$false;stage='Preflight';errorType=$null;newRoot=$newRoot}
$serverManager=$null; $gate=$null; $lock=$null; $poolStopped=$false; $startedNewConfiguration=$false; $settingsChanged=$false; $apiVerified=$false
function Assert-NoConfigurationOverrides {
    foreach ($name in @('FileDatabase__RootPath','FileDatabase:RootPath','MasterData__AutoSeedOnStartup','MasterData:AutoSeedOnStartup')) {
        if ([Environment]::GetEnvironmentVariable($name,'Machine') -or [Environment]::GetEnvironmentVariable($name,'Process')) { throw 'A machine/process configuration override requires review.' }
    }
    foreach ($file in Get-ChildItem -LiteralPath $apiRoot -Filter 'appsettings.*.json' -File) {
        $variant=Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName | ConvertFrom-Json -AsHashtable
        if (($variant['FileDatabase'] -and $variant.FileDatabase.Contains('RootPath')) -or ($variant['MasterData'] -and $variant.MasterData.Contains('AutoSeedOnStartup'))) { throw 'An environment-specific configuration override requires review.' }
    }
    [xml]$web=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $apiRoot 'web.config')
    foreach ($node in $web.SelectNodes('//aspNetCore/environmentVariables/environmentVariable')) {
        if ($node.GetAttribute('name') -match '^(FileDatabase|MasterData)(__|:)') { throw 'A web.config override requires review.' }
    }
    if ($web.SelectSingleNode('//aspNetCore').GetAttribute('arguments') -match '(?i)FileDatabase|MasterData') { throw 'Command-line configuration overrides require review.' }
    [xml]$iis=Get-Content -Raw -Encoding UTF8 -LiteralPath "$env:windir\System32\inetsrv\config\applicationHost.config"
    $poolNode=$iis.SelectSingleNode('/configuration/system.applicationHost/applicationPools/add[@name="AstralRecordApiPool"]')
    foreach ($node in $poolNode.SelectNodes('environmentVariables/add')) {
        if ($node.GetAttribute('name') -match '^(FileDatabase|MasterData)(__|:)') { throw 'An app pool configuration override requires review.' }
    }
}
function Invoke-LocalApi([string]$Path) {
    # The fixed HTTPS origin is verified normally. Never include the key in a URL or report.
    return Invoke-RestMethod -Uri ('https://app-01:444/api/'+$Path) -Headers $headers -TimeoutSec 120 -MaximumRedirection 0 -NoProxy
}
function Wait-Pool([string]$State) {
    $timer=[Diagnostics.Stopwatch]::StartNew()
    while ($pool.State.ToString() -ne $State) {
        if ($timer.Elapsed.TotalSeconds -gt 60) { throw 'The API app pool did not reach the required state.' }
        Start-Sleep -Milliseconds 250
    }
}
try {
    foreach ($path in @($apiRoot,$newRoot,$settingsPath,$ManifestPath,$ConfigPath)) { Assert-CutoverLocalPath $path }
    if ((Get-FileHash -LiteralPath $ManifestPath -Algorithm SHA256).Hash -ine $ManifestSha256) { throw 'The approved manifest hash differs.' }
    $expected=Get-Content -Raw -Encoding UTF8 -LiteralPath $ManifestPath | ConvertFrom-Json -AsHashtable
    Assert-CutoverFileManifest $expected (Get-CutoverFileManifest $newRoot)
    $originalBytes=[IO.File]::ReadAllBytes($settingsPath)
    $originalJson=Get-Content -Raw -Encoding UTF8 -LiteralPath $settingsPath
    $updatedJson=ConvertTo-CutoverApiSettings $originalJson
    $settings=$originalJson | ConvertFrom-Json -AsHashtable
    $headers=@{'X-Api-Key'=[string]$settings.ApiKey.Key}
    if (!$headers['X-Api-Key']) { throw 'The API key is not available in the local configuration.' }
    Assert-NoConfigurationOverrides
    Add-Type -Path "$env:windir\System32\inetsrv\Microsoft.Web.Administration.dll"
    $serverManager=[Microsoft.Web.Administration.ServerManager]::new()
    $site=$serverManager.Sites['AstralRecordApi']
    $pool=$serverManager.ApplicationPools['AstralRecordApiPool']
    if (!$site -or !$pool -or $site.Applications['/'].ApplicationPoolName -ne $pool.Name -or [Environment]::ExpandEnvironmentVariables($site.Applications['/'].VirtualDirectories['/'].PhysicalPath).TrimEnd('\') -ine $apiRoot) { throw 'The IIS site, pool, or physical path differs.' }
    if ($pool.State.ToString() -ne 'Started') { throw 'The API pool must be healthy and started before cutover.' }
    if ($pool.ProcessModel.IdentityType.ToString() -ne 'ApplicationPoolIdentity' -or $pool.ProcessModel.ManualGroupMembership) { throw 'A nonstandard API pool identity requires an access review.' }
    $poolSid=[Security.Principal.NTAccount]::new('IIS AppPool\AstralRecordApiPool').Translate([Security.Principal.SecurityIdentifier]).Value
    $aclPaths=@($newRoot)+@($expected.files.Keys | ForEach-Object { Join-Path $newRoot $_ })+@(Get-ChildItem -LiteralPath $newRoot -Directory -Recurse | ForEach-Object { $_.FullName })
    foreach ($path in $aclPaths) {
        Assert-CutoverAccessRules (Get-Acl -LiteralPath $path).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) @($poolSid,'S-1-5-32-568')
    }
    $health=Invoke-LocalApi 'health'
    if ($health.status -cne 'ok' -or $health.service -cne 'AstralRecordApi') { throw 'The API did not pass the authenticated TLS health check.' }
    $config=Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json -AsHashtable
    if ((Get-ControlGateMode $config) -cne 'Managed') { throw 'The API cutover requires the managed Control gate.' }
    $runtimeIds=@('Dev','Build','Channel1','Channel2')
    $bindings=@($config.controlGate.bindings.servers.runtimeServerId | Sort-Object -Unique)
    if (($bindings -join ',') -cne (($runtimeIds | Sort-Object) -join ',')) { throw 'Bind every shared API RPG server exactly once.' }
    $boundPaths=@($config.controlGate.bindings.paths.path)
    if ($boundPaths.Count -ne 2 -or $apiRoot -notin $boundPaths -or $newRoot -notin $boundPaths) { throw 'Bind exactly the local API and new Filebase directories for this cutover.' }
    # Enter-ControlGate validates canonical paths/IDs and acquires live Runner holds
    # before any app pool or configuration mutation in Apply.
    $report['fileCount']=$expected.fileCount
    $report['oldRoot']=[string]$settings.FileDatabase.RootPath
    $report['poolIdentityType']=$pool.ProcessModel.IdentityType.ToString()
    if ($Phase -eq 'Preflight') { $report.success=$true; $report.stage='PreflightComplete'; return }
    if (!$AdmissionClosed -or !$AutomaticWritersStopped) { throw 'Apply requires explicit -AdmissionClosed and -AutomaticWritersStopped after checking player admission and all automatic writers.' }
    if ($settings.FileDatabase.RootPath -ieq $newRoot) { throw 'The root already points to the new location; verify the existing run rather than seeding again.' }
    New-CutoverPrivateDirectory $privateRoot
    $lock=[IO.File]::Open((Join-Path $privateRoot 'cutover.lock'),'OpenOrCreate','ReadWrite','None')
    New-CutoverPrivateDirectory $runDirectory
    [IO.File]::WriteAllBytes((Join-Path $runDirectory 'appsettings.original.json'),$originalBytes)
    $gate=Enter-ControlGate -Configuration $config -Operation '01' -RuntimeServerIds $runtimeIds -AffectedPaths @($apiRoot,$newRoot) -RequiredState Stopped -RunReference $runDirectory
    $report['workflowId']=$gate.WorkflowId; $report['reservationId']=$gate.ReservationId
    Assert-ControlGateStep $gate 'Deploy'
    $report.stage='StoppingApiPool'
    $null=$pool.Stop(); Wait-Pool 'Stopped'; $poolStopped=$true
    Assert-ControlGateStep $gate 'Deploy'
    Assert-NoConfigurationOverrides
    Assert-CutoverFileManifest $expected (Get-CutoverFileManifest $newRoot)
    if (![Linq.Enumerable]::SequenceEqual[byte]($originalBytes,[IO.File]::ReadAllBytes($settingsPath))) { throw 'API configuration changed after preflight.' }
    # Preserve the original UTF-8 BOM and ACL; File.Replace retains a recoverable original.
    $hasBom=$originalBytes.Length -ge 3 -and $originalBytes[0] -eq 239 -and $originalBytes[1] -eq 187 -and $originalBytes[2] -eq 191
    $temporary=Join-Path $runDirectory 'appsettings.updated.json'
    [IO.File]::WriteAllText($temporary,$updatedJson,[Text.UTF8Encoding]::new($hasBom))
    Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $settingsPath)
    [IO.File]::Replace($temporary,$settingsPath,(Join-Path $runDirectory 'appsettings.replaced.json'))
    $settingsChanged=$true
    $report.stage='StartingApiPoolAndSeed'
    $startedAt=[DateTimeOffset]::UtcNow
    Assert-ControlGateStep $gate 'Seed'
    $startedNewConfiguration=$true
    $null=$pool.Start(); Wait-Pool 'Started'; $poolStopped=$false
    $deadline=[DateTimeOffset]::UtcNow.AddMinutes(10)
    do {
        Assert-ControlGateStep $gate 'Seed'
        try { $health=Invoke-LocalApi 'health'; break } catch { if ([DateTimeOffset]::UtcNow -ge $deadline) { throw 'API startup did not complete within ten minutes.' }; Start-Sleep -Seconds 2 }
    } while ($true)
    if ($health.status -cne 'ok') { throw 'The API health check failed.' }
    $master=Invoke-LocalApi 'master-data/health'
    $runs=@(Invoke-LocalApi 'master-data/seed-runs?limit=20')
    $seed=@($runs | Where-Object { $_.seedRunId -eq $master.lastSeedRunId })
    if ($master.status -cne 'ok' -or $master.lastSeedRunStatus -cne 'SUCCEEDED' -or $seed.Count -ne 1 -or $seed[0].sourceRootPath -ine $newRoot -or (ConvertFrom-CutoverUtcTimestamp $seed[0].startedAt) -lt $startedAt.AddSeconds(-5) -or $seed[0].triggerType -cne 'STARTUP') { throw 'The new Filebase startup seed could not be confirmed.' }
    Assert-CutoverFileManifest $expected (Get-CutoverFileManifest $newRoot)
    $report['seedRunId']=$master.lastSeedRunId; $report['activeEntryCount']=$master.activeEntryCount
    $apiVerified=$true; $report['apiVerified']=$true
    $report.stage='ReleasingControl'
    Complete-ControlGate $gate $runDirectory
    $report.stage='Verified'
    $report.success=$true
} catch {
    $report.errorType=$_.Exception.GetType().FullName
    # Once startup seed may have run, restoring settings would silently seed old data.
    # Keep the API stopped and retain the reservation for deliberate recovery instead.
    if ($apiVerified) {
        # Release may have reached Control even when its acknowledgement/local
        # journal write failed. Do not stop a verified API after releasing its hold.
        $report['reservationReleaseUncertain']=$true
    } elseif ($startedNewConfiguration -and $pool) {
        try { $null=$pool.Stop(); Wait-Pool 'Stopped'; $poolStopped=$true } catch { $report['poolStopFailed']=$true }
    } elseif ($poolStopped -and $settingsChanged) {
        [IO.File]::WriteAllBytes($settingsPath,$originalBytes)
    }
    Write-Host 'Cutover stopped. Review the sanitized report and protected backup; keep admission closed.'
    throw [InvalidOperationException]::new('API Filebase cutover failed; see the sanitized stage/errorType report.')
} finally {
    if ($serverManager) { $serverManager.Dispose() }
    if ($lock) { $lock.Dispose() }
    $headers=$null; $settings=$null; $originalJson=$null; $updatedJson=$null; $originalBytes=$null
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    $report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $reportDirectory ('APP-01-filebase-'+$runId+'.json')) -Encoding utf8NoBOM
    Write-Host "Report directory: $reportDirectory"
}
