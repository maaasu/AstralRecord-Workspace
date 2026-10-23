#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot '../Control.Gate.psm1') -Force
function Assert([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Reject([scriptblock]$Action) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$true }; Assert $failed 'Expected rejection.' }
$temp=Join-Path ([IO.Path]::GetTempPath()) ('ar-dev-lifecycle-'+[Guid]::NewGuid().ToString('N'))
$prior=$env:AR_DEV_FIXTURE
try {
    New-Item -ItemType Directory -Path $temp | Out-Null
    $env:AR_DEV_FIXTURE=$temp
    $server=[Guid]::NewGuid().ToString('D'); $session=[Guid]::NewGuid().ToString('D')
    $settings=@{ServerId=$server;StopTimeoutSeconds=2;StartTimeoutSeconds=2;PollIntervalSeconds=1}
    $mock=Join-Path $temp 'cli.ps1'
    @'
$ErrorActionPreference='Stop'
$path=Join-Path $env:AR_DEV_FIXTURE 'central.json'
$state=Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
$command=$args[1]
if ($command -eq 'dev-runtime') {
    @{serverId=$state.serverId;configRevision=1;stateRevision=$state.revision;currentSessionId=$state.session;processState=$state.processState} | ConvertTo-Json -Compress
} elseif ($command -eq 'dev-operate') {
    $key=$args[[array]::IndexOf($args,'--idempotency-key')+1]
    $requestFile=$args[[array]::IndexOf($args,'--request-file')+1]
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath $requestFile
    $state.requests+=@(@{key=$key;body=$request})
    if (!$state.operations.ContainsKey($key)) {
        $r=$request | ConvertFrom-Json
        $state.operations[$key]=[Guid]::NewGuid().ToString('D'); $state.executions++
        if ($r.kind -ceq 'Stop') { $state.processState='Stopped';$state.session=$null }
        else { $state.processState='Running';$state.session=[Guid]::NewGuid().ToString('D') }
        $state.revision++
    }
    $result=@{operationId=$state.operations[$key];status='Accepted'}
    if ($state.loseResponse) { $state.loseResponse=$false; $state | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $path; $global:LASTEXITCODE=1; return }
    $result | ConvertTo-Json -Compress
} elseif ($command -eq 'dev-operation') {
    $state.polls++
    if ($state.losePoll) { $state.losePoll=$false;$state | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $path; $global:LASTEXITCODE=1;return }
    @{operationId=$args[[array]::IndexOf($args,'--operation-id')+1];status=$state.status} | ConvertTo-Json -Compress
} else { throw 'Unexpected command.' }
$state | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath $path
$global:LASTEXITCODE=0
'@ | Set-Content -Encoding UTF8 -LiteralPath $mock
    $context=@{Mode='Managed';AdapterId='01';RunReference=$temp;CliPath=$mock;LeaseFile=(Join-Path $temp 'lease.json');ServerUrl='https://control.example.test';WorkflowId=[Guid]::NewGuid().ToString('D');ReservationId=[Guid]::NewGuid().ToString('D')}
    function Write-Central($State) { $State | ConvertTo-Json -Depth 20 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $temp 'central.json') }
    function Read-Central { Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'central.json') | ConvertFrom-Json -AsHashtable }
    $central=@{serverId=$server;session=$session;processState='Running';revision=2;requests=@();operations=@{};executions=0;polls=0;loseResponse=$true;losePoll=$false;status='Succeeded'}
    Write-Central $central
    Reject { Invoke-ControlDevLifecycle $context $settings $temp Stop }
    $journal=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $temp 'control-dev-stop.json') | ConvertFrom-Json
    Assert (!$journal.operationId) 'A lost POST response must retain the original key without fabricating an operation ID.'
    Invoke-ControlDevLifecycle $context $settings $temp Stop
    $central=Read-Central
    Assert ($central.executions -eq 1 -and $central.requests.Count -eq 2) 'Lost Stop response must be replayed once with no duplicate execution.'
    Assert ($central.requests[0].key -ceq $central.requests[1].key -and $central.requests[0].body -ceq $central.requests[1].body) 'Replays must preserve the entire request and idempotency key.'
    Assert (($central.requests[0].body | ConvertFrom-Json).expectedServerSessionId -ceq $session) 'Stop must bind to the observed running session.'
    $central.losePoll=$true;Write-Central $central
    Reject { Invoke-ControlDevLifecycle $context $settings $temp Start }
    Invoke-ControlDevLifecycle $context $settings $temp Start
    $central=Read-Central
    Assert ($central.executions -eq 2 -and $central.requests.Count -eq 3) 'A lost operation poll must not resubmit Start.'
    Assert ($central.processState -ceq 'Running' -and $central.session -cne $session) 'Start must confirm a new running session.'
    $central.status='NeedsAttention';Write-Central $central
    Reject { Invoke-ControlDevLifecycle $context $settings $temp Stop }
    Assert ((Read-Central).executions -eq 2) 'NeedsAttention must preserve the operation, never kill or replace it.'
    $configuration=@{devWorkflow=@{controlDevLifecycle=@{enabled=$true}};controlGate=@{mode='Managed';bindings=@{servers=@(@{runtimeServerId='Dev';serverId=$server})}}}
    Assert ((Get-ControlDevLifecycleConfiguration $configuration @('Dev')).ServerId -ceq $server) 'Dev binding must be selected exactly.'
    Reject { Get-ControlDevLifecycleConfiguration $configuration @('Channel1') }
    Reject { Get-ControlDevLifecycleConfiguration $configuration @('Dev','Build') }
    Reject { Get-ControlDevLifecycleConfiguration $configuration @('Dev') -ServerAlreadyRunning }
    Write-Host 'Dev lifecycle replay and target boundary tests passed.'
} finally {
    $env:AR_DEV_FIXTURE=$prior
    if (Test-Path -LiteralPath $temp) {
        $resolved=[IO.Path]::GetFullPath($temp)
        if (![IO.Path]::GetFileName($resolved).StartsWith('ar-dev-lifecycle-') -or [IO.Path]::GetDirectoryName($resolved) -ine [IO.Path]::GetTempPath().TrimEnd('\')) { throw 'Unsafe fixture cleanup path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
