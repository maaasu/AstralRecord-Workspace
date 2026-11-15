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
function New-WorkflowMigration([string[]] $Servers) { return @{ enabled=$true;baseUrl='http://127.0.0.1:5010';apiKeyEnvironmentVariable='UPDATE_WORKFLOW_API';migrationKeyEnvironmentVariable='UPDATE_WORKFLOW_MIGRATION';serverIds=$Servers } }
function Get-TestProperty($Object,[string]$Name) { if ($Object -is [Collections.IDictionary]) { return $Object[$Name] }; return $Object.PSObject.Properties[$Name].Value }

$env:UPDATE_WORKFLOW_API='test-api'; $env:UPDATE_WORKFLOW_MIGRATION='test-migration'
$generation=('a' * 64); $old=[Guid]::NewGuid().ToString('D'); $new=[Guid]::NewGuid().ToString('D')
$script:driftAfterPublication=$false
$script:failPublicationOnce=$false
function Invoke-SkillTreeMigration { throw 'Normal deployment must never invoke player migration.' }
function New-TestPublication($Headers,$Body) {
    $script:publicationCalls++
    Assert-Workflow ($Headers['X-Api-Key'] -ceq 'test-api' -and $Headers['X-SkillTree-Migration-Key'] -ceq 'test-migration') 'Publication must use both configured credentials.'
    Assert-Workflow ($Body.definitionGenerationId -ceq $script:generation) 'Publication must use the confirmed generation.'
    if ($script:failPublicationOnce -and $script:publicationCalls -eq 1) { throw 'simulated publication response loss' }
    if ($script:driftAfterPublication) { $script:generation='b'*64 }
    return [pscustomobject]@{definitionGenerationId=$Body.definitionGenerationId;patchVersion=7}
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
        if ($Method -eq 'POST' -and $Uri -match '/patches/publish\?server_session_id=') { return New-TestPublication $Headers $Body }
        throw 'Unexpected mock API request.'
    }
    $script:poll=0;$script:new=$new;$script:generation=$generation;$script:publicationCalls=0
    $result=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$true} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('1'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:deployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' -Encoding utf8 } -ServersStopped -AdmissionClosed -HttpInvoker $mock -SleepAction { param($Seconds) }
    Assert-Workflow ($result.Status -eq 'COMPLETED' -and $script:deployCount -eq 1) 'Workflow should deploy once and complete.'
    Assert-Workflow (($calls | Where-Object Method -eq 'POST' | Select-Object -First 1).Uri -match 'master-data/seed') 'Seed must occur before patch publication.'
    Assert-Workflow ($script:poll -ge 3) 'A 404 baseline plus two ready observations must be polled.'
    $state=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $result.RunDirectory 'workflow-state.json') | ConvertFrom-Json
    Assert-Workflow ($state.status -eq 'Completed' -and $state.seedStatus -eq 'SUCCEEDED') 'Completed state must be persisted.'
    Assert-Workflow ($script:publicationCalls -eq 1 -and $result.PatchPublicationResult.patchVersion -eq 7) 'The confirmed patch must be published once.'
    Assert-Workflow (@($calls | Where-Object Uri -match 'migration|accounts|definitions').Count -eq 0) 'No player enumeration or migration API is permitted.'
    Assert-Workflow (!(Test-Path -LiteralPath (Join-Path $result.RunDirectory 'migration-commit-started.json'))) 'Normal deployment must not create a player migration marker.'

    # A completed active record starts a distinct new run.
    $next=[Guid]::NewGuid().ToString('D'); $script:nextPoll=0; $script:next=$next; $script:publicationCalls=0
    $newRunMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { $script:nextPoll++; $session=if($script:nextPoll -eq 1){$script:new}else{$script:next}; return [pscustomobject]@{serverId='alpha';serverSessionId=$session;definitionGenerationId=$script:generation;ready=$true} }
        if ($Method -eq 'POST' -and $Uri -match '/patches/publish\?server_session_id=') { return New-TestPublication $Headers $Body }
        throw 'Unexpected request.'
    }
    $nextRun=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('1'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:deployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $newRunMock -SleepAction { param($Seconds) }
    Assert-Workflow ($nextRun.RunDirectory -ne $result.RunDirectory -and $script:deployCount -eq 2) 'Completed runs must not be resumed or redeployed in place.'

    # Old ready session is never accepted; a generation mismatch times out without publication.
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
    Assert-Workflow $generationTimedOut 'Different fresh runtime generations must not publish a patch.'

    # Seed failure is terminal for this attempt and is not repeated after a recorded success.
    $f=New-WorkflowFixture; $script:seedCalls=0
    $seedFail={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { return [pscustomobject]@{StatusCode=404;Body=$null} }
        if ($Method -eq 'POST' -and $Uri -match 'master-data/seed') { $script:seedCalls++; return [pscustomobject]@{status='FAILED'} }
        throw 'Unexpected request.'
    }
    $seedFailed=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=2;pollIntervalSeconds=1;seedMasterData=$true} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('3'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $seedFail -SleepAction { param($Seconds) } | Out-Null } catch { $seedFailed=$_.Exception.Message -match 'seed did not succeed' }
    Assert-Workflow ($seedFailed -and $script:seedCalls -eq 1) 'A failed seed must stop before runtime polling/publication.'

    # An interrupted deployment cannot be silently repeated, while a verified maintenance journal can resume.
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run; Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('4'*64);status='Deploying'} (Join-Path $f.Run 'active-run.json'); Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('4'*64);label='Dev';status='Deploying';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    $blocked=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('4'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {} -ServersStopped -AdmissionClosed -HttpInvoker { throw 'must not call API' } | Out-Null } catch { $blocked=$_.Exception.Message -match 'will not redeploy' }
    Assert-Workflow $blocked 'Interrupted deployment without evidence must require recovery.'

    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('c'*64);status='Created'} (Join-Path $f.Run 'active-run.json')
    Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('c'*64);label='Channels';status='WaitingForStartup';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    Save-UpdateWorkflowJson @{version=1;runId=('a'*32);status='Restored';operations=@()} (Join-Path $run.Path 'deployment.json')
    $restoredRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('c'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {throw 'must not redeploy'} -ServersStopped -AdmissionClosed -Label Channels -HttpInvoker {throw 'must not call API'} | Out-Null } catch { $restoredRejected=$_.Exception.Message -match '配布journalが不正' }
    Assert-Workflow $restoredRejected 'An incomplete restored journal must not authorize a new deployment.'
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('d'*64);status='Created'} (Join-Path $f.Run 'active-run.json')
    Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('d'*64);label='Dev';status='WaitingForStartup';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json')
    Save-UpdateWorkflowJson @{version=1;status='Deployed';operations=@()} (Join-Path $run.Path 'deployment.json')
    $devMarkerRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('d'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {throw 'must not redeploy'} -ServersStopped -AdmissionClosed -HttpInvoker {throw 'must not call API'} | Out-Null } catch { $devMarkerRejected=$_.Exception.Message -match 'Dev deployment completion marker is missing' }
    Assert-Workflow $devMarkerRejected 'A channel journal cannot prove Dev deployment completion.'

    # A maintenance journal marked Deployed is the one trusted recovery proof for an interrupted workflow.
    $f=New-WorkflowFixture; $run=New-UpdateWorkflowRunDirectory $f.Run; Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=('a'*64);status='Deploying'} (Join-Path $f.Run 'active-run.json'); Save-UpdateWorkflowJson @{schemaVersion=1;configurationFingerprint=('a'*64);label='Channels';status='Deploying';baselineSessions=@{alpha=$null};deployedRuntimes=@();seedStatus='PENDING'} (Join-Path $run.Path 'workflow-state.json'); Save-UpdateWorkflowJson @{version=1;status='Deployed';operations=@()} (Join-Path $run.Path 'deployment.json')
    $script:maintenancePoll=0; $script:maintenanceSession=[Guid]::NewGuid().ToString('D'); $script:publicationCalls=0
    $maintenanceResumeMock={ param($Method,$Uri,$Headers,$Body,$StartupPollDeadlineUtc)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') {
            if ($StartupPollDeadlineUtc) {
                Assert-Workflow (($StartupPollDeadlineUtc-[DateTime]::UtcNow).TotalSeconds -le 5) 'A readiness request exceeded the configured startup wait.'
                $script:maintenancePoll++
                if ($script:maintenancePoll -eq 2) { throw [TimeoutException]::new('transient fixture') }
                if ($script:maintenancePoll -eq 4) { return @{StatusCode=503;Body=$null} }
            }
            return [pscustomobject]@{serverId='alpha';serverSessionId=$script:maintenanceSession;definitionGenerationId=$script:generation;ready=$true}
        }
        if ($Method -eq 'POST' -and $Uri -match '/patches/publish\?server_session_id=') { return New-TestPublication $Headers $Body }
        throw 'Unexpected request.'
    }
    $maintenanceResume=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('a'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { throw 'verified deployment must not run again' } -ServersStopped -AdmissionClosed -Label Channels -HttpInvoker $maintenanceResumeMock -SleepAction { param($Seconds) }
    Assert-Workflow ($maintenanceResume.Status -eq 'COMPLETED' -and $maintenanceResume.RunDirectory -eq $run.Path) 'A verified maintenance deployment must resume the same run without redeploying.'
    Assert-Workflow ($script:maintenancePoll -eq 6 -and $script:publicationCalls -eq 1) 'Each transient readiness read must reset the ready streak before one publication.'

    $pollArguments=@{Migration=@{BaseUrl='http://127.0.0.1:1';ServerIds=@('alpha')};BaselineRuntimes=@{alpha=$null};Headers=@{};TimeoutSeconds=5;PollIntervalSeconds=1;RetryTransientReads=$true;SleepAction={throw 'A nontransient poll must not wait.'}}
    $invalidReady=@{StatusCode=200;Body=@{serverId='alpha';serverSessionId=$script:maintenanceSession;ready='true'}}
    foreach ($failure in @(@{StatusCode=401;Body=$null}, @{StatusCode=403;Body=$null}, @{StatusCode=200;Body=@{serverId='other'}}, $invalidReady, [System.Security.Authentication.AuthenticationException]::new('fixture TLS failure'))) {
        $script:attempts=0
        $failed=$false
        try { Wait-UpdateWorkflowRuntimes @pollArguments -Invoker { $script:attempts++; if ($failure -is [Exception]) { throw $failure }; return $failure } | Out-Null }
        catch { $failed=$_.Exception.Message -match 'did not accept|did not match|invalid ready flag|request failed' }
        Assert-Workflow ($failed -and $script:attempts -eq 1) 'Authorization, invalid runtime, and TLS errors must fail before retry.'
    }
    $script:attempts=0
    $failed=$false
    try { Invoke-UpdateWorkflowRequest { $script:attempts++; throw [TimeoutException]::new('fixture POST') } 'POST' 'http://127.0.0.1:1/' @{} $null -StartupPollDeadlineUtc ([DateTime]::UtcNow.AddSeconds(1)) | Out-Null }
    catch { $failed=$_.Exception.Message -eq 'The update workflow API request failed.' }
    Assert-Workflow ($failed -and $script:attempts -eq 1) 'A mutating request must fail without retry even on a typed transient exception.'

    $pollArguments.TimeoutSeconds=1; $pollArguments.PollIntervalSeconds=60; $pollArguments.Remove('SleepAction')
    $script:attempts=0; $failed=$false; $watch=[Diagnostics.Stopwatch]::StartNew()
    try { Wait-UpdateWorkflowRuntimes @pollArguments -Invoker { $script:attempts++; return @{StatusCode=504;Body=$null} } | Out-Null }
    catch { $failed=$_.Exception.Message -match 'Timed out' }
    Assert-Workflow ($failed -and $script:attempts -eq 1 -and $watch.Elapsed.TotalSeconds -lt 5) 'Persistent transient reads must stop at the existing startup deadline.'

    # An idle timeout alone would keep this request alive because the peer keeps sending bytes.
    $listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
    $redirect=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
    $firstByte=[Threading.ManualResetEventSlim]::new()
    $stopPeer=[Threading.ManualResetEventSlim]::new()
    $peer=[PowerShell]::Create()
    $listener.Start()
    $redirect.Start()
    $peerTask=$peer.AddScript({
        param($Listener,$RedirectPort,$FirstByte,$StopPeer)
        $connections=[Collections.Generic.List[Net.Sockets.TcpClient]]::new()
        try {
            foreach ($response in @("401 Unauthorized`r`nContent-Length: 32", "302 Found`r`nLocation: http://127.0.0.1:$RedirectPort/redirect`r`nContent-Length: 0", "200 OK`r`nContent-Length: 32")) {
                $connection=$Listener.AcceptTcpClient()
                $connections.Add($connection)
                $connection.ReceiveTimeout=5000
                $stream=$connection.GetStream()
                $reader=[IO.StreamReader]::new($stream)
                while ($reader.ReadLine()) {}
                $header=[Text.Encoding]::ASCII.GetBytes("HTTP/1.1 $response`r`nConnection: close`r`n`r`n")
                $stream.Write($header,0,$header.Length)
            }
            for ($i=0; $i -lt 32; $i++) {
                $stream.WriteByte(32)
                $stream.Flush()
                $FirstByte.Set()
                if ($StopPeer.Wait(200)) { break }
            }
        } catch [IO.IOException] {} finally { foreach ($connection in $connections) { $connection.Dispose() } }
    }.ToString()).AddArgument($listener).AddArgument($redirect.LocalEndpoint.Port).AddArgument($firstByte).AddArgument($stopPeer).BeginInvoke()
    try {
        $url='http://127.0.0.1:'+$listener.LocalEndpoint.Port+'/api/skilltree/runtime/servers/alpha'
        $unauthorized=Invoke-UpdateWorkflowHttp -Method GET -Uri $url -Headers @{} -StartupPollDeadlineUtc ([DateTime]::UtcNow.AddSeconds(3))
        Assert-Workflow ($unauthorized.StatusCode -eq 401) 'A readiness authorization response must be handled from headers without waiting for its body.'
        $redirected=Invoke-UpdateWorkflowHttp -Method GET -Uri $url -Headers @{'X-Api-Key'='fixture'} -StartupPollDeadlineUtc ([DateTime]::UtcNow.AddSeconds(3))
        Assert-Workflow ($redirected.StatusCode -eq 302 -and !$redirect.Pending()) 'A readiness request must not follow redirects or forward its credentials.'
        $watch.Restart()
        $slowResult=Invoke-UpdateWorkflowHttp -Method GET -Uri $url -Headers @{} -StartupPollDeadlineUtc ([DateTime]::UtcNow.AddSeconds(3))
        Assert-Workflow ($firstByte.IsSet -and $slowResult.TransientTransportFailure -eq $true -and $watch.Elapsed.TotalSeconds -lt 5) 'A slow response body must be cancelled at the startup deadline.'
    } finally {
        $stopPeer.Set()
        $listener.Stop()
        $redirect.Stop()
        try { $peer.EndInvoke($peerTask) | Out-Null } finally { $peer.Dispose(); $stopPeer.Dispose(); $firstByte.Dispose() }
    }

    # A publication failure keeps the same active run and deployment; retrying must not redeploy.
    $f=New-WorkflowFixture; $script:poll=0; $script:new=$new; $script:generation=$generation; $script:publicationCalls=0; $script:failPublicationOnce=$true; $script:resumeDeployCount=0
    # First request establishes an offline baseline; subsequent requests are the new runtime.
    $resumeMock={ param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { $script:poll++; if ($script:poll -eq 1) { return [pscustomobject]@{StatusCode=404;Body=$null} }; return [pscustomobject]@{serverId='alpha';serverSessionId=$script:new;definitionGenerationId=$script:generation;ready=$true} }
        if ($Method -eq 'POST' -and $Uri -match '/patches/publish\?server_session_id=') { return New-TestPublication $Headers $Body }
        throw 'Unexpected request.'
    }
    $firstPublicationFailure=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('8'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { param($RunDirectory) $script:resumeDeployCount++; Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}' } -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction { param($Seconds) } | Out-Null } catch { $firstPublicationFailure=$_.Exception.Message -match 'publication did not complete' }
    Assert-Workflow ($firstPublicationFailure -and $script:resumeDeployCount -eq 1) 'Publication failure must retain the completed deployment.'
    $script:failPublicationOnce=$false
    $resumed=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('8'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction { throw 'must not redeploy' } -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction { param($Seconds) }
    Assert-Workflow ($resumed.Status -eq 'COMPLETED' -and $script:resumeDeployCount -eq 1 -and $script:publicationCalls -eq 2) 'Publication retry must use the same deployed run.'

    # A reload after publication must not produce a false completion message.
    $f=New-WorkflowFixture; $script:poll=0; $script:generation='a'*64; $script:driftAfterPublication=$true; $script:publicationCalls=0
    $driftRejected=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=$f.Run;startupTimeoutSeconds=5;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig (New-WorkflowMigration @('alpha')) -ConfigurationFingerprint ('b'*64) -ServerRoots @($f.Server,$f.Source) -DeployAction {param($RunDirectory) Set-Content -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -Value '{}'} -ServersStopped -AdmissionClosed -HttpInvoker $resumeMock -SleepAction {param($Seconds)} | Out-Null } catch { $driftRejected=$_.Exception.Message -match 'runtime changed' }
    Assert-Workflow $driftRejected 'Post-publication runtime drift must block completion.'
    $script:driftAfterPublication=$false; $script:generation='a'*64

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
