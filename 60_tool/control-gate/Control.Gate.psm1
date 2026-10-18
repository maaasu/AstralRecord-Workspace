Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ControlGateMode {
    param([Parameter(Mandatory)]$Configuration)
    $mode = if ($Configuration.ContainsKey('controlGate') -and $Configuration.controlGate -and $Configuration.controlGate.mode) { [string]$Configuration.controlGate.mode } else { 'Legacy' }
    if ($mode -cnotin @('Managed','Legacy')) { throw 'Unsupported controlGate.mode.' }
    $marker = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    if (Test-Path -LiteralPath $marker) {
        if ($mode -eq 'Legacy') { throw 'Control management is enabled on this PC; legacy entry points are disabled.' }
        $attestation=Get-Content -Raw -Encoding UTF8 -LiteralPath $marker | ConvertFrom-Json -AsHashtable
        $toolRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\','/')
        if ($attestation.schemaVersion -ne 1 -or !$attestation.toolRoot -or
            [IO.Path]::GetFullPath([string]$attestation.toolRoot).TrimEnd('\','/') -ine $toolRoot) { throw 'Managed tool attestation does not match this installation.' }
        $required=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
            'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
            'master-data-reload\master-data-reload.ps1')
        foreach ($file in $required) { if (!$attestation.hashes.ContainsKey($file)) { throw 'Managed tool attestation is incomplete.' } }
        foreach ($entry in $attestation.hashes.GetEnumerator()) {
            $path=Join-Path $toolRoot $entry.Key
            if (!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $entry.Value) {
                throw 'Managed tool attestation hash differs from the installed entry points.'
            }
        }
    } elseif ($mode -eq 'Managed') {
        throw 'Control management requires a verified installation marker.'
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
    $workflowId=[Guid]::Empty
    if (![Guid]::TryParse([string]$identity.workflowId,[ref]$workflowId) -or $workflowId -eq [Guid]::Empty) { throw 'Active run Control workflow ID is invalid.' }
    return [pscustomobject]@{ WorkflowId=$workflowId.ToString('D'); RunReference=$run }
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

function Enter-ControlGate {
    param(
        [Parameter(Mandatory)]$Configuration,
        [Parameter(Mandatory)][ValidateSet('01','02','03','16','17')][string]$Operation,
        [Parameter(Mandatory)][string[]]$RuntimeServerIds,
        [string[]]$AffectedPaths=@(),
        [Parameter(Mandatory)][ValidateSet('Stopped','Running')][string]$RequiredState,
        [string]$RunReference = '',
        [string]$WorkflowId = '',
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
        return [pscustomobject]@{ Mode='Managed'; Owner=$true; LeaseFile=$lease; CliPath=$cli; ServerUrl=$serverUrl }
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
    Invoke-ControlGateCli $Context.CliPath @('gate','release','--lease-file',$Context.LeaseFile,'--outcome','Succeeded','--run-reference',$RunReference,'--expected-server-url',$Context.ServerUrl)
    if (Test-Path -LiteralPath (Join-Path $RunReference 'control-gate-run.json') -PathType Leaf) {
        @{releasedAtUtc=[DateTimeOffset]::UtcNow.ToString('O');runReference=$RunReference} | ConvertTo-Json |
            Set-Content -Encoding UTF8 -LiteralPath (Join-Path $RunReference 'control-gate-released.json')
    }
    [Environment]::SetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE', $null, 'Process')
}

Export-ModuleMember -Function Get-ControlGateMode, Get-ControlGateResumeIdentity, New-ControlGateRunReference, Enter-ControlGate, Assert-ControlGateStep, Complete-ControlGate
