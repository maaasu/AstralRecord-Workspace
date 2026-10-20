Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-DeploymentGateRuntimeServerIds {
    param($Configuration,[string[]]$RuntimeServerIds,[switch]$PluginOnly,[switch]$MasterDataOnly,[switch]$ServerAlreadyRunning)
    $gate=$Configuration['controlGate']
    $hasShared=$gate -and $gate['mode'] -ceq 'Managed' -and $gate.ContainsKey('sharedApiRuntimeServerIds')
    $changesSharedApi=!$PluginOnly -and ($Configuration['fileDatabase']['enabled'] -or (!$MasterDataOnly -and $Configuration['api']['enabled']))
    if (!$changesSharedApi -or !$hasShared) { return $RuntimeServerIds }
    $shared=@($gate['sharedApiRuntimeServerIds'])
    if ($shared.Count -lt 2 -or @($shared | Sort-Object -Unique).Count -ne $shared.Count -or
        @($shared | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -or
        @($RuntimeServerIds | Where-Object { $_ -cnotin $shared }).Count) { throw 'sharedApiRuntimeServerIds must be a nonempty set of at least two distinct runtime IDs and include every workflow target.' }
    if ($ServerAlreadyRunning) { throw 'This API is shared by multiple RPG servers. Use stopped mode for 03, then start every shared server before patch publication.' }
    return @($shared | Sort-Object -Unique)
}

function Get-ControlGateMode {
    param([Parameter(Mandatory)]$Configuration)
    $mode = if ($Configuration.ContainsKey('controlGate') -and $Configuration.controlGate -and $Configuration.controlGate.mode) { [string]$Configuration.controlGate.mode } else { 'Legacy' }
    if ($mode -cnotin @('Managed','Legacy')) { throw 'Unsupported controlGate.mode.' }
    $marker = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    if (Test-Path -LiteralPath $marker) {
        if ($mode -eq 'Legacy') { throw 'Control management is enabled on this PC; legacy entry points are disabled.' }
        if (!(Test-Path -LiteralPath $marker -PathType Leaf) -or
            ((Get-Item -LiteralPath $marker -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Invalid management installation marker.' }
        $installation=Get-Content -Raw -Encoding UTF8 -LiteralPath $marker | ConvertFrom-Json -AsHashtable
        $toolRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\','/')
        if ($installation.schemaVersion -ne 1 -or !$installation.toolRoot -or
            [IO.Path]::GetFullPath([string]$installation.toolRoot).TrimEnd('\','/') -ine $toolRoot) { throw 'Managed tool registration does not match this installation.' }
        # The marker records the one-time installation. Local tool updates do not require another OS-admin approval.
        # Existing schema-1 hashes are retained for compatibility with older installers, not used as authentication.
    } elseif ($mode -eq 'Managed') {
        throw 'Control management requires an installed management marker.'
    }
    return $mode
}

function Assert-ControlGateCli {
    param([Parameter(Mandatory)]$Gate)
    $path = [string]$Gate.cliPath
    if (!$path -or ![IO.Path]::IsPathFullyQualified($path) -or !(Test-Path -LiteralPath $path -PathType Leaf)) {
        throw 'Managed controlGate.cliPath must name an installed Control CLI executable.'
    }
    $uri = $null
    if (![Uri]::TryCreate([string]$Gate.serverUrl, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or $uri.AbsolutePath -cne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
        throw 'Managed controlGate.serverUrl must be a fixed HTTPS origin.'
    }
    $environmentId = [Guid]::Empty
    if (![Guid]::TryParse([string]$Gate.environmentId, [ref]$environmentId) -or $environmentId -eq [Guid]::Empty) { throw 'Managed controlGate.environmentId is required.' }
    return $path
}

function Resolve-ControlGateResources {
    param($Gate, [string[]]$RuntimeServerIds, [string[]]$AffectedPaths, [string]$RequiredState)
    if (!$Gate.bindings -or !$Gate.bindings.servers) { throw 'Managed gate requires complete server bindings.' }
    if ($AffectedPaths.Count -and !$Gate.bindings.paths) { throw 'Managed gate requires complete path bindings.' }
    $keys = [Collections.Generic.List[string]]::new()
    $conditions = [Collections.Generic.List[object]]::new()
    $keys.Add('environment:' + ([Guid]::Parse([string]$Gate.environmentId)).ToString('D'))
    foreach ($runtimeId in @($RuntimeServerIds | Sort-Object -Unique)) {
        $matches = @($Gate.bindings.servers | Where-Object { $_.runtimeServerId -ceq $runtimeId })
        if ($matches.Count -ne 1) { throw "Managed gate server binding is missing or ambiguous: $runtimeId" }
        $serverId = [Guid]::Empty
        if (![Guid]::TryParse([string]$matches[0].serverId, [ref]$serverId) -or $serverId -eq [Guid]::Empty) { throw 'Managed gate server binding has an invalid serverId.' }
        $keys.Add('server:' + $serverId.ToString('D'))
        $conditions.Add(@{ serverId=$serverId.ToString('D'); requiredState=$RequiredState })
    }
    foreach ($path in @($AffectedPaths | Sort-Object -Unique)) {
        if (![IO.Path]::IsPathFullyQualified($path)) { throw 'Managed gate requires absolute affected paths.' }
        $canonical = [IO.Path]::GetFullPath($path).TrimEnd('\','/')
        $matches = @($Gate.bindings.paths | Where-Object { [IO.Path]::IsPathFullyQualified([string]$_.path) -and [IO.Path]::GetFullPath([string]$_.path).TrimEnd('\','/') -ieq $canonical })
        if ($matches.Count -ne 1) { throw "Managed gate path binding is missing or ambiguous: $path" }
        $binding = $matches[0]
        $hostId = [Guid]::Empty; $rootId = [Guid]::Empty
        if (![Guid]::TryParse([string]$binding.hostId,[ref]$hostId) -or $hostId -eq [Guid]::Empty -or
            ![Guid]::TryParse([string]$binding.managedRootId,[ref]$rootId) -or $rootId -eq [Guid]::Empty) { throw 'Managed gate path binding IDs are invalid.' }
        $relative = [string]$binding.relativePath
        if (!$relative -or [IO.Path]::IsPathRooted($relative) -or @($relative -split '[\\/]' | Where-Object { $_ -in @('','.', '..') }).Count) { throw 'Managed gate relative path is invalid.' }
        $accessRoot=[string]$binding.accessRoot
        if (![IO.Path]::IsPathFullyQualified($accessRoot)) { throw 'Managed gate binding requires an absolute accessRoot.' }
        $expected=[IO.Path]::GetFullPath((Join-Path $accessRoot $relative)).TrimEnd('\','/')
        if ($expected -ine $canonical) { throw 'Managed gate path does not match accessRoot plus relativePath.' }
        $keys.Add('directory:' + $hostId.ToString('D') + ':' + $rootId.ToString('D') + ':' + ($relative -replace '\\','/'))
    }
    return [pscustomobject]@{ Keys=@($keys | Sort-Object -Unique); Conditions=@($conditions) }
}

function Get-ControlGateResumeIdentity {
    param([Parameter(Mandatory)][string]$RunRoot)
    $activePath=Join-Path $RunRoot 'active-run.json'
    if (!(Test-Path -LiteralPath $activePath -PathType Leaf)) { return $null }
    $active=Get-Content -Raw -Encoding UTF8 -LiteralPath $activePath | ConvertFrom-Json
    $relative=[string]$active.relativeRunDirectory
    if ($relative -cnotmatch '^run-\d{8}T\d{6}Z-[a-f0-9]{32}$') { throw 'Active run reference is invalid.' }
    $run=Join-Path $RunRoot $relative
    if ($active.status -ceq 'Completed' -and (Test-Path -LiteralPath (Join-Path $run 'control-gate-released.json') -PathType Leaf)) { return $null }
    $gatePath=Join-Path $run 'control-gate-run.json'
    if (!(Test-Path -LiteralPath $gatePath -PathType Leaf)) { throw 'Active run has no Control reservation identity.' }
    $identity=Get-Content -Raw -Encoding UTF8 -LiteralPath $gatePath | ConvertFrom-Json
    $workflowId=[Guid]::Empty; $reservationId=[Guid]::Empty
    if (![Guid]::TryParse([string]$identity.workflowId,[ref]$workflowId) -or $workflowId -eq [Guid]::Empty -or
        ![Guid]::TryParse([string]$identity.reservationId,[ref]$reservationId) -or $reservationId -eq [Guid]::Empty) {
        throw 'Active run Control reservation identity is invalid.'
    }
    $completed=$active.status -ceq 'Completed'
    if ($completed) {
        $statePath=Join-Path $run 'workflow-state.json'
        if (!(Test-Path -LiteralPath $statePath -PathType Leaf) -or
            (Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json).status -cne 'Completed') {
            throw 'Completed run state is inconsistent; retain the Control reservation.'
        }
    }
    return [pscustomobject]@{ WorkflowId=$workflowId.ToString('D'); ReservationId=$reservationId.ToString('D'); RunReference=$run; Completed=$completed }
}

function New-ControlGateRunReference {
    param([Parameter(Mandatory)][string]$RunRoot)
    $relative='run-{0}-{1}' -f [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'), [Guid]::NewGuid().ToString('N')
    return [IO.Path]::GetFullPath((Join-Path $RunRoot $relative))
}

function New-ControlGateLeasePath {
    $root = Join-Path $env:LOCALAPPDATA 'AstralRecordControl\GateLeases'
    if (!(Test-Path -LiteralPath $root -PathType Container)) {
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        if ($IsWindows) {
            $acl = [Security.AccessControl.DirectorySecurity]::new()
            $acl.SetAccessRuleProtection($true, $false)
            $current = [Security.Principal.WindowsIdentity]::GetCurrent().User
            foreach ($sid in @($current, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
                $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
                $acl.AddAccessRule($rule)
            }
            Set-Acl -LiteralPath $root -AclObject $acl
        }
    }
    if ($IsWindows) {
        $acl = Get-Acl -LiteralPath $root
        if (!$acl.AreAccessRulesProtected) { throw 'Gate lease directory has inherited permissions.' }
    }
    return Join-Path $root (([Guid]::NewGuid().ToString('N')) + '.json')
}

function Invoke-ControlGateCli {
    param([Parameter(Mandatory)][string]$CliPath, [Parameter(Mandatory)][string[]]$Arguments)
    & $CliPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Control gate rejected the operation (exit $LASTEXITCODE)." }
}

function Assert-ControlGateLeaseIdentity {
    param($Local, $Remote, [string]$WorkflowId, [string]$ReservationId, [string]$Operation,
        [string]$RunReference, [string[]]$ExpectedKeys)
    $workflow=[Guid]::Empty; $reservation=[Guid]::Empty
    if (![Guid]::TryParse($WorkflowId,[ref]$workflow) -or $workflow -eq [Guid]::Empty -or
        ![Guid]::TryParse($ReservationId,[ref]$reservation) -or $reservation -eq [Guid]::Empty) {
        throw 'Control gate resume identity is invalid.'
    }
    foreach ($lease in @($Local,$Remote)) {
        if (!$lease -or [string]$lease.workflowId -cne $workflow.ToString('D') -or
            [string]$lease.reservationId -cne $reservation.ToString('D') -or
            [string]$lease.adapterId -cne $Operation -or [string]$lease.runReference -cne $RunReference) {
            throw 'Control gate inspection belongs to a different run or reservation.'
        }
        $keys=@($lease.resourceKeys)
        if (!$keys.Count -or @($keys | Sort-Object -Unique -CaseSensitive).Count -ne $keys.Count) { throw 'Control gate resource identity is invalid.' }
        foreach ($key in $ExpectedKeys) { if ($key -cnotin $keys) { throw 'Control gate lease does not cover every required resource.' } }
    }
    $localKeys=@($Local.resourceKeys | Sort-Object -CaseSensitive)
    $remoteKeys=@($Remote.resourceKeys | Sort-Object -CaseSensitive)
    if ($localKeys.Count -ne $remoteKeys.Count -or [string]::Join("`n",$localKeys) -cne [string]::Join("`n",$remoteKeys)) {
        throw 'Control gate inspected resources differ from the protected lease.'
    }
    $fences=@()
    foreach ($lease in @($Local,$Remote)) {
        $values=@(foreach ($item in @($lease.fences)) {
            $hostId=[Guid]::Empty; $fence=0L
            if (![Guid]::TryParse([string]$item.hostId,[ref]$hostId) -or $hostId -eq [Guid]::Empty -or
                ![long]::TryParse([string]$item.fence,[ref]$fence) -or $fence -le 0) { throw 'Control gate fence identity is invalid.' }
            $hostId.ToString('D') + ':' + $fence
        })
        if (!$values.Count -or @($values | Sort-Object -Unique -CaseSensitive).Count -ne $values.Count) { throw 'Control gate fence identity is invalid.' }
        $fences+=,[string]::Join("`n",@($values | Sort-Object -CaseSensitive))
    }
    if ($fences[0] -cne $fences[1]) { throw 'Control gate inspected fences differ from the protected lease.' }
}

function Get-ControlGateInspectedLease {
    param([string]$CliPath, [string]$LeaseFile, [string]$ServerUrl, [string]$WorkflowId,
        [string]$ReservationId, [string]$Operation, [string]$RunReference, [string[]]$ExpectedKeys)
    $local=Get-Content -Raw -Encoding UTF8 -LiteralPath $LeaseFile | ConvertFrom-Json
    $response=@(Invoke-ControlGateCli $CliPath @('gate','inspect','--lease-file',$LeaseFile,'--expected-server-url',$ServerUrl))
    try { $remote=($response -join "`n") | ConvertFrom-Json } catch { throw 'Control gate inspection response is invalid.' }
    Assert-ControlGateLeaseIdentity $local $remote $WorkflowId $ReservationId $Operation $RunReference $ExpectedKeys
    if ([string]$remote.status -cnotin @('Prepared','Releasing','Released')) { throw 'Control gate reservation needs manual recovery.' }
    if ([string]$local.status -cnotin @('Prepared','Releasing','Released') -or
        ($local.status -ceq 'Released' -and $remote.status -cne 'Released')) { throw 'Protected Control gate lease status conflicts with the inspected reservation.' }
    return $remote
}

function Find-ControlGateResumeLease {
    param([string]$WorkflowId, [string]$ReservationId, [string]$Operation, [string]$RunReference)
    $root=Join-Path $env:LOCALAPPDATA 'AstralRecordControl\GateLeases'
    if (!(Test-Path -LiteralPath $root -PathType Container)) { throw 'Protected Control gate lease directory is missing.' }
    $matches=@()
    foreach ($file in @(Get-ChildItem -LiteralPath $root -Filter '*.json' -File | Sort-Object FullName)) {
        if ($file.Length -gt 1048576) { continue }
        try { $lease=Get-Content -Raw -Encoding UTF8 -LiteralPath $file.FullName | ConvertFrom-Json } catch { continue }
        if ([string]$lease.workflowId -cne $WorkflowId) { continue }
        if ([string]$lease.reservationId -cne $ReservationId -or [string]$lease.adapterId -cne $Operation -or
            [string]$lease.runReference -cne $RunReference) { throw 'Saved Control gate lease conflicts with the active run.' }
        $matches+=,$file.FullName
    }
    if (!$matches.Count) { throw 'Protected Control gate lease for the active run is missing.' }
    return $matches[0]
}

function Enter-ControlGate {
    param(
        [Parameter(Mandatory)]$Configuration,
        [Parameter(Mandatory)][ValidateSet('01','02','03','16','17')][string]$Operation,
        [Parameter(Mandatory)][string[]]$RuntimeServerIds,
        [string[]]$AffectedPaths=@(),
        [Parameter(Mandatory)][ValidateSet('Stopped','Running')][string]$RequiredState,
        [string]$RunReference = '',
        [string]$WorkflowId = '',
        [string]$ReservationId = '',
        [switch]$CompletedRun,
        [ValidateSet('Deploy','Restore','Seed','WaitForStartup','Publish','Preview','Commit','Resume')][string]$InitialStep='Deploy'
    )
    $mode = Get-ControlGateMode $Configuration
    if ($mode -eq 'Legacy') { return [pscustomobject]@{ Mode='Legacy'; Owner=$false; LeaseFile=''; CliPath=''; ServerUrl='' } }
    $gate = $Configuration.controlGate
    $cli = Assert-ControlGateCli $gate
    $serverUrl=([Uri][string]$gate.serverUrl).AbsoluteUri.TrimEnd('/')
    if (!$RuntimeServerIds.Count -or @($RuntimeServerIds | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count) { throw 'Managed gate requires every affected runtime server.' }
    if (!$AffectedPaths) { $AffectedPaths=@() }
    $resources = Resolve-ControlGateResources $gate $RuntimeServerIds $AffectedPaths $RequiredState
    if ($WorkflowId) {
        if (!$ReservationId -or !$RunReference) { throw 'Control gate resume requires the saved reservation and run reference.' }
        $lease=Find-ControlGateResumeLease $WorkflowId $ReservationId $Operation $RunReference
        $remote=Get-ControlGateInspectedLease $cli $lease $serverUrl $WorkflowId $ReservationId $Operation $RunReference $resources.Keys
        if (!$CompletedRun -and $remote.status -cne 'Prepared') { throw 'Incomplete run cannot resume a releasing or released Control reservation.' }
        if (!$CompletedRun) { Invoke-ControlGateCli $cli @('gate','validate','--lease-file',$lease,'--step',$InitialStep,'--expected-server-url',$serverUrl) }
        [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE', $lease, 'Process')
        return [pscustomobject]@{ Mode='Managed'; Owner=$true; LeaseFile=$lease; CliPath=$cli; ServerUrl=$serverUrl;
            WorkflowId=$WorkflowId; ReservationId=$ReservationId; AdapterId=$Operation; RunReference=$RunReference;
            ResourceKeys=$resources.Keys; CompletedRun=[bool]$CompletedRun; RemoteStatus=[string]$remote.status }
    }
    if ($ReservationId -or $CompletedRun) { throw 'Control gate resume metadata is incomplete.' }
    $inherited = [Environment]::GetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE', 'Process')
    if ($inherited) {
        if (![IO.Path]::IsPathFullyQualified($inherited) -or !(Test-Path -LiteralPath $inherited -PathType Leaf)) { throw 'Inherited Control gate lease is missing.' }
        $old = Get-Content -Raw -Encoding UTF8 -LiteralPath $inherited | ConvertFrom-Json
        if ($old.adapterId -cne $Operation) { throw 'Inherited gate adapter does not match the child operation.' }
        if ($RunReference -and [IO.Path]::GetFullPath([string]$old.runReference) -ine [IO.Path]::GetFullPath($RunReference)) { throw 'Inherited gate lease belongs to another run.' }
        foreach ($key in $resources.Keys) { if ($key -cnotin @($old.resourceKeys)) { throw 'Inherited gate lease does not cover every required resource.' } }
        Invoke-ControlGateCli $cli @('gate','validate','--lease-file',$inherited,'--step',$InitialStep,'--expected-server-url',$serverUrl)
        return [pscustomobject]@{ Mode='Managed'; Owner=$false; LeaseFile=$inherited; CliPath=$cli; ServerUrl=$serverUrl }
    }
    $lease = New-ControlGateLeasePath
    $request = [ordered]@{
        workflowId=if ($WorkflowId) { ([Guid]::Parse($WorkflowId)).ToString('D') } else { [Guid]::NewGuid().ToString('D') }; resourceKeys=$resources.Keys
        mode='Maintenance'; adapterId=$Operation; runReference=$RunReference
        serverConditions=$resources.Conditions
    }
    $requestFile = [IO.Path]::ChangeExtension($lease, '.request.json')
    try {
        [IO.File]::WriteAllText($requestFile, ($request | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
        Invoke-ControlGateCli $cli @('gate','acquire','--request-file',$requestFile,'--lease-file',$lease,'--expected-server-url',$serverUrl)
        if (!(Test-Path -LiteralPath $lease -PathType Leaf)) { throw 'Control CLI did not create a lease file.' }
        [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE', $lease, 'Process')
        Invoke-ControlGateCli $cli @('gate','validate','--lease-file',$lease,'--step',$InitialStep,'--expected-server-url',$serverUrl)
        $saved=Get-Content -Raw -Encoding UTF8 -LiteralPath $lease | ConvertFrom-Json
        return [pscustomobject]@{ Mode='Managed'; Owner=$true; LeaseFile=$lease; CliPath=$cli; ServerUrl=$serverUrl;
            WorkflowId=[string]$saved.workflowId; ReservationId=[string]$saved.reservationId; AdapterId=$Operation;
            RunReference=$RunReference; ResourceKeys=$resources.Keys; CompletedRun=$false; RemoteStatus='Prepared' }
    } finally {
        if (Test-Path -LiteralPath $requestFile) { Remove-Item -LiteralPath $requestFile -Force }
    }
}

function Assert-ControlGateStep {
    param($Context, [Parameter(Mandatory)][string]$Step)
    if ($Context.Mode -eq 'Managed') { Invoke-ControlGateCli $Context.CliPath @('gate','validate','--lease-file',$Context.LeaseFile,'--step',$Step,'--expected-server-url',$Context.ServerUrl) }
}

function Complete-ControlGate {
    param($Context, [Parameter(Mandatory)][string]$RunReference)
    if ($Context.Mode -ne 'Managed' -or !$Context.Owner) { return }
    if ([string]::IsNullOrWhiteSpace($RunReference)) { throw 'Managed gate release requires a run reference.' }
    if ($RunReference -cne $Context.RunReference) { throw 'Control gate release run reference differs from its reservation.' }
    $remote=Get-ControlGateInspectedLease $Context.CliPath $Context.LeaseFile $Context.ServerUrl $Context.WorkflowId `
        $Context.ReservationId $Context.AdapterId $RunReference $Context.ResourceKeys
    if ($remote.status -cne 'Released') {
        if ($remote.status -cne 'Prepared' -and $remote.status -cne 'Releasing') { throw 'Control gate release requires manual recovery.' }
        Invoke-ControlGateCli $Context.CliPath @('gate','release','--lease-file',$Context.LeaseFile,'--outcome','Succeeded','--run-reference',$RunReference,'--expected-server-url',$Context.ServerUrl)
        $remote=Get-ControlGateInspectedLease $Context.CliPath $Context.LeaseFile $Context.ServerUrl $Context.WorkflowId `
            $Context.ReservationId $Context.AdapterId $RunReference $Context.ResourceKeys
    }
    if ($remote.status -cne 'Released') { throw 'Control gate release remains unconfirmed.' }
    if (Test-Path -LiteralPath (Join-Path $RunReference 'control-gate-run.json') -PathType Leaf) {
        @{releasedAtUtc=[DateTimeOffset]::UtcNow.ToString('O');runReference=$RunReference} | ConvertTo-Json |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $RunReference 'control-gate-released.json')
    }
    [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE', $null, 'Process')
}

Export-ModuleMember -Function Get-ControlGateMode, Get-ControlGateResumeIdentity, New-ControlGateRunReference, Enter-ControlGate, Assert-ControlGateStep, Complete-ControlGate, Get-DeploymentGateRuntimeServerIds
