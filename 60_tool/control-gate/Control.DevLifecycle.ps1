# Dot-sourced by Control.Gate.psm1. No direct process or service control is permitted here.
function Save-ControlDevJournal {
    param($Value,[string]$Path)
    $temporary=$Path+'.tmp'
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 12))
    $stream=[IO.File]::Open($temporary,'Create','Write','None')
    try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    [IO.File]::Move($temporary,$Path,$true)
}
function Get-ControlDevLifecycleConfiguration {
    param([hashtable]$Configuration, [string[]]$RuntimeServerIds, [switch]$ServerAlreadyRunning)
    $workflow=$Configuration['devWorkflow']
    if (!$workflow -or !$workflow.ContainsKey('controlDevLifecycle')) { return $null }
    $settings=$workflow['controlDevLifecycle']
    if ($settings -isnot [hashtable] -or $settings['enabled'] -isnot [bool]) { throw 'controlDevLifecycle.enabled must be boolean.' }
    if (!$settings['enabled']) { return $null }
    if ($ServerAlreadyRunning -or $RuntimeServerIds.Count -ne 1 -or $RuntimeServerIds[0] -cne 'Dev' -or
        !$Configuration['controlGate'] -or $Configuration['controlGate']['mode'] -cne 'Managed') {
        throw 'Automatic lifecycle requires Managed Control, the single runtime Dev, and stopped deployment mode.'
    }
    $binding=@($Configuration.controlGate.bindings.servers | Where-Object { $_.runtimeServerId -ceq 'Dev' })
    $id=[Guid]::Empty
    if ($binding.Count -ne 1 -or ![Guid]::TryParse([string]$binding[0].serverId,[ref]$id) -or $id -eq [Guid]::Empty) { throw 'Automatic lifecycle requires exactly one valid Dev binding.' }
    $result=@{ServerId=$id.ToString('D');StopTimeoutSeconds=120;StartTimeoutSeconds=900;PollIntervalSeconds=2}
    foreach ($name in @('stopTimeoutSeconds','startTimeoutSeconds','pollIntervalSeconds')) {
        if ($settings.ContainsKey($name)) {
            $value=$settings[$name]
            $maximum=if ($name -eq 'pollIntervalSeconds') { 60 } else { 86400 }
            if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 1 -or $value -gt $maximum) { throw "Invalid controlDevLifecycle.$name." }
            $result[$name]=$value
        }
    }
    return $result
}

function Get-ControlDevRuntime {
    param($Context, [string]$ServerId)
    $output=@(Invoke-ControlGateCli $Context.CliPath @('gate','dev-runtime','--lease-file',$Context.LeaseFile,'--expected-server-url',$Context.ServerUrl))
    try { $runtime=($output -join "`n") | ConvertFrom-Json -AsHashtable } catch { throw 'Invalid Control Dev runtime response.' }
    if (!$runtime -or $runtime['serverId'] -cne $ServerId -or $runtime['processState'] -cnotin @('Stopped','Running') -or
        $runtime['configRevision'] -isnot [long] -and $runtime['configRevision'] -isnot [int] -or
        $runtime['stateRevision'] -isnot [long] -and $runtime['stateRevision'] -isnot [int] -or
        $runtime['configRevision'] -lt 1 -or $runtime['stateRevision'] -lt 0) { throw 'Control Dev runtime is stale, unmanaged, or invalid.' }
    $session=[Guid]::Empty
    if (($runtime.processState -ceq 'Running' -and (![Guid]::TryParse([string]$runtime['currentSessionId'],[ref]$session) -or $session -eq [Guid]::Empty)) -or
        ($runtime.processState -ceq 'Stopped' -and $runtime['currentSessionId'])) { throw 'Control Dev session identity is invalid.' }
    return $runtime
}

function Invoke-ControlDevLifecycle {
    param($Context, [hashtable]$Settings, [Parameter(Mandatory)][string]$RunDirectory,
        [Parameter(Mandatory)][ValidateSet('Stop','Start')][string]$Kind, [scriptblock]$SleepAction)
    if ($Context.Mode -cne 'Managed' -or $Context.AdapterId -cnotin @('01','02','03') -or
        [IO.Path]::GetFullPath($RunDirectory) -ine [IO.Path]::GetFullPath($Context.RunReference)) { throw 'Dev lifecycle must use its own managed deployment run.' }
    if (!$SleepAction) { $SleepAction={param($Seconds) Start-Sleep -Seconds $Seconds} }
    $journalPath=Join-Path $RunDirectory ("control-dev-$($Kind.ToLowerInvariant()).json")
    $requestPath=Join-Path $RunDirectory ("control-dev-$($Kind.ToLowerInvariant()).request.json")
    $journal=if (Test-Path -LiteralPath $journalPath -PathType Leaf) { Get-Content -Raw -Encoding UTF8 -LiteralPath $journalPath | ConvertFrom-Json -AsHashtable } else { $null }
    if (!$journal) {
        $runtime=Get-ControlDevRuntime $Context $Settings.ServerId
        if ($Kind -ceq 'Start' -and $runtime.processState -cne 'Stopped') { throw 'Dev is already running without this run Start record. Retain the reservation and inspect it.' }
        $timeout=if ($Kind -ceq 'Stop') { $Settings.StopTimeoutSeconds } else { $Settings.StartTimeoutSeconds }
        $request=[ordered]@{kind=$Kind;payload=@{};configRevision=$runtime.configRevision;expectedStateRevision=$runtime.stateRevision;
            expectedServerSessionId=$runtime.currentSessionId;workflowId=$Context.WorkflowId;reservationId=$Context.ReservationId;executionTimeoutSeconds=$timeout}
        # The complete request and key are durable before the first POST. Replays never mint a replacement key.
        $journal=[ordered]@{schemaVersion=1;serverId=$Settings.ServerId;workflowId=$Context.WorkflowId;reservationId=$Context.ReservationId;
            kind=$Kind;idempotencyKey=[Guid]::NewGuid().ToString('D');request=$request;operationId=$null;status='Pending'}
        if ($Kind -ceq 'Stop' -and $runtime.processState -ceq 'Stopped') { $journal.status='AlreadyStopped' }
        Save-ControlDevJournal $journal $journalPath
    }
    $key=[Guid]::Empty
    if ($journal['schemaVersion'] -ne 1 -or $journal['serverId'] -cne $Settings.ServerId -or $journal['workflowId'] -cne $Context.WorkflowId -or
        $journal['reservationId'] -cne $Context.ReservationId -or $journal['kind'] -cne $Kind -or $journal['request']['kind'] -cne $Kind -or
        $journal['request']['workflowId'] -cne $Context.WorkflowId -or $journal['request']['reservationId'] -cne $Context.ReservationId -or
        ![Guid]::TryParse([string]$journal['idempotencyKey'],[ref]$key) -or $key -eq [Guid]::Empty) { throw 'Saved Dev operation belongs to another run or target.' }
    if ($journal.status -cne 'AlreadyStopped') {
        if (!$journal.operationId) {
            [IO.File]::WriteAllText($requestPath,($journal.request | ConvertTo-Json -Depth 12),[Text.UTF8Encoding]::new($false))
            $output=@(Invoke-ControlGateCli $Context.CliPath @('gate','dev-operate','--lease-file',$Context.LeaseFile,'--request-file',$requestPath,
                '--idempotency-key',[string]$journal.idempotencyKey,'--expected-server-url',$Context.ServerUrl))
            try { $operation=($output -join "`n") | ConvertFrom-Json -AsHashtable } catch { throw 'Invalid Control Dev operation response; retry the same run.' }
            $operationId=[Guid]::Empty
            if (!$operation -or ![Guid]::TryParse([string]$operation['operationId'],[ref]$operationId) -or $operationId -eq [Guid]::Empty) { throw 'Control did not return a Dev operation ID; retry the same run.' }
            $journal.operationId=$operationId.ToString('D')
            Save-ControlDevJournal $journal $journalPath
        }
        $deadline=[DateTime]::UtcNow.AddSeconds([int]$journal.request.executionTimeoutSeconds+60)
        do {
            $output=@(Invoke-ControlGateCli $Context.CliPath @('gate','dev-operation','--lease-file',$Context.LeaseFile,
                '--operation-id',[string]$journal.operationId,'--expected-server-url',$Context.ServerUrl))
            try { $operation=($output -join "`n") | ConvertFrom-Json -AsHashtable } catch { throw 'Invalid Control Dev operation status; retain the reservation.' }
            if (!$operation -or $operation['operationId'] -cne $journal.operationId) { throw 'Control returned another Dev operation.' }
            $journal.status=[string]$operation['status']
            Save-ControlDevJournal $journal $journalPath
            if ($journal.status -ceq 'Succeeded') { break }
            if ($journal.status -cnotin @('Prepared','Queued','Accepted','Running','Dispatched','Pending')) { throw "Dev $Kind did not succeed ($($journal.status)). Reservation retained; inspect Control before recovery." }
            if ([DateTime]::UtcNow -ge $deadline) { throw "Dev $Kind confirmation timed out. Reservation retained; retry the same run to inspect the same operation." }
            & $SleepAction $Settings.PollIntervalSeconds
        } while ($true)
    }
    $runtime=Get-ControlDevRuntime $Context $Settings.ServerId
    if ($Kind -ceq 'Stop') {
        if ($runtime.processState -cne 'Stopped') { throw 'Dev normal stop is not confirmed; deployment is blocked.' }
    } elseif ($runtime.processState -cne 'Running' -or !$runtime.currentSessionId -or $runtime.currentSessionId -eq $journal.request.expectedServerSessionId) {
        throw 'Dev new process session is not confirmed; publication is blocked.'
    }
    Write-Host "Control: Dev $Kind confirmed."
}
