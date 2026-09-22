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

function Resolve-MaintenanceDeploymentSelection {
    param([string]$RunDirectory, [ValidateSet('Ask','Include','Skip')][string]$WorldCopy = 'Ask',
        [ValidateSet('Ask','Include','Skip')][string]$NetworkPlugins = 'Ask',
        [switch]$PromptForNewRun, [scriptblock]$PromptAction,
        [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ConfigurationDigest)
    # Caller must hold workflow.lock (guided workflow) or run.lock (explicit Deploy).
    $WorldCopy = switch ($WorldCopy) { Include {'Include'} Skip {'Skip'} default {'Ask'} }
    $NetworkPlugins = switch ($NetworkPlugins) { Include {'Include'} Skip {'Skip'} default {'Ask'} }
    $path = Join-Path $RunDirectory 'deployment-selection.json'
    if (Test-Path -LiteralPath $path) {
        $record = Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
        if ($record.schemaVersion -ne 1 -or $record.worldCopy -cnotin @('Include','Skip') -or $record.networkPlugins -cnotin @('Include','Skip')) { throw 'Invalid deployment selection record.' }
        if ($ConfigurationDigest -and (!$record.ContainsKey('configurationDigest') -or $record.configurationDigest -cne $ConfigurationDigest.ToUpperInvariant())) {
            throw 'Configuration changed within the selected deployment run.'
        }
        $choices = @{worldCopy=$record.worldCopy;networkPlugins=$record.networkPlugins}
    } elseif ((Test-Path -LiteralPath (Join-Path $RunDirectory 'workflow-state.json')) -or
              (Test-Path -LiteralPath (Join-Path $RunDirectory 'deployment.json')) -or
              (Test-Path -LiteralPath (Join-Path $RunDirectory 'config-digest.json'))) {
        # Pre-feature runs always used all configured distributions. Never change them on resume.
        $choices = @{worldCopy='Include';networkPlugins='Include'}
    } else {
        $choices = @{}
        $requests = @{worldCopy=$WorldCopy;networkPlugins=$NetworkPlugins}
        $labels = @{worldCopy='Buildのワールド';networkPlugins='ネットワークプラグイン（Lobby・Proxy・Geyser拡張）'}
        foreach ($name in @('worldCopy','networkPlugins')) {
            $choices[$name] = if ($requests[$name] -eq 'Ask' -and !$PromptForNewRun) { 'Include' } else {
                Select-MaintenanceDistributionOption $labels[$name] $requests[$name] -PromptAction $PromptAction
            }
        }
    }
    if (($WorldCopy -ne 'Ask' -and $WorldCopy -cne $choices.worldCopy) -or
        ($NetworkPlugins -ne 'Ask' -and $NetworkPlugins -cne $choices.networkPlugins)) { throw 'Deployment selections cannot change within this run.' }
    if (!(Test-Path -LiteralPath $path)) {
        $record=@{schemaVersion=1;worldCopy=$choices.worldCopy;networkPlugins=$choices.networkPlugins}
        if ($ConfigurationDigest) { $record.configurationDigest=$ConfigurationDigest.ToUpperInvariant() }
        Write-MaintenanceJson $record $path
    }
    Write-Host "World copy: $($choices.worldCopy); Network plugins: $($choices.networkPlugins) (saved for this run)"
    Write-MaintenanceDiagnostic 'deployment.selection' $choices
    return $choices
}

function Get-MaintenanceSelectedConfig {
    param([hashtable]$Config, [ValidateSet('Include','Skip')][string]$WorldCopy,
        [ValidateSet('Include','Skip')][string]$NetworkPlugins)
    $selected = $Config.Clone()
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
        $source=if ([string]::IsNullOrWhiteSpace($network.sourceDirectory)) { Join-Path $PSScriptRoot '../network-plugin-build/output' } else { $network.sourceDirectory }
        $source=Get-MaintenanceAbsolutePath $source
        $serverRoot=[IO.Path]::GetDirectoryName($destination)
        if ($entry.artifact -eq 'AstralRecordGeyserExtension.jar') { $serverRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($serverRoot)) }
        foreach ($boundary in @($source,$serverRoot)) {
            if (Test-MaintenanceOverlap $RunRoot $boundary) { throw 'Run directory overlaps a network source/server root.' }
            if ($PassThru -and (Test-Path -LiteralPath $boundary -PathType Container)) { $boundary }
        }
    }
}
