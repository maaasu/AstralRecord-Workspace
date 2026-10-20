#requires -Version 7.0
#requires -RunAsAdministrator
[CmdletBinding()]
param([string]$OutputDirectory,[string]$CliPath)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'EnvironmentFacts.psm1') -Force
if (!$IsWindows -or $env:COMPUTERNAME -notin @('APP-01','DEV-01','MC-01')) { throw 'Run locally on APP-01, DEV-01, or MC-01.' }
if (!$OutputDirectory) {
    $OutputDirectory=switch ($env:COMPUTERNAME) {
        'APP-01' {'C:\app-01\ControlDeployment\EnvironmentCutover'}
        'MC-01' {'E:\mc-01\ControlDeployment\EnvironmentCutover'}
        'DEV-01' {'C:\AstralRecord-Worktrees\logs\EnvironmentCutover'}
    }
}
if (![IO.Path]::IsPathFullyQualified($OutputDirectory) -or $OutputDirectory.StartsWith('\\')) { throw 'Use a local absolute report directory.' }
$report=[ordered]@{schemaVersion=1;computer=$env:COMPUTERNAME;observedAtUtc=[DateTimeOffset]::UtcNow.ToString('O');checks=[ordered]@{}}
function Read-Facts([string]$Name,[scriptblock]$Read) {
    try { $report.checks[$Name]=@{success=$true;items=@(& $Read)} }
    catch { $report.checks[$Name]=@{success=$false;errorType=$_.Exception.GetType().FullName} }
}
Read-Facts 'services' {
    Get-CimInstance Win32_Service -Filter "Name LIKE 'AstralRecordControl%' OR Name='W3SVC' OR Name='WAS'" |
        Select-Object Name,State,StartName
}
foreach ($component in @('Agent','Runner')) {
    $path=Join-Path $env:ProgramData "AstralRecordControl\$component\config.json"
    Read-Facts $component { Get-PublicControlFacts (Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable) }
}
Read-Facts 'managedMarker' {
    $markerPath=Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    if (!(Test-Path -LiteralPath $markerPath)) { return @{exists=$false} }
    $marker=Get-Content -Raw -Encoding UTF8 -LiteralPath $markerPath | ConvertFrom-Json -AsHashtable
    @{exists=$true;schemaVersion=$marker['schemaVersion'];toolRoot=$marker['toolRoot']}
}
Read-Facts 'runnerSnapshot' {
    if (!$CliPath -or !(Test-Path -LiteralPath $CliPath -PathType Leaf)) { throw 'Provide the current Control CLI path to collect a snapshot.' }
    $runner=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $env:ProgramData 'AstralRecordControl\Runner\config.json') | ConvertFrom-Json -AsHashtable
    $pipe=if ($runner['pipeName']) {[string]$runner.pipeName} else {'AstralRecordControl.Runner.v1'}
    $response=& $CliPath runner snapshot --pipe-name $pipe
    if ($LASTEXITCODE -ne 0) { throw 'Runner snapshot failed.' }
    $snapshot=($response -join "`n") | ConvertFrom-Json -AsHashtable
    [ordered]@{appliedInventoryRevision=$snapshot['appliedInventoryRevision'];servers=@($snapshot['servers'] | Select-Object serverId,configRevision,processState,currentSessionId);holdCount=@($snapshot['holds']).Count}
}
Read-Facts 'javaProcesses' { Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Select-Object ProcessId,ExecutablePath }
Read-Facts 'apiSettings' {
    if ($env:COMPUTERNAME -ne 'APP-01') { return }
    $api=Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\app-01\AstralRecordApi\appsettings.json' | ConvertFrom-Json -AsHashtable
    [xml]$web=Get-Content -Raw -Encoding UTF8 -LiteralPath 'C:\app-01\AstralRecordApi\web.config'
    $overrides=@($web.SelectNodes('//aspNetCore/environmentVariables/environmentVariable') | Where-Object { $_.GetAttribute('name') -in @('FileDatabase__RootPath','ASPNETCORE_ENVIRONMENT','DOTNET_ENVIRONMENT') } | ForEach-Object {@{name=$_.GetAttribute('name');value=$_.GetAttribute('value')}})
    $variants=@(foreach ($file in Get-ChildItem -LiteralPath 'C:\app-01\AstralRecordApi' -Filter 'appsettings.*.json' -File) {
        $variant=Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName | ConvertFrom-Json -AsHashtable
        $filebase=if ($variant['FileDatabase'] -is [Collections.IDictionary]) {$variant.FileDatabase} else {@{}}
        $master=if ($variant['MasterData'] -is [Collections.IDictionary]) {$variant.MasterData} else {@{}}
        @{fileName=$file.Name;filebaseRoot=$filebase['RootPath'];autoSeedOnStartup=$master['AutoSeedOnStartup']}
    })
    [ordered]@{baseFilebaseRoot=$api.FileDatabase.RootPath;baseAutoSeedOnStartup=$api.MasterData.AutoSeedOnStartup;environmentSettings=$variants;targetExists=(Test-Path -LiteralPath 'C:\app-01\workspace\40_filebase' -PathType Container);machineFilebaseOverride=[Environment]::GetEnvironmentVariable('FileDatabase__RootPath','Machine');machineAspNetCoreEnvironment=[Environment]::GetEnvironmentVariable('ASPNETCORE_ENVIRONMENT','Machine');machineDotnetEnvironment=[Environment]::GetEnvironmentVariable('DOTNET_ENVIRONMENT','Machine');webConfigOverrides=$overrides}
}
Read-Facts 'iisSites' {
    if ($env:COMPUTERNAME -ne 'APP-01') { return }
    [xml]$iis=Get-Content -Raw -Encoding UTF8 -LiteralPath "$env:windir\System32\inetsrv\config\applicationHost.config"
    foreach ($site in $iis.configuration.'system.applicationHost'.sites.site) {
        if ([string]$site.name -notin @('AstralRecordApi','AstralRecordWeb')) { continue }
        [ordered]@{name=[string]$site.name;applications=@($site.application | ForEach-Object {@{pool=[string]$_.applicationPool;path=[string]$_.path}});bindings=@($site.bindings.binding | ForEach-Object {@{protocol=[string]$_.protocol;binding=[string]$_.bindingInformation;sslFlags=$_.GetAttribute('sslFlags')}})}
    }
    $pool=$iis.SelectSingleNode('/configuration/system.applicationHost/applicationPools/add[@name="AstralRecordApiPool"]')
    if ($null -ne $pool) {
        [ordered]@{pool='AstralRecordApiPool';environmentOverrides=@($pool.SelectNodes('environmentVariables/add') | Where-Object { $_.GetAttribute('name') -in @('FileDatabase__RootPath','ASPNETCORE_ENVIRONMENT','DOTNET_ENVIRONMENT') } | ForEach-Object {@{name=$_.GetAttribute('name');value=$_.GetAttribute('value')}})}
    }
}
Read-Facts 'https444Bindings' {
    if ($env:COMPUTERNAME -ne 'APP-01') { return }
    foreach ($endpoint in @('ipport=0.0.0.0:444','ipport=[::]:444','ipport=192.168.0.88:444','hostnameport=app-01:444')) {
        $lines=& netsh.exe http show sslcert $endpoint 2>&1
        # HTTP.sys reports public certificate hash/store and TLS policy, never private keys.
        [ordered]@{endpoint=$endpoint;exitCode=$LASTEXITCODE;binding=($lines -join "`n")}
    }
}
Read-Facts 'certificates' {
    foreach ($location in @('Cert:\LocalMachine\My','Cert:\LocalMachine\Root')) {
        foreach ($cert in @(Get-ChildItem -LiteralPath $location)) {
            $san=@($cert.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' } | ForEach-Object { $_.Format($false) }) -join '; '
            if ($cert.Subject -notmatch 'app-01|device_server|AstralRecord|astralrecord.com' -and $san -notmatch 'app-01|192\.168\.0\.88|astralrecord\.com') { continue }
            [ordered]@{store=$location;subject=$cert.Subject;issuer=$cert.Issuer;thumbprint=$cert.Thumbprint;expiresUtc=$cert.NotAfter.ToUniversalTime().ToString('O');subjectAlternativeNames=$san;hasPrivateKey=$cert.HasPrivateKey}
        }
    }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$destination=Join-Path $OutputDirectory ($env:COMPUTERNAME+'-environment-facts.json')
$report | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath $destination -Encoding UTF8
Write-Host "Read-only environment report: $destination"
if (@($report.checks.Values | Where-Object { !$_.success }).Count) { Write-Host 'Some checks failed; see their errorType fields. No configuration was changed.'; exit 1 }
