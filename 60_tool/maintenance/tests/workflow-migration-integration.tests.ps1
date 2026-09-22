#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-workflow-migration-' + [guid]::NewGuid().ToString('N'))
$server=Join-Path $fixture 'server'
New-Item -ItemType Directory -Path $server | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
$env:AR_WORKFLOW_TEST_API='api-test'
$env:AR_WORKFLOW_TEST_MIGRATION='migration-test'
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
try {
    # Round-trip JSON so integers and objects have the same types as actual user settings.
    $workflow=@{runRoot=(Join-Path $fixture 'new-runs');startupTimeoutSeconds=20;pollIntervalSeconds=1;seedMasterData=$true} | ConvertTo-Json | ConvertFrom-Json -AsHashtable
    $migration=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='AR_WORKFLOW_TEST_API';migrationKeyEnvironmentVariable='AR_WORKFLOW_TEST_MIGRATION';serverIds=@('dev');scope='ExplicitAccounts';accountIds=@([guid]::NewGuid().ToString())}
    $script:deployed=$false; $script:seeded=$false; $script:deployCount=0
    $script:session=[guid]::NewGuid().ToString(); $script:generation='a'*64
    $http={
        param($Method,$Uri,$Headers,$Body)
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
        if ($Uri -match '/runtime/definitions/') { return @{StatusCode=200;Body=@{nodes=@();positions=@();edges=@()}} }
        if ($Uri -match 'migration-candidates') { return @{StatusCode=200;Body=@{runtime=$runtime;page=1;pageSize=100;totalCount=0;items=@()}} }
        if ($Uri -match 'migration-state$') { return @{StatusCode=200;Body=@{definitionGenerationId=$script:generation;version=1;unlockedNodes=@()}} }
        throw 'Unexpected mock route.'
    }
    $deploy={param($RunDirectory) $script:deployCount++; $script:deployed=$true; Write-MaintenanceJson @{completed=$true} (Join-Path $RunDirectory 'deploy-action-success.json')}
    $result=Invoke-UpdateWorkflow -WorkflowConfig $workflow -MigrationConfig $migration -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -DeployAction $deploy -ServersStopped -AdmissionClosed -HttpInvoker $http -SleepAction {param($Seconds)}
    Assert ($result.Status -eq 'COMPLETED' -and $result.MigrationResult.Status -eq 'APPLIED') 'Real migration helper did not complete with HTTP envelope adapter.'
    Assert ($script:deployCount -eq 1) 'Deployment repeated.'
    Assert (Test-Path -LiteralPath (Join-Path $result.RunDirectory 'skilltree-migration-result.json')) 'Migration evidence missing.'
    $wrong=@(@{serverId='dev';serverSessionId=[guid]::NewGuid().ToString();definitionGenerationId=$script:generation})
    $raw={param($Method,$Uri,$Headers,$Body) $response=& $http -Method $Method -Uri $Uri -Headers $Headers -Body $Body; return $response.Body}
    $rejected=$false
    try { Invoke-SkillTreeMigration -Config $migration -RunDirectory $result.RunDirectory -ExpectedRuntimes $wrong -HttpInvoker $raw | Out-Null } catch { $rejected=$_.Exception.Message -match 'Startup runtime session or generation changed' }
    Assert $rejected 'Migration must reject a runtime that differs from the confirmed startup.'
    Write-Host "PASS: JSON settings, automatic runRoot creation, real workflow-to-migration handoff, seed/startup ordering. Evidence: $fixture"
} finally {
    Remove-Item Env:AR_WORKFLOW_TEST_API,Env:AR_WORKFLOW_TEST_MIGRATION -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}
