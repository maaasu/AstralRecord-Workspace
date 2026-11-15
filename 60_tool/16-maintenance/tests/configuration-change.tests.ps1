#requires -Version 7.0
param([switch]$Interactive)
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
    # A completed Dev deployment waiting for startup can be superseded only with explicit external-state confirmation.
    $f=New-ChangeFixture 'WaitingForStartup' 'SUCCEEDED'; $arguments=$f.Arguments
    Save-UpdateWorkflowJson @{completedAtUtc=[DateTime]::UtcNow.ToString('o')} (Join-Path $f.Run.Path 'deploy-action-success.json')
    $previousHash=(Get-FileHash $f.ActivePath).Hash
    $arguments.Recovery='Restart'; $arguments.RecoveryChecked=$true
    $arguments.WorkflowConfig.seedMasterData=$true
    $script:restartDeploymentCount=0; $script:restartSeedCount=0; $script:restartRuntimeCount=0; $script:restartPublicationCount=0
    $script:restartSession=[Guid]::NewGuid().ToString('D')
    $arguments.DeployAction={param($RunDirectory) $script:restartDeploymentCount++; Save-UpdateWorkflowJson @{} (Join-Path $RunDirectory 'deploy-action-success.json')}
    $arguments.HttpInvoker={param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'GET') {
            $script:restartRuntimeCount++
            if ($script:restartRuntimeCount -eq 1) { return [pscustomobject]@{StatusCode=404;Body=$null} }
            return [pscustomobject]@{serverId='Dev';serverSessionId=$script:restartSession;definitionGenerationId=('a'*64);ready=$true;lastSeenUtc=[DateTime]::UtcNow.ToString('o')}
        }
        if ($Uri -match '/master-data/seed') { $script:restartSeedCount++; return [pscustomobject]@{status='SUCCEEDED'} }
        if ($Uri -match '/patches/publish') { $script:restartPublicationCount++; return [pscustomobject]@{definitionGenerationId=('a'*64);patchVersion=9} }
        throw 'Unexpected restart API request.'
    }
    $arguments.SleepAction={param($Seconds)}
    $result=Invoke-UpdateWorkflow @arguments
    Assert-Change ($result.Status -eq 'COMPLETED' -and $result.RunDirectory -ne $f.Run.Path) 'Confirmed startup restart must complete a distinct run.'
    Assert-Change ($script:restartDeploymentCount -eq 1 -and $script:restartSeedCount -eq 1 -and $script:restartPublicationCount -eq 1) 'Confirmed restart must deploy, seed and publish once using the new run.'
    $archives=@(Get-ChildItem -LiteralPath $f.Run.Path -Filter 'active-run.before-recovery-*.json' | Where-Object Name -NotLike '*.reason.json')
    Assert-Change ($archives.Count -eq 1 -and (Get-FileHash $archives[0].FullName).Hash -eq $previousHash) 'Startup restart must preserve the previous active pointer.'
    $previousState=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $f.Run.Path 'workflow-state.json') | ConvertFrom-Json
    Assert-Change ($previousState.status -eq 'WaitingForStartup' -and $previousState.seedStatus -eq 'SUCCEEDED') 'Startup restart must retain all previous workflow records.'

    # Auto/noninteractive and incompletely confirmed Restart cannot supersede an already deployed run.
    foreach ($recovery in @('Auto','Restart')) {
        $f=New-ChangeFixture 'WaitingForStartup' 'SUCCEEDED'; $arguments=$f.Arguments; $arguments.Recovery=$recovery
        Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path 'deploy-action-success.json')
        $hash=(Get-FileHash $f.ActivePath).Hash; $message=''
        try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
        Assert-Change ($message.Contains('-Recovery Restart -RecoveryChecked -ServersStopped -AdmissionClosed')) 'Startup restart requires all explicit confirmations when input is redirected.'
        Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Unconfirmed restart must preserve active records.'
    }

    # Publication ambiguity, bulk migration and contradictory distribution records remain blocked even with all confirmations.
    foreach ($name in @('patch-publication-result.json','migration-commit-started.json','deployment.json')) {
        $f=New-ChangeFixture 'WaitingForStartup' 'SUCCEEDED'; $arguments=$f.Arguments; $arguments.Recovery='Restart'; $arguments.RecoveryChecked=$true
        Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path 'deploy-action-success.json')
        Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path $name)
        $hash=(Get-FileHash $f.ActivePath).Hash; $message=''
        try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
        Assert-Change ($message -match 'Configuration changed') "Conflicting evidence must block startup restart: $name"
        Assert-Change ((Get-FileHash $f.ActivePath).Hash -eq $hash) 'Conflicting evidence must retain all records.'
    }
    $f=New-ChangeFixture 'PublishingPatch' 'SUCCEEDED'; $arguments=$f.Arguments; $arguments.Recovery='Restart'; $arguments.RecoveryChecked=$true
    Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path 'deploy-action-success.json')
    $hash=(Get-FileHash $f.ActivePath).Hash; $message=''
    try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
    Assert-Change ($message.Contains('PublishingPatch') -and (Get-FileHash $f.ActivePath).Hash -eq $hash) 'An unknown publication result must never authorize superseding the run.'

    # Neither a live child lock nor running-server mode can be bypassed by the confirmed restart switches.
    $f=New-ChangeFixture 'WaitingForStartup' 'SUCCEEDED'; $arguments=$f.Arguments; $arguments.Recovery='Restart'; $arguments.RecoveryChecked=$true
    Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path 'deploy-action-success.json')
    $hash=(Get-FileHash $f.ActivePath).Hash
    $lock=[IO.File]::Open((Join-Path $f.Run.Path 'run.lock'),'OpenOrCreate','ReadWrite','None')
    try {
        $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
        Assert-Change ($message.Contains('前回の処理が実行中') -and (Get-FileHash $f.ActivePath).Hash -eq $hash) 'A live child lock must block confirmed startup restart.'
    } finally { $lock.Dispose() }
    $arguments.Recovery='Auto'; $arguments.ServersStopped=$false; $arguments.ServerAlreadyRunning=$true; $arguments.AllowSameSessionPublication=$true
    $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
    Assert-Change ($message.Contains('-ServerAlreadyRunningを外して') -and (Get-FileHash $f.ActivePath).Hash -eq $hash) 'Running-server mode must not supersede a configured deployment.'

    if ($Interactive) {
        Assert-Change (![Console]::IsInputRedirected) 'Interactive menu tests require a console/PTY.'
        $f=New-ChangeFixture 'WaitingForStartup' 'SUCCEEDED'; $arguments=$f.Arguments
        $arguments.Remove('ServersStopped'); $arguments.Remove('AdmissionClosed')
        Save-UpdateWorkflowJson @{} (Join-Path $f.Run.Path 'deploy-action-success.json')
        $hash=(Get-FileHash $f.ActivePath).Hash
        function Read-Host { param($Prompt) return '2' }
        try {
            $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
            Assert-Change ($message.Contains('復旧を中止') -and (Get-FileHash $f.ActivePath).Hash -eq $hash) 'Cancel in the menu must preserve the old run.'
        } finally { Remove-Item Function:Read-Host }
        $script:interactivePrompts=[Collections.Generic.List[string]]::new()
        function Read-Host {
            param($Prompt)
            $script:interactivePrompts.Add($Prompt)
            if ($Prompt.StartsWith('1:')) { return '1' }
            if ($Prompt.Contains('RECOVERY-CHECKED')) { return 'RECOVERY-CHECKED' }
            if ($Prompt.Contains('Type DEPLOY')) { return 'DEPLOY' }
            throw "Unexpected confirmation prompt: $Prompt"
        }
        $arguments.HttpInvoker={return [pscustomobject]@{StatusCode=404;Body=$null}}
        $script:interactiveDeploymentReached=$false
        $arguments.DeployAction={$script:interactiveDeploymentReached=$true; throw 'fixture deployment stop'}
        try {
            $message=''; try { Invoke-UpdateWorkflow @arguments | Out-Null } catch { $message=$_.Exception.Message }
            Assert-Change ($script:interactiveDeploymentReached -and $message.Contains('Deployment action failed') -and $script:interactivePrompts.Count -eq 3) 'Menu selection and both confirmations must lead to fresh deployment.'
            $active=Get-Content -Raw -Encoding UTF8 -LiteralPath $f.ActivePath | ConvertFrom-Json
            Assert-Change ($active.relativeRunDirectory -ne $f.Run.Relative -and $active.configurationFingerprint -eq $newFingerprint) 'The confirmed interactive menu must create a new run.'
        } finally { Remove-Item Function:Read-Host }
        Write-Host 'PASS: interactive startup restart menu cancellation and confirmed fresh deployment.'
    }
    Write-Host 'PASS: configuration mismatch diagnostics, startup restart confirmation, undeployed archival, evidence and lock protection.'


} finally {
    foreach ($root in $fixtures) {
        $resolved=[IO.Path]::GetFullPath($root); $temp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
        if (!$resolved.StartsWith($temp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^ar-config-change-[a-f0-9]{32}$') { throw 'Unexpected fixture cleanup path.' }
        if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
    Remove-Item Env:CONFIG_CHANGE_API -ErrorAction SilentlyContinue
    Remove-Item Env:CONFIG_CHANGE_MIGRATION -ErrorAction SilentlyContinue
}
