#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')

function Assert-Workflow([bool] $Condition, [string] $Message) { if (!$Condition) { throw $Message } }
$script:workflowFixtures=[Collections.Generic.List[string]]::new()
function New-WorkflowFixture {
    $root=Join-Path ([IO.Path]::GetTempPath()) ('ar-update-workflow-' + [Guid]::NewGuid().ToString('N'))
    $run=Join-Path $root 'runs'; $server=Join-Path $root 'server'; $source=Join-Path $root 'source'
    New-Item -ItemType Directory -Path $run,$server,$source | Out-Null
    $script:workflowFixtures.Add($root)
    return [pscustomobject]@{ Root=$root; Run=$run; Server=$server; Source=$source }
}
function New-WorkflowMigration([string[]] $Servers) { return @{ enabled=$true;baseUrl='http://127.0.0.1:5010';apiKeyEnvironmentVariable='UPDATE_WORKFLOW_API';migrationKeyEnvironmentVariable='UPDATE_WORKFLOW_MIGRATION';serverIds=$Servers;scope='AllCandidates';accountIds=@() } }
function Get-TestProperty($Object,[string]$Name) { if ($Object -is [Collections.IDictionary]) { return $Object[$Name] }; return $Object.PSObject.Properties[$Name].Value }

$env:UPDATE_WORKFLOW_API='test-api'; $env:UPDATE_WORKFLOW_MIGRATION='test-migration'
$generation=('a' * 64); $old=[Guid]::NewGuid().ToString('D'); $new=[Guid]::NewGuid().ToString('D')
$script:driftAfterMigration=$false
# The integration branch supplies ExpectedRuntimes to the real migration helper. Keep this
# workflow test isolated from its API-level tests while asserting that handoff contract.
function Invoke-SkillTreeMigration {
    param($Config, [string]$RunDirectory, [switch]$Commit, [object[]]$ExpectedRuntimes, [scriptblock]$HttpInvoker, [switch]$AllowRuntimeSessionRefresh)
    $script:migrationCalls++
    $script:lastExpectedRuntimes=@($ExpectedRuntimes)
    if ($script:failMigrationOnce -and $script:migrationCalls -eq 1) { throw 'simulated migration response loss' }
    if ($script:driftAfterMigration) { $script:generation='b'*64 }
    return [pscustomobject]@{ Status='APPLIED'; RunDirectory=$RunDirectory; SelectedCount=0; AppliedCount=0 }
}
try {
    # Happy path: first 404 is a valid baseline, seed precedes waiting, and two identical fresh polls are required.
    $f=New-WorkflowFixture; $calls=[Collections.Generic.List[object]]::new(); $poll=0; $deployCount=0
    $mock={ param($Method,$Uri,$Headers,$Body)
        $calls.Add([pscustomobject]@{Method=$Method;Uri=$Uri;Body=$Body})
        if ($Method -eq 'POST' -and $Uri -match '/master-data/seed\?mode=diff$') { return [pscustomobject]@{status='SUCCEEDED'} }
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') {
            $script:poll++
            if ($script:poll -eq 1) { return [pscustomobject]@{StatusCode=404;Body=$null} }
            $active=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $f.Run 'active-run.json') | ConvertFrom-Json
            $handle=$null; $locked=$false
            try { $handle=[IO.File]::Open((Join-Path $f.Run "$($active.relativeRunDirectory)/run.lock"),'OpenOrCreate','ReadWrite','None') } catch [IO.IOException] { $locked=$true } finally { if ($handle) { $handle.Dispose() } }
            Assert-Workflow $locked 'Startup waiting must exclude concurrent Restore.'
            return [pscustomobject]@{serverId='alpha';serverSessionId=$script:new;definitionGenerationId=$script:generation;ready=$true;lastSeenUtc=[DateTime]::UtcNow.ToString('o')}
        }
        if ($Method -eq 'GET' -and $Uri -match '/runtime/definitions/') { return [pscustomobject]@{ generation=$script:generation;snapshot=@('root') } }
        if ($Method -eq 'GET' -and $Uri -match 'migration-candidates') { return [pscustomobject]@{runtime=[pscustomobject]@{serverId='alpha';serverSessionId=$script:new;definitionGenerationId=$script:generation;ready=$true};page=1;pageSize=100;totalCount=0;items=@()} }
        throw 'Unexpected mock API request.'
    }
    $script:poll=0;$script:new=$new;$script:generation=$generation;$script:migrationCalls=0;$script:failMigrationOnce=$false
    $result=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$true} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('1'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:deployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' -Encoding utf8 } -ServersStopped -AdmissionClosed -HttpInvoker $mock -SleepAction { param($Seconds) }
    Assert-Workflow ($result.Status -eq 'COMPLETED' -and $script:deployCount -eq 1) 'Workflow should deploy once and complete.'
    Assert-Workflow (($calls | Where-Object Method -eq 'POST' | Select-Object -First 1).Uri -match 'master-data/seed') 'Seed must occur before migration API activity.'
    Assert-Workflow ($script:poll -ge 3) 'A 404 baseline plus two ready observations must be polled.'
    $state=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $result.RunDirectory 'workflow-state.json') | ConvertFrom-Json
    Assert-Workflow ($state.status -eq 'Completed' -and $state.seedStatus -eq 'SUCCEEDED') 'Completed state must be persisted.'
    Assert-Workflow ($script:lastExpectedRuntimes.Count -eq 1 -and $script:lastExpectedRuntimes[0].ServerSessionId -eq $new) 'Confirmed runtime set must be passed to migration.'

    # A completed active record starts a distinct new run rather than reusing a completed migration.
    $next=[Guid]::NewGuid().ToString('D'); $script:nextPoll=0; $script:next=$next; $script:migrationCalls=0
    $newRunMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { $script:nextPoll++; $session=if($script:nextPoll -eq 1){$script:new}else{$script:next}; return [pscustomobject]@{serverId='alpha';serverSessionId=$session;definitionGenerationId=$script:generation;ready=$true} }
        throw 'Unexpected request.'
    }
    $nextRun=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('1'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:deployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $newRunMock -SleepAction { param($Seconds) }
    Assert-Workflow ($nextRun.RunDirectory -ne $result.RunDirectory -and $script:deployCount -eq 2) 'Completed runs must not be resumed or redeployed in place.'

    # Old ready session is never accepted; a generation mismatch times out without migration.
    $f=New-WorkflowFixture; $script:runtimeCalls=0; $script:old=$old; $script:new=$new; $script:generation=$generation
    $oldSessionMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { $script:runtimeCalls++; return [pscustomobject]@{serverId='alpha';serverSessionId=$script:old;definitionGenerationId=$script:generation;ready=$true} }
        throw 'No other request should occur while old session remains.'
    }
    $timedOut=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('2'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $oldSessionMock -SleepAction { param($Seconds) } | Out-Null } catch { $timedOut=$_.Exception.Message -match 'Timed out' }
    Assert-Workflow $timedOut 'Old session must not be accepted as a post-deployment runtime.'

    # Fresh runtimes still cannot advance when their generations disagree.
    $f=New-WorkflowFixture; $script:generationA=$generation; $script:generationB=('b' * 64); $script:alphaSession=[Guid]::NewGuid().ToString('D'); $script:betaSession=[Guid]::NewGuid().ToString('D'); $script:generationPoll=@{alpha=0;beta=0}
    $generationMismatchMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/(alpha|beta)$') {
            $id=$Matches[1]; $script:generationPoll[$id]++
            if ($script:generationPoll[$id] -eq 1) { return [pscustomobject]@{StatusCode=404;Body=$null} }
            $session=if($id -eq 'alpha'){$script:alphaSession}else{$script:betaSession}; $generationValue=if($id -eq 'alpha'){$script:generationA}else{$script:generationB}
            return [pscustomobject]@{serverId=$id;serverSessionId=$session;definitionGenerationId=$generationValue;ready=$true}
        }
        throw 'Unexpected request.'
    }
    $generationTimedOut=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha','beta')) -ConfigurationFingerprint ('9'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $generationMismatchMock -SleepAction { param($Seconds) } | Out-Null } catch { $generationTimedOut=$_.Exception.Message -match 'Timed out' }
    Assert-Workflow $generationTimedOut 'Different fresh runtime generations must not start migration.'

    # Seed failure is terminal for this attempt and is not repeated after a recorded success.
    $f=New-WorkflowFixture; $script:seedCalls=0
    $seedFail={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { return [pscustomobject]@{StatusCode=404;Body=$null} }
        if ($Method -eq 'POST' -and $Uri -match 'master-data/seed') { $script:seedCalls++; return [pscustomobject]@{status='FAILED'} }
        throw 'Unexpected request.'
    }
    $seedFailed=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=2;pollIntervalSeconds=1;seedMasterData=$true} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('3'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $seedFail -SleepAction { param($Seconds) } | Out-Null } catch { $seedFailed=$_.Exception.Message -match 'seed did not succeed' }
    Assert-Workflow ($seedFailed -and $script:seedCalls -eq 1) 'A failed seed must stop before runtime polling/migration.'

    # An interrupted deployment cannot be silently repeated, while a verified maintenance journal can resume.
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run; Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('4'*64);status='Deploying'} (Join-Path $f.Run 'active-run.json'); Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('4'*64);label='Dev';status='Deploying';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    $blocked=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('4'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {} -ServersStopped -AdmissionClosed -HttpInvoker { throw 'must not call API' } | Out-Null } catch { $blocked=$_.Exception.Message -match 'will not redeploy' }
    Assert-Workflow $blocked 'Interrupted deployment without evidence must require recovery.'

    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('c'*64);status='Created'} (Join-Path $f.Run 'active-run.json')
    Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('c'*64);label='Channels';status='WaitingForStartup';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    Save-UpdateWorkflowJson @{version=1;status='Restored';operations=@()} (Join-Path $run.Path 'deployment.json')
    $restoredRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('c'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {throw 'must not redeploy'} -ServersStopped -AdmissionClosed -Label Channels -HttpInvoker {throw 'must not call API'} | Out-Null } catch { $restoredRejected=$_.Exception.Message -match 'possibly restored' }
    Assert-Workflow $restoredRejected 'Restored deployment must not resume startup or migration.'
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('d'*64);status='Created'} (Join-Path $f.Run 'active-run.json')
    Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('d'*64);label='Dev';status='WaitingForStartup';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    Save-UpdateWorkflowJson @{version=1;status='Deployed';operations=@()} (Join-Path $run.Path 'deployment.json')
    $devMarkerRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('d'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {throw 'must not redeploy'} -ServersStopped -AdmissionClosed -HttpInvoker {throw 'must not call API'} | Out-Null } catch { $devMarkerRejected=$_.Exception.Message -match 'Dev deployment completion marker is missing' }
    Assert-Workflow $devMarkerRejected 'A channel journal cannot prove Dev deployment completion.'

    # A maintenance journal marked Deployed is the one trusted recovery proof for an interrupted workflow.
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run; Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('a'*64);status='Deploying'} (Join-Path $f.Run 'active-run.json'); Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('a'*64);label='Channels';status='Deploying';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json'); Save-UpdateWorkflowJson @{version=1;status='Deployed';operations=@()} (Join-Path $run.Path 'deployment.json')
    $script:maintenancePoll=0; $script:maintenanceSession=[Guid]::NewGuid().ToString('D'); $script:migrationCalls=0
    $maintenanceResumeMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { return [pscustomobject]@{serverId='alpha';serverSessionId=$script:maintenanceSession;definitionGenerationId=$script:generation;ready=$true} }
        throw 'Unexpected request.'
    }
    $maintenanceResume=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('a'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { throw 'verified deployment must not run again' } -ServersStopped -AdmissionClosed -Label Channels -HttpInvoker $maintenanceResumeMock -SleepAction { param($Seconds) }
    Assert-Workflow ($maintenanceResume.Status -eq 'COMPLETED') 'A verified maintenance deployment must resume without redeploying.'

    # A migration failure keeps the same active run and deployment; retrying must not redeploy.
    $f=New-WorkflowFixture; $script:poll=0; $script:new=$new; $script:generation=$generation; $script:migrationCalls=0; $script:failMigrationOnce=$true; $script:resumeDeployCount=0
    # First request establishes an offline baseline; subsequent requests are the new runtime.
    $resumeMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { $script:poll++; if ($script:poll -eq 1) { return [pscustomobject]@{StatusCode=404;Body=$null} }; return [pscustomobject]@{serverId='alpha';serverSessionId=$script:new;definitionGenerationId=$script:generation;ready=$true} }
        throw 'Unexpected request.'
    }
    $firstMigrationFailure=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('8'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:resumeDeployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction { param($Seconds) } | Out-Null } catch { $firstMigrationFailure=$_.Exception.Message -match 'migration did not complete' }
    Assert-Workflow ($firstMigrationFailure -and $script:resumeDeployCount -eq 1) 'Migration failure must retain the completed deployment.'
    $script:failMigrationOnce=$false
    $resumed=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('8'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { throw 'must not redeploy' } -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction { param($Seconds) }
    Assert-Workflow ($resumed.Status -eq 'COMPLETED' -and $script:resumeDeployCount -eq 1) 'Migration retry must use the same deployed run.'

    # A reload after migration must not produce a false completion message.
    $f=New-WorkflowFixture; $script:poll=0; $script:generation='a'*64; $script:driftAfterMigration=$true; $script:migrationCalls=0
    $driftRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('b'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}'} -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction {param($Seconds)} | Out-Null } catch { $driftRejected=$_.Exception.Message -match 'runtime changed' }
    Assert-Workflow $driftRejected 'Post-migration runtime drift must block completion.'
    $script:driftAfterMigration=$false; $script:generation='a'*64

    # Fingerprint and root-boundary validation happen before deployment/API traffic.
    $f=New-WorkflowFixture; $changed=$false
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory='run-20260101T000000Z-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';configurationFingerprint=('5'*64);status='WaitingForStartup'} (Join-Path $f.Run 'active-run.json')
    New-Item -ItemType Directory -Path (Join-Path $f.Run 'run-20260101T000000Z-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') | Out-Null
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('6'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {} -ServersStopped -AdmissionClosed -HttpInvoker { throw 'must not call API' } | Out-Null } catch { $changed=$_.Exception.Message -match 'Configuration changed' }
    Assert-Workflow $changed 'An active run must reject changed configuration.'
    $overlap=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Server;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('7'*64) -ServerRoots @($f.Server) -DeployAction {} -ServersStopped -AdmissionClosed -HttpInvoker { throw 'must not call API' } | Out-Null } catch { $overlap=$_.Exception.Message -match 'overlaps' }
    Assert-Workflow $overlap 'runRoot must not overlap a server/source root.'
    Write-Host 'PASS: update workflow initial/offline/runtime readiness/seed/recovery/fingerprint/path tests.'
} finally {
    foreach ($fixturePath in $script:workflowFixtures) {
        if (Test-Path -LiteralPath $fixturePath) {
            $resolved=(Resolve-Path -LiteralPath $fixturePath).Path
            $tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
            if (!$resolved.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ar-update-workflow-[a-f0-9]{32}$') { throw 'Unexpected fixture cleanup path.' }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
    Remove-Item Env:UPDATE_WORKFLOW_API -ErrorAction SilentlyContinue
    Remove-Item Env:UPDATE_WORKFLOW_MIGRATION -ErrorAction SilentlyContinue
}
