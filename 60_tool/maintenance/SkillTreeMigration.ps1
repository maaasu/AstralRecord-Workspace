Set-StrictMode -Version Latest

function Get-SkillTreeMigrationValue {
    param([Parameter(Mandatory)] $Object, [Parameter(Mandatory)][string] $Name)

    if ($Object -is [System.Collections.IDictionary]) {
        $value = $Object[$Name]
    }
    else {
        $property = $Object.PSObject.Properties[$Name]
        if ($null -eq $property) { $value = $null } else { $value = $property.Value }
    }
    Write-Output -InputObject $value -NoEnumerate
}

function Get-SkillTreeMigrationSha256 {
    param([Parameter(Mandatory)][string] $Text)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return ($algorithm.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '' }
    finally { $algorithm.Dispose() }
}

function ConvertTo-SkillTreeMigrationJson {
    param([Parameter(Mandatory)] $Value)
    return $Value | ConvertTo-Json -Depth 40 -Compress
}

function Save-SkillTreeMigrationState {
    param([Parameter(Mandatory)] $State, [Parameter(Mandatory)][string] $Path)

    $temporaryPath = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [System.IO.File]::WriteAllText($temporaryPath, (ConvertTo-SkillTreeMigrationJson $State), [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::Move($temporaryPath, $Path, $true)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
    }
}

function Get-SkillTreeMigrationRequiredString {
    param([Parameter(Mandatory)] $Value, [Parameter(Mandatory)][string] $Name)
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) { throw "$Name must be a non-empty string." }
    return $Value.Trim()
}

function ConvertTo-SkillTreeMigrationConfig {
    param([Parameter(Mandatory)] $Config)

    $enabled = Get-SkillTreeMigrationValue $Config 'enabled'
    if ($enabled -isnot [bool]) { throw 'enabled must be boolean.' }

    $baseUrl = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $Config 'baseUrl') 'baseUrl'
    $uri = $null
    if (-not [Uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref] $uri)) { throw 'baseUrl must be an absolute URI.' }
    $isLoopbackHttp = $uri.Scheme -eq 'http' -and ($uri.IsLoopback -or $uri.Host -in @('localhost', '127.0.0.1', '::1'))
    if ($uri.Scheme -ne 'https' -and -not $isLoopbackHttp) { throw 'baseUrl must use HTTPS or loopback HTTP.' }
    if ($uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'baseUrl must not contain user info, query, or fragment.' }

    $apiEnvironment = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $Config 'apiKeyEnvironmentVariable') 'apiKeyEnvironmentVariable'
    $migrationEnvironment = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $Config 'migrationKeyEnvironmentVariable') 'migrationKeyEnvironmentVariable'
    if ($apiEnvironment -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or $migrationEnvironment -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
        throw 'Key environment variable names are invalid.'
    }
    $apiSettingsPath = Get-SkillTreeMigrationValue $Config 'apiSettingsPath'
    if ($null -ne $apiSettingsPath -and $apiSettingsPath -isnot [string]) { throw 'apiSettingsPath must be a string.' }
    if (![string]::IsNullOrWhiteSpace($apiSettingsPath) -and ![IO.Path]::IsPathFullyQualified($apiSettingsPath)) { throw 'apiSettingsPath must be absolute.' }

    $serverIdsValue = Get-SkillTreeMigrationValue $Config 'serverIds'
    if ($serverIdsValue -is [string] -or $null -eq $serverIdsValue -or $serverIdsValue -isnot [System.Collections.IEnumerable]) { throw 'serverIds must be an array.' }
    $serverIds = @($serverIdsValue | ForEach-Object { Get-SkillTreeMigrationRequiredString $_ 'serverIds[]' })
    if ($serverIds.Count -eq 0 -or $serverIds.Count -ne @($serverIds | Select-Object -Unique).Count) { throw 'serverIds must be a non-empty array without duplicates.' }

    $scope = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $Config 'scope') 'scope'
    if ($scope -notin @('ExplicitAccounts', 'AllCandidates')) { throw "scope must be ExplicitAccounts or AllCandidates." }
    $accountIdsValue = Get-SkillTreeMigrationValue $Config 'accountIds'
    if ($accountIdsValue -is [string] -or $null -eq $accountIdsValue -or $accountIdsValue -isnot [System.Collections.IEnumerable]) { throw 'accountIds must be an array.' }
    $accountIds = @($accountIdsValue | ForEach-Object {
        try { ([Guid] (Get-SkillTreeMigrationRequiredString $_ 'accountIds[]')).ToString('D') }
        catch { throw 'accountIds must contain UUIDs.' }
    })
    if ($accountIds.Count -ne @($accountIds | Select-Object -Unique).Count) { throw 'accountIds must not contain duplicates.' }
    $userIdsValue=Get-SkillTreeMigrationValue $Config 'accountUserIds'
    $userIds=@()
    if ($null -ne $userIdsValue) {
        if ($userIdsValue -is [string] -or $userIdsValue -isnot [Collections.IEnumerable]) { throw 'accountUserIds must be an array.' }
        $userIds=@($userIdsValue | ForEach-Object {
            try { $id=[guid](Get-SkillTreeMigrationRequiredString $_ 'accountUserIds[]'); if ($id -eq [guid]::Empty) { throw 'Empty UUID' }; $id.ToString('D') }
            catch { throw 'accountUserIds must contain non-empty UUIDs.' }
        })
        if ($userIds.Count -ne @($userIds | Select-Object -Unique).Count) { throw 'accountUserIds must not contain duplicates.' }
    }
    if ($scope -eq 'AllCandidates' -and $userIds.Count -gt 0) { throw 'accountUserIds requires ExplicitAccounts scope.' }
    if ($scope -eq 'ExplicitAccounts' -and $accountIds.Count -eq 0 -and $userIds.Count -eq 0) { throw 'ExplicitAccounts requires accountIds or accountUserIds.' }

    return [pscustomobject][ordered]@{
        Enabled = $enabled; BaseUrl = $uri.AbsoluteUri.TrimEnd('/'); ApiKeyEnvironmentVariable = $apiEnvironment
        MigrationKeyEnvironmentVariable = $migrationEnvironment; ServerIds = $serverIds; Scope = $scope; AccountIds = $accountIds
        ApiSettingsPath=$apiSettingsPath; AccountUserIds=$userIds
    }
}

function Get-SkillTreeMigrationCredentials {
    param([Parameter(Mandatory)] $NormalizedConfig)
    if (![string]::IsNullOrWhiteSpace($NormalizedConfig.ApiSettingsPath)) {
        try {
            $settings=Get-Content -Raw -Encoding UTF8 -LiteralPath $NormalizedConfig.ApiSettingsPath | ConvertFrom-Json
            $apiKey=[string]$settings.ApiKey.Key
            $migrationKey=[string]$settings.SkillTreeRuntime.MigrationKey
            $runtimeKey=[string]$settings.SkillTreeRuntime.Key
        } catch { throw 'Unable to load API authentication settings from apiSettingsPath.' }
        if ([string]::IsNullOrWhiteSpace($runtimeKey)) { throw 'The API settings runtime key must be non-empty.' }
        if ($migrationKey -ceq $runtimeKey) { throw 'The migration key must differ from the runtime key.' }
    } else {
        $apiKey=[Environment]::GetEnvironmentVariable($NormalizedConfig.ApiKeyEnvironmentVariable)
        $migrationKey=[Environment]::GetEnvironmentVariable($NormalizedConfig.MigrationKeyEnvironmentVariable)
    }
    if ([string]::IsNullOrWhiteSpace($apiKey) -or [string]::IsNullOrWhiteSpace($migrationKey)) { throw 'API and migration credentials must be non-empty.' }
    if ($apiKey -ceq $migrationKey) { throw 'The migration key must differ from the common API key.' }
    return [pscustomobject]@{ApiKey=$apiKey;MigrationKey=$migrationKey}
}

function Invoke-SkillTreeMigrationHttp {
    param([Parameter(Mandatory)][string] $Method, [Parameter(Mandatory)][string] $Uri, [Parameter(Mandatory)][hashtable] $Headers, $Body)

    $parameters = @{ Method = $Method; Uri = $Uri; Headers = $Headers; SkipHttpErrorCheck = $true; MaximumRedirection = 0; TimeoutSec = 120; ErrorAction = 'Stop' }
    if ($null -ne $Body) { $parameters.ContentType = 'application/json'; $parameters.Body = ConvertTo-SkillTreeMigrationJson $Body }
    $response = Invoke-WebRequest @parameters
    if ($response.StatusCode -ne 200) { throw "HTTP request failed ($Method status $($response.StatusCode))." }
    if ([string]::IsNullOrWhiteSpace($response.Content)) { throw "HTTP request returned an empty body ($Method)." }
    try { return $response.Content | ConvertFrom-Json -Depth 40 }
    catch { throw "HTTP request returned invalid JSON ($Method)." }
}

function Invoke-SkillTreeMigrationRequest {
    param([Parameter(Mandatory)][scriptblock] $Invoker, [Parameter(Mandatory)][string] $Method, [Parameter(Mandatory)][string] $Uri, [Parameter(Mandatory)][hashtable] $Headers, $Body)
    try { return & $Invoker -Method $Method -Uri $Uri -Headers $Headers -Body $Body }
    catch { throw "Migration API request result is unknown or failed for $Method. The persisted operation IDs will be reused." }
}

function Assert-SkillTreeMigrationRuntime {
    param([Parameter(Mandatory)] $Runtime, [Parameter(Mandatory)][string] $ServerId)
    if ((Get-SkillTreeMigrationValue $Runtime 'serverId') -ne $ServerId) { throw "Runtime response serverId did not match '$ServerId'." }
    if ((Get-SkillTreeMigrationValue $Runtime 'ready') -ne $true) { throw "Server '$ServerId' is not ready." }
    try { $session = [Guid] (Get-SkillTreeMigrationValue $Runtime 'serverSessionId') } catch { throw "Server '$ServerId' returned an invalid session ID." }
    $generation = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $Runtime 'definitionGenerationId') 'runtime.definitionGenerationId'
    if ($generation -notmatch '^[0-9a-f]{64}$') { throw "Server '$ServerId' returned an invalid generation ID." }
    return [pscustomobject][ordered]@{ ServerId = $ServerId; ServerSessionId = $session.ToString('D'); DefinitionGenerationId = $generation }
}

function Assert-SkillTreeMigrationSnapshot {
    param([Parameter(Mandatory)] $Snapshot, [Parameter(Mandatory)][string] $GenerationId, [string] $ExpectedHash)
    $json = ConvertTo-SkillTreeMigrationJson $Snapshot
    $hash = Get-SkillTreeMigrationSha256 $json
    if ($ExpectedHash -and $ExpectedHash -ne $hash) { throw "Definition snapshot changed for generation '$GenerationId'." }
    return $hash
}

function Get-SkillTreeMigrationNodeIds {
    param([Parameter(Mandatory)] $State)
    $unlocked = Get-SkillTreeMigrationValue $State 'unlockedNodes'
    if ($null -eq $unlocked) { throw 'migration-state did not include unlockedNodes.' }
    return @($unlocked | ForEach-Object {
        $nodeId = Get-SkillTreeMigrationRequiredString (Get-SkillTreeMigrationValue $_ 'nodeId') 'migration-state.unlockedNodes[].nodeId'
        $nodeId
    } | Sort-Object)
}

function Test-SkillTreeMigrationStringSet {
    param([AllowNull()][AllowEmptyCollection()][string[]] $Left, [AllowNull()][AllowEmptyCollection()][string[]] $Right)
    return (@($Left | Sort-Object) -join "`n") -ceq (@($Right | Sort-Object) -join "`n")
}

function New-SkillTreeMigrationRequestItem {
    param([Parameter(Mandatory)] $Record, [Parameter(Mandatory)][string] $TargetGeneration)
    if ($Record.FromGenerationId -eq $null) { throw "Legacy account '$($Record.AccountId)' cannot be automatically migrated." }
    return [ordered]@{
        accountId = $Record.AccountId
        migration = [ordered]@{
            operationId = $Record.OperationId; expectedStateVersion = [int] $Record.ExpectedStateVersion
            fromGenerationId = $Record.FromGenerationId; toGenerationId = $TargetGeneration
            legacyBaselineNodeIds = @($Record.LegacyBaselineNodeIds); removeNodeIds = @()
            consumedClassAssignments = @(); confirmLegacyBaseline = $false; previewOnly = $false
        }
    }
}

function New-SkillTreeMigrationReport($State, [string]$Status, [string]$RunDirectory) {
    return [pscustomobject][ordered]@{
        Status=$Status; RunDirectory=$RunDirectory; SelectedCount=@($State.Records).Count
        PreviewCount=@($State.Records | Where-Object PreviewStatus -eq 'PREVIEW').Count
        AppliedCount=@($State.Records | Where-Object CommitStatus -eq 'APPLIED').Count
        Accounts=@($State.Records | ForEach-Object {
            [ordered]@{ accountId=$_.AccountId; operationId=$_.OperationId; preflight=$_.PreflightStatus; preview=$_.PreviewStatus; commit=$_.CommitStatus }
        })
    }
}

function Set-SkillTreeMigrationBatchResults($Response, $Batch, [string]$Phase) {
    $resultsValue = Get-SkillTreeMigrationValue $Response 'items'
    $results = @($resultsValue | ForEach-Object { $_ })
    if ($results.Count -ne $Batch.Count) { throw "$Phase response item count did not match the request." }
    $expected = if ($Phase -eq 'Commit') { 'APPLIED' } else { 'PREVIEW' }
    # Validate the full response before changing any record. Invalid responses remain REQUESTED.
    for ($index=0; $index -lt $Batch.Count; $index++) {
        $item=$results[$index]; $record=$Batch[$index]
        if ((Get-SkillTreeMigrationValue $item 'accountId') -ne $record.AccountId -or
            (Get-SkillTreeMigrationValue $item 'operationId') -ne $record.OperationId -or
            (Get-SkillTreeMigrationValue $item 'status') -notin @($expected,'REJECTED')) { throw "$Phase response identity/status did not match the request." }
    }
    $rejected=$false
    for ($index=0; $index -lt $Batch.Count; $index++) {
        $item=$results[$index]; $record=$Batch[$index]
        $status=Get-SkillTreeMigrationValue $item 'status'
        $record."${Phase}Status"=$status
        $record."${Phase}Result"=$item
        if ($status -eq 'REJECTED') { $rejected=$true }
    }
    return $rejected
}

function Invoke-SkillTreeMigration {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] $Config,
        [Parameter(Mandatory)][string] $RunDirectory,
        [switch] $Commit,
        [object[]] $ExpectedRuntimes,
        [scriptblock] $HttpInvoker
    )

    $normalized = ConvertTo-SkillTreeMigrationConfig $Config
    if (-not [System.IO.Path]::IsPathFullyQualified($RunDirectory) -or -not (Test-Path -LiteralPath $RunDirectory -PathType Container)) {
        throw 'RunDirectory must be an existing absolute directory.'
    }
    $resolvedRunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
    if (-not $normalized.Enabled) { return [pscustomobject]@{ Status = 'DISABLED'; RunDirectory = $resolvedRunDirectory } }

    $credentials=Get-SkillTreeMigrationCredentials $normalized
    $headers = @{ 'X-Api-Key' = $credentials.ApiKey; 'X-SkillTree-Migration-Key' = $credentials.MigrationKey }
    if (-not $HttpInvoker) { $HttpInvoker = ${function:Invoke-SkillTreeMigrationHttp} }

    $fingerprintInput = [ordered]@{ baseUrl = $normalized.BaseUrl; apiKeyEnvironmentVariable = $normalized.ApiKeyEnvironmentVariable; migrationKeyEnvironmentVariable = $normalized.MigrationKeyEnvironmentVariable; serverIds = @($normalized.ServerIds); scope = $normalized.Scope; accountIds = @($normalized.AccountIds) }
    if (![string]::IsNullOrWhiteSpace($normalized.ApiSettingsPath)) { $fingerprintInput.apiSettingsPath=$normalized.ApiSettingsPath }
    if ($normalized.AccountUserIds.Count) { $fingerprintInput.accountUserIds=@($normalized.AccountUserIds) }
    $configurationFingerprint = Get-SkillTreeMigrationSha256 (ConvertTo-SkillTreeMigrationJson $fingerprintInput)
    $statePath = Join-Path $resolvedRunDirectory 'skilltree-migration-state.json'
    $resultPath = Join-Path $resolvedRunDirectory 'skilltree-migration-result.json'
    $state = if (Test-Path -LiteralPath $statePath) { Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json -Depth 40 } else { $null }
    try {
    if ($state -and $state.ConfigurationFingerprint -ne $configurationFingerprint) { throw 'Configuration or scope changed for this run. Start a new RunDirectory.' }

    $runtimes = @()
    foreach ($serverId in $normalized.ServerIds) {
        $runtime = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($serverId))" $headers $null
        $runtimes += Assert-SkillTreeMigrationRuntime $runtime $serverId
    }
    if (@($runtimes.DefinitionGenerationId | Select-Object -Unique).Count -ne 1) { throw 'All configured servers must be ready on the same generation.' }
    if ($PSBoundParameters.ContainsKey('ExpectedRuntimes')) {
        if (@($ExpectedRuntimes).Count -ne $runtimes.Count) { throw 'Startup runtime set changed before migration.' }
        foreach ($runtime in $runtimes) {
            $expected=@($ExpectedRuntimes | Where-Object { (Get-SkillTreeMigrationValue $_ 'serverId') -ceq $runtime.ServerId })
            if ($expected.Count -ne 1 -or (Get-SkillTreeMigrationValue $expected[0] 'serverSessionId') -ne $runtime.ServerSessionId -or
                (Get-SkillTreeMigrationValue $expected[0] 'definitionGenerationId') -ne $runtime.DefinitionGenerationId) {
                throw 'Startup runtime session or generation changed before migration.'
            }
        }
    }
    $targetRuntime = $runtimes[0]
    if ($state) {
        foreach ($savedRuntime in @($state.Runtimes)) {
            $current = @($runtimes | Where-Object ServerId -ceq $savedRuntime.ServerId)
            if ($current.Count -ne 1 -or $current[0].ServerSessionId -ne $savedRuntime.ServerSessionId -or $current[0].DefinitionGenerationId -ne $savedRuntime.DefinitionGenerationId) {
                throw 'Runtime session or generation changed for this run. Resume is unsafe.'
            }
        }
        if ($state.TargetServerId -ne $targetRuntime.ServerId -or $state.TargetGenerationId -ne $targetRuntime.DefinitionGenerationId) { throw 'Target runtime changed for this run.' }
    }

    $targetSnapshot = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/definitions/$($targetRuntime.DefinitionGenerationId)" $headers $null
    $targetSnapshotHash = Assert-SkillTreeMigrationSnapshot $targetSnapshot $targetRuntime.DefinitionGenerationId $(if ($state) { $state.TargetDefinitionSnapshotHash } else { $null })

    if (-not $state) {
        # Freeze the resolved account set in this run. New characters are picked up next run,
        # not during a retry of a partially committed migration.
        $targetAccountIds=@($normalized.AccountIds)
        if ($normalized.AccountUserIds.Count) {
            $targetsPath=Join-Path $resolvedRunDirectory 'skilltree-migration-targets.json'
            $runtimeFingerprint=Get-SkillTreeMigrationSha256 (ConvertTo-SkillTreeMigrationJson @($runtimes))
            if (Test-Path -LiteralPath $targetsPath) {
                $targets=Get-Content -Raw -Encoding UTF8 -LiteralPath $targetsPath | ConvertFrom-Json -AsHashtable
                if ($targets.configurationFingerprint -ne $configurationFingerprint -or $targets.runtimeFingerprint -ne $runtimeFingerprint) { throw 'Resolved migration targets belong to another configuration or runtime.' }
            } else {
                $targets=@{configurationFingerprint=$configurationFingerprint;runtimeFingerprint=$runtimeFingerprint;accountsByUser=@{}}
                Save-SkillTreeMigrationState $targets $targetsPath
            }
        }
        foreach ($userId in $normalized.AccountUserIds) {
            if (!$targets.accountsByUser.ContainsKey($userId)) {
                $accounts=Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/account?user_id=$userId" $headers $null
                $resolvedAccounts=@()
                foreach ($account in @($accounts)) {
                    if ($null -eq $account) { continue }
                    $owner=Get-SkillTreeMigrationValue $account 'userId'
                    if ($owner -ne $userId) { throw 'Account resolution returned an unexpected owner.' }
                    try { $id=[guid](Get-SkillTreeMigrationValue $account 'uuid'); if ($id -eq [guid]::Empty) { throw 'Empty UUID' } }
                    catch { throw 'Account resolution returned an invalid account UUID.' }
                    $resolvedAccounts+=$id.ToString('D')
                }
                $targets.accountsByUser[$userId]=@($resolvedAccounts)
                Save-SkillTreeMigrationState $targets $targetsPath
            }
            $targetAccountIds+=@($targets.accountsByUser[$userId])
        }
        $targetAccountIds=@($targetAccountIds | Select-Object -Unique)
        $allCandidates = @(); $pageSize = 100; $page = 1; $total = $null
        do {
            $candidateUri = "$($normalized.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($targetRuntime.ServerId))/migration-candidates?server_session_id=$($targetRuntime.ServerSessionId)&to_generation_id=$($targetRuntime.DefinitionGenerationId)&page=$page&page_size=$pageSize"
            $candidatePage = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' $candidateUri $headers $null
            $candidateRuntime = Assert-SkillTreeMigrationRuntime (Get-SkillTreeMigrationValue $candidatePage 'runtime') $targetRuntime.ServerId
            if ($candidateRuntime.ServerSessionId -ne $targetRuntime.ServerSessionId -or $candidateRuntime.DefinitionGenerationId -ne $targetRuntime.DefinitionGenerationId) { throw 'Runtime changed while enumerating migration candidates.' }
            if ([int](Get-SkillTreeMigrationValue $candidatePage 'page') -ne $page -or [int](Get-SkillTreeMigrationValue $candidatePage 'pageSize') -ne $pageSize) { throw 'Candidate page response did not match the requested page.' }
            $receivedTotal = [int](Get-SkillTreeMigrationValue $candidatePage 'totalCount')
            if ($receivedTotal -lt 0 -or ($null -ne $total -and $total -ne $receivedTotal)) { throw 'Candidate totalCount was invalid or changed during enumeration.' }
            $total = $receivedTotal; $itemsValue = Get-SkillTreeMigrationValue $candidatePage 'items'; $items = @($itemsValue | ForEach-Object { $_ })
            if ($items.Count -gt $pageSize) { throw 'Candidate page exceeded the requested page size.' }
            if ($items.Count -eq 0 -and $allCandidates.Count -lt $total) { throw 'Candidate pagination did not make progress.' }
            $allCandidates += $items; $page++
        } while ($allCandidates.Count -lt $total)
        if ($allCandidates.Count -ne $total) { throw 'Candidate page enumeration was incomplete.' }
        $candidateIds = @($allCandidates | ForEach-Object { ([Guid](Get-SkillTreeMigrationValue $_ 'accountId')).ToString('D') })
        if ($candidateIds.Count -ne @($candidateIds | Select-Object -Unique).Count) { throw 'Candidate enumeration contained duplicate account IDs.' }
        $selected = @(if ($normalized.Scope -eq 'AllCandidates') { $allCandidates } else {
            $missing = @($targetAccountIds | Where-Object { $_ -notin $candidateIds })
            foreach ($accountId in $missing) {
                $current = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/accounts/$accountId/migration-state" $headers $null
                $currentGeneration = Get-SkillTreeMigrationValue $current 'definitionGenerationId'
                if ($currentGeneration -eq $targetRuntime.DefinitionGenerationId) { Write-Host "SKIP already current account: $accountId"; continue }
                if ($null -eq $currentGeneration -and @(Get-SkillTreeMigrationNodeIds $current).Count -eq 0) { Write-Host "SKIP empty unbound account: $accountId"; continue }
                throw "Explicit account '$accountId' was not a candidate and is not already compatible."
            }
            @($allCandidates | Where-Object { ([Guid](Get-SkillTreeMigrationValue $_ 'accountId')).ToString('D') -in $targetAccountIds })
        })
        $records = @($selected | ForEach-Object {
            $accountId = ([Guid](Get-SkillTreeMigrationValue $_ 'accountId')).ToString('D')
            [pscustomobject][ordered]@{
                AccountId = $accountId; OperationId = [Guid]::NewGuid().ToString('D'); FromGenerationId = Get-SkillTreeMigrationValue $_ 'fromGenerationId'
                ExpectedStateVersion = [int](Get-SkillTreeMigrationValue $_ 'expectedStateVersion'); LegacyBaselineNodeIds = @((Get-SkillTreeMigrationValue $_ 'legacyBaselineNodeIds') | Sort-Object)
                PreflightStatus = 'PENDING'; PreviewStatus = 'PENDING'; CommitStatus = 'PENDING'; PreviewResult = $null; CommitResult = $null
            }
        })
        $state = [pscustomobject][ordered]@{
            SchemaVersion = 1; ConfigurationFingerprint = $configurationFingerprint; TargetServerId = $targetRuntime.ServerId; TargetGenerationId = $targetRuntime.DefinitionGenerationId
            TargetDefinitionSnapshotHash = $targetSnapshotHash; Runtimes = $runtimes; Records = $records; SourceDefinitionSnapshotHashes = [ordered]@{}
        }
        Save-SkillTreeMigrationState $state $statePath
    }

    $checkedSourceGenerations = @{}
    foreach ($record in @($state.Records)) {
        if ($record.CommitStatus -eq 'APPLIED') { continue }
        if ($record.CommitStatus -eq 'REQUESTED') {
            if (!$Commit) { throw 'An unknown COMMIT result remains. Use MigrateCommit with the same RunDirectory to resolve it.' }
            # The API may already have changed state. Its persisted operation receipt is authoritative.
            continue
        }
        $stateUri = "$($normalized.BaseUrl)/api/skilltree/runtime/accounts/$($record.AccountId)/migration-state"
        $currentState = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' $stateUri $headers $null
        $currentGeneration = Get-SkillTreeMigrationValue $currentState 'definitionGenerationId'
        if ($currentGeneration -ne $record.FromGenerationId -or [int](Get-SkillTreeMigrationValue $currentState 'version') -ne [int]$record.ExpectedStateVersion -or -not (Test-SkillTreeMigrationStringSet (Get-SkillTreeMigrationNodeIds $currentState) @($record.LegacyBaselineNodeIds))) {
            $record.PreflightStatus = 'REJECTED_STATE_CHANGED'; Save-SkillTreeMigrationState $state $statePath; throw "Account '$($record.AccountId)' changed after candidate enumeration."
        }
        if ($null -eq $record.FromGenerationId) {
            $record.PreflightStatus = 'REJECTED_LEGACY_REQUIRES_MANUAL_APPROVAL'; Save-SkillTreeMigrationState $state $statePath
            throw "Legacy account '$($record.AccountId)' requires manual approval and was not submitted."
        }
        if (!$checkedSourceGenerations.ContainsKey($record.FromGenerationId)) {
        $sourceSnapshot = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/definitions/$($record.FromGenerationId)" $headers $null
        $savedHash = Get-SkillTreeMigrationValue $state.SourceDefinitionSnapshotHashes $record.FromGenerationId
        $hash = Assert-SkillTreeMigrationSnapshot $sourceSnapshot $record.FromGenerationId $savedHash
        if (-not $savedHash) {
            if ($state.SourceDefinitionSnapshotHashes -is [System.Collections.IDictionary]) { $state.SourceDefinitionSnapshotHashes[$record.FromGenerationId] = $hash }
            else { $state.SourceDefinitionSnapshotHashes | Add-Member -NotePropertyName $record.FromGenerationId -NotePropertyValue $hash }
            Save-SkillTreeMigrationState $state $statePath
        }
        $checkedSourceGenerations[$record.FromGenerationId] = $true
        }
        $record.PreflightStatus = 'VERIFIED'; Save-SkillTreeMigrationState $state $statePath
    }

    $pendingPreview = @($state.Records | Where-Object { $_.CommitStatus -ne 'APPLIED' -and $_.PreviewStatus -ne 'PREVIEW' })
    foreach ($batch in @($pendingPreview | ForEach-Object -Begin { $buffer = @() } -Process { $buffer += $_; if ($buffer.Count -eq 100) { ,$buffer; $buffer = @() } } -End { if ($buffer.Count) { ,$buffer } })) {
        $items = @($batch | ForEach-Object { New-SkillTreeMigrationRequestItem $_ $state.TargetGenerationId })
        foreach ($record in $batch) { $record.PreviewStatus = 'REQUESTED' }; Save-SkillTreeMigrationState $state $statePath
        $body = [ordered]@{ mode = 'PREVIEW'; items = $items }
        $uri = "$($normalized.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($state.TargetServerId))/migrations/batch?server_session_id=$($targetRuntime.ServerSessionId)"
        $response = Invoke-SkillTreeMigrationRequest $HttpInvoker 'POST' $uri $headers $body
        $rejected = Set-SkillTreeMigrationBatchResults $response $batch 'Preview'
        Save-SkillTreeMigrationState $state $statePath
        if ($rejected) { throw 'PREVIEW rejected one or more accounts. All batch results were saved.' }
    }
    if (@($state.Records | Where-Object { $_.CommitStatus -ne 'APPLIED' -and $_.PreviewStatus -ne 'PREVIEW' }).Count -gt 0) { throw 'All selected accounts must have a successful PREVIEW before COMMIT.' }
    if (-not $Commit) {
        $result = New-SkillTreeMigrationReport $state 'PREVIEW_COMPLETE' $resolvedRunDirectory
        Save-SkillTreeMigrationState $result $resultPath; return $result
    }

    $pendingCommit = @($state.Records | Where-Object { $_.CommitStatus -ne 'APPLIED' })
    $commitMarkerPath = Join-Path $resolvedRunDirectory 'migration-commit-started.json'
    if (-not (Test-Path -LiteralPath $commitMarkerPath -PathType Leaf)) { throw 'Commit requires the entrypoint migration-commit-started.json marker.' }
    foreach ($savedRuntime in @($state.Runtimes)) {
        $runtime = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($savedRuntime.ServerId))" $headers $null
        $current = Assert-SkillTreeMigrationRuntime $runtime $savedRuntime.ServerId
        if ($current.ServerSessionId -ne $savedRuntime.ServerSessionId -or $current.DefinitionGenerationId -ne $savedRuntime.DefinitionGenerationId) { throw 'Runtime session or generation changed before COMMIT.' }
    }
    $commitSnapshot = Invoke-SkillTreeMigrationRequest $HttpInvoker 'GET' "$($normalized.BaseUrl)/api/skilltree/runtime/definitions/$($state.TargetGenerationId)" $headers $null
    $null = Assert-SkillTreeMigrationSnapshot $commitSnapshot $state.TargetGenerationId $state.TargetDefinitionSnapshotHash
    foreach ($batch in @($pendingCommit | ForEach-Object -Begin { $buffer = @() } -Process { $buffer += $_; if ($buffer.Count -eq 100) { ,$buffer; $buffer = @() } } -End { if ($buffer.Count) { ,$buffer } })) {
        $items = @($batch | ForEach-Object { New-SkillTreeMigrationRequestItem $_ $state.TargetGenerationId })
        foreach ($record in $batch) { $record.CommitStatus = 'REQUESTED' }; Save-SkillTreeMigrationState $state $statePath
        $body = [ordered]@{ mode = 'COMMIT'; items = $items }
        $uri = "$($normalized.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($state.TargetServerId))/migrations/batch?server_session_id=$($targetRuntime.ServerSessionId)"
        $response = Invoke-SkillTreeMigrationRequest $HttpInvoker 'POST' $uri $headers $body
        $rejected = Set-SkillTreeMigrationBatchResults $response $batch 'Commit'
        Save-SkillTreeMigrationState $state $statePath
        if ($rejected) { throw 'COMMIT rejected one or more accounts. All batch results were saved.' }
    }
    $result = New-SkillTreeMigrationReport $state 'APPLIED' $resolvedRunDirectory
    Save-SkillTreeMigrationState $result $resultPath
    return $result
    } catch {
        if ($state) {
            Save-SkillTreeMigrationState $state $statePath
            Save-SkillTreeMigrationState (New-SkillTreeMigrationReport $state 'FAILED' $resolvedRunDirectory) $resultPath
        }
        throw
    }
}
