function Select-MaintenanceDistributionOption {
    param([string]$Label, [ValidateSet('Ask','Include','Skip')][string]$Requested = 'Ask', [scriptblock]$PromptAction)
    if ($Requested -eq 'Include') { return 'Include' }
    if ($Requested -eq 'Skip') { return 'Skip' }
    if (!$PromptAction -and [Console]::IsInputRedirected) { throw 'Non-interactive execution requires explicit -WorldCopy and -NetworkPlugins selections (Include or Skip).' }
    do {
        Write-Host "${Label}: 1 = 配布する / 2 = 配布しない"
        $answer = if ($PromptAction) { & $PromptAction } else { Read-Host '1 または 2 を入力してください' }
        switch ([string]$answer) {
            '1' { return 'Include' }
            '2' { return 'Skip' }
        }
        Write-Host '1 または 2 を選択してください。'
    } while ($true)
}

function Read-MaintenanceConfiguration([string]$ConfigPath) {
    $bytes = [IO.File]::ReadAllBytes($ConfigPath)
    $reader = [IO.StreamReader]::new([IO.MemoryStream]::new($bytes, $false), [Text.Encoding]::UTF8, $true)
    try { $config = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable }
    finally { $reader.Dispose() }
    return @{
        config = $config
        digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    }
}

function Get-MaintenanceDistributionOptions([hashtable]$Config) {
    if ($Config.schemaVersion -ne 1) { throw 'Unsupported schemaVersion.' }
    $servers = @{}
    foreach ($server in $Config.servers) {
        if ($server.enabled -isnot [bool]) { throw 'Server enabled must be boolean.' }
        if ($server.id -notmatch '^[a-zA-Z0-9_-]+$' -or $servers.ContainsKey($server.id)) { throw 'Invalid or duplicate server ID.' }
        $servers[$server.id] = $server.enabled
    }
    $row = 0
    foreach ($entry in $Config.distributions) {
        $row++
        if ($entry.enabled -isnot [bool]) { throw 'Distribution enabled must be boolean.' }
        if (!$entry.enabled) { continue }
        $kind = [string](@('Jar','Placement','Filebase','World') -eq $entry.kind)
        if (!$kind) { throw "Unknown kind: $($entry.kind)" }
        if (!$servers[$entry.source]) { throw "Source server is missing/disabled: $($entry.source)" }
        if ([string]::IsNullOrWhiteSpace($entry.relativePath)) { throw 'Enabled distribution has no relative path.' }
        if (@($entry.targets).Count -eq 0) { throw 'Enabled distribution has no targets.' }
        $target = 0
        $seen = @{}
        foreach ($serverId in $entry.targets) {
            $target++
            if (!$servers.ContainsKey($serverId)) { throw "Unknown target server: $serverId" }
            if ($seen.ContainsKey($serverId)) { throw "Duplicate target server: $serverId" }
            $seen[$serverId] = $true
            if (!$servers[$serverId]) { continue }
            @{id="d$row-t$target"; kind=$kind; label=$entry.relativePath; source=$entry.source; destination=$serverId}
        }
    }
    $row = 0
    if ($Config['networkPlugins']) {
        foreach ($entry in $Config.networkPlugins.destinations) {
            $row++
            if ([string]::IsNullOrWhiteSpace($entry.deployDirectory)) { continue }
            if ($entry.artifact -notin @('AstralRecordLobby.jar','AstralRecordProxy.jar','AstralRecordGeyserExtension.jar')) { throw 'Unsupported network plugin artifact.' }
            @{id="n$row"; kind='Network'; label=$entry.artifact; source='ネットワークビルド出力'; destination=$entry.deployDirectory}
        }
    }
}

function ConvertTo-MaintenanceDistributionIds([string[]]$Ids) {
    if ($null -eq $Ids -or !$Ids.Count) { throw '配布対象を1件以上選択してください。' }
    $unique = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $Ids) {
        if ($id -cnotmatch '^(d[1-9][0-9]*-t[1-9][0-9]*|n[1-9][0-9]*)$' -or !$unique.Add($id)) { throw '配布対象IDが無効、または重複しています。' }
    }
    $unique | Sort-Object -CaseSensitive
}

function Select-MaintenanceDistributionDetails([hashtable]$Config, [scriptblock]$PromptAction) {
    $options = @(Get-MaintenanceDistributionOptions $Config)
    if (!$options.Count) { throw '設定済みの配布対象がありません。' }
    for ($i = 0; $i -lt $options.Count; $i++) {
        $option = $options[$i]
        Write-Host ('{0}. {1}  {2} → {3}' -f ($i+1), $option.label, $option.source, $option.destination)
    }
    while ($true) {
        $answer = if ($PromptAction) { & $PromptAction } else { Read-Host 'Enterで全件、番号をカンマ区切りで選択、0で中止' }
        if ([string]::IsNullOrWhiteSpace($answer)) { return $options.id }
        if ($answer.Trim() -eq '0') { throw '配布を中止しました。' }
        $ids = [Collections.Generic.List[string]]::new()
        foreach ($number in ($answer -split ',')) {
            $index = 0
            if (![int]::TryParse($number.Trim(), [ref]$index) -or $index -lt 1 -or $index -gt $options.Count) { break }
            $id = $options[$index-1].id
            if ($ids.Contains($id)) { break }
            $ids.Add($id)
        }
        if ($ids.Count -eq ($answer -split ',').Count) { return $ids.ToArray() }
        Write-Host '一覧にある番号を重複せずに指定してください。'
    }
}

function Get-MaintenanceDeploymentSelectionRecord {
    param([string]$RunDirectory, [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ConfigurationDigest)
    $path = Join-Path $RunDirectory 'deployment-selection.json'
    if (!(Test-Path -LiteralPath $path)) { return }
    Assert-MaintenanceNoLinks $path
    $record = Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
    if ($record.schemaVersion -notin @(1,2) -or $record.worldCopy -cnotin @('Include','Skip') -or $record.networkPlugins -cnotin @('Include','Skip')) { throw 'Invalid deployment selection record.' }
    if (($ConfigurationDigest -and $record['configurationDigest'] -cne $ConfigurationDigest.ToUpperInvariant()) -or
        ($record.schemaVersion -eq 2 -and $record['configurationDigest'] -cnotmatch '^[A-F0-9]{64}$')) {
        throw 'Configuration changed within the selected deployment run.'
    }
    $ids = $null
    if ($record.schemaVersion -eq 2) {
        if ($record['distributionIds'] -isnot [array] -or @($record.distributionIds | Where-Object { $_ -isnot [string] }).Count) { throw 'Invalid deployment selection record.' }
        $ids = @(ConvertTo-MaintenanceDistributionIds $record.distributionIds)
    } elseif ($record.ContainsKey('distributionIds')) { throw 'Invalid deployment selection record.' }
    return @{worldCopy=$record.worldCopy; networkPlugins=$record.networkPlugins; distributionIds=$ids}
}

function Resolve-MaintenanceDeploymentSelection {
    param([string]$RunDirectory, [ValidateSet('Ask','Include','Skip')][string]$WorldCopy = 'Ask',
        [ValidateSet('Ask','Include','Skip')][string]$NetworkPlugins = 'Ask',
        [switch]$PromptForNewRun, [scriptblock]$PromptAction,
        [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ConfigurationDigest,
        [hashtable]$Config, [string[]]$DistributionIds, [switch]$PromptForDetails, [switch]$ValidatePlan,
        [string]$ExpectedFingerprint)
    # Caller must hold workflow.lock (guided workflow) or run.lock (explicit Deploy).
    $WorldCopy = switch ($WorldCopy) { Include {'Include'} Skip {'Skip'} default {'Ask'} }
    $NetworkPlugins = switch ($NetworkPlugins) { Include {'Include'} Skip {'Skip'} default {'Ask'} }
    $path = Join-Path $RunDirectory 'deployment-selection.json'
    $choices = if ($ConfigurationDigest) { Get-MaintenanceDeploymentSelectionRecord $RunDirectory -ConfigurationDigest $ConfigurationDigest }
        else { Get-MaintenanceDeploymentSelectionRecord $RunDirectory }
    if ($ExpectedFingerprint -and !$choices) { throw 'The deployment selection record is missing.' }
    if ($null -ne $DistributionIds) { $DistributionIds = @(ConvertTo-MaintenanceDistributionIds $DistributionIds) }
    if (!$choices -and ((Test-Path -LiteralPath (Join-Path $RunDirectory 'workflow-state.json')) -or
              (Test-Path -LiteralPath (Join-Path $RunDirectory 'deployment.json')) -or
              (Test-Path -LiteralPath (Join-Path $RunDirectory 'config-digest.json')))) {
        # Pre-feature runs always used all configured distributions. Never change them on resume.
        $choices = @{worldCopy='Include';networkPlugins='Include';distributionIds=$null}
    }
    if (!$choices) {
        $choices = @{distributionIds=$DistributionIds}
        if ($null -eq $DistributionIds -and $PromptForDetails -and ($PromptAction -or ![Console]::IsInputRedirected)) {
            $choices.distributionIds = @(ConvertTo-MaintenanceDistributionIds @(Select-MaintenanceDistributionDetails $Config $PromptAction))
        }
        $requests = @{worldCopy=$WorldCopy;networkPlugins=$NetworkPlugins}
        $labels = @{worldCopy='Buildのワールド';networkPlugins='ネットワークプラグイン（Lobby・Proxy・Geyser拡張）'}
        foreach ($name in @('worldCopy','networkPlugins')) {
            $choices[$name] = if ($requests[$name] -eq 'Ask' -and (!$PromptForNewRun -or $null -ne $choices.distributionIds)) { 'Include' } else {
                Select-MaintenanceDistributionOption $labels[$name] $requests[$name] -PromptAction $PromptAction
            }
        }
    }
    if (($WorldCopy -ne 'Ask' -and $WorldCopy -cne $choices.worldCopy) -or
        ($NetworkPlugins -ne 'Ask' -and $NetworkPlugins -cne $choices.networkPlugins) -or
        ($null -ne $DistributionIds -and ($DistributionIds -join ',') -cne ($choices.distributionIds -join ','))) {
        throw 'Deployment selections cannot change within this run.'
    }
    if ($ExpectedFingerprint -and (Get-MaintenanceSelectionFingerprint $choices) -cne $ExpectedFingerprint) {
        throw 'Deployment selections changed within this run.'
    }
    if ($null -ne $choices.distributionIds -and !$ConfigurationDigest) { throw 'Detailed selection requires a configuration digest.' }
    if ($ValidatePlan -or $null -ne $choices.distributionIds) {
        $selected = Get-MaintenanceSelectedConfig $Config $choices.worldCopy $choices.networkPlugins -DistributionIds $choices.distributionIds
        if ($ValidatePlan) { $null = Get-MaintenanceNonemptyPlan $selected }
    }
    if (!(Test-Path -LiteralPath $path)) {
        $record=@{schemaVersion=1;worldCopy=$choices.worldCopy;networkPlugins=$choices.networkPlugins}
        if ($null -ne $choices.distributionIds) {
            $record.schemaVersion = 2
            $record.distributionIds = $choices.distributionIds
        }
        if ($ConfigurationDigest) { $record.configurationDigest=$ConfigurationDigest.ToUpperInvariant() }
        Write-MaintenanceJson $record $path
    }
    Write-Host "World copy: $($choices.worldCopy); Network plugins: $($choices.networkPlugins) (saved for this run)"
    if ($null -ne $choices.distributionIds) { Write-Host "選択した配布対象: $($choices.distributionIds -join ', ')" }
    Write-MaintenanceDiagnostic 'deployment.selection' $choices
    return $choices
}

function Get-MaintenanceSelectionFingerprint($Choices) {
    $value = $Choices.worldCopy + '|' + $Choices.networkPlugins
    if ($null -ne $Choices.distributionIds) { $value += '|' + ($Choices.distributionIds -join ',') }
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($value))).ToLowerInvariant()
}

function Get-MaintenanceNonemptyPlan($Config) {
    $plan = @(Get-MaintenancePlan $Config)
    if (!$plan.Count) { throw '配布対象がありません。配布設定と選択を確認してください。' }
    return $plan
}

function Get-MaintenanceSelectedConfig {
    param([hashtable]$Config, [ValidateSet('Include','Skip')][string]$WorldCopy,
        [ValidateSet('Include','Skip')][string]$NetworkPlugins, [string[]]$DistributionIds)
    $selected = $Config.Clone()
    if ($null -ne $DistributionIds) {
        $options = @(Get-MaintenanceDistributionOptions $Config)
        $byId = @{}
        foreach ($option in $options) { $byId[$option.id] = $option }
        $targets = @{}
        $networkRows = @{}
        foreach ($id in @(ConvertTo-MaintenanceDistributionIds $DistributionIds)) {
            $option = $byId[$id]
            if (!$option) { throw "配布対象IDが見つかりません: $id" }
            if (($option.kind -eq 'World' -and $WorldCopy -eq 'Skip') -or ($option.kind -eq 'Network' -and $NetworkPlugins -eq 'Skip')) {
                throw "配布対象IDがグループのスキップ指定と矛盾しています: $id"
            }
            if ($option.kind -eq 'Network') { $networkRows[[int]$id.Substring(1)] = $true }
            else { $targets[$id] = $true }
        }
        $distributions = @($Config.distributions)
        $selected.distributions = @(
            for ($i = 0; $i -lt $distributions.Count; $i++) {
                $entry = $distributions[$i]
                if (!$entry.enabled) { continue }
                $entryTargets = @($entry.targets)
                $chosenTargets = @(for ($j = 0; $j -lt $entryTargets.Count; $j++) {
                    if ($targets[('d{0}-t{1}' -f ($i+1), ($j+1))]) { $entryTargets[$j] }
                })
                if (!$chosenTargets.Count) { continue }
                $copy = $entry.Clone()
                $copy.targets = $chosenTargets
                $copy
            }
        )
        $selected.Remove('networkPlugins')
        if ($networkRows.Count) {
            $selected.networkPlugins = $Config.networkPlugins.Clone()
            $destinations = @($Config.networkPlugins.destinations)
            $selected.networkPlugins.destinations = @(for ($i = 0; $i -lt $destinations.Count; $i++) {
                if ($networkRows[$i+1]) { $destinations[$i] }
            })
        }
        return $selected
    }
    $selected.distributions = @($Config.distributions | ForEach-Object {
        $entry = $_.Clone()
        if ($entry.enabled -isnot [bool]) { throw 'Distribution enabled must be boolean.' }
        if ($WorldCopy -eq 'Skip' -and $entry.kind -eq 'World') { $entry.enabled = $false }
        $entry
    })
    if ($NetworkPlugins -eq 'Skip') { $selected.Remove('networkPlugins') }
    return $selected
}

function Assert-MaintenanceNetworkRunBoundary([hashtable]$Config, [string]$RunRoot, [switch]$PassThru) {
    # Check even skipped network locations without requiring their JARs/directories to exist.
    $RunRoot=Get-MaintenanceAbsolutePath $RunRoot
    if (!$Config.ContainsKey('networkPlugins') -or $null -eq $Config.networkPlugins) { return }
    $network=$Config.networkPlugins
    foreach ($entry in $network.destinations) {
        if ([string]::IsNullOrWhiteSpace($entry.deployDirectory)) { continue }
        $destination=Get-MaintenanceAbsolutePath $entry.deployDirectory
        $source=if ([string]::IsNullOrWhiteSpace($network['sourceDirectory'])) { Join-Path $PSScriptRoot '../12-build-network-plugins/output' } else { $network['sourceDirectory'] }
        $source=Get-MaintenanceAbsolutePath $source
        $serverRoot=[IO.Path]::GetDirectoryName($destination)
        if ($entry.artifact -eq 'AstralRecordGeyserExtension.jar') { $serverRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($serverRoot)) }
        foreach ($boundary in @($source,$serverRoot)) {
            if (Test-MaintenanceOverlap $RunRoot $boundary) { throw 'Run directory overlaps a network source/server root.' }
            if ($PassThru -and (Test-Path -LiteralPath $boundary -PathType Container)) { $boundary }
        }
    }
}
