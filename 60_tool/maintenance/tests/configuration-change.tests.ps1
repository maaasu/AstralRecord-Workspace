#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
function Assert-Change([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
$fixtures=[Collections.Generic.List[string]]::new()
$oldFingerprint='1'*64; $newFingerprint='2'*64
$env:CONFIG_CHANGE_API='fixture-api'; $env:CONFIG_CHANGE_MIGRATION='fixture-migration'
function New-ChangeFixture([string]$Status='AwaitingDeployment',[string]$Seed='PENDING') {
    $root=Join-Path ([IO.Path]::GetTempPath()) ('ar-config-change-'+[Guid]::NewGuid().ToString('N'))
    $fixtures.Add($root)
    $runRoot=Join-Path $root 'runs'; $server=Join-Path $root 'server'
    New-Item -ItemType Directory -Path $runRoot,$server | Out-Null
    $run=New-UpdateWorkflowRunDirectory $runRoot
    $activePath=Join-Path $runRoot 'active-run.json'
    Save-UpdateWorkflowJson @{schemaVersion=1;relativeRunDirectory=$run.Relative;configurationFingerprint=$oldFingerprint;status='Created'} $activePath
    Save-UpdateWorkflowJson @{schemaVersion=1;label='Dev';configurationFingerprint=$oldFingerprint;status=$Status;seedStatus=$Seed} (Join-Path $run.Path 'workflow-state.json')
    return @{Root=$root;Run=$run;ActivePath=$activePath;Arguments=@{
        WorkflowConfig=@{runRoot=$runRoot;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false}
        MigrationConfig=@{enabled=$true;baseUrl='http://127.0.0.1:5010';serverIds=@('Dev');apiKeyEnvironmentVariable='CONFIG_CHANGE_API';migrationKeyEnvironmentVariable='CONFIG_CHANGE_MIGRATION'}
        ConfigurationFingerprint=$newFingerprint;ServerRoots=@($server);ServersStopped=$true;AdmissionClosed=$true
        DeployAction={throw 'unexpected deployment'};HttpInvoker={throw 'unexpected API request'}
    }}
}
try {
    # A verified pre-deployment record is archived byte-for-byte before creating a new run.
    $f=New-ChangeFixture; $activeHash=(Get-FileHash $f.ActivePath).Hash
    $arguments=$f.Arguments
    $arguments.HttpInvoker={return [pscustomobject]@{StatusCode=404;Body=$null}}
    $script:freshDeploymentReached=$false
    $arguments.DeployAction={$script:freshDeploymentReached=$true; throw 'fixture deployment stop'}
    $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
    Assert-Change ($message -match 'Deployment action failed' -and $script:freshDeploymentReached) 'Fresh execution should reach deployment, not reuse the old state.'
    $active=Get-Content -Raw -Encoding UTF8 -LiteralPath $f.ActivePath | ConvertFrom-Json
    Assert-Change ($active.configurationFingerprint -eq $newFingerprint -and $active.relativeRunDirectory -ne $f.Run.Relative) 'Fresh execution must use a distinct run and the new fingerprint.'
    $archives=@(Get-ChildItem -LiteralPath $f.Run.Path -Filter 'active-run.before-recovery-*.json' | Where-Object Name -NotLike '*.reason.json')
    Assert-Change ($archives.Count -eq 1 -and (Get-FileHash $archives[0].FullName).Hash -eq $activeHash) 'The old active pointer must be preserved without changes.'
    $oldState=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $f.Run.Path 'workflow-state.json') | ConvertFrom-Json
    Assert-Change ($oldState.status -eq 'AwaitingDeployment') 'Previous workflow state must be retained.'

    # Post-deployment states, including explicit Restart, keep the record and show actionable diagnostics.
    foreach ($status in @('WaitingForStartup','PublishingPatch')) {
        foreach ($recovery in @('Auto','Restart')) {
            $f=New-ChangeFixture $status 'SUCCEEDED'; $arguments=$f.Arguments; $arguments.Recovery=$recovery
            $hash=(Get-FileHash $f.ActivePath).Hash; $message=''
            try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
            Assert-Change ($message -match 'Configuration changed' -and $message.Contains($status) -and $message.Contains('SUCCEEDED') -and $message.Contains($f.Run.Path) -and $message.Contains('設定を元に戻して再開') -and $message.Contains('-Recovery Restartではやり直せません')) 'Post-deployment mismatch must report state, seed, records and resume guidance.'
            Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Post-deployment mismatch must not replace the active record.'
        }
    }

    # Contradictory deployment/publication/migration evidence blocks automatic archival.
    foreach ($name in @('dev-deployment-progress.json','dev-deploy.log','deploy-action-success.json','deployment.json','patch-publication-result.json','migration-commit-started.json')) {
        $f=New-ChangeFixture; $arguments=$f.Arguments; $hash=(Get-FileHash $f.ActivePath).Hash
        Set-Content -LiteralPath (Join-Path $f.Run.Path $name) -Value '{}'
        $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
        Assert-Change ($message -match 'Configuration changed' -and $message.Contains('配布未着手を確認できません')) "Contradictory evidence must block fresh execution: $name"
        Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Contradictory evidence must retain the pointer.'
    }

    # A live child lock cannot be bypassed even for an AwaitingDeployment state.
    $f=New-ChangeFixture; $arguments=$f.Arguments; $hash=(Get-FileHash $f.ActivePath).Hash
    $lock=[IO.File]::Open((Join-Path $f.Run.Path 'run.lock'),'OpenOrCreate','ReadWrite','None')
    try {
        $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
        Assert-Change ($message.Contains('前回の処理が実行中')) 'An existing child lock must block archival.'
        Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Lock conflict must retain the pointer.'
    } finally { $lock.Dispose() }

    # Missing state is not proof that deployment never started.
    $f=New-ChangeFixture; $arguments=$f.Arguments; $hash=(Get-FileHash $f.ActivePath).Hash
    Remove-Item -LiteralPath (Join-Path $f.Run.Path 'workflow-state.json')
    $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
    Assert-Change ($message.Contains('不明（workflow-state.jsonなし）')) 'Missing state must be reported as unknown.'
    Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Missing state must retain the pointer.'
    Write-Host 'PASS: configuration mismatch diagnostics, undeployed archival, evidence and lock protection.'
} finally {
    foreach ($root in $fixtures) {
        $resolved=[IO.Path]::GetFullPath($root); $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
        if (!$resolved.StartsWith($temp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ar-config-change-[a-f0-9]{32}$') { throw 'Unexpected fixture cleanup path.' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
    Remove-Item Env:CONFIG_CHANGE_API -ErrorAction SilentlyContinue
    Remove-Item Env:CONFIG_CHANGE_MIGRATION -ErrorAction SilentlyContinue
}
