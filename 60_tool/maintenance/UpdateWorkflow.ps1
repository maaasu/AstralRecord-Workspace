#requires -Version 7.0
# Shared deployment workflow. This module never stops or starts a Minecraft server.
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'Distribution.ps1')
. (Join-Path $PSScriptRoot 'SkillTreeMigration.ps1')

function Get-UpdateWorkflowValue {
    param([Parameter(Mandatory)] $Object, [Parameter(Mandatory)][string] $Name)

    if ($Object -is [System.Collections.IDictionary]) { return $Object[$Name] }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Save-UpdateWorkflowJson {
    param([Parameter(Mandatory)] $Value, [Parameter(Mandatory)][string] $Path)
    Write-MaintenanceJson $Value $Path
    Write-MaintenanceDiagnostic 'state.saved' @{file=[IO.Path]::GetFileName($Path); status=(Get-UpdateWorkflowValue $Value 'status')}
}

function Get-UpdateWorkflowRunDirectory {
    param([Parameter(Mandatory)][string] $RunRoot, [Parameter(Mandatory)][string] $RelativeRunDirectory)

    # Only a generated leaf name is persisted. Do not permit a state file to select another path.
    if ($RelativeRunDirectory -notmatch '^run-\d{8}T\d{6}Z-[a-f0-9]{32}$') {
        throw 'The active workflow run name is invalid.'
    }
    $path = Get-MaintenanceAbsolutePath (Join-Path $RunRoot $RelativeRunDirectory)
    if (!$path.StartsWith($RunRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The active workflow run escapes runRoot.'
    }
    return $path
}

function New-UpdateWorkflowRunDirectory {
    param([Parameter(Mandatory)][string] $RunRoot)
    $relative = 'run-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), [Guid]::NewGuid().ToString('N')
    $path = Get-UpdateWorkflowRunDirectory $RunRoot $relative
    New-Item -ItemType Directory -Path $path -ErrorAction Stop | Out-Null
    return [pscustomobject]@{ Relative = $relative; Path = $path }
}

function Assert-UpdateWorkflowInputs {
    param(
        [Parameter(Mandatory)][hashtable] $WorkflowConfig,
        [Parameter(Mandatory)] $MigrationConfig,
        [Parameter(Mandatory)][string] $ConfigurationFingerprint,
        [Parameter(Mandatory)][string[]] $ServerRoots
    )

    if ($ConfigurationFingerprint -notmatch '^[0-9a-f]{64}$') { throw 'ConfigurationFingerprint must be a lowercase SHA-256 hash.' }
    foreach ($name in @('runRoot','startupTimeoutSeconds','pollIntervalSeconds','seedMasterData')) {
        if (!$WorkflowConfig.ContainsKey($name)) { throw "WorkflowConfig.$name is required." }
    }
    if ($WorkflowConfig.runRoot -isnot [string]) { throw 'WorkflowConfig.runRoot must be a string.' }
    $runRoot = Get-MaintenanceAbsolutePath $WorkflowConfig.runRoot
    if ((Test-Path -LiteralPath $runRoot) -and !(Test-Path -LiteralPath $runRoot -PathType Container)) { throw 'WorkflowConfig.runRoot must be a directory.' }
    if (($WorkflowConfig.startupTimeoutSeconds -isnot [int] -and $WorkflowConfig.startupTimeoutSeconds -isnot [long]) -or $WorkflowConfig.startupTimeoutSeconds -lt 1 -or $WorkflowConfig.startupTimeoutSeconds -gt 86400) {
        throw 'WorkflowConfig.startupTimeoutSeconds must be an integer from 1 through 86400.'
    }
    if (($WorkflowConfig.pollIntervalSeconds -isnot [int] -and $WorkflowConfig.pollIntervalSeconds -isnot [long]) -or $WorkflowConfig.pollIntervalSeconds -lt 1 -or $WorkflowConfig.pollIntervalSeconds -gt 60) {
        throw 'WorkflowConfig.pollIntervalSeconds must be an integer from 1 through 60.'
    }
    if ($WorkflowConfig.seedMasterData -isnot [bool]) { throw 'WorkflowConfig.seedMasterData must be boolean.' }
    if (!$ServerRoots -or $ServerRoots.Count -eq 0) { throw 'ServerRoots must include every server root and important source directory.' }
    foreach ($serverRootValue in $ServerRoots) {
        $serverRoot = Get-MaintenanceAbsolutePath $serverRootValue
        if (!(Test-Path -LiteralPath $serverRoot -PathType Container)) { throw "Missing server/source root: $serverRoot" }
        if (Test-MaintenanceOverlap $runRoot $serverRoot) { throw 'Workflow runRoot overlaps a server root or source directory.' }
    }

    $normalizedMigration = ConvertTo-SkillTreeMigrationConfig $MigrationConfig
    if (!$normalizedMigration.Enabled) { throw 'migration.enabled must be true for the update workflow.' }
    $credentials=Get-SkillTreeMigrationCredentials $normalizedMigration
    return [pscustomobject]@{ RunRoot=$runRoot; Migration=$normalizedMigration; ApiKey=$credentials.ApiKey; MigrationKey=$credentials.MigrationKey }
}

function Invoke-UpdateWorkflowHttp {
    param([Parameter(Mandatory)][string] $Method, [Parameter(Mandatory)][string] $Uri, [Parameter(Mandatory)][hashtable] $Headers, $Body,
        [string] $ApiBaseUrl, [switch] $AllowPrivateApiInsecureTls)

    try {
        $parameters = @{ Method=$Method; Uri=$Uri; Headers=$Headers; SkipHttpErrorCheck=$true; MaximumRedirection=0; TimeoutSec=120; ErrorAction='Stop' }
        $tlsOptions=Get-SkillTreeTlsRequestOptions -Uri $Uri -ApiBaseUrl $ApiBaseUrl -AllowPrivateApiInsecureTls:$AllowPrivateApiInsecureTls
        foreach ($key in $tlsOptions.Keys) { $parameters[$key]=$tlsOptions[$key] }
        if ($null -ne $Body) { $parameters.ContentType='application/json'; $parameters.Body=($Body | ConvertTo-Json -Depth 40 -Compress) }
        $response = Invoke-WebRequest @parameters
        $content = $response.Content
        $parsed = $null
        if ($response.StatusCode -eq 200 -and ![string]::IsNullOrWhiteSpace($content)) {
            try { $parsed = $content | ConvertFrom-Json -Depth 40 } catch { throw 'The API response JSON was invalid.' }
        }
        return [pscustomobject]@{ StatusCode=[int]$response.StatusCode; Body=$parsed }
    } catch {
        # Do not include HTTP bodies, request headers, URIs, or exception text: they can contain credentials.
        throw 'The update workflow API request failed.'
    }
}

function Invoke-UpdateWorkflowRequest {
    param([Parameter(Mandatory)][scriptblock] $Invoker, [Parameter(Mandatory)][string] $Method, [Parameter(Mandatory)][string] $Uri, [Parameter(Mandatory)][hashtable] $Headers, $Body)

    try { $result = & $Invoker -Method $Method -Uri $Uri -Headers $Headers -Body $Body }
    catch { throw 'The update workflow API request failed.' }
    if ($null -eq $result) { throw 'The update workflow API request returned no result.' }
    $status = Get-UpdateWorkflowValue $result 'StatusCode'
    if ($null -eq $status) { return [pscustomobject]@{ StatusCode=200; Body=$result } } # test/mock contract
    try { $numericStatus = [int]$status } catch { throw 'The update workflow API response status was invalid.' }
    return [pscustomobject]@{ StatusCode=$numericStatus; Body=(Get-UpdateWorkflowValue $result 'Body') }
}

function Get-UpdateWorkflowRuntime {
    param([Parameter(Mandatory)] $Migration, [Parameter(Mandatory)][string] $ServerId, [Parameter(Mandatory)][scriptblock] $Invoker, [Parameter(Mandatory)][hashtable] $Headers)

    $uri = "$($Migration.BaseUrl)/api/skilltree/runtime/servers/$([Uri]::EscapeDataString($ServerId))"
    $response = Invoke-UpdateWorkflowRequest $Invoker 'GET' $uri $Headers $null
    if ($response.StatusCode -eq 404) { return $null }
    if ($response.StatusCode -ne 200) { throw 'The runtime API did not accept the update workflow request.' }
    $runtime = $response.Body
    if ($null -eq $runtime -or (Get-UpdateWorkflowValue $runtime 'serverId') -ne $ServerId) { throw "Runtime response did not match server '$ServerId'." }
    try { $session = ([Guid](Get-UpdateWorkflowValue $runtime 'serverSessionId')).ToString('D') } catch { throw "Runtime response for '$ServerId' has an invalid session ID." }
    if ((Get-UpdateWorkflowValue $runtime 'ready') -ne $true) { return [pscustomobject]@{ ServerId=$ServerId; Ready=$false; ServerSessionId=$session } }
    $generation = Get-UpdateWorkflowValue $runtime 'definitionGenerationId'
    if ($generation -isnot [string] -or $generation -notmatch '^[0-9a-f]{64}$') { throw "Runtime response for '$ServerId' has an invalid generation." }
    $lastSeen = Get-UpdateWorkflowValue $runtime 'lastSeenUtc'
    if ($null -ne $lastSeen) {
        try { $seen = [DateTimeOffset]::Parse($lastSeen, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind) }
        catch { throw "Runtime response for '$ServerId' has an invalid lastSeenUtc." }
        if ($seen.UtcDateTime -gt [DateTime]::UtcNow.AddMinutes(5)) { throw "Runtime response for '$ServerId' has a future lastSeenUtc." }
    }
    return [pscustomobject]@{ ServerId=$ServerId; Ready=$true; ServerSessionId=$session; DefinitionGenerationId=$generation }
}

function Test-UpdateWorkflowDeploymentEvidence {
    param([Parameter(Mandatory)][string] $RunDirectory, [Parameter(Mandatory)][ValidateSet('Dev','Channels')][string] $Label)
    if ($Label -eq 'Dev') { return Test-Path -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -PathType Leaf }
    $maintenanceJournal = Join-Path $RunDirectory 'deployment.json'
    if (Test-Path -LiteralPath $maintenanceJournal -PathType Leaf) {
        try { $journal = Get-Content -Raw -Encoding UTF8 -LiteralPath $maintenanceJournal | ConvertFrom-Json -AsHashtable }
        catch { throw 'The maintenance deployment journal is invalid; recovery is required.' }
        if ($journal.status -eq 'Deployed') { return $true }
    }
    return $false
}

function Wait-UpdateWorkflowRuntimes {
    param(
        [Parameter(Mandatory)] $Migration,
        [Parameter(Mandatory)][hashtable] $BaselineSessions,
        [Parameter(Mandatory)][scriptblock] $Invoker,
        [Parameter(Mandatory)][hashtable] $Headers,
        [Parameter(Mandatory)][int] $TimeoutSeconds,
        [Parameter(Mandatory)][int] $PollIntervalSeconds,
        [scriptblock] $SleepAction
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $previousSignature = $null
    $consecutive = 0
    do {
        $runtimes = @(); $usable = $true
        foreach ($serverId in $Migration.ServerIds) {
            $runtime = Get-UpdateWorkflowRuntime $Migration $serverId $Invoker $Headers
            Write-MaintenanceDiagnostic 'startup.poll' @{server=$serverId; ready=($null -ne $runtime -and $runtime.Ready)}
            if ($null -eq $runtime -or !$runtime.Ready) { $usable=$false; break }
            if ($BaselineSessions[$serverId] -and $runtime.ServerSessionId -eq $BaselineSessions[$serverId]) { $usable=$false; break }
            $runtimes += $runtime
        }
        if ($usable -and @($runtimes.DefinitionGenerationId | Select-Object -Unique).Count -eq 1) {
            $signature = @($runtimes | Sort-Object ServerId | ForEach-Object { "$($_.ServerId)|$($_.ServerSessionId)|$($_.DefinitionGenerationId)" }) -join "`n"
            if ($signature -ceq $previousSignature) { $consecutive++ } else { $previousSignature=$signature; $consecutive=1 }
            if ($consecutive -ge 2) { return $runtimes }
        } else { $previousSignature=$null; $consecutive=0 }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        if ($SleepAction) { & $SleepAction -Seconds $PollIntervalSeconds } else { Start-Sleep -Seconds $PollIntervalSeconds }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timed out waiting for all servers to start with new, matching ready runtimes.'
}

function Assert-UpdateWorkflowExpectedRuntimes {
    param(
        [Parameter(Mandatory)] $Migration,
        [Parameter(Mandatory)] $ExpectedRuntimes,
        [Parameter(Mandatory)][scriptblock] $Invoker,
        [Parameter(Mandatory)][hashtable] $Headers
    )

    $expected = @($ExpectedRuntimes)
    if ($expected.Count -ne $Migration.ServerIds.Count) { throw 'Persisted ready runtime set is incomplete.' }
    $current = @()
    foreach ($serverId in $Migration.ServerIds) {
        $saved = @($expected | Where-Object { $_.ServerId -ceq $serverId })
        if ($saved.Count -ne 1) { throw 'Persisted ready runtime set is invalid.' }
        $runtime = Get-UpdateWorkflowRuntime $Migration $serverId $Invoker $Headers
        if ($null -eq $runtime -or !$runtime.Ready -or $runtime.ServerSessionId -ne $saved[0].ServerSessionId -or
            $runtime.DefinitionGenerationId -ne $saved[0].DefinitionGenerationId) {
            throw 'A server runtime changed after startup readiness was confirmed.'
        }
        $current += $runtime
    }
    if (@($current.DefinitionGenerationId | Select-Object -Unique).Count -ne 1) { throw 'Server runtime generations no longer match.' }
    return $current
}

function Invoke-UpdateWorkflow {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable] $WorkflowConfig,
        [Parameter(Mandatory)] $MigrationConfig,
        [Parameter(Mandatory)][string] $ConfigurationFingerprint,
        [Parameter(Mandatory)][string[]] $ServerRoots,
        [Parameter(Mandatory)][scriptblock] $DeployAction,
        [scriptblock] $PrepareRunAction,
        [switch] $ServersStopped,
        [switch] $AdmissionClosed,
        [ValidateSet('Dev','Channels')][string] $Label = 'Dev',
        [scriptblock] $HttpInvoker,
        [scriptblock] $SleepAction
    )

    $maintenanceDiagnosticFile = $null
    $validated = Assert-UpdateWorkflowInputs $WorkflowConfig $MigrationConfig $ConfigurationFingerprint $ServerRoots
    if (!$HttpInvoker) {
        $workflowTransportBaseUrl=$validated.Migration.BaseUrl
        $workflowTransportPrivateTls=$validated.Migration.AllowPrivateApiInsecureTls
        $HttpInvoker={param($Method,$Uri,$Headers,$Body)
            Invoke-UpdateWorkflowHttp -Method $Method -Uri $Uri -Headers $Headers -Body $Body -ApiBaseUrl $workflowTransportBaseUrl -AllowPrivateApiInsecureTls:$workflowTransportPrivateTls
        }
    }
    $headers = @{ 'X-Api-Key'=$validated.ApiKey; 'X-SkillTree-Migration-Key'=$validated.MigrationKey }
    $rootLock = $null
    $runLock = $null
    try {
        New-Item -ItemType Directory -Path $validated.RunRoot -Force | Out-Null
        $rootLock = [IO.File]::Open((Join-Path $validated.RunRoot 'workflow.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        $activePath = Join-Path $validated.RunRoot 'active-run.json'
        $active = if (Test-Path -LiteralPath $activePath -PathType Leaf) { Get-Content -Raw -Encoding UTF8 -LiteralPath $activePath | ConvertFrom-Json -AsHashtable } else { $null }
        if ($active -and $active.status -ne 'Completed') {
            if ($active.configurationFingerprint -ne $ConfigurationFingerprint) { throw 'Configuration changed while an update workflow run is active.' }
            $runDirectory = Get-UpdateWorkflowRunDirectory $validated.RunRoot $active.relativeRunDirectory
            if (!(Test-Path -LiteralPath $runDirectory -PathType Container)) { throw 'The active workflow run directory is missing.' }
            Write-Host "Resuming update: $runDirectory"
        } else {
            $run = New-UpdateWorkflowRunDirectory $validated.RunRoot; $runDirectory=$run.Path
            $active = [ordered]@{ schemaVersion=1; relativeRunDirectory=$run.Relative; configurationFingerprint=$ConfigurationFingerprint; status='Created'; createdAtUtc=[DateTime]::UtcNow.ToString('o') }
            Save-UpdateWorkflowJson $active $activePath
        }
        Write-Host "Run records: $runDirectory"
        $maintenanceDiagnosticFile = New-MaintenanceDiagnosticLog $runDirectory 'Workflow'
        Write-MaintenanceDiagnostic 'workflow.begin' @{label=$Label; powershell=$PSVersionTable.PSVersion.ToString()}
        $statePath = Join-Path $runDirectory 'workflow-state.json'
        $state = if (Test-Path -LiteralPath $statePath -PathType Leaf) { Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json -AsHashtable } else { $null }
        if ($state -and ($state.schemaVersion -ne 1 -or $state.configurationFingerprint -ne $ConfigurationFingerprint -or $state.label -cne $Label)) { throw 'Configuration or workflow label changed within this run.' }
        # Optional entry-specific choices are fixed under the root lock, before any API/deployment action.
        $preparedFingerprint=$null
        if ($PrepareRunAction) {
            $preparedFingerprint=& $PrepareRunAction $runDirectory
            if ($preparedFingerprint -isnot [string] -or $preparedFingerprint -cnotmatch '^[0-9a-f]{64}$') { throw 'Run preparation must return a SHA-256 fingerprint.' }
            if ($state -and $state.ContainsKey('runPreparationFingerprint') -and $state.runPreparationFingerprint -cne $preparedFingerprint) {
                throw 'Deployment selections changed within this run.'
            }
        }
        if (!$state) {
            # This happens before the deployment action and establishes the old session baseline.
            $baseline = [ordered]@{}
            foreach ($serverId in $validated.Migration.ServerIds) {
                $runtime = Get-UpdateWorkflowRuntime $validated.Migration $serverId $HttpInvoker $headers
                $baseline[$serverId] = if ($null -eq $runtime) { $null } else { $runtime.ServerSessionId }
            }
            $state = [ordered]@{ schemaVersion=1; configurationFingerprint=$ConfigurationFingerprint; label=$Label; status='AwaitingDeployment'; baselineSessions=$baseline; deployedRuntimes=@(); seedStatus='PENDING'; createdAtUtc=[DateTime]::UtcNow.ToString('o') }
            if ($PrepareRunAction) { $state.runPreparationFingerprint=$preparedFingerprint }
            Save-UpdateWorkflowJson $state $statePath
        }
        if ($state.status -eq 'Completed') {
            # Recover a crash between the run-state write and the active-pointer write.
            $active.status='Completed'; Save-UpdateWorkflowJson $active $activePath
            return [pscustomobject]@{ Status='COMPLETED'; RunDirectory=$runDirectory; MigrationResult=(Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $runDirectory 'skilltree-migration-result.json') | ConvertFrom-Json -Depth 40) }
        }
        if ($state.status -in @('Deploying','DeploymentFailed')) {
            Write-MaintenanceDiagnostic 'deployment.evidence.check' @{status=$state.status}
            if (!(Test-UpdateWorkflowDeploymentEvidence $runDirectory $Label)) { throw 'Deployment was interrupted or failed without verified completion. Recover it before resuming; this workflow will not redeploy automatically.' }
            $state.status='WaitingForStartup'; Save-UpdateWorkflowJson $state $statePath
        }
        if ($state.status -eq 'AwaitingDeployment') {
            if (!$ServersStopped -or !$AdmissionClosed) {
                if ([Console]::IsInputRedirected) { throw 'Non-interactive execution requires -ServersStopped and -AdmissionClosed.' }
                $answer = Read-Host "Confirm $Label servers are stopped, player admission remains closed, and automatic writers are stopped. Type DEPLOY to continue"
                if ($answer -cne 'DEPLOY') { throw 'Deployment was not confirmed.' }
            }
            $state.status='Deploying'; Save-UpdateWorkflowJson $state $statePath
            try {
                Write-MaintenanceDiagnostic 'deployment.action.begin'
                & $DeployAction $runDirectory
                Write-MaintenanceDiagnostic 'deployment.action.end'
            }
            catch {
                Write-MaintenanceDiagnostic 'deployment.action.failed' -Failure $_
                $state.status='DeploymentFailed'; Save-UpdateWorkflowJson $state $statePath; throw 'Deployment action failed. Resolve the deployment before retrying.'
            }
            $state.status='WaitingForStartup'; $state.deployedAtUtc=[DateTime]::UtcNow.ToString('o'); Save-UpdateWorkflowJson $state $statePath
        }
        # Child Deploy owns run.lock while copying. Acquire it only after that child exits,
        # and keep it through startup/migration so advanced Restore cannot race this workflow.
        $runLock=[IO.File]::Open((Join-Path $runDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None')
        if ($Label -eq 'Dev' -and !(Test-UpdateWorkflowDeploymentEvidence $runDirectory $Label)) {
            throw 'Dev deployment completion marker is missing. Recovery is required.'
        }
        $deploymentJournal=Join-Path $runDirectory 'deployment.json'
        if (Test-Path -LiteralPath $deploymentJournal -PathType Leaf) {
            $journal=Get-Content -Raw -Encoding UTF8 -LiteralPath $deploymentJournal | ConvertFrom-Json -AsHashtable
            if ($journal.status -ne 'Deployed') { throw 'Deployment is not Deployed (possibly restored). Recovery is required before a new update.' }
        } elseif (!(Test-UpdateWorkflowDeploymentEvidence $runDirectory $Label)) {
            throw 'Deployment completion evidence is missing. Recovery is required.'
        }
        if ($state.status -eq 'WaitingForStartup') {
            if ($WorkflowConfig.seedMasterData -and $state.seedStatus -ne 'SUCCEEDED') {
                Write-MaintenanceDiagnostic 'seed.begin'
                $seed = Invoke-UpdateWorkflowRequest $HttpInvoker 'POST' "$($validated.Migration.BaseUrl)/api/master-data/seed?mode=diff" @{ 'X-Api-Key'=$validated.ApiKey } $null
                if ($seed.StatusCode -ne 200 -or (Get-UpdateWorkflowValue $seed.Body 'status') -cne 'SUCCEEDED') { throw 'Master data seed did not succeed; startup waiting and migration are stopped.' }
                $state.seedStatus='SUCCEEDED'; $state.seededAtUtc=[DateTime]::UtcNow.ToString('o'); Save-UpdateWorkflowJson $state $statePath
                Write-MaintenanceDiagnostic 'seed.end'
            }
            Write-Host '対象サーバーを起動してください。入場制限を維持したまま、起動を自動確認しています。'
            Write-MaintenanceDiagnostic 'startup.wait.begin'
            $runtimes = Wait-UpdateWorkflowRuntimes $validated.Migration $state.baselineSessions $HttpInvoker $headers $WorkflowConfig.startupTimeoutSeconds $WorkflowConfig.pollIntervalSeconds $SleepAction
            $state.deployedRuntimes=@($runtimes); $state.status='ReadyForMigration'; Save-UpdateWorkflowJson $state $statePath
            Write-MaintenanceDiagnostic 'startup.wait.end'
        }
        if ($state.status -eq 'ReadyForMigration') {
            $marker = Join-Path $runDirectory 'migration-commit-started.json'
            if (!(Test-Path -LiteralPath $marker -PathType Leaf)) { Save-UpdateWorkflowJson @{ startedAtUtc=[DateTime]::UtcNow.ToString('o') } $marker }
            $state.status='Migrating'; Save-UpdateWorkflowJson $state $statePath
        }
        if ($state.status -eq 'Migrating') {
            # Re-read immediately before the irreversible commit path. The migration helper also
            # verifies this exact set before it enumerates candidates.
            $expectedRuntimes = Assert-UpdateWorkflowExpectedRuntimes $validated.Migration $state.deployedRuntimes $HttpInvoker $headers
            # The workflow transport exposes HTTP status (404 means offline); migration consumes JSON bodies.
            $workflowTransport=$HttpInvoker
            $migrationTransport={
                param($Method,$Uri,$Headers,$Body)
                $response=Invoke-UpdateWorkflowRequest $workflowTransport $Method $Uri $Headers $Body
                if ($response.StatusCode -ne 200) { throw 'Migration API returned an unsuccessful status.' }
                return $response.Body
            }
            try {
                Write-MaintenanceDiagnostic 'migration.begin'
                $migrationResult = Invoke-SkillTreeMigration -Config $MigrationConfig -RunDirectory $runDirectory -Commit -ExpectedRuntimes $expectedRuntimes -HttpInvoker $migrationTransport
                Write-MaintenanceDiagnostic 'migration.end'
            }
            catch {
                Write-MaintenanceDiagnostic 'migration.failed' -Failure $_
                throw 'Skill tree migration did not complete. The active run and persisted operation IDs were retained for a same-run retry.'
            }
            if ($migrationResult.Status -cne 'APPLIED') { throw 'Skill tree migration did not report APPLIED.' }
            $null=Assert-UpdateWorkflowExpectedRuntimes $validated.Migration $state.deployedRuntimes $HttpInvoker $headers
            $state.status='Completed'; $state.completedAtUtc=[DateTime]::UtcNow.ToString('o'); Save-UpdateWorkflowJson $state $statePath
            $active.status='Completed'; $active.completedAtUtc=$state.completedAtUtc; Save-UpdateWorkflowJson $active $activePath
            Write-Host "$Label update workflow completed."
            return [pscustomobject]@{ Status='COMPLETED'; RunDirectory=$runDirectory; MigrationResult=$migrationResult }
        }
        throw "Unsupported workflow status '$($state.status)'."
    } catch {
        Write-MaintenanceDiagnostic 'workflow.failed' -Failure $_
        throw
    } finally {
        Write-MaintenanceDiagnostic 'workflow.finally'
        if ($runLock) { $runLock.Dispose() }
        if ($rootLock) { $rootLock.Dispose() }
    }
}
