#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar-existing-settings-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
Start-Transcript -LiteralPath (Join-Path $root 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
function New-Run([string]$Name) { $path=Join-Path $root $Name; New-Item -ItemType Directory -Path $path | Out-Null; return $path }
$env:AR_SETTINGS_TEST_API='stale-environment-api'
$env:AR_SETTINGS_TEST_MIGRATION='stale-environment-migration'
try {
    $settingsPath=Join-Path $root 'appsettings.json'
    $settings=@{ApiKey=@{Key='api-fixture-1'};SkillTreeRuntime=@{Key='runtime-fixture';MigrationKey='migration-fixture-1'}}
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $settingsPath
    $user=[guid]::NewGuid().ToString(); $other=[guid]::NewGuid().ToString()
    $a=[guid]::NewGuid().ToString(); $b=[guid]::NewGuid().ToString(); $c=[guid]::NewGuid().ToString(); $d=[guid]::NewGuid().ToString()
    $config=@{enabled=$true;baseUrl='http://127.0.0.1:1';apiKeyEnvironmentVariable='AR_SETTINGS_TEST_API';migrationKeyEnvironmentVariable='AR_SETTINGS_TEST_MIGRATION';apiSettingsPath=$settingsPath;serverIds=@('Dev');scope='ExplicitAccounts';accountIds=@();accountUserIds=@($user)}
    $script:owned=@($a,$b); $script:otherAccount=$c; $script:owner=$user; $script:wrongOwner=$other; $script:spoof=$false
    $script:resolveCount=0; $script:posts=0; $script:expectedApi='api-fixture-1'; $script:expectedMigration='migration-fixture-1'
    $script:session=[guid]::NewGuid().ToString(); $script:target='a'*64; $script:source='b'*64
    $http={param($Method,$Uri,$Headers,$Body)
        Assert ($Headers['X-Api-Key'] -eq $script:expectedApi -and $Headers['X-SkillTree-Migration-Key'] -eq $script:expectedMigration) 'Existing API settings must override stale environment credentials.'
        $runtime=@{serverId='Dev';serverSessionId=$script:session;definitionGenerationId=$script:target;ready=$true}
        if ($Uri -match '/runtime/servers/Dev$') { return $runtime }
        if ($Uri -match '/runtime/definitions/') { return @{nodes=@();positions=@();edges=@()} }
        if ($Uri -match '/api/account\?user_id=') {
            $script:resolveCount++
            return @($script:owned | ForEach-Object { @{uuid=$_;userId=$(if($script:spoof){$script:wrongOwner}else{$script:owner})} })
        }
        if ($Uri -match 'migration-candidates') {
            $ids=@($script:owned)+@($script:otherAccount)
            return @{runtime=$runtime;page=1;pageSize=100;totalCount=$ids.Count;items=@($ids | ForEach-Object { @{accountId=$_;fromGenerationId=$script:source;expectedStateVersion=3;legacyBaselineNodeIds=@()} })}
        }
        if ($Uri -match 'migration-state$') { return @{definitionGenerationId=$script:source;version=3;unlockedNodes=@()} }
        if ($Method -eq 'POST') {
            $script:posts++
            Assert (@($Body.items | Where-Object accountId -eq $script:otherAccount).Count -eq 0) 'Another user account must not be migrated.'
            return @{items=@($Body.items | ForEach-Object {@{accountId=$_.accountId;operationId=$_.migration.operationId;status='PREVIEW'}})}
        }
        throw 'Unexpected mock route'
    }
    $first=New-Run 'first'
    $preview=Invoke-SkillTreeMigration -Config $config -RunDirectory $first -HttpInvoker $http
    Assert ($preview.SelectedCount -eq 2 -and $script:resolveCount -eq 1) 'All configured user characters must be resolved.'
    $script:owned+=@($d)
    $again=Invoke-SkillTreeMigration -Config $config -RunDirectory $first -HttpInvoker $http
    Assert ($again.SelectedCount -eq 2 -and $script:resolveCount -eq 1) 'Retry must preserve the frozen account set.'
    $next=Invoke-SkillTreeMigration -Config $config -RunDirectory (New-Run 'next') -HttpInvoker $http
    Assert ($next.SelectedCount -eq 3 -and $script:resolveCount -eq 2) 'New run must include new characters.'
    $settings.ApiKey.Key='api-fixture-2'; $settings.SkillTreeRuntime.MigrationKey='migration-fixture-2'
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $settingsPath
    $script:expectedApi='api-fixture-2'; $script:expectedMigration='migration-fixture-2'
    $again=Invoke-SkillTreeMigration -Config $config -RunDirectory $first -HttpInvoker $http
    Assert ($again.SelectedCount -eq 2) 'Key rotation must be read without replacing the existing run.'
    $earlyRun=New-Run 'early-failure'; $script:failCandidates=$true
    $earlyHttp={param($Method,$Uri,$Headers,$Body) if($script:failCandidates -and $Uri -match 'migration-candidates'){throw 'simulated candidate connection failure'}; & $http -Method $Method -Uri $Uri -Headers $Headers -Body $Body}
    $earlyFailed=$false
    try { Invoke-SkillTreeMigration -Config $config -RunDirectory $earlyRun -HttpInvoker $earlyHttp | Out-Null } catch {$earlyFailed=$true}
    Assert ($earlyFailed -and (Test-Path -LiteralPath (Join-Path $earlyRun 'skilltree-migration-targets.json'))) 'Resolved users must be persisted before candidate enumeration.'
    $resolveCountBefore=$script:resolveCount; $ownedCountBefore=$script:owned.Count
    $script:owned+=@([guid]::NewGuid().ToString()); $script:failCandidates=$false
    $earlyResumed=Invoke-SkillTreeMigration -Config $config -RunDirectory $earlyRun -HttpInvoker $earlyHttp
    Assert ($earlyResumed.SelectedCount -eq $ownedCountBefore -and $script:resolveCount -eq $resolveCountBefore) 'Early retry must not resolve or add new characters.'
    $script:spoof=$true; $before=$script:posts; $blocked=$false
    try { Invoke-SkillTreeMigration -Config $config -RunDirectory (New-Run 'spoof') -HttpInvoker $http | Out-Null } catch { $blocked=$_.Exception.Message -match 'unexpected owner' }
    Assert ($blocked -and $script:posts -eq $before) 'Wrong owner must stop before posting.'
    $settings.SkillTreeRuntime.MigrationKey=$settings.SkillTreeRuntime.Key
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $settingsPath
    $blocked=$false
    try { Get-SkillTreeMigrationCredentials (ConvertTo-SkillTreeMigrationConfig $config) | Out-Null } catch { $blocked=$_.Exception.Message -match 'differ from the runtime key' }
    Assert $blocked 'Runtime and migration keys must remain distinct.'
    $settings.SkillTreeRuntime.Key=''; $settings.SkillTreeRuntime.MigrationKey='migration-fixture-2'
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $settingsPath
    $blocked=$false
    try { Get-SkillTreeMigrationCredentials (ConvertTo-SkillTreeMigrationConfig $config) | Out-Null } catch { $blocked=$_.Exception.Message -match 'runtime key must be non-empty' }
    Assert $blocked 'Missing runtime credentials must be rejected before deployment.'
    foreach($file in Get-ChildItem -LiteralPath $first -File) {
        $text=Get-Content -Raw -LiteralPath $file.FullName
        Assert ($text -notmatch 'api-fixture|migration-fixture|runtime-fixture') 'Run records must not contain credentials.'
    }
    Write-Host "PASS: API settings reference/rotation, user-scoped characters, frozen retries, owner validation, no secret persistence. Evidence: $root"
} finally {
    Remove-Item Env:AR_SETTINGS_TEST_API,Env:AR_SETTINGS_TEST_MIGRATION -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}
