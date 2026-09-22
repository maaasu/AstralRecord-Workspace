# PowerShell 7. All paths are literal; deployment replaces only configured leaf artifacts.
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'Diagnostics.ps1')

function Write-MaintenanceJson($Value, [string]$Path) {
    $temporary = "$Path.tmp"
    $Value | ConvertTo-Json -Depth 80 | Set-Content -LiteralPath $temporary -Encoding utf8
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Get-MaintenanceAbsolutePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or ![IO.Path]::IsPathFullyQualified($Path)) {
        throw "An absolute path is required: $Path"
    }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($full -eq [IO.Path]::GetPathRoot($full).TrimEnd('\', '/')) { throw "A volume/share root is not allowed: $Path" }
    Assert-MaintenanceNoLinks $full
    return $full
}

function Assert-MaintenanceNoLinks([string]$Path) {
    $cursor = $Path
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Links/junctions are not allowed: $cursor" }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
}

function Test-MaintenanceOverlap([string]$A, [string]$B) {
    return $A.Equals($B, [StringComparison]::OrdinalIgnoreCase) -or
        $A.StartsWith($B + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        $B.StartsWith($A + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Resolve-MaintenanceChild([string]$Root, [string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative) -or
        $Relative -match '[:*?\[\]]' -or $Relative -match '(^|[\\/])\.{1,2}([\\/]|$)') {
        throw "Invalid relative artifact path: $Relative"
    }
    $full = Get-MaintenanceAbsolutePath (Join-Path $Root $Relative)
    if (!$full.StartsWith($Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Artifact escapes its server root: $Relative"
    }
    return $full
}

function Get-MaintenancePlan($Config) {
    if ($Config.schemaVersion -ne 1) { throw 'Unsupported schemaVersion.' }
    $servers = @{}
    foreach ($server in $Config.servers) {
        if ($server.enabled -isnot [bool]) { throw 'Server enabled must be boolean.' }
        if ($server.id -notmatch '^[a-zA-Z0-9_-]+$' -or $servers.ContainsKey($server.id)) { throw 'Invalid or duplicate server ID.' }
        if ($server.enabled) {
            $serverRoot = Get-MaintenanceAbsolutePath $server.rootPath
            if (!(Test-Path -LiteralPath $serverRoot -PathType Container)) { throw "Missing server root: $serverRoot" }
            foreach ($previous in $servers.Values) {
                if ($previous.enabled -and (Test-MaintenanceOverlap $serverRoot (Get-MaintenanceAbsolutePath $previous.rootPath))) { throw 'Enabled server roots overlap.' }
            }
        }
        $servers[$server.id] = $server
    }
    $plan = [Collections.Generic.List[object]]::new()
    foreach ($entry in $Config.distributions) {
        if ($entry.enabled -isnot [bool]) { throw 'Distribution enabled must be boolean.' }
        if (!$entry.enabled) { continue }
        if ($entry.kind -notin @('Jar','Placement','Filebase','World')) { throw "Unknown kind: $($entry.kind)" }
        if (!$servers.ContainsKey($entry.source) -or !$servers[$entry.source].enabled) { throw "Source server is missing/disabled: $($entry.source)" }
        $sourceRoot = Get-MaintenanceAbsolutePath $servers[$entry.source].rootPath
        if (!(Test-Path -LiteralPath $sourceRoot -PathType Container)) { throw "Missing source server root: $sourceRoot" }
        $relative = $entry.relativePath.Replace('\','/')
        switch ($entry.kind) {
            Jar { if ($relative -notmatch '^plugins/[^/]+\.jar$') { throw 'Jar must select one plugins/*.jar file.' } }
            Placement {
                if ($relative -notmatch '^plugins/AstralRecord/(mob_spawners|gathering_spawners|npc_locations|waystones)\.yml$') { throw 'Placement must select a supported shared placement YAML.' }
            }
            Filebase { if ($relative -ne 'filebase') { throw 'Filebase must select the dedicated filebase directory.' } }
            World {
                if ($relative -match '(^|/)(plugins|filebase|logs|backups|\.astral-maintenance)(/|$)' -and
                    $relative -notmatch '^plugins/AstralRecord/worlds/(?!.*(^|/)(_temp|system)(/|$)).+') {
                    throw 'World must select a dedicated world directory.'
                }
            }
        }
        $source = Resolve-MaintenanceChild $sourceRoot $relative
        if (!(Test-Path -LiteralPath $source)) { throw "Missing source artifact: $source" }
        $directory = (Get-Item -LiteralPath $source).PSIsContainer
        if ($directory -ne ($entry.kind -in @('Filebase','World'))) { throw "Wrong artifact type: $source" }
        if ($entry.kind -eq 'World' -and !(Test-Path -LiteralPath (Join-Path $source 'level.dat') -PathType Leaf)) { throw "World has no level.dat: $source" }
        if ($entry.kind -eq 'Filebase' -and !(Test-Path -LiteralPath (Join-Path $source 'config.yml') -PathType Leaf)) { throw "Filebase has no config.yml: $source" }
        if (@($entry.targets).Count -eq 0) { throw 'Enabled distribution has no targets.' }
        foreach ($targetId in $entry.targets) {
            if (!$servers.ContainsKey($targetId)) { throw "Unknown target server: $targetId" }
            if (!$servers[$targetId].enabled) { Write-Host "SKIP disabled server: $targetId"; continue }
            $root = Get-MaintenanceAbsolutePath $servers[$targetId].rootPath
            if (!(Test-Path -LiteralPath $root -PathType Container)) { throw "Missing destination server root: $root" }
            $destination = Resolve-MaintenanceChild $root $relative
            if (Test-MaintenanceOverlap $source $destination) { throw 'Source and destination overlap.' }
            if ((Test-Path -LiteralPath $destination) -and (Get-Item -LiteralPath $destination).PSIsContainer -ne $directory) { throw 'Destination type differs.' }
            $plan.Add([ordered]@{ index=$plan.Count; kind=$entry.kind; source=$source; destination=$destination; serverId=$targetId; root=$root; directory=$directory })
        }
    }
    # Optional, already-built network JARs. Blank destinations are explicitly skipped.
    if ($Config.ContainsKey('networkPlugins') -and $null -ne $Config.networkPlugins) {
        $network=$Config.networkPlugins
        foreach ($entry in $network.destinations) {
            if ([string]::IsNullOrWhiteSpace($entry.deployDirectory)) { Write-Host "SKIP unconfigured network artifact: $($entry.artifact)"; continue }
            if ($entry.artifact -notin @('AstralRecordLobby.jar','AstralRecordProxy.jar','AstralRecordGeyserExtension.jar')) { throw 'Unsupported network plugin artifact.' }
            $sourceDirectory=if ([string]::IsNullOrWhiteSpace($network.sourceDirectory)) {
                [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../network-plugin-build/output'))
            } else { $network.sourceDirectory }
            $sourceDirectory=Get-MaintenanceAbsolutePath $sourceDirectory
            $source=Resolve-MaintenanceChild $sourceDirectory $entry.artifact
            if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Network output JAR is missing (no build is performed): $source" }
            $deployDirectory=Get-MaintenanceAbsolutePath $entry.deployDirectory
            if (!(Test-Path -LiteralPath $deployDirectory -PathType Container)) { throw "Network deploy directory is missing: $deployDirectory" }
            if ($entry.artifact -eq 'AstralRecordGeyserExtension.jar') {
                $plugins=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($deployDirectory))
                if ([IO.Path]::GetFileName($deployDirectory) -ne 'extensions' -or
                    [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($deployDirectory)) -ne 'Geyser-Velocity' -or
                    [IO.Path]::GetFileName($plugins) -ne 'plugins') { throw 'Geyser extension destination must be plugins/Geyser-Velocity/extensions.' }
                $root=Get-MaintenanceAbsolutePath ([IO.Path]::GetDirectoryName($plugins))
            } else {
                if ([IO.Path]::GetFileName($deployDirectory) -ne 'plugins') { throw 'Lobby/Proxy destination must be a plugins directory.' }
                $root=Get-MaintenanceAbsolutePath ([IO.Path]::GetDirectoryName($deployDirectory))
            }
            $destination=Resolve-MaintenanceChild $root ([IO.Path]::GetRelativePath($root,(Join-Path $deployDirectory $entry.artifact)))
            if ((Test-Path -LiteralPath $destination) -and !(Test-Path -LiteralPath $destination -PathType Leaf)) { throw 'Network destination is not a file.' }
            $plan.Add([ordered]@{index=$plan.Count;kind='Jar';source=$source;destination=$destination;serverId='network';root=$root;directory=$false})
        }
    }
    foreach ($item in $plan) {
        foreach ($other in $plan) {
            if (Test-MaintenanceOverlap $item.destination $other.source) { throw 'A destination overlaps another distribution source.' }
            if ($item.index -ne $other.index -and (Test-MaintenanceOverlap $item.destination $other.destination)) { throw 'Distribution destinations overlap.' }
        }
    }
    return $plan.ToArray()
}

function Test-MaintenanceWorldLocal([string]$Relative) {
    # Identity and vanilla per-player state belong to each destination, never to Build.
    return ($Relative.Replace('\','/').Split('/')[0] -in @('uid.dat','session.lock','playerdata','stats','advancements'))
}

function Get-MaintenanceSharedManifest([string]$Manifest, [switch]$World) {
    if (!$World) { return $Manifest }
    # Derive the shared subset from the already hashed full destination. Do not read it twice.
    return (@($Manifest.Split("`n") | Where-Object {
        $_ -and !(Test-MaintenanceWorldLocal (($_ -split '\|', 3)[1]))
    }) -join "`n")
}

function Assert-MaintenanceTreeNoLinks([string]$Path) {
    Assert-MaintenanceNoLinks $Path
    foreach ($item in Get-ChildItem -LiteralPath $Path -Force -Recurse -ErrorAction Stop) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Link in artifact: $($item.FullName)" }
    }
}

function Get-MaintenanceManifest([string]$Path, [switch]$World) {
    Write-MaintenanceDiagnostic 'manifest.begin' @{path=$Path; world=[bool]$World}
    Assert-MaintenanceNoLinks $Path
    $result = [Collections.Generic.List[string]]::new()
    if (!(Test-Path -LiteralPath $Path)) { throw "Artifact disappeared: $Path" }
    $isDirectory = (Get-Item -LiteralPath $Path).PSIsContainer
    $items = if ($isDirectory) { @(Get-ChildItem -LiteralPath $Path -Force -Recurse) } else { @(Get-Item -LiteralPath $Path) }
    foreach ($item in $items) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Link in artifact: $($item.FullName)" }
        $relative = if ($isDirectory) { [IO.Path]::GetRelativePath($Path,$item.FullName) } else { '.' }
        if ($World -and (Test-MaintenanceWorldLocal $relative)) { continue }
        if ($item.PSIsContainer) { $result.Add("D|$relative") }
        else {
            Write-MaintenanceDiagnostic 'hash.begin' @{path=$item.FullName; bytes=$item.Length}
            $result.Add("F|$relative|$($item.Length)|$((Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash)")
            Write-MaintenanceDiagnostic 'hash.end' @{path=$item.FullName}
        }
    }
    Write-MaintenanceDiagnostic 'manifest.end' @{path=$Path; entries=$result.Count}
    return ($result | Sort-Object) -join "`n"
}

function Copy-MaintenanceArtifact([string]$Source, [string]$Destination, [switch]$World) {
    Write-MaintenanceDiagnostic 'copy.begin' @{source=$Source; destination=$Destination; world=[bool]$World}
    Assert-MaintenanceNoLinks $Source
    Assert-MaintenanceNoLinks $Destination
    if ((Get-Item -LiteralPath $Source).PSIsContainer) {
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        foreach ($child in Get-ChildItem -LiteralPath $Source -Force) {
            if ($World -and (Test-MaintenanceWorldLocal $child.Name)) { continue }
            Write-MaintenanceDiagnostic 'copy.entry.begin' @{source=$child.FullName; destination=(Join-Path $Destination $child.Name)}
            Copy-Item -LiteralPath $child.FullName -Destination (Join-Path $Destination $child.Name) -Recurse -Force
            Write-MaintenanceDiagnostic 'copy.entry.end' @{source=$child.FullName}
        }
    } else {
        Copy-Item -LiteralPath $Source -Destination $Destination
    }
    Write-MaintenanceDiagnostic 'copy.end' @{source=$Source; destination=$Destination}
}

function Assert-MaintenanceRunOutsideServers([string]$RunDirectory, $Plan) {
    foreach ($item in $Plan) {
        if ((Test-MaintenanceOverlap $RunDirectory $item.root) -or
            (Test-MaintenanceOverlap $RunDirectory $item.source)) { throw 'Run directory must be separate from servers and source artifacts.' }
    }
}

function Invoke-MaintenanceDeploy($Config, [string]$RunDirectory) {
    $maintenanceDiagnosticFile = New-MaintenanceDiagnosticLog $RunDirectory 'Deploy'
    Write-MaintenanceDiagnostic 'deploy.begin' @{role='Deploy'; powershell=$PSVersionTable.PSVersion.ToString()}
    $plan = @(Get-MaintenancePlan $Config)
    if (!$plan.Count) { throw 'No enabled distribution targets.' }
    Assert-MaintenanceRunOutsideServers $RunDirectory $plan
    $journalPath = Join-Path $RunDirectory 'deployment.json'
    if (Test-Path -LiteralPath $journalPath) { throw 'This run already has a deployment. Use a new run or Restore.' }
    $runId = [guid]::NewGuid().ToString('N')
    $journal = [ordered]@{ version=1; runId=$runId; status='Preparing'; operations=@() }
    foreach ($item in $plan) {
        $work = Resolve-MaintenanceChild $item.root ".astral-maintenance/$runId/$($item.index)"
        $journal.operations += [ordered]@{
            index=$item.index; kind=$item.kind; source=$item.source; destination=$item.destination; root=$item.root
            stage=(Join-Path $work 'stage'); backup=(Join-Path $work 'backup'); displaced=(Join-Path $work 'displaced')
            existed=(Test-Path -LiteralPath $item.destination); status='Pending'; manifest=''; originalManifest=''
        }
    }
    Write-MaintenanceJson $journal $journalPath
    try {
        # Capture every source before touching any destination. Cache repeated source mappings.
        $snapshots = @{}
        foreach ($op in $journal.operations) {
            if (!$snapshots.ContainsKey($op.source)) {
                Write-Host "CAPTURE [$($op.index + 1)/$($journal.operations.Count)] $($op.source)"
                Write-MaintenanceDiagnostic 'capture.begin' @{index=$op.index; kind=$op.kind; source=$op.source}
                $snapshot = Join-Path $RunDirectory "source-$($op.index)"
                $before = Get-MaintenanceManifest $op.source -World:($op.kind -eq 'World')
                Copy-MaintenanceArtifact $op.source $snapshot -World:($op.kind -eq 'World')
                $after = Get-MaintenanceManifest $op.source -World:($op.kind -eq 'World')
                if ($before -cne $after -or $before -cne (Get-MaintenanceManifest $snapshot)) { throw 'Source changed while capturing release; retry after stopping its writer.' }
                $snapshots[$op.source] = @{ path=$snapshot; manifest=$before }
                Write-MaintenanceDiagnostic 'capture.end' @{index=$op.index; source=$op.source}
            }
            $op.manifest = $snapshots[$op.source].manifest
        }
        foreach ($op in $journal.operations) {
            Write-Host "PREPARE [$($op.index + 1)/$($journal.operations.Count)] $($op.destination)"
            Write-MaintenanceDiagnostic 'prepare.begin' @{index=$op.index; destination=$op.destination; stage=$op.stage}
            if ($op.existed) { $op.originalManifest = Get-MaintenanceManifest $op.destination }
            if ($op.existed -and $op.manifest -ceq (Get-MaintenanceSharedManifest $op.originalManifest -World:($op.kind -eq 'World'))) {
                # Keep the existing artifact and its local world data in place. Recheck at apply.
                $op.status = 'Unchanged'
                Write-MaintenanceJson $journal $journalPath
                Write-MaintenanceDiagnostic 'prepare.unchanged' @{index=$op.index; destination=$op.destination}
                Write-Host "UNCHANGED [$($op.index + 1)/$($journal.operations.Count)] $($op.destination)"
                continue
            }
            Assert-MaintenanceNoLinks $op.stage
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($op.stage)) -Force | Out-Null
            Copy-MaintenanceArtifact $snapshots[$op.source].path $op.stage
            if ($op.manifest -cne (Get-MaintenanceManifest $op.stage)) { throw 'Staged artifact verification failed.' }
            if ($op.kind -eq 'World' -and $op.existed) {
                # Only link detection is needed here. Content is rehashed before the atomic swap.
                Assert-MaintenanceTreeNoLinks $op.destination
                foreach ($name in @('uid.dat','playerdata','stats','advancements')) {
                    $local = Join-Path $op.destination $name
                    if (Test-Path -LiteralPath $local) { Copy-MaintenanceArtifact $local (Join-Path $op.stage $name) }
                }
            }
            $op.status = 'Prepared'
            Write-MaintenanceJson $journal $journalPath
            Write-MaintenanceDiagnostic 'prepare.end' @{index=$op.index}
        }
        $journal.status = 'Applying'
        Write-MaintenanceJson $journal $journalPath
        foreach ($op in $journal.operations) {
            Write-MaintenanceDiagnostic 'apply.begin' @{index=$op.index; destination=$op.destination; backup=$op.backup}
            if ($op.status -eq 'Unchanged') {
                if ($op.manifest -cne (Get-MaintenanceManifest $op.destination -World:($op.kind -eq 'World'))) {
                    throw 'Unchanged destination contents changed before application.'
                }
                Write-MaintenanceDiagnostic 'apply.unchanged' @{index=$op.index; destination=$op.destination}
                continue
            }
            Assert-MaintenanceNoLinks $op.destination
            Assert-MaintenanceNoLinks $op.backup
            if ($op.manifest -cne (Get-MaintenanceManifest $op.stage -World:($op.kind -eq 'World'))) { throw 'Stage changed before application.' }
            if ((Test-Path -LiteralPath $op.destination) -ne $op.existed) { throw 'Destination changed after preflight.' }
            if ($op.existed -and $op.originalManifest -cne (Get-MaintenanceManifest $op.destination)) { throw 'Destination contents changed after preflight.' }
            $op.status = 'Applying'
            Write-MaintenanceJson $journal $journalPath
            if ($op.existed) {
                Write-MaintenanceDiagnostic 'backup.begin' @{index=$op.index}
                Move-Item -LiteralPath $op.destination -Destination $op.backup
                Write-MaintenanceDiagnostic 'backup.end' @{index=$op.index}
            }
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($op.destination)) -Force | Out-Null
            Move-Item -LiteralPath $op.stage -Destination $op.destination
            if ($op.manifest -cne (Get-MaintenanceManifest $op.destination -World:($op.kind -eq 'World'))) { throw 'Deployed artifact verification failed.' }
            $op.status = 'Applied'
            Write-MaintenanceJson $journal $journalPath
            Write-Host "APPLIED $($op.destination)"
            Write-MaintenanceDiagnostic 'apply.end' @{index=$op.index}
        }
        $journal.status = 'Deployed'
        Write-MaintenanceJson $journal $journalPath
        Write-MaintenanceDiagnostic 'deploy.completed'
    } catch {
        Write-MaintenanceDiagnostic 'deploy.failed' -Failure $_
        $journal.status = 'Failed'
        Write-MaintenanceJson $journal $journalPath
        throw
    } finally {
        Write-MaintenanceDiagnostic 'deploy.finally' @{status=$journal.status}
    }
}

function Restore-MaintenanceDeployment([string]$RunDirectory) {
    if (Test-Path -LiteralPath (Join-Path $RunDirectory 'migration-commit-started.json')) { throw 'Migration commit was started. File-only restore is blocked; inspect DB migration results.' }
    $path = Join-Path $RunDirectory 'deployment.json'
    $journal = Get-Content -Raw -Encoding utf8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
    if ($journal.version -ne 1 -or $journal.runId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid deployment journal.' }
    # Validate all recorded paths before moving anything; journal paths are never trusted blindly.
    foreach ($op in $journal.operations) {
        $root = Get-MaintenanceAbsolutePath $op.root
        $relative = [IO.Path]::GetRelativePath($root, $op.destination)
        if ((Resolve-MaintenanceChild $root $relative) -ne $op.destination) { throw 'Invalid destination journal path.' }
        if ($relative -match '(^|[\\/])\.astral-maintenance([\\/]|$)') { throw 'Destination overlaps maintenance backups.' }
        if ($op.index -isnot [long] -and $op.index -isnot [int]) { throw 'Invalid journal index.' }
        $work = Resolve-MaintenanceChild $root ".astral-maintenance/$($journal.runId)/$($op.index)"
        foreach ($name in @('stage','backup','displaced')) {
            if ($op[$name] -ne (Join-Path $work $name)) { throw 'Invalid backup journal path.' }
            Assert-MaintenanceNoLinks $op[$name]
        }
    }
    $operations = @($journal.operations)
    [array]::Reverse($operations)
    foreach ($op in $operations) {
        if ($op.status -notin @('Applying','Applied','Restoring')) { continue }
        $op.status = 'Restoring'
        Write-MaintenanceJson $journal $path
        if (Test-Path -LiteralPath $op.backup) {
            if ($op.originalManifest -cne (Get-MaintenanceManifest $op.backup)) { throw 'Backup content changed; refusing restoration.' }
            if (Test-Path -LiteralPath $op.destination) {
                if (Test-Path -LiteralPath $op.displaced) { throw 'Displaced copy already exists; inspect restore before retry.' }
                Move-Item -LiteralPath $op.destination -Destination $op.displaced
            }
            Move-Item -LiteralPath $op.backup -Destination $op.destination
        } elseif (!$op.existed -and (Test-Path -LiteralPath $op.destination)) {
            Move-Item -LiteralPath $op.destination -Destination $op.displaced
        } elseif ($op.existed) {
            if (!(Test-Path -LiteralPath $op.destination) -or
                $op.originalManifest -cne (Get-MaintenanceManifest $op.destination)) { throw 'Original backup is missing and destination is not restored.' }
        }
        $op.status = 'Restored'
        Write-MaintenanceJson $journal $path
    }
    $journal.status = 'Restored'
    Write-MaintenanceJson $journal $path
}
