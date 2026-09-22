#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar-private-tls-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
Start-Transcript -LiteralPath (Join-Path $root 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
function Reject([scriptblock]$Action,$Message) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$true }; Assert $failed $Message }
try {
    foreach($url in @('https://10.0.0.1','https://172.16.0.1','https://172.31.255.254','https://192.168.0.88:444','https://127.0.0.1','https://[::1]','https://[::ffff:192.168.1.2]')) {
        Assert (Test-SkillTreePrivateApiUri ([uri]$url)) "Private address rejected: $url"
    }
    foreach($url in @('https://8.8.8.8','https://172.15.0.1','https://172.32.0.1','https://169.254.169.254','https://100.64.0.1','https://device_server:444','https://example.com','http://192.168.0.88','https://[2001:4860:4860::8888]','https://[fe80::1]','https://user:pass@192.168.0.88')) {
        Assert (!(Test-SkillTreePrivateApiUri ([uri]$url))) "Non-private/unsafe endpoint accepted: $url"
    }
    $script:requests=0; $script:statusCode=200; $script:lastOptions=$null
    function Invoke-WebRequest {
        param($Method,$Uri,$Headers,[switch]$SkipHttpErrorCheck,$MaximumRedirection,$TimeoutSec,$ErrorAction,$ContentType,$Body,[switch]$SkipCertificateCheck,[switch]$NoProxy)
        $script:requests++
        $script:lastOptions=@{skip=[bool]$SkipCertificateCheck;noProxy=[bool]$NoProxy;redirects=$MaximumRedirection}
        return @{StatusCode=$script:statusCode;Content='{"ready":true}'}
    }
    $headers=@{'X-Api-Key'='test-fixture'}
    foreach($transport in @('Invoke-SkillTreeMigrationHttp','Invoke-UpdateWorkflowHttp')) {
        & $transport -Method GET -Uri 'https://192.168.0.88:444/api/health' -Headers $headers -Body $null -ApiBaseUrl 'https://192.168.0.88:444' -AllowPrivateApiInsecureTls | Out-Null
        Assert ($script:lastOptions.skip -and $script:lastOptions.noProxy -and $script:lastOptions.redirects -eq 0) 'Private bypass must disable proxies and redirects.'
        & $transport -Method GET -Uri 'https://example.com/api/health' -Headers $headers -Body $null | Out-Null
        Assert (!$script:lastOptions.skip) 'Certificate verification must remain enabled by default.'
        foreach($bad in @('https://8.8.8.8/api/health','https://device_server:444/api/health','http://192.168.0.88:444/api/health','https://192.168.0.88:445/api/health')) {
            $before=$script:requests
            Reject { & $transport -Method GET -Uri $bad -Headers $headers -Body $null -ApiBaseUrl 'https://192.168.0.88:444' -AllowPrivateApiInsecureTls } 'Unsafe bypass must fail.'
            Assert ($script:requests -eq $before) 'Rejected bypass must not send credentials.'
        }
    }
    $script:statusCode=302
    Reject { Invoke-SkillTreeMigrationHttp -Method GET -Uri 'https://192.168.0.88:444/api/health' -Headers $headers -ApiBaseUrl 'https://192.168.0.88:444' -AllowPrivateApiInsecureTls } 'Redirect must not be treated as success.'
    Assert ($script:lastOptions.redirects -eq 0) 'Redirect policy changed.'
    $config=@{enabled=$true;baseUrl='https://device_server:444';allowPrivateApiInsecureTls=$true;apiKeyEnvironmentVariable='FIXTURE_API';migrationKeyEnvironmentVariable='FIXTURE_MIG';serverIds=@('Dev');scope='AllCandidates';accountIds=@()}
    Reject { ConvertTo-SkillTreeMigrationConfig $config } 'DNS bypass must fail in config validation.'
    $config.baseUrl='https://192.168.0.88:444'
    Assert ((ConvertTo-SkillTreeMigrationConfig $config).AllowPrivateApiInsecureTls) 'Private bypass setting was not retained.'
    $config.allowPrivateApiInsecureTls='true'
    Reject { ConvertTo-SkillTreeMigrationConfig $config } 'String boolean must be rejected.'
    # Exercise the default invoker wiring, not only the two transport functions in isolation.
    $env:FIXTURE_API='fake-common'; $env:FIXTURE_MIG='fake-migration'
    $config.allowPrivateApiInsecureTls=$true; $config.serverIds=@('Dev')
    $script:runtimeReady=$true; $script:session=[guid]::NewGuid().ToString(); $script:generation='a'*64
    function Invoke-WebRequest {
        param($Method,$Uri,$Headers,[switch]$SkipHttpErrorCheck,$MaximumRedirection,$TimeoutSec,$ErrorAction,$ContentType,$Body,[switch]$SkipCertificateCheck,[switch]$NoProxy)
        Assert ($SkipCertificateCheck -and $NoProxy -and $MaximumRedirection -eq 0) 'Default invoker lost private TLS settings.'
        Assert ($Headers['X-Api-Key'] -eq 'fake-common') 'Authentication headers lost.'
        $runtime=@{serverId='Dev';serverSessionId=$script:session;definitionGenerationId=$script:generation;ready=$true}
        if ($Uri -match '/runtime/servers/Dev$') {
            if (!$script:runtimeReady) { return @{StatusCode=404;Content=''} }
            $payload=$runtime
        } elseif ($Uri -match '/runtime/definitions/') { $payload=@{nodes=@();positions=@();edges=@()} }
        elseif ($Uri -match 'migration-candidates') { $payload=@{runtime=$runtime;page=1;pageSize=100;totalCount=0;items=@()} }
        else { throw 'Unexpected fixture route.' }
        return @{StatusCode=200;Content=($payload | ConvertTo-Json -Depth 10 -Compress)}
    }
    $migrationRun=Join-Path $root 'standalone'; New-Item -ItemType Directory -Path $migrationRun | Out-Null
    $preview=Invoke-SkillTreeMigration -Config $config -RunDirectory $migrationRun
    Assert ($preview.Status -eq 'PREVIEW_COMPLETE') 'Standalone migration default transport failed.'
    $server=Join-Path $root 'server'; New-Item -ItemType Directory -Path $server | Out-Null
    $script:runtimeReady=$false
    $completed=Invoke-UpdateWorkflow -WorkflowConfig @{runRoot=(Join-Path $root 'workflow');startupTimeoutSeconds=10;pollIntervalSeconds=1;seedMasterData=$false} -MigrationConfig $config -ConfigurationFingerprint ('a'*64) -ServerRoots @($server) -ServersStopped -AdmissionClosed -DeployAction {param($RunDirectory) $script:runtimeReady=$true; Write-MaintenanceJson @{done=$true} (Join-Path $RunDirectory 'deploy-action-success.json')} -SleepAction {param($Seconds)}
    Assert ($completed.Status -eq 'COMPLETED') 'Workflow default transport failed.'
    Write-Host "PASS: private-IP bounds, configured origin, secure default, no proxy/redirect, fail-before-send. Evidence: $root"
} finally { Remove-Item Env:FIXTURE_API,Env:FIXTURE_MIG -ErrorAction SilentlyContinue; Stop-Transcript | Out-Null }
