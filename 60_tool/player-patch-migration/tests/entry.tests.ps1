#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-player-patch-entry-'+[guid]::NewGuid().ToString('N'))
$priorProgramData=$env:ProgramData; $priorLocalAppData=$env:LOCALAPPDATA
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
try {
    $toolsRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    foreach ($relative in @('player-patch-migration/player-patch-migration.ps1','maintenance/Distribution.ps1','maintenance/Diagnostics.ps1','maintenance/SkillTreeMigration.ps1','control-gate/Control.Gate.psm1')) {
        $destination=Join-Path $fixture $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $toolsRoot $relative) -Destination $destination
    }
    # Run the real entry and migration state machine, replacing only HTTP with local fixture responses.
    Add-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'maintenance/SkillTreeMigration.ps1') -Value @'
function Invoke-SkillTreeMigrationHttp {
    param($Method,$Uri,$Headers,$Body,$ApiBaseUrl,[switch]$AllowPrivateApiInsecureTls)
    $fixtureRoot=Split-Path $PSScriptRoot
    $data=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $fixtureRoot 'api.json') | ConvertFrom-Json
    if ($Headers['X-Api-Key'] -ne 'entry-api' -or $Headers['X-SkillTree-Migration-Key'] -ne 'entry-migration') { throw 'Missing fixture credentials.' }
    $runtime=@{serverId='channel';serverSessionId=$data.session;definitionGenerationId=('a'*64);ready=$true}
    if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/channel$') { return $runtime }
    if ($Method -eq 'GET' -and $Uri -match '/runtime/definitions/') { return [ordered]@{nodes=@();edges=@();positions=@()} }
    if ($Method -eq 'GET' -and $Uri -match '/migration-candidates\?') {
        return @{runtime=$runtime;page=1;pageSize=100;totalCount=1;items=@(@{accountId=$data.account;fromGenerationId=('b'*64);expectedStateVersion=3;legacyBaselineNodeIds=@()})}
    }
    if ($Method -eq 'GET' -and $Uri -match '/migration-state$') { return @{definitionGenerationId=('b'*64);version=3;unlockedNodes=@()} }
    if ($Method -eq 'POST' -and $Uri -match '/migrations/batch\?') {
        @{mode=$Body.mode;operationId=$Body.items[0].migration.operationId} | ConvertTo-Json -Compress | Add-Content -Encoding UTF8 -LiteralPath (Join-Path $fixtureRoot 'requests.jsonl')
        $status=if ($Body.mode -eq 'PREVIEW') {'PREVIEW'} else {'APPLIED'}
        if ($data.online -and $Body.mode -eq 'COMMIT') { $status='REJECTED' }
        return @{items=@(@{accountId=$data.account;operationId=$Body.items[0].migration.operationId;status=$status})}
    }
    throw 'Unexpected fixture API route; no network is permitted.'
}
'@
    $data=@{session=[guid]::NewGuid().ToString();account=[guid]::NewGuid().ToString();online=$false}
    $data | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'api.json')
    $config=@{schemaVersion=1;migration=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='PLAYER_PATCH_TEST_API';migrationKeyEnvironmentVariable='PLAYER_PATCH_TEST_MIGRATION';serverIds=@('channel');scope='AllCandidates';accountIds=@()}}
    $configPath=Join-Path $fixture 'config.json'
    $config | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $configPath
    $env:PLAYER_PATCH_TEST_API='entry-api'; $env:PLAYER_PATCH_TEST_MIGRATION='entry-migration'
    $entry=Join-Path $fixture 'player-patch-migration/player-patch-migration.ps1'
    $run=Join-Path $fixture 'run'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run 2>&1 | Out-Host
    Assert ($LASTEXITCODE -ne 0 -and !(Test-Path -LiteralPath $run)) 'Missing admission confirmation must fail before writes.'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run -AdmissionClosed
    Assert ($LASTEXITCODE -eq 0) 'Default Preview failed.'
    Assert (!(Test-Path -LiteralPath (Join-Path $run 'migration-commit-started.json'))) 'Default Preview must not create a Commit marker.'
    $requests=@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl') | ConvertFrom-Json)
    Assert ($requests.Count -eq 1 -and $requests[0].mode -eq 'PREVIEW') 'Default entry sent a Commit.'
    $operationId=$requests[0].operationId
    $lock=[IO.File]::Open((Join-Path $run 'run.lock'),'OpenOrCreate','ReadWrite','None')
    try {
        & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run -AdmissionClosed -Phase Commit 2>&1 | Out-Host
        Assert ($LASTEXITCODE -ne 0) 'Concurrent Commit must be refused.'
    } finally { $lock.Dispose() }
    $data.online=$true
    $data | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'api.json')
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run -AdmissionClosed -Phase Commit 2>&1 | Out-Host
    Assert ($LASTEXITCODE -ne 0) 'An API-rejected online player must not complete.'
    $data.online=$false
    $data | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'api.json')
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run -AdmissionClosed -Phase Commit
    Assert ($LASTEXITCODE -eq 0) 'Explicit Commit retry failed.'
    $requests=@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl') | ConvertFrom-Json)
    Assert ($requests.Count -eq 3 -and @($requests.operationId | Select-Object -Unique).Count -eq 1 -and $requests[-1].operationId -eq $operationId) 'Preview and Commit retries must retain the same operation ID.'
    $result=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $run 'skilltree-migration-result.json') | ConvertFrom-Json
    Assert ($result.Status -eq 'APPLIED' -and $result.AppliedCount -eq 1) 'Applied result was not persisted.'
    $config.migration.scope='ExplicitAccounts'; $config.migration.accountIds=@($data.account)
    $config | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $configPath
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $run -AdmissionClosed -Phase Commit 2>&1 | Out-Host
    Assert ($LASTEXITCODE -ne 0) 'Changing scope in an existing run must fail.'
    Assert (!(Test-Path -LiteralPath (Join-Path $run 'deployment.json'))) 'Bulk entry must not perform deployment.'
    # The Control response may be lost after the server has committed Release.
    foreach ($relative in @('deploy-debug/dev-update.ps1','deploy-debug/deploy-debug.ps1','maintenance/maintenance.ps1',
        'maintenance/UpdateWorkflow.ps1','master-data-reload/master-data-reload.ps1')) {
        $destination=Join-Path $fixture $relative
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $toolsRoot $relative) -Destination $destination
    }
    $markerRoot=Join-Path $fixture 'AstralRecordControl'; New-Item -ItemType Directory -Path $markerRoot | Out-Null
    $entryFiles=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    $hashes=@{}; foreach ($file in $entryFiles) { $hashes[$file]=(Get-FileHash -LiteralPath (Join-Path $fixture $file) -Algorithm SHA256).Hash }
    @{schemaVersion=1;toolRoot=$fixture;hashes=$hashes} | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $markerRoot 'managed-operations.enabled')
    $mockCli=Join-Path $fixture 'mock-gate.ps1'
    @'
$command=($args[0..1] -join ' ')
$leaseFile=$args[[array]::IndexOf($args,'--lease-file')+1]
Add-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG -Value ($args -join '|')
if ($command -eq 'gate acquire') {
    $request=Get-Content -Raw -Encoding UTF8 -LiteralPath $args[[array]::IndexOf($args,'--request-file')+1] | ConvertFrom-Json
    $lease=@{workflowId=$request.workflowId;reservationId=[Guid]::NewGuid().ToString('D');resourceKeys=$request.resourceKeys;fences=@(@{hostId=$env:MOCK_GATE_HOST;fence=1});status='Prepared';adapterId=$request.adapterId;runReference=$request.runReference}
    $lease | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $leaseFile
    $lease | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath (Join-Path (Split-Path $env:MOCK_GATE_LOG) ("central-$($lease.reservationId).json"))
} elseif ($command -in @('gate inspect','gate release')) {
    $lease=Get-Content -Raw -Encoding UTF8 -LiteralPath $leaseFile | ConvertFrom-Json
    $centralPath=Join-Path (Split-Path $env:MOCK_GATE_LOG) ("central-$($lease.reservationId).json")
    $central=Get-Content -Raw -Encoding UTF8 -LiteralPath $centralPath | ConvertFrom-Json
    if ($command -eq 'gate inspect') { $central | ConvertTo-Json -Depth 8 }
    else { $central.status='Released'; $central | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $centralPath; $global:LASTEXITCODE=1; return }
}
$global:LASTEXITCODE=0
'@ | Set-Content -Encoding UTF8 -LiteralPath $mockCli
    $env:ProgramData=$fixture; $env:LOCALAPPDATA=$fixture
    $env:MOCK_GATE_LOG=Join-Path $fixture 'gate-commands.log'
    $env:MOCK_GATE_HOST=[Guid]::NewGuid().ToString('D')
    $config.migration.scope='AllCandidates'; $config.migration.accountIds=@()
    $config.controlGate=@{mode='Managed';cliPath=$mockCli;serverUrl='https://control.example.test';environmentId=[Guid]::NewGuid().ToString('D');bindings=@{
        servers=@(@{runtimeServerId='channel';serverId=[Guid]::NewGuid().ToString('D')})
    }}
    $config | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $configPath
    $managedRun=Join-Path $fixture 'managed-run'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $managedRun -AdmissionClosed 2>&1 | Out-Host
    Assert ($LASTEXITCODE -ne 0) 'Lost Preview release response must retain the run.'
    $managedRequests=@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl') | ConvertFrom-Json)
    $previewCount=$managedRequests.Count
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $managedRun -AdmissionClosed
    Assert ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath (Join-Path $managedRun 'control-gate-Preview-released.json'))) 'Preview release marker was not recovered.'
    Assert (@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl')).Count -eq $previewCount) 'Preview recovery repeated an API request.'
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $managedRun -AdmissionClosed -Phase Commit 2>&1 | Out-Host
    Assert ($LASTEXITCODE -ne 0) 'Lost Commit release response must retain the run.'
    $commitCount=@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl')).Count
    & pwsh -NoProfile -File $entry -ConfigPath $configPath -RunDirectory $managedRun -AdmissionClosed -Phase Commit
    Assert ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath (Join-Path $managedRun 'control-gate-Commit-released.json'))) 'Commit release marker was not recovered.'
    Assert (@(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $fixture 'requests.jsonl')).Count -eq $commitCount) 'Commit recovery repeated an API request.'
    $acquires=@(Get-Content -Encoding UTF8 -LiteralPath $env:MOCK_GATE_LOG | Where-Object { $_ -like 'gate|acquire|*' }).Count
    Assert ($acquires -eq 2) 'Preview and Commit must keep separate reservations without recovery reacquire.'
    Write-Host "PASS: default Preview, explicit Commit, admission/run locks, online rejection, persistent operation IDs and config guard. Evidence: $fixture"
} finally {
    Remove-Item Env:PLAYER_PATCH_TEST_API,Env:PLAYER_PATCH_TEST_MIGRATION -ErrorAction SilentlyContinue
    Remove-Item Env:MOCK_GATE_LOG,Env:MOCK_GATE_HOST -ErrorAction SilentlyContinue
    $env:ProgramData=$priorProgramData; $env:LOCALAPPDATA=$priorLocalAppData
    Stop-Transcript | Out-Null
}
