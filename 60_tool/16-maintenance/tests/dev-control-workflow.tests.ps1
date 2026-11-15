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
    $script:events=[Collections.Generic.List[string]]::new();$script:failedStop=$false;$script:baselineCalls=0;$script:denyDeploy=$false
    $script:newSession=[Guid]::NewGuid().ToString('D');$script:generation='a'*64
    function Assert-ControlGateStep($Context,$Step) {
        $script:events.Add('gate:'+ $Step)
        if ($Step -ceq 'Deploy' -and $script:denyDeploy) { throw 'fixture stopped-state validation rejected' }
    }
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

    # A confirmed build failure retries in the same reservation/run and preserves old evidence.
    function New-FailedBuildFixture {
        $retryRuns=Join-Path $temp ('retry-runs-'+[guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $retryRuns | Out-Null
        $retryRun=New-UpdateWorkflowRunDirectory $retryRuns
        $savedLease=Get-Content -Raw -Encoding UTF8 -LiteralPath $leaseFile | ConvertFrom-Json
        Write-MaintenanceJson $savedLease (Join-Path $retryRun.Path 'control-gate-run.json')
        $retryState=@{schemaVersion=1;configurationFingerprint=('c'*64);label='Dev';status='DeploymentFailed';seedStatus='PENDING';deployedRuntimes=@();baselineRuntimes=@{Dev=$null};baselineSessions=@{Dev=$null}}
        $progress=@{schemaVersion=1;configurationFingerprint=('c'*64);stage='Build';status='Failed';buildIsolated=$true}
        Write-MaintenanceJson $retryState (Join-Path $retryRun.Path 'workflow-state.json')
        Write-MaintenanceJson $progress (Join-Path $retryRun.Path 'dev-deployment-progress.json')
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $retryRun.Path 'dev-deploy.log') -Value 'failed build log'
        Write-MaintenanceJson @{schemaVersion=1;relativeRunDirectory=$retryRun.Relative;configurationFingerprint=('c'*64);status='Created'} (Join-Path $retryRuns 'active-run.json')
        $retryParameters=$parameters.Clone()
        $retryParameters.WorkflowConfig=@{runRoot=$retryRuns;startupTimeoutSeconds=4;pollIntervalSeconds=1;seedMasterData=$true}
        $retryParameters.PreferredRunDirectory=$retryRun.Path
        $retryParameters.GateContext=@{Mode='Managed';LeaseFile=$leaseFile;RemoteStatus='Prepared';RunReference=$retryRun.Path}
        $retryParameters.DeployAction={param($RunDirectory)
            Assert (!(Test-Path -LiteralPath (Join-Path $RunDirectory 'dev-deployment-progress.json'))) 'Retry must clear old progress before the child starts.'
            $handle=[IO.File]::Open((Join-Path $RunDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None');$handle.Dispose()
            $script:events.Add('retry-deploy')
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}'
        }
        return @{Run=$retryRun;State=$retryState;Progress=$progress;Arguments=$retryParameters}
    }
    $fixture=New-FailedBuildFixture;$retryArguments=$fixture.Arguments
    $hashes=@{};foreach ($name in @('workflow-state.json','dev-deployment-progress.json','dev-deploy.log')) { $hashes[$name]=(Get-FileHash -LiteralPath (Join-Path $fixture.Run.Path $name)).Hash }
    $leaseHash=(Get-FileHash -LiteralPath $leaseFile).Hash
    $retryResult=Invoke-UpdateWorkflow @retryArguments
    Assert ($retryResult.Status -ceq 'COMPLETED' -and $retryResult.RunDirectory -ceq $fixture.Run.Path) 'Build failure must resume the same run.'
    Assert (@($script:events | Where-Object { $_ -ceq 'retry-deploy' }).Count -eq 1) 'A build retry must invoke deployment once.'
    Assert ((Get-FileHash -LiteralPath $leaseFile).Hash -ceq $leaseHash) 'Build retry must preserve the Control lease.'
    $attempts=@(Get-ChildItem -LiteralPath (Join-Path $fixture.Run.Path 'build-retries') -Directory)
    Assert ($attempts.Count -eq 1) 'Build retry must preserve the failed attempt.'
    foreach ($name in $hashes.Keys) { Assert ((Get-FileHash -LiteralPath (Join-Path $attempts[0].FullName $name)).Hash -ceq $hashes[$name]) "Build retry lost the original record: $name" }

    # A durable ReadyToDeploy intent resumes on either side of old-progress removal.
    foreach ($progressRemains in @($true,$false)) {
        $fixture=New-FailedBuildFixture;$retryArguments=$fixture.Arguments
        $fixture.State.status='ReadyToDeploy'
        Write-MaintenanceJson $fixture.State (Join-Path $fixture.Run.Path 'workflow-state.json')
        if (!$progressRemains) { Remove-Item -LiteralPath (Join-Path $fixture.Run.Path 'dev-deployment-progress.json') }
        $retryResult=Invoke-UpdateWorkflow @retryArguments
        Assert ($retryResult.Status -ceq 'COMPLETED' -and $retryResult.RunDirectory -ceq $fixture.Run.Path) 'Interrupted retry preparation must resume in the same run.'
    }
    foreach ($case in @('Database','Files','Running','NotIsolated','StringIsolation','Deploying','Seeded','Published','Migration','Start','Configuration','Reservation','GateRejected','StoppedCondition','Locked')) {
        $fixture=New-FailedBuildFixture;$retryArguments=$fixture.Arguments;$handle=$null
        switch ($case) {
            'Database' { $fixture.Progress.stage='Database' }
            'Files' { $fixture.Progress.stage='Files' }
            'Running' { $fixture.Progress.status='Running' }
            'NotIsolated' { $fixture.Progress.buildIsolated=$false }
            'StringIsolation' { $fixture.Progress.buildIsolated='true' }
            'Deploying' { $fixture.State.status='Deploying' }
            'Seeded' { $fixture.State.seedStatus='SUCCEEDED' }
            'Published' { Write-MaintenanceJson @{} (Join-Path $fixture.Run.Path 'patch-publication-result.json') }
            'Migration' { Write-MaintenanceJson @{} (Join-Path $fixture.Run.Path 'migration-commit-started.json') }
            'Start' { Write-MaintenanceJson @{} (Join-Path $fixture.Run.Path 'control-dev-start.request.json') }
            'Configuration' { $retryArguments.ConfigurationFingerprint='d'*64 }
            'Reservation' { Write-MaintenanceJson @{workflowId=[guid]::NewGuid().ToString('D');reservationId=[guid]::NewGuid().ToString('D')} (Join-Path $fixture.Run.Path 'control-gate-run.json') }
            'GateRejected' { $retryArguments.GateContext.RemoteStatus='Released' }
            'StoppedCondition' { $script:denyDeploy=$true }
            'Locked' { $handle=[IO.File]::Open((Join-Path $fixture.Run.Path 'run.lock'),'OpenOrCreate','ReadWrite','None') }
        }
        Write-MaintenanceJson $fixture.State (Join-Path $fixture.Run.Path 'workflow-state.json')
        Write-MaintenanceJson $fixture.Progress (Join-Path $fixture.Run.Path 'dev-deployment-progress.json')
        $stateHash=(Get-FileHash -LiteralPath (Join-Path $fixture.Run.Path 'workflow-state.json')).Hash
        $progressHash=(Get-FileHash -LiteralPath (Join-Path $fixture.Run.Path 'dev-deployment-progress.json')).Hash
        $deployCount=@($script:events | Where-Object { $_ -ceq 'retry-deploy' }).Count;$message=''
        try { Invoke-UpdateWorkflow @retryArguments | Out-Null } catch { $message=$_.Exception.Message } finally { if ($handle) { $handle.Dispose() };$script:denyDeploy=$false }
        Assert ($message -and @($script:events | Where-Object { $_ -ceq 'retry-deploy' }).Count -eq $deployCount) "Unsafe retry reached deployment: $case"
        Assert ((Get-FileHash -LiteralPath (Join-Path $fixture.Run.Path 'workflow-state.json')).Hash -ceq $stateHash) "Blocked retry changed state: $case"
        Assert ((Get-FileHash -LiteralPath (Join-Path $fixture.Run.Path 'dev-deployment-progress.json')).Hash -ceq $progressHash) "Blocked retry changed progress: $case"
        Assert (!(Test-Path -LiteralPath (Join-Path $fixture.Run.Path 'build-retries'))) "Blocked retry archived records: $case"
    }

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
