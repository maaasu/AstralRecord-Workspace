#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '..\Control.Deployment.psm1') -Force
$scriptPath=Join-Path $PSScriptRoot '..\Enable-ControlWebOperations.ps1'
$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($scriptPath,[ref]$null,[ref]$parseErrors)
if ($parseErrors.Count) { throw 'Web operations script parse failed.' }
# Load only helpers; never run the deployment body.
foreach ($name in @('Assert-OperationHostReports','Write-OperationWebConfig','Get-OperationWebBinaryRoot')) {
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if (!$definition) { throw "Missing helper $name." }
    . ([scriptblock]::Create($definition.Extent.Text))
}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('control-web-operations-'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $temp
function Assert-Rejected([scriptblock]$Action, [string]$Label='unspecified') {
    $rejected=$false
    try { & $Action } catch { $rejected=$true }
    if (!$rejected) { throw "Expected readiness rejection: $Label" }
}
try {
    $binaryRoot=Join-Path $temp 'Web\release'
    $data=Join-Path $temp 'WebData'
    $service=@{PathName=('"{0}\AstralRecordControl.Web.exe" --Control:DataRoot "{1}" --contentRoot "{0}"' -f $binaryRoot,$data);StartName='NT SERVICE\AstralRecordControl.Web';State='Running'}
    if ((Get-OperationWebBinaryRoot $service $data) -ine $binaryRoot) { throw 'Canonical Web service arguments were rejected.' }
    Assert-Rejected { Get-OperationWebBinaryRoot $service (Join-Path $temp 'WrongData') }
    $wrong=$service.Clone(); $wrong.StartName='LocalSystem'
    Assert-Rejected { Get-OperationWebBinaryRoot $wrong $data }
    $wrong=$service.Clone(); $wrong.PathName=('"{0}\AstralRecordControl.Web.exe" --data-root "{1}"' -f $binaryRoot,$data)
    Assert-Rejected { Get-OperationWebBinaryRoot $wrong $data }
    $wrong=$service.Clone(); $wrong.PathName=('"{0}\AstralRecordControl.Web.exe" --Control:DataRoot "{1}" --contentRoot "{2}"' -f $binaryRoot,$data,(Join-Path $temp 'WrongContent'))
    Assert-Rejected { Get-OperationWebBinaryRoot $wrong $data }
    $expected=@{'DEV-01'=[Guid]::NewGuid().ToString();'MC-01'=[Guid]::NewGuid().ToString()}
    $paths=@()
    foreach ($name in $expected.Keys) {
        $path=Join-Path $temp "$name.json"
        $report=@{schemaVersion=1;computerName=$name;hostId=$expected[$name];success=$true;processControlEnabled=$true;appliedInventoryRevision=3;registeredServerCount=1;markerToolRoot='C:\tools';runnerStatus='Running';agentStatus='Running';collectedUtc=[DateTime]::UtcNow.ToString('o')}
        [IO.File]::WriteAllText($path,($report|ConvertTo-Json))
        $paths+=$path
    }
    Assert-OperationHostReports $paths $expected
    Assert-Rejected { Assert-OperationHostReports @($paths[0]) $expected }
    Assert-Rejected { Assert-OperationHostReports @($paths[0],$paths[0]) $expected }
    $original=Get-Content -Raw -Encoding UTF8 -LiteralPath $paths[0] | ConvertFrom-Json -AsHashtable
    foreach ($mutation in @(
        @{success=$false},@{success='true'},@{processControlEnabled=$false},@{agentStatus='Stopped'},
        @{runnerStatus='Stopped'},@{hostId=[Guid]::NewGuid().ToString()},@{schemaVersion=2},
        @{registeredServerCount=0},@{appliedInventoryRevision=0},@{appliedInventoryRevision='3'},
        @{collectedUtc=[DateTime]::UtcNow.AddDays(-2).ToString('o')},@{collectedUtc=[DateTime]::UtcNow.AddHours(1).ToString('o')}
    )) {
        $bad=$original.Clone()
        foreach ($key in $mutation.Keys) { $bad[$key]=$mutation[$key] }
        [IO.File]::WriteAllText($paths[0],($bad|ConvertTo-Json))
        Assert-Rejected { Assert-OperationHostReports $paths $expected } ($mutation | ConvertTo-Json -Compress)
    }
    [IO.File]::WriteAllText($paths[0],($original|ConvertTo-Json))
    Assert-OperationHostReports $paths $expected
    $configPath=Join-Path $temp 'config.json'
    $config=@{Control=@{PublicOrigin='https://control.example.test';EnableProcessControl=$false;EnableFileControl=$false;EnableSchedules=$false};Kestrel=@{Endpoints=@{Https=@{Url='https://127.0.0.1:5443';Certificate=@{Subject='control.example.test'}}}};PrivateFixture='kept'}
    $before=$config|ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($configPath,$before)
    $aclBefore=(Get-Acl -LiteralPath $configPath).Sddl
    $config.Control.EnableProcessControl=$true
    $backup=Write-OperationWebConfig $configPath $config
    $actual=Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath|ConvertFrom-Json -AsHashtable
    if (!$actual.Control.EnableProcessControl -or $actual.Control.EnableFileControl -or $actual.Control.EnableSchedules -or
        $actual.PrivateFixture -cne 'kept' -or $actual.Kestrel.Endpoints.Https.Url -cne 'https://127.0.0.1:5443' -or
        (Get-Content -Raw -Encoding UTF8 -LiteralPath $backup) -cne $before -or
        (Get-Acl -LiteralPath $configPath).Sddl -cne $aclBefore) { throw 'Atomic configuration preservation failed.' }
    Write-Output 'Web operations fixtures passed: readiness rejection, configuration/ACL/backup preservation. No services or Minecraft were used.'
} finally {
    $resolved=[IO.Path]::GetFullPath($temp)
    if ([IO.Path]::GetDirectoryName($resolved) -ine [IO.Path]::GetTempPath().TrimEnd('\') -or [IO.Path]::GetFileName($resolved) -notlike 'control-web-operations-*') { throw 'Unsafe fixture cleanup.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
