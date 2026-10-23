#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
function Assert([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
$temp=Join-Path ([IO.Path]::GetTempPath()) ('ar-dev-control-workflow-'+[Guid]::NewGuid().ToString('N'))
$priorApi=$env:AR_LIFECYCLE_API;$priorMigration=$env:AR_LIFECYCLE_MIGRATION
try {
    $runs=Join-Path $temp 'runs';$server=Join-Path $temp 'server'
    New-Item -ItemType Directory -Path $runs,$server | Out-Null
    $env:AR_LIFECYCLE_API='fixture-api';$env:AR_LIFECYCLE_MIGRATION='fixture-migration'
    $leaseFile=Join-Path $temp 'lease.json'
    @{workflowId=[Guid]::NewGuid().ToString('D');reservationId=[Guid]::NewGuid().ToString('D');adapterId='01'} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $leaseFile
    $script:events=[Collections.Generic.List[string]]::new();$script:failedStop=$false;$script:baselineCalls=0
    $script:newSession=[Guid]::NewGuid().ToString('D');$script:generation='a'*64
    function Assert-ControlGateStep($Context,$Step) { $script:events.Add('gate:'+ $Step) }
    $mock={param($Method,$Uri,$Headers,$Body)
        if ($Method -ceq 'GET' -and $Uri -match '/runtime/servers/Dev$') {
            $script:events.Add('runtime');$script:baselineCalls++
            if (!$script:events.Contains('start')) { return @{StatusCode=404;Body=$null} }
            Assert ($script:events.Contains('start')) 'New runtime verification must follow Control Start.'
            return @{serverId='Dev';serverSessionId=$script:newSession;definitionGenerationId=$script:generation;ready=$true;serverStartedAtUtc=[DateTime]::UtcNow}
        }
        if ($Method -ceq 'POST' -and $Uri -match '/master-data/seed') { $script:events.Add('seed');return @{status='SUCCEEDED'} }
        if ($Method -ceq 'POST' -and $Uri -match '/patches/publish') { $script:events.Add('publish'); return @{definitionGenerationId=$script:generation;patchVersion=3} }
        throw 'Unexpected API request.'
    }
    $parameters=@{
        WorkflowConfig=@{runRoot=$runs;startupTimeoutSeconds=4;pollIntervalSeconds=1;seedMasterData=$true}
        MigrationConfig=@{enabled=$true;baseUrl='http://127.0.0.1:5010';serverIds=@('Dev');apiKeyEnvironmentVariable='AR_LIFECYCLE_API';migrationKeyEnvironmentVariable='AR_LIFECYCLE_MIGRATION'}
        ConfigurationFingerprint=('c'*64);ServerRoots=@($server);AdmissionClosed=$true
        GateContext=@{Mode='Managed';LeaseFile=$leaseFile};HttpInvoker=$mock;SleepAction={param($Seconds)}
        StopDevAction={param($RunDirectory)
            $script:events.Add('stop')
            $state=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'workflow-state.json') | ConvertFrom-Json
            Assert ($state.status -ceq 'StoppingDev') 'StoppingDev must be durable before any Stop request.'
            if (!$script:failedStop) { $script:failedStop=$true; throw 'simulated unknown Stop response' }
            return @{WasRunning=$false}
        }
        StartDevAction={param($RunDirectory) $script:events.Add('start')}
        DeployAction={param($RunDirectory)
            $script:events.Add('deploy')
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}'
        }
    }
    $failed=$false
    try { Invoke-UpdateWorkflow @parameters | Out-Null } catch { $failed=$_.Exception.Message -match 'unknown Stop' }
    Assert $failed 'A Stop failure must stop the workflow.'
    Assert (!$script:events.Contains('deploy') -and !$script:events.Contains('seed') -and !$script:events.Contains('start') -and !$script:events.Contains('publish')) 'An uncertain Stop must block every later action.'
    $active=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $runs 'active-run.json') | ConvertFrom-Json
    $run=Join-Path $runs $active.relativeRunDirectory
    Assert (!(Test-UpdateWorkflowUndeployedRun $run 'Dev' ('c'*64))) 'A stopped/unknown process run must not be archived as untouched.'
    $changed=$parameters.Clone();$changed.ConfigurationFingerprint='d'*64
    $blocked=$false;try { Invoke-UpdateWorkflow @changed | Out-Null } catch { $blocked=$_.Exception.Message -match 'existing reservation' }
    Assert $blocked 'Changing configuration after Stop begins must retain the original reservation and run.'
    $result=Invoke-UpdateWorkflow @parameters
    Assert ($result.Status -ceq 'COMPLETED' -and $result.RunDirectory -ceq $run) 'Retry must resume the same run.'
    Assert (@($script:events | Where-Object { $_ -ceq 'deploy' }).Count -eq 1) 'Retry must deploy only once.'
    $order=@($script:events)
    Assert ([array]::IndexOf($order,'deploy') -gt [array]::LastIndexOf($order,'stop') -and
        [array]::IndexOf($order,'seed') -gt [array]::IndexOf($order,'deploy') -and
        [array]::IndexOf($order,'start') -gt [array]::IndexOf($order,'seed') -and
        [array]::IndexOf($order,'publish') -gt [array]::IndexOf($order,'start')) 'Required order: stop, deploy, seed, start, readiness, publication.'

    # A 404 baseline followed by a late old heartbeat must not qualify as a new boot.
    $migration=ConvertTo-SkillTreeRuntimeConfig $parameters.MigrationConfig
    $instant=[DateTimeOffset]::UtcNow
    $decoded=('{"at":"'+$instant.ToString('o')+'"}') | ConvertFrom-Json
    Assert ((ConvertTo-UpdateWorkflowInstant $decoded.at).UtcDateTime.Ticks -eq $instant.UtcDateTime.Ticks) 'JSON date decoding must preserve UTC and fractional seconds on resume.'
    $script:oldBoot=[DateTimeOffset]::UtcNow.AddHours(-1).ToString('o')
    $script:lateHeartbeat=[DateTimeOffset]::UtcNow.AddMinutes(1).ToString('o')
    $oldReady={param($Method,$Uri,$Headers,$Body)
        return @{serverId='Dev';serverSessionId=$script:newSession;definitionGenerationId=$script:generation;ready=$true;serverStartedAtUtc=$script:oldBoot;lastSeenUtc=$script:lateHeartbeat}
    }
    $blocked=$false
    try { Wait-UpdateWorkflowRuntimes -Migration $migration -BaselineRuntimes @{Dev=$null} -Invoker $oldReady -Headers @{} -TimeoutSeconds 1 -PollIntervalSeconds 1 -MinimumServerStartedAtUtc ([DateTimeOffset]::UtcNow.ToString('o')) -SleepAction {param($Seconds)} | Out-Null }
    catch { $blocked=$_.Exception.Message -match 'Timed out' }
    Assert $blocked 'A recent heartbeat must not make an older boot ready for publication.'
    $missingBoot={param($Method,$Uri,$Headers,$Body)
        return @{serverId='Dev';serverSessionId=$script:newSession;definitionGenerationId=$script:generation;ready=$true}
    }
    $blocked=$false
    try { Wait-UpdateWorkflowRuntimes -Migration $migration -BaselineRuntimes @{Dev=$null} -Invoker $missingBoot -Headers @{} -TimeoutSeconds 1 -PollIntervalSeconds 1 -MinimumServerStartedAtUtc ([DateTimeOffset]::UtcNow.ToString('o')) -SleepAction {param($Seconds)} | Out-Null }
    catch { $blocked=$_.Exception.Message -match 'Timed out' }
    Assert $blocked 'An older API without boot timestamp must not silently publish.'
    Write-Host 'Dev automatic workflow ordering and recovery tests passed.'
} finally {
    $env:AR_LIFECYCLE_API=$priorApi;$env:AR_LIFECYCLE_MIGRATION=$priorMigration
    if (Test-Path -LiteralPath $temp) {
        $resolved=[IO.Path]::GetFullPath($temp)
        if (![IO.Path]::GetFileName($resolved).StartsWith('ar-dev-control-workflow-') -or [IO.Path]::GetDirectoryName($resolved) -ine [IO.Path]::GetTempPath().TrimEnd('\')) { throw 'Unsafe fixture cleanup path.' }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
