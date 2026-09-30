#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-workflow-patch-' + [guid]::NewGuid().ToString('N'))
$server=Join-Path $fixture 'server'
New-Item -ItemType Directory -Path $server | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
$env:AR_WORKFLOW_TEST_API='api-test'
$env:AR_WORKFLOW_TEST_MIGRATION='migration-test'
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
try {
    # Round-trip JSON so integers and objects have the same types as actual user settings.
    $workflow=@{runRoot=(Join-Path $fixture 'new-runs');startupTimeoutSeconds=20;pollIntervalSeconds=1;seedMasterData=$true} | ConvertTo-Json | ConvertFrom-Json -AsHashtable
    $migration=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='AR_WORKFLOW_TEST_API';migrationKeyEnvironmentVariable='AR_WORKFLOW_TEST_MIGRATION';serverIds=@('dev')}
    $script:deployed=$false; $script:seeded=$false; $script:deployCount=0
    $script:publishStatus=200; $script:publishGeneration='a'*64; $script:publishVersion=12
    $script:requests=[Collections.Generic.List[string]]::new()
    $script:session=[guid]::NewGuid().ToString(); $script:generation='a'*64
    $http={
        param($Method,$Uri,$Headers,$Body)
        $script:requests.Add("$Method $Uri")
        Assert ($Headers['X-Api-Key'] -eq 'api-test') 'Common API key missing.'
        if ($Uri -match 'master-data/seed') {
            Assert $script:deployed 'Seed preceded deployment.'
            $script:seeded=$true
            return @{StatusCode=200;Body=@{status='SUCCEEDED'}}
        }
        $runtime=@{serverId='dev';serverSessionId=$script:session;definitionGenerationId=$script:generation;ready=$true;lastSeenUtc=[DateTime]::UtcNow.ToString('o')}
        if ($Uri -match '/runtime/servers/dev$') {
            if (!$script:deployed) { return @{StatusCode=404;Body=$null} }
            Assert $script:seeded 'Startup checked before seed.'
            return @{StatusCode=200;Body=$runtime}
        }
        if ($Method -eq 'POST' -and $Uri -match '/patches/publish\?server_session_id=') {
            Assert ($Headers['X-SkillTree-Migration-Key'] -eq 'migration-test') 'Publication credential missing.'
            Assert ($Uri.EndsWith($script:session)) 'Publication did not use the confirmed startup session.'
            Assert ($Body.definitionGenerationId -ceq $script:generation) 'Publication generation mismatch.'
            return @{StatusCode=$script:publishStatus;Body=@{definitionGenerationId=$script:publishGeneration;patchVersion=$script:publishVersion}}
        }
        throw 'Unexpected mock route.'
    }
    $deploy={param($RunDirectory) $script:deployCount++; $script:deployed=$true; Write-MaintenanceJson @{completed=$true} (Join-Path $RunDirectory 'deploy-action-success.json')}
    $result=Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -DeployAction $deploy -ServersStopped -AdmissionClosed -HttpInvoker $http -SleepAction {param($Seconds)}
    Assert ($result.Status -eq 'COMPLETED' -and $result.PatchPublicationResult.patchVersion -eq 12) 'Patch publication did not complete with HTTP envelope adapter.'
    Assert ($script:deployCount -eq 1) 'Deployment repeated.'
    Assert (Test-Path -LiteralPath (Join-Path $result.RunDirectory 'patch-publication-result.json')) 'Publication evidence missing.'
    Assert (@($script:requests | Where-Object {$_ -match 'accounts|migrations|migration-candidates'}).Count -eq 0) 'Normal workflow contacted a player-update route.'

    # Non-success responses and malformed successes must leave publication incomplete.
    foreach ($failure in @(@{status=401;generation=('a'*64);version=12},@{status=409;generation=('a'*64);version=12},@{status=200;generation=('b'*64);version=12},@{status=200;generation=('a'*64);version=0},@{status=200;generation=('a'*64);version='1.5'})) {
        $script:deployed=$false; $script:seeded=$false
        $script:publishStatus=$failure.status; $script:publishGeneration=$failure.generation; $script:publishVersion=$failure.version
        $workflow.runRoot=Join-Path $fixture ([guid]::NewGuid().ToString('N'))
        $rejected=$false
        try { Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -DeployAction $deploy -ServersStopped -AdmissionClosed -HttpInvoker $http -SleepAction {param($Seconds)} | Out-Null }
        catch { $rejected=$_.Exception.Message -match 'publication did not complete' }
        Assert $rejected 'An unsuccessful/mismatched publication was accepted.'
        $active=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $workflow.runRoot 'active-run.json') | ConvertFrom-Json
        Assert ($active.status -ne 'Completed') 'Failed publication completed the active run.'
    }

    # An unfinished legacy migration is never committed by normal deployment.
    $script:publishStatus=200; $script:publishGeneration='a'*64; $script:publishVersion=12
    $legacyRun=Join-Path $workflow.runRoot $active.relativeRunDirectory
    $statePath=Join-Path $legacyRun 'workflow-state.json'
    $state=Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json -AsHashtable
    $state.status='Migrating'; Write-MaintenanceJson $state $statePath
    Write-MaintenanceJson @{startedAtUtc=[DateTime]::UtcNow.ToString('o')} (Join-Path $legacyRun 'migration-commit-started.json')
    $before=$script:requests.Count
    $blocked=$false
    try { Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -DeployAction {throw 'must not deploy'} -ServersStopped -AdmissionClosed -HttpInvoker $http | Out-Null }
    catch { $blocked=$_.Exception.Message -match '17-player-patch-migration.bat' }
    Assert ($blocked -and $script:requests.Count -eq $before) 'Unfinished legacy migration must require explicit bulk recovery without API traffic.'
    Write-MaintenanceJson @{Status='APPLIED'} (Join-Path $legacyRun 'skilltree-migration-result.json')
    $resumed=Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -DeployAction {throw 'must not deploy'} -ServersStopped -AdmissionClosed -HttpInvoker $http
    Assert ($resumed.Status -eq 'COMPLETED') 'Completed explicit legacy recovery did not allow patch publication.'
    Write-Host "PASS: publication-only workflow, seed/startup ordering, HTTP failures, response validation, legacy recovery separation. Evidence: $fixture"
} finally {
    Remove-Item Env:AR_WORKFLOW_TEST_API,Env:AR_WORKFLOW_TEST_MIGRATION -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}
