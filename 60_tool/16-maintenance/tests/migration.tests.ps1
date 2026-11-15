$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot '..\SkillTreeMigration.ps1'
. (Resolve-Path -LiteralPath $scriptPath)

function Assert-True { param([bool] $Condition, [string] $Message) if (-not $Condition) { throw $Message } }

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "skilltree-migration-tests-$([Guid]::NewGuid().ToString('N'))"
[System.IO.Directory]::CreateDirectory($testRoot) | Out-Null
Start-Transcript -LiteralPath (Join-Path $testRoot 'verification.log') | Out-Null
$outputPath = Join-Path $testRoot 'migration.tests.output.txt'
try {
    $targetGeneration = ('a' * 64); $sourceGeneration = ('b' * 64); $session = [Guid]::NewGuid().ToString('D')
    $accountIds = @(1..101 | ForEach-Object { [Guid]::NewGuid().ToString('D') })
    $script:applied = @{}
    $records = [System.Collections.Generic.List[object]]::new(); $commitAttempts = 0; $firstCommitOperationIds = @(); $requestLog = [System.Collections.Generic.List[object]]::new()
    $config = [pscustomobject]@{ enabled = $true; baseUrl = 'http://127.0.0.1:5010'; apiKeyEnvironmentVariable = 'SKILLTREE_TEST_API'; migrationKeyEnvironmentVariable = 'SKILLTREE_TEST_MIGRATION'; serverIds = @('alpha', 'beta'); scope = 'AllCandidates'; accountIds = @() }
    [Environment]::SetEnvironmentVariable('SKILLTREE_TEST_API', 'common-test-secret')
    [Environment]::SetEnvironmentVariable('SKILLTREE_TEST_MIGRATION', 'migration-test-secret')
    Set-Content -LiteralPath (Join-Path $testRoot 'migration-commit-started.json') -Value '{"test":true}' -Encoding utf8NoBOM
    $mock = {
        param($Method, $Uri, $Headers, $Body)
        Assert-True ($Headers['X-Api-Key'] -eq 'common-test-secret' -and $Headers['X-SkillTree-Migration-Key'] -eq 'migration-test-secret') 'Both API authentication headers are required.'
        $script:requestLog.Add([pscustomobject]@{ Method = $Method; Uri = $Uri; Body = $Body })
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/(alpha|beta)$') { return [pscustomobject]@{ serverId = $Matches[1]; serverSessionId = $script:session; definitionGenerationId = $script:targetGeneration; ready = $true } }
        if ($Method -eq 'GET' -and $Uri -match '/runtime/definitions/') { return [pscustomobject]@{ generation = ($Uri.Split('/')[-1]); snapshot = @('root') } }
        if ($Method -eq 'GET' -and $Uri -match 'migration-candidates') {
            $page = [int]([regex]::Match($Uri, 'page=(\d+)').Groups[1].Value); $slice = if ($page -eq 1) { $script:accountIds[0..99] } else { $script:accountIds[100..100] }
            return [pscustomobject]@{ runtime = [pscustomobject]@{ serverId = 'alpha'; serverSessionId = $script:session; definitionGenerationId = $script:targetGeneration; ready = $true }; page = $page; pageSize = 100; totalCount = 101; items = @($slice | ForEach-Object { [pscustomobject]@{ accountId = $_; fromGenerationId = $script:sourceGeneration; expectedStateVersion = 7; legacyBaselineNodeIds = $(if ($_ -eq $script:accountIds[0]) { @() } else { @('root') }) } }) }
        }
        if ($Method -eq 'GET' -and $Uri -match '/accounts/([^/]+)/migration-state$') {
            $accountId=$Matches[1]; $done=$script:applied.ContainsKey($accountId)
            return [pscustomobject]@{ definitionGenerationId=$(if ($done) { $script:targetGeneration } else { $script:sourceGeneration }); version=$(if ($done) { 8 } else { 7 }); unlockedNodes=@(if ($accountId -ne $script:accountIds[0]) { [pscustomobject]@{ nodeId='root' } }) }
        }
        if ($Method -eq 'POST' -and $Uri -match '/migrations/batch') {
            Assert-True ($Body.items.Count -le 100) 'Batches must contain no more than 100 accounts.'
            foreach ($item in $Body.items) { Assert-True ($item.migration.removeNodeIds.Count -eq 0 -and $item.migration.consumedClassAssignments.Count -eq 0 -and -not $item.migration.confirmLegacyBaseline) 'Automated requests must be keep-only and never approve legacy.' }
            if ($Body.mode -eq 'COMMIT') {
                # Model the API transaction BEFORE dropping the HTTP response.
                foreach ($item in $Body.items) { $script:applied[$item.accountId]=$true }
                $script:commitAttempts++; if ($script:commitAttempts -eq 1) { $script:firstCommitOperationIds = @($Body.items | ForEach-Object { $_.migration.operationId }); throw 'simulated response loss' }
            }
            $status = if ($Body.mode -eq 'PREVIEW') { 'PREVIEW' } else { 'APPLIED' }
            return [pscustomobject]@{ items = @($Body.items | ForEach-Object { [pscustomobject]@{ accountId = $_.accountId; operationId = $_.migration.operationId; status = $status; migration = [pscustomobject]@{ status = $status } } }) }
        }
        throw "Unexpected mock route: $Method $Uri"
    }
    $script:targetGeneration = $targetGeneration; $script:sourceGeneration = $sourceGeneration; $script:session = $session; $script:accountIds = $accountIds; $script:requestLog = $requestLog
    $failed = $false
    try { Invoke-SkillTreeMigration -Config $config -RunDirectory $testRoot -Commit -HttpInvoker $mock | Out-Null } catch { $failed = $true; $firstFailure = $_.Exception.Message; $firstFailureStack = $_.ScriptStackTrace }
    Assert-True $failed 'The simulated unknown commit result must fail the first run.'
    Assert-True ($firstFailure -match 'Migration API request result is unknown') "The first failure must safely report an unknown COMMIT result: $firstFailure"
    $saved = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $testRoot 'skilltree-migration-state.json') | ConvertFrom-Json
    Assert-True (@($saved.Records | Where-Object CommitStatus -eq 'APPLIED').Count -eq 0) 'No account may be marked applied after an unknown batch response.'
    $result = Invoke-SkillTreeMigration -Config $config -RunDirectory $testRoot -Commit -HttpInvoker $mock
    Assert-True ($result.Status -eq 'APPLIED' -and $result.AppliedCount -eq 101) 'Resume must apply every previewed account.'
    $resumedCommit = @($script:requestLog | Where-Object { $_.Method -eq 'POST' -and $_.Body.mode -eq 'COMMIT' })[1]
    Assert-True ((@($resumedCommit.Body.items | ForEach-Object { $_.migration.operationId }) -join ',') -eq ($script:firstCommitOperationIds -join ',')) 'Resume must reuse persisted operation IDs after an unknown result.'
    Assert-True ((@($script:requestLog | Where-Object { $_.Method -eq 'GET' -and $_.Uri -match 'migration-candidates' }).Count) -eq 2) 'All candidate pages must be enumerated exactly once.'
    Assert-True ($result.Accounts.Count -eq 101) 'Report must retain account-level results.'

    $mixedRoot=Join-Path $testRoot 'mixed'; [IO.Directory]::CreateDirectory($mixedRoot) | Out-Null
    Set-Content -LiteralPath (Join-Path $mixedRoot 'migration-commit-started.json') -Value '{}' -Encoding utf8
    $mixedConfig=[pscustomobject]@{ enabled=$true; baseUrl=$config.baseUrl; apiKeyEnvironmentVariable=$config.apiKeyEnvironmentVariable; migrationKeyEnvironmentVariable=$config.migrationKeyEnvironmentVariable; serverIds=@('alpha'); scope='ExplicitAccounts'; accountIds=@($accountIds[0],$accountIds[1]) }
    $script:applied=@{}; $script:rejectFirst=$true
    $mixedMock={
        param($Method,$Uri,$Headers,$Body)
        if ($Method -eq 'POST' -and $Body.mode -eq 'COMMIT') {
            return [pscustomobject]@{items=@($Body.items | ForEach-Object {
                $status=if ($script:rejectFirst -and $_.accountId -eq $script:accountIds[0]) { 'REJECTED' } else { $script:applied[$_.accountId]=$true; 'APPLIED' }
                [pscustomobject]@{accountId=$_.accountId;operationId=$_.migration.operationId;status=$status}
            })}
        }
        & $mock -Method $Method -Uri $Uri -Headers $Headers -Body $Body
    }
    $mixedFailed=$false
    try { Invoke-SkillTreeMigration -Config $mixedConfig -RunDirectory $mixedRoot -Commit -HttpInvoker $mixedMock | Out-Null } catch { $mixedFailed=$_.Exception.Message -match 'COMMIT rejected' }
    Assert-True $mixedFailed 'Mixed response must fail after preserving all results.'
    $report=Get-Content -Raw -LiteralPath (Join-Path $mixedRoot 'skilltree-migration-result.json') | ConvertFrom-Json
    Assert-True ($report.Accounts[0].commit -eq 'REJECTED' -and $report.Accounts[1].commit -eq 'APPLIED') 'Later APPLIED must survive an earlier rejection.'
    $script:rejectFirst=$false
    $resumed=Invoke-SkillTreeMigration -Config $mixedConfig -RunDirectory $mixedRoot -Commit -HttpInvoker $mixedMock
    Assert-True ($resumed.AppliedCount -eq 2) 'Mixed batch can resume without losing its success.'

    $zeroMock={
        param($Method,$Uri,$Headers,$Body)
        if ($Uri -match 'migration-candidates') { return [pscustomobject]@{runtime=[pscustomobject]@{serverId='alpha';serverSessionId=$script:session;definitionGenerationId=$script:targetGeneration;ready=$true};page=1;pageSize=100;totalCount=0;items=@()} }
        & $mock -Method $Method -Uri $Uri -Headers $Headers -Body $Body
    }
    $zeroRoot=Join-Path $testRoot 'zero'; [IO.Directory]::CreateDirectory($zeroRoot) | Out-Null
    $zero=Invoke-SkillTreeMigration -Config $config -RunDirectory $zeroRoot -HttpInvoker $zeroMock
    Assert-True ($zero.SelectedCount -eq 0) 'No candidates is a successful no-op.'
    $currentRoot=Join-Path $testRoot 'current'; [IO.Directory]::CreateDirectory($currentRoot) | Out-Null
    $current=Invoke-SkillTreeMigration -Config $mixedConfig -RunDirectory $currentRoot -HttpInvoker $zeroMock
    Assert-True ($current.SelectedCount -eq 0) 'Already current explicit accounts are a successful no-op.'

    $differentRuntimeMock={
        param($Method,$Uri,$Headers,$Body)
        if ($Uri -match '/runtime/servers/beta$') { return [pscustomobject]@{serverId='beta';serverSessionId=$script:session;definitionGenerationId=$script:sourceGeneration;ready=$true} }
        & $mock -Method $Method -Uri $Uri -Headers $Headers -Body $Body
    }
    $differentRoot=Join-Path $testRoot 'different'; [IO.Directory]::CreateDirectory($differentRoot) | Out-Null
    $differentFailed=$false
    try { Invoke-SkillTreeMigration -Config $config -RunDirectory $differentRoot -HttpInvoker $differentRuntimeMock | Out-Null } catch { $differentFailed=$_.Exception.Message -match 'same generation' }
    Assert-True $differentFailed 'Mixed channel generations must be rejected.'

    $legacyRoot = Join-Path $testRoot 'legacy'; [System.IO.Directory]::CreateDirectory($legacyRoot) | Out-Null
    Set-Content -LiteralPath (Join-Path $legacyRoot 'migration-commit-started.json') -Value '{"test":true}' -Encoding utf8NoBOM
    $legacyAccount = [Guid]::NewGuid().ToString('D'); $script:legacyPostCount = 0
    $legacyConfig = [pscustomobject]@{ enabled = $true; baseUrl = 'http://127.0.0.1:5010'; apiKeyEnvironmentVariable = 'SKILLTREE_TEST_API'; migrationKeyEnvironmentVariable = 'SKILLTREE_TEST_MIGRATION'; serverIds = @('alpha'); scope = 'ExplicitAccounts'; accountIds = @($legacyAccount) }
    $legacyMock = {
        param($Method, $Uri, $Headers, $Body)
        if ($Method -eq 'GET' -and $Uri -match '/runtime/servers/alpha$') { return [pscustomobject]@{ serverId = 'alpha'; serverSessionId = $script:session; definitionGenerationId = $script:targetGeneration; ready = $true } }
        if ($Method -eq 'GET' -and $Uri -match '/runtime/definitions/') { return [pscustomobject]@{ generation = $script:targetGeneration; snapshot = @('root') } }
        if ($Method -eq 'GET' -and $Uri -match 'migration-candidates') { return [pscustomobject]@{ runtime = [pscustomobject]@{ serverId = 'alpha'; serverSessionId = $script:session; definitionGenerationId = $script:targetGeneration; ready = $true }; page = 1; pageSize = 100; totalCount = 1; items = @([pscustomobject]@{ accountId = $script:legacyAccount; fromGenerationId = $null; expectedStateVersion = 2; legacyBaselineNodeIds = @('root') }) } }
        if ($Method -eq 'GET' -and $Uri -match '/migration-state$') { return [pscustomobject]@{ definitionGenerationId = $null; version = 2; unlockedNodes = @([pscustomobject]@{ nodeId = 'root' }) } }
        if ($Method -eq 'POST') { $script:legacyPostCount++; throw 'legacy must not be posted' }
        throw 'Unexpected legacy mock route.'
    }
    $script:legacyAccount = $legacyAccount; $legacyFailed = $false
    try { Invoke-SkillTreeMigration -Config $legacyConfig -RunDirectory $legacyRoot -Commit -HttpInvoker $legacyMock | Out-Null } catch { $legacyFailed = $_.Exception.Message -match 'requires manual approval' }
    Assert-True $legacyFailed 'Legacy candidates must be rejected before PREVIEW or COMMIT.'
    Assert-True ($script:legacyPostCount -eq 0) 'Legacy candidates must never be posted automatically.'
    $encodedRoot=Join-Path $testRoot 'encoded'; [IO.Directory]::CreateDirectory($encodedRoot) | Out-Null
    Set-Content -LiteralPath (Join-Path $encodedRoot 'migration-commit-started.json') -Value '{}' -Encoding utf8
    $encodedConfig=[pscustomobject]@{enabled=$true;baseUrl=$config.baseUrl;apiKeyEnvironmentVariable=$config.apiKeyEnvironmentVariable;migrationKeyEnvironmentVariable=$config.migrationKeyEnvironmentVariable;serverIds=@('channel?maint');scope='ExplicitAccounts';accountIds=@($accountIds[0])}
    $script:applied=@{}; $script:encodedPosts=0
    $encodedMock={
        param($Method,$Uri,$Headers,$Body)
        if ($Uri -match '/runtime/servers/channel%3Fmaint$') { return [pscustomobject]@{serverId='channel?maint';serverSessionId=$script:session;definitionGenerationId=$script:targetGeneration;ready=$true} }
        if ($Method -eq 'POST') {
            Assert-True ($Uri.Contains('/servers/channel%3Fmaint/migrations/batch?server_session_id=')) 'POST serverId must be URI-escaped.'
            $script:encodedPosts++
        }
        $response = & $mock -Method $Method -Uri $Uri -Headers $Headers -Body $Body
        if ($Uri -match 'migration-candidates') { $response.runtime.serverId='channel?maint' }
        return $response
    }
    $encodedResult=Invoke-SkillTreeMigration -Config $encodedConfig -RunDirectory $encodedRoot -Commit -HttpInvoker $encodedMock
    Assert-True ($encodedResult.AppliedCount -eq 1 -and $script:encodedPosts -eq 2) 'Escaped serverId must reach both PREVIEW and COMMIT.'
    'PASS: paging, empty nodes/candidates, explicit scope/no-op, runtime mismatch, response-loss after DB commit, mixed results, reports and legacy rejection.' | Tee-Object -FilePath $outputPath
}
finally {
    [Environment]::SetEnvironmentVariable('SKILLTREE_TEST_API', $null)
    [Environment]::SetEnvironmentVariable('SKILLTREE_TEST_MIGRATION', $null)
    "Test result output: $outputPath"
    Stop-Transcript | Out-Null
}
