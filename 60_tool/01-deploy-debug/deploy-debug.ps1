[CmdletBinding()]
param(
    [switch]$PluginOnly,
    [switch]$MasterDataOnly,
    [switch]$ReleaseManagementOnly,
    [switch]$PreflightOnly,
    [ValidateSet('All','Selected','Skip')][string]$TestMode,
    [string]$Tests,
    [string]$ConfigPath,
    [string]$WorkflowRunDirectory,
    [string]$WorkflowFingerprint
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir 'PluginTestSelection.ps1')
$testSelection = Resolve-PluginTestSelection -TestMode $TestMode -Tests $Tests -PluginOnly:$PluginOnly `
    -TestsSpecified:($PSBoundParameters.ContainsKey('Tests')) `
    -NoPlugin:($MasterDataOnly -or $ReleaseManagementOnly -or $PreflightOnly)
if ([string]::IsNullOrWhiteSpace($ConfigPath)) {
    $ConfigPath = Join-Path $scriptDir "deploy-debug.config.json"
}
$encodingNormalizerPath = Join-Path $scriptDir "normalize-source-encoding.ps1"
$script:iisResetCommand = $null

if ($PluginOnly -and $ReleaseManagementOnly) {
    throw "-PluginOnly and -ReleaseManagementOnly cannot be used together."
}
if ($MasterDataOnly -and ($PluginOnly -or $ReleaseManagementOnly)) {
    throw "-MasterDataOnly cannot be combined with other component-only modes."
}

if ($PreflightOnly -and -not $ReleaseManagementOnly) {
    throw "-PreflightOnly requires -ReleaseManagementOnly."
}
if ([bool]$WorkflowRunDirectory -ne [bool]$WorkflowFingerprint -or
    ($WorkflowRunDirectory -and ($ReleaseManagementOnly -or $PreflightOnly))) {
    throw 'Workflow progress parameters must be supplied together for a Dev deployment.'
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-DevDeploymentProgress {
    param([string]$Stage, [string]$Status)
    if (!$WorkflowRunDirectory) { return }
    $path=Join-Path $WorkflowRunDirectory 'dev-deployment-progress.json'
    $temporary="$path.tmp"
    @{schemaVersion=1;configurationFingerprint=$WorkflowFingerprint;stage=$Stage;status=$Status;
        buildIsolated=$script:devBuildIsolated;updatedAtUtc=[DateTime]::UtcNow.ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding UTF8
    Move-Item -LiteralPath $temporary -Destination $path -Force
}

function Test-DevBuildIsolation {
    param($Configuration)
    $outputs=@()
    foreach ($name in @('api','web','plugin')) {
        $component=$Configuration.$name
        if ($component.enabled) {
            foreach ($value in @($component.projectPath,$component.buildOutputPath)) {
                if (![IO.Path]::IsPathRooted($value) -or $value.StartsWith('\\')) { return $false }
                $full=[IO.Path]::GetFullPath($value).TrimEnd('\','/')
                $cursor=$full
                while ($cursor) {
                    if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $false }
                    $cursor=[IO.Path]::GetDirectoryName($cursor)
                }
                $outputs += $full
            }
        }
    }
    foreach ($name in @('api','web','plugin','fileDatabase')) {
        $component=$Configuration.$name
        if (!$component.enabled) { continue }
        $destination=[IO.Path]::GetFullPath($component.deployPath).TrimEnd('\','/')
        foreach ($output in $outputs) {
            if ($output.Equals($destination,[StringComparison]::OrdinalIgnoreCase) -or
                $output.StartsWith($destination+'\',[StringComparison]::OrdinalIgnoreCase) -or
                $destination.StartsWith($output+'\',[StringComparison]::OrdinalIgnoreCase)) { return $false }
        }
    }
    return $true
}

function Test-CommandExists {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Resolve-IisResetCommand {
    param($IisConfig)

    if ($null -eq $IisConfig -or -not $IisConfig.enabled) {
        return $null
    }

    if (-not [string]::IsNullOrWhiteSpace($IisConfig.executablePath)) {
        if (-not (Test-Path -LiteralPath $IisConfig.executablePath)) {
            throw "Configured iisreset executable was not found: $($IisConfig.executablePath)"
        }

        return $IisConfig.executablePath
    }

    $command = Get-Command "iisreset" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $wellKnownPaths = @(
        "C:\Windows\System32\iisreset.exe",
        "C:\Windows\SysWOW64\iisreset.exe"
    )

    foreach ($path in $wellKnownPaths) {
        if (Test-Path -LiteralPath $path) {
            return $path
        }
    }

    throw "iisreset.exe was not found. Disable IIS control in deploy-debug.config.json or set iis.executablePath."
}

function Assert-PathExists {
    param(
        [string]$Label,
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function Invoke-RobocopyMirror {
    param(
        [string]$Source,
        [string]$Destination,
        [string[]]$ExcludeDirectories = @(),
        [string[]]$ExcludeFiles = @()
    )

    Assert-PathExists -Label "Source path" -Path $Source

    if (-not (Test-Path -LiteralPath $Destination)) {
        New-Item -ItemType Directory -Path $Destination | Out-Null
    }

    $arguments = @(
        $Source,
        $Destination,
        "/MIR",
        "/R:2",
        "/W:2",
        "/NFL",
        "/NDL",
        "/NP",
        "/NJH",
        "/NJS"
    )

    if ($ExcludeDirectories.Count -gt 0) {
        $arguments += "/XD"
        $arguments += $ExcludeDirectories
    }

    if ($ExcludeFiles.Count -gt 0) {
        $arguments += "/XF"
        $arguments += $ExcludeFiles
    }

    & robocopy @arguments
    $exitCode = $LASTEXITCODE

    if ($exitCode -ge 8) {
        throw "robocopy failed with exit code $exitCode. Source=$Source Destination=$Destination"
    }
}

function Backup-DeployTarget {
    param($Component)

    if (-not $Component.backup.enabled) {
        return
    }

    $deployPath = $Component.deployPath
    $backupPath = Join-Path $deployPath $Component.backup.folderName

    Assert-PathExists -Label "Deploy path" -Path $deployPath

    if (-not (Test-Path -LiteralPath $backupPath)) {
        New-Item -ItemType Directory -Path $backupPath | Out-Null
    }

    $excludeFiles = @()
    $excludeFileProperty = $Component.backup.PSObject.Properties['excludeFilePatterns']
    if ($null -ne $excludeFileProperty -and $null -ne $excludeFileProperty.Value) {
        $excludeFiles += @($excludeFileProperty.Value)
    }

    Write-Step "Backing up $deployPath to $backupPath"
    Invoke-RobocopyMirror -Source $deployPath -Destination $backupPath -ExcludeDirectories @($backupPath) -ExcludeFiles $excludeFiles
}

function Assert-TokenFileReady {
    param($Component)

    $tokenFileName = [string]$Component.tokenFileName
    if ([string]::IsNullOrWhiteSpace($tokenFileName) -or [IO.Path]::IsPathRooted($tokenFileName) -or $tokenFileName.Contains("..")) {
        throw "API tokenFileName must be a non-empty relative file name."
    }

    $tokenPath = Join-Path $Component.deployPath $tokenFileName
    Assert-PathExists -Label "Discord token file" -Path $tokenPath
    $token = Get-Content -LiteralPath $tokenPath -Raw -Encoding UTF8 -ErrorAction Stop
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Discord token file is empty: $tokenFileName"
    }
}

function Get-RequiredPropertyValue {
    param(
        [object]$Object,
        [string]$PropertyName,
        [string]$Label
    )

    if ($null -eq $Object) {
        throw "$Label is missing."
    }

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "$Label is missing."
    }

    return $property.Value
}

function Read-JsonFile {
    param(
        [string]$Label,
        [string]$Path
    )

    Assert-PathExists -Label $Label -Path $Path

    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
    }
    catch {
        throw "$Label is not valid JSON: $Path"
    }
}

function ConvertTo-AbsoluteHttpsUri {
    param(
        [string]$Value,
        [string]$Label
    )

    [Uri]$uri = $null
    if ([string]::IsNullOrWhiteSpace($Value) -or
        -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne [Uri]::UriSchemeHttps) {
        throw "$Label must be an absolute HTTPS URL."
    }

    return $uri
}

function Assert-SkillTreeMigrationKeyReady {
    param([object]$ApiComponent)

    Write-Step "Validating skill tree migration credential"
    $apiSettingsPath = Join-Path $ApiComponent.deployPath "appsettings.json"
    $apiSettings = Read-JsonFile -Label "API production appsettings.json" -Path $apiSettingsPath
    $apiKeySection = Get-RequiredPropertyValue -Object $apiSettings -PropertyName "ApiKey" -Label "API ApiKey section"
    $apiKey = [string](Get-RequiredPropertyValue -Object $apiKeySection -PropertyName "Key" -Label "API ApiKey:Key")
    $runtimeSection = Get-RequiredPropertyValue -Object $apiSettings -PropertyName "SkillTreeRuntime" -Label "API SkillTreeRuntime section"
    $runtimeKey = [string](Get-RequiredPropertyValue -Object $runtimeSection -PropertyName "Key" -Label "API SkillTreeRuntime:Key")
    $migrationKey = [string](Get-RequiredPropertyValue -Object $runtimeSection -PropertyName "MigrationKey" -Label "API SkillTreeRuntime:MigrationKey")
    if ([string]::IsNullOrWhiteSpace($migrationKey)) {
        throw "API SkillTreeRuntime:MigrationKey must not be empty."
    }
    if ($migrationKey -ceq $runtimeKey -or $migrationKey -ceq $apiKey) {
        throw "API SkillTreeRuntime:MigrationKey must differ from the runtime key and shared API key."
    }

    Write-Step "Skill tree migration credential is ready"
}

function Assert-ReleaseManagementReady {
    param(
        [object]$ApiComponent,
        [object]$WebComponent
    )

    Write-Step "Validating release management production settings"
    Assert-TokenFileReady -Component $ApiComponent

    $expectedBaseUrl = [string](Get-RequiredPropertyValue -Object $WebComponent -PropertyName "expectedApiBaseUrl" -Label "web.expectedApiBaseUrl")
    $expectedBaseUri = ConvertTo-AbsoluteHttpsUri -Value $expectedBaseUrl -Label "web.expectedApiBaseUrl"

    $apiSettingsPath = Join-Path $ApiComponent.deployPath "appsettings.json"
    $webSettingsPath = Join-Path $WebComponent.deployPath "appsettings.json"
    $apiSettings = Read-JsonFile -Label "API production appsettings.json" -Path $apiSettingsPath
    $webSettings = Read-JsonFile -Label "WEB production appsettings.json" -Path $webSettingsPath

    $apiKeySection = Get-RequiredPropertyValue -Object $apiSettings -PropertyName "ApiKey" -Label "API ApiKey section"
    $apiKey = [string](Get-RequiredPropertyValue -Object $apiKeySection -PropertyName "Key" -Label "API ApiKey:Key")
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw "API ApiKey:Key must not be empty."
    }

    $webApiSection = Get-RequiredPropertyValue -Object $webSettings -PropertyName "AstralRecordApi" -Label "WEB AstralRecordApi section"
    $actualBaseUrl = [string](Get-RequiredPropertyValue -Object $webApiSection -PropertyName "BaseUrl" -Label "WEB AstralRecordApi:BaseUrl")
    $actualBaseUri = ConvertTo-AbsoluteHttpsUri -Value $actualBaseUrl -Label "WEB AstralRecordApi:BaseUrl"
    if (-not [string]::Equals(
            $actualBaseUri.AbsoluteUri.TrimEnd('/'),
            $expectedBaseUri.AbsoluteUri.TrimEnd('/'),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "WEB AstralRecordApi:BaseUrl does not match the configured production API URL. Expected=$($expectedBaseUri.AbsoluteUri.TrimEnd('/')) Actual=$($actualBaseUri.AbsoluteUri.TrimEnd('/'))"
    }

    $webApiKey = [string](Get-RequiredPropertyValue -Object $webApiSection -PropertyName "ApiKey" -Label "WEB AstralRecordApi:ApiKey")
    if ([string]::IsNullOrWhiteSpace($webApiKey)) {
        throw "WEB AstralRecordApi:ApiKey must not be empty."
    }

    if ($apiKey -cne $webApiKey) {
        throw "API ApiKey:Key and WEB AstralRecordApi:ApiKey do not match."
    }

    $releaseNotesSection = Get-RequiredPropertyValue -Object $webSettings -PropertyName "ReleaseNotes" -Label "WEB ReleaseNotes section"
    $syncOnStartup = Get-RequiredPropertyValue -Object $releaseNotesSection -PropertyName "SyncOnStartup" -Label "WEB ReleaseNotes:SyncOnStartup"
    if ($syncOnStartup -isnot [bool] -or -not $syncOnStartup) {
        throw "WEB ReleaseNotes:SyncOnStartup must be true."
    }

    Write-Step "Release management production settings are ready"
}

function Enter-AppOffline {
    param($Component, [string]$Name)

    $offlinePath = Join-Path $Component.deployPath "app_offline.htm"
    $content = @"
<html>
<head><title>$Name deploying</title></head>
<body>$Name is being deployed.</body>
</html>
"@

    Write-Step "Putting $Name offline"
    Set-Content -LiteralPath $offlinePath -Value $content -Encoding UTF8
    Start-Sleep -Seconds 5

    return $offlinePath
}

function Exit-AppOffline {
    param([string]$Path)

    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path)) {
        Remove-Item -LiteralPath $Path -Force
    }
}

function Publish-DotNetProject {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name project" -Path $Component.projectPath

    Write-Step "Publishing $Name"
    & dotnet publish $Component.projectPath --configuration Release --output $Component.buildOutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed for $Name."
    }
}

function Publish-ResourcePacks {
    param($Component)

    Write-Step "Building Minecraft resource packs for API"
    $buildScript = Join-Path $scriptDir '../../50_resourcepack/scripts/build-resourcepack.ps1'
    $output = Join-Path $Component.buildOutputPath 'resource-packs'
    $archiveNamePattern = '^([a-f0-9]{40}\.zip|[a-f0-9]{64}\.mcpack)$'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    & $buildScript -OutputDirectory $output

    $java = Join-Path $output 'AstralRecordResourcePack.zip'
    $bedrock = Join-Path $output 'AstralRecordResourcePack.mcpack'
    $archive = [IO.Compression.ZipFile]::OpenRead($bedrock)
    try {
        $reader = [IO.StreamReader]::new($archive.GetEntry('manifest.json').Open())
        try {
            $bedrockManifest = $reader.ReadToEnd() | ConvertFrom-Json
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }

    $sha1 = (Get-FileHash -LiteralPath $java -Algorithm SHA1).Hash.ToLowerInvariant()
    $sha256 = (Get-FileHash -LiteralPath $bedrock -Algorithm SHA256).Hash.ToLowerInvariant()
    $uuid = ([guid]::Parse($bedrockManifest.header.uuid)).ToString()
    $sunbirdPresentation = $null
    $mossZombiePresentation = $null
    $seleneaPresentation = $null
    $archmagePhoenixPresentation = $null
    $nyxeronPresentation = $null
    $itemTooltips = $null
    $skillTooltips = $null
    $weaponAttackProjectiles = $null
    $skillEffects = $null
    $combatRewardPlaque = $null
    $receptionDisplay = $null
    $challengeCountdown = $null
    $dungeonClearSound = $null
    $profileHud = $null
    $questTrackingHud = $null
    $actionRingCooldownHud = $null
    $locationHud = $null
    $fishingRodTip = $null
    $wideCombatMeter = $null
    $wornHelmetModels = $null
    $archive = [IO.Compression.ZipFile]::OpenRead($java)
    try {
        $panelEntry = $archive.GetEntry('action-ring-panel.sha256')
        if ($null -eq $panelEntry -or $panelEntry.Length -ne 64) { throw 'Missing action ring panel revision in Java pack.' }
        $reader = [IO.StreamReader]::new($panelEntry.Open())
        try { $panelRevision = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
        if ($panelRevision -cnotmatch '^[a-f0-9]{64}$') { throw 'Invalid action ring panel revision.' }
        $guiEntry = $archive.GetEntry('gui-theme-version.txt')
        if ($null -eq $guiEntry -or $guiEntry.Length -ne 1) { throw 'Missing GUI theme version in Java pack.' }
        $reader = [IO.StreamReader]::new($guiEntry.Open())
        try { $guiTheme = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
        if ($guiTheme -cne '1') { throw 'Unsupported GUI theme version.' }
        $soundsEntry = $archive.GetEntry('gameplay-sounds-version.txt')
        if ($null -eq $soundsEntry -or $soundsEntry.Length -ne 1) { throw 'Missing gameplay sounds version in Java pack.' }
        $reader = [IO.StreamReader]::new($soundsEntry.Open())
        try { $gameplaySounds = $reader.ReadToEnd() }
        finally { $reader.Dispose() }
        if ($gameplaySounds -cne '1') { throw 'Unsupported gameplay sounds version.' }
        $combatRewardPlaqueEntry = $archive.GetEntry('combat-reward-plaque-version.txt')
        if ($null -ne $combatRewardPlaqueEntry) {
            if ($combatRewardPlaqueEntry.Length -ne 1) { throw 'Invalid combat reward plaque version marker.' }
            $reader = [IO.StreamReader]::new($combatRewardPlaqueEntry.Open())
            try { $combatRewardPlaque = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($combatRewardPlaque -cne '1') { throw 'Unsupported combat reward plaque version.' }
            $plaqueAssets = @(
                'assets/astralrecord/font/combat_reward_plaque.json'
                'assets/astralrecord/textures/font/combat_reward_plaque_icons.png'
                'assets/astralrecord/textures/font/combat_reward_plaque_divider.png'
                foreach ($style in @('normal', 'rare', 'rare_glint')) {
                    foreach ($lineCount in 5..10) {
                        "assets/astralrecord/textures/font/combat_reward_plaque_${style}_$lineCount.png"
                    }
                }
            )
            foreach ($asset in $plaqueAssets) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing combat reward plaque asset in Java pack: $asset" }
            }
        }

        $countdownEntry = $archive.GetEntry('challenge-countdown-version.txt')
        $dungeonClearEntry = $archive.GetEntry('dungeon-clear-sound-version.txt')
        if ($null -ne $dungeonClearEntry) {
            if ($dungeonClearEntry.Length -ne 1) { throw 'Invalid dungeon clear sound version marker.' }
            $reader = [IO.StreamReader]::new($dungeonClearEntry.Open())
            try { $dungeonClearSound = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($dungeonClearSound -cne '1') { throw 'Unsupported dungeon clear sound version.' }
            $dungeonClearOgg = $archive.GetEntry('assets/astralrecord/sounds/dungeon/clear.ogg')
            if ($null -eq $dungeonClearOgg -or $dungeonClearOgg.Length -eq 0) {
                throw 'Missing dungeon clear sound in Java pack.'
            }
        }
        if ($null -ne $countdownEntry) {
            if ($countdownEntry.Length -ne 1) { throw 'Invalid countdown version marker.' }
            $reader = [IO.StreamReader]::new($countdownEntry.Open())
            try { $challengeCountdown = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($challengeCountdown -cne '1') { throw 'Unsupported countdown version.' }
            $countdownAssets = @('assets/astralrecord/font/challenge_countdown.json',
                'assets/astralrecord/font/challenge_countdown_name.json',
                'assets/astralrecord/textures/font/challenge_countdown_name.png')
            foreach ($kind in @('boss', 'dungeon')) {
                foreach ($seconds in 0..10) { $countdownAssets += "assets/astralrecord/textures/gui/challenge_countdown/${kind}_${seconds}.png" }
            }
            foreach ($cue in @('tick', 'three', 'two', 'one', 'start')) {
                $countdownAssets += "assets/astralrecord/sounds/challenge/countdown/$cue.ogg"
            }
            foreach ($asset in $countdownAssets) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing countdown asset: $asset" }
            }
        }

        $receptionEntry = $archive.GetEntry('reception-display-version.txt')
        if ($null -ne $receptionEntry) {
            if ($receptionEntry.Length -ne 1) { throw 'Invalid reception display version marker.' }
            $reader = [IO.StreamReader]::new($receptionEntry.Open())
            try { $receptionDisplay = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($receptionDisplay -cne '1') { throw 'Unsupported reception display version.' }
            foreach ($name in @('boss_reception_altar', 'dungeon_reception_gate')) {
                foreach ($asset in @(
                    "assets/astralrecord/items/$name.json"
                    "assets/astralrecord/models/world/$name.json"
                    "assets/astralrecord/textures/item/world/$name.png"
                )) {
                    $entry = $archive.GetEntry($asset)
                    if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing reception display asset in Java pack: $asset" }
                }
            }
        }

        $seleneaEntry = $archive.GetEntry('selenea-presentation-version.txt')
        if ($null -ne $seleneaEntry) {
            if ($seleneaEntry.Length -ne 1) { throw 'Invalid Selenea presentation version marker.' }
            $reader = [IO.StreamReader]::new($seleneaEntry.Open())
            try { $seleneaPresentation = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($seleneaPresentation -cne '1') { throw 'Unsupported Selenea presentation version.' }
        }

        $phoenixEntry = $archive.GetEntry('archmage-phoenix-presentation-version.txt')
        if ($null -ne $phoenixEntry) {
            if ($phoenixEntry.Length -ne 1) { throw 'Invalid Archmage Phoenix presentation version marker.' }
            $reader = [IO.StreamReader]::new($phoenixEntry.Open())
            try { $archmagePhoenixPresentation = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($archmagePhoenixPresentation -cne '1') { throw 'Unsupported Archmage Phoenix presentation version.' }
            foreach ($asset in @(
                'assets/astralrecord/items/archmage_phoenix.json'
                'assets/astralrecord/models/item/archmage_phoenix.json'
                'assets/astralrecord/models/item/archmage_phoenix_wing_up.json'
                'assets/astralrecord/models/item/archmage_phoenix_wing_down.json'
                'assets/astralrecord/textures/item/archmage_phoenix.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing Archmage Phoenix asset in Java pack: $asset" }
            }
        }

        $nyxeronEntry = $archive.GetEntry('nyxeron-presentation-version.txt')
        if ($null -ne $nyxeronEntry) {
            if ($nyxeronEntry.Length -ne 1) { throw 'Invalid Nyxeron presentation version marker.' }
            $reader = [IO.StreamReader]::new($nyxeronEntry.Open())
            try { $nyxeronPresentation = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($nyxeronPresentation -cne '2') { throw 'Unsupported Nyxeron presentation version.' }
            foreach ($asset in @(
                'assets/astralrecord/items/nyxeron_core.json'
                'assets/astralrecord/items/nyxeron_ring_outer.json'
                'assets/astralrecord/items/nyxeron_petal.json'
                'assets/astralrecord/items/nyxeron_disc.json'
                'assets/astralrecord/items/nyxeron_bullet.json'
                'assets/astralrecord/items/nyxeron_ring_low.json'
                'assets/astralrecord/items/nyxeron_beam_high.json'
                'assets/astralrecord/textures/item/nyxeron.png'
                'assets/astralrecord/textures/item/nyxeron_skill.png'
                'assets/astralrecord/textures/item/nyxeron_skill.png.mcmeta'
                'assets/astralrecord/sounds/boss/nyxeron/cue_nova.ogg'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing Nyxeron asset in Java pack: $asset" }
            }
        }

        $mossZombieEntry = $archive.GetEntry('midgard-moss-zombie-presentation-version.txt')
        if ($null -ne $mossZombieEntry) {
            if ($mossZombieEntry.Length -ne 1) { throw 'Invalid Moss Zombie version marker.' }
            $reader = [IO.StreamReader]::new($mossZombieEntry.Open())
            try { $mossZombiePresentation = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($mossZombiePresentation -cne '2') { throw 'Unsupported Moss Zombie presentation version.' }
            foreach ($asset in @(
                'assets/astralrecord/items/midgard_moss_zombie.json'
                'assets/astralrecord/models/item/midgard_moss_zombie.json'
                'assets/astralrecord/models/item/midgard_moss_zombie_walk_a.json'
                'assets/astralrecord/models/item/midgard_moss_zombie_walk_b.json'
                'assets/astralrecord/models/item/midgard_moss_zombie_raise.json'
                'assets/astralrecord/models/item/midgard_moss_zombie_slam.json'
                'assets/astralrecord/models/item/midgard_moss_zombie_roar.json'
                'assets/astralrecord/textures/item/midgard_moss_zombie.png'
                'assets/astralrecord/items/midgard_moss_zombie_warning_ring.json'
                'assets/astralrecord/items/midgard_moss_zombie_warning_spore.json'
                'assets/astralrecord/items/midgard_moss_zombie_warning_vine.json'
                'assets/astralrecord/items/midgard_moss_zombie_impact_spikes.json'
                'assets/astralrecord/items/midgard_moss_zombie_impact_spores.json'
                'assets/astralrecord/textures/item/midgard_moss_zombie_skill.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing Moss Zombie asset in Java pack: $asset" }
            }
        }
        $sunbirdEntry = $archive.GetEntry('sunbird-presentation-version.txt')
        if ($null -ne $sunbirdEntry) {
            if ($sunbirdEntry.Length -ne 1) { throw 'Invalid Sunbird presentation version marker.' }
            $reader = [IO.StreamReader]::new($sunbirdEntry.Open())
            try { $sunbirdPresentation = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($sunbirdPresentation -cnotin @('1', '2')) { throw 'Unsupported Sunbird presentation version.' }
            if ($sunbirdPresentation -ceq '2') {
                foreach ($asset in @(
                    'assets/astralrecord/items/sunbird_body_v2.json'
                    'assets/astralrecord/items/sunbird_wing_left_v2.json'
                    'assets/astralrecord/items/sunbird_wing_right_v2.json'
                    'assets/astralrecord/items/sunbird_tip_left_v2.json'
                    'assets/astralrecord/items/sunbird_tip_right_v2.json'
                    'assets/astralrecord/items/sunbird_warning_circle_v2.json'
                    'assets/astralrecord/items/sunbird_warning_line_v2.json'
                    'assets/astralrecord/items/sunbird_warning_boundary_v2.json'
                    'assets/astralrecord/items/sunbird_impact_v2.json'
                    'assets/astralrecord/items/sunbird_warning_safe_v2.json'
                    'assets/astralrecord/items/sunbird_impact_line_v2.json'
                    'assets/astralrecord/models/item/sunbird_body_v2.json'
                    'assets/astralrecord/models/item/sunbird_wing_left_v2.json'
                    'assets/astralrecord/models/item/sunbird_wing_right_v2.json'
                    'assets/astralrecord/models/item/sunbird_tip_left_v2.json'
                    'assets/astralrecord/models/item/sunbird_tip_right_v2.json'
                    'assets/astralrecord/models/item/sunbird_warning_circle_v2.json'
                    'assets/astralrecord/models/item/sunbird_warning_line_v2.json'
                    'assets/astralrecord/models/item/sunbird_warning_boundary_v2.json'
                    'assets/astralrecord/models/item/sunbird_impact_v2.json'
                    'assets/astralrecord/models/item/sunbird_warning_safe_v2.json'
                    'assets/astralrecord/models/item/sunbird_impact_line_v2.json'
                    'assets/astralrecord/textures/item/sunbird_v2.png'
                    'assets/astralrecord/textures/item/sunbird_skill_v2.png'
                    'assets/astralrecord/textures/item/sunbird_skill_v2.png.mcmeta'
                )) {
                    $entry = $archive.GetEntry($asset)
                    if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing Sunbird V2 asset in Java pack: $asset" }
                }
            }
        }
        $questEntry = $archive.GetEntry('quest-tracking-hud-version.txt')
        if ($null -ne $questEntry) {
            if ($questEntry.Length -ne 1) { throw 'Invalid quest tracking HUD version marker.' }
            $reader = [IO.StreamReader]::new($questEntry.Open())
            try { $questTrackingHud = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($questTrackingHud -cnotin @('1', '2', '3', '4')) { throw 'Unsupported quest tracking HUD version.' }
            $questShaders = @(
                'assets/minecraft/shaders/core/rendertype_text.vsh'
                'quest_hud_84/assets/minecraft/shaders/core/rendertype_text.vsh'
                'quest_hud_88/assets/minecraft/shaders/core/text.vsh'
                'quest_hud_97/assets/minecraft/shaders/core/text.vsh'
            )
            $questAssets = @(
                'pack.mcmeta'
                'assets/astralrecord/font/quest_hud.json'
                'assets/astralrecord/font/quest_hud_space.json'
                'assets/astralrecord/textures/font/profile_hud_class_name_compact.png'
                $questShaders
            )
            if ($questTrackingHud -cin @('2', '3', '4')) {
                $questAssets += 'assets/astralrecord/textures/font/quest_hud_pixel.png'
            }
            if ($questTrackingHud -ceq '4') {
                $pixelFont = $archive.GetEntry('assets/astralrecord/font/quest_hud_pixel_text.json')
                if ($null -eq $pixelFont) { throw 'Missing pixel text font in Java quest HUD pack.' }
                $reader = [IO.StreamReader]::new($pixelFont.Open())
                try { $pixelProviders = ($reader.ReadToEnd() | ConvertFrom-Json -AsHashtable)['providers'] }
                finally { $reader.Dispose() }
                foreach ($sheet in @('minecraft:font/ascii.png', 'astralrecord:font/quest_hud_pixel_text.png')) {
                    if (-not ($pixelProviders | Where-Object { $_['type'] -ceq 'bitmap' -and $_['file'] -ceq $sheet })) {
                        throw "Missing pixel text font provider in Java quest HUD pack: $sheet"
                    }
                }
                $questAssets += 'assets/astralrecord/textures/font/quest_hud_pixel_text.png'
            }
            $questFont = $archive.GetEntry('assets/astralrecord/font/quest_hud.json')
            if ($null -ne $questFont) {
                $reader = [IO.StreamReader]::new($questFont.Open())
                try { $questFontData = $reader.ReadToEnd() | ConvertFrom-Json -AsHashtable }
                finally { $reader.Dispose() }
                $hdBitmap = @($questFontData['providers'] | Where-Object {
                    $_['type'] -ceq 'bitmap' -and $_['file'] -ceq 'astralrecord:font/quest_hud_text_hd.png'
                })
                if ($hdBitmap.Count -gt 0) {
                    $questAssets += 'assets/astralrecord/textures/font/quest_hud_text_hd.png'
                }
            }
            foreach ($asset in $questAssets) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing quest tracking HUD asset in Java pack: $asset" }
            }
            $reader = [IO.StreamReader]::new($archive.GetEntry('pack.mcmeta').Open())
            try { $questMetadata = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            foreach ($overlaySpec in @(
                @{ directory = 'quest_hud_84'; min = '84,0'; max = '87,99' }
                @{ directory = 'quest_hud_88'; min = '88,0'; max = '97,0' }
                @{ directory = 'quest_hud_97'; min = '97,1'; max = '97,1' }
            )) {
                $matches = @($questMetadata.overlays.entries | Where-Object {
                    $_.directory -ceq $overlaySpec.directory -and
                    ($_.min_format -join ',') -ceq $overlaySpec.min -and
                    ($_.max_format -join ',') -ceq $overlaySpec.max
                })
                if ($matches.Count -ne 1) { throw "Missing quest tracking HUD shader overlay: $($overlaySpec.directory)" }
            }
        }
        $ringHudEntry = $archive.GetEntry('action-ring-cooldown-hud-version.txt')
        if ($null -ne $ringHudEntry) {
            $reader = [IO.StreamReader]::new($ringHudEntry.Open())
            try { $actionRingCooldownHud = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($actionRingCooldownHud -cne '1') { throw 'Invalid action ring cooldown HUD version marker.' }
            foreach ($asset in @(
                'assets/astralrecord/font/action_ring_cooldown.json'
                'assets/astralrecord/textures/font/action_ring_cooldown.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing action ring cooldown HUD asset: $asset" }
            }
            foreach ($asset in @(
                'assets/minecraft/shaders/core/rendertype_text.vsh'
                'quest_hud_84/assets/minecraft/shaders/core/rendertype_text.vsh'
                'quest_hud_88/assets/minecraft/shaders/core/text.vsh'
                'quest_hud_97/assets/minecraft/shaders/core/text.vsh'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry) { throw "Missing action ring cooldown HUD shader: $asset" }
                $reader = [IO.StreamReader]::new($entry.Open())
                try { $shader = $reader.ReadToEnd() }
                finally { $reader.Dispose() }
                if (!$shader.Contains('// BEGIN ASTRALRECORD ACTION RING COOLDOWN HUD')) {
                    throw "Missing action ring cooldown HUD shader block: $asset"
                }
            }
        }
        $locationEntry = $archive.GetEntry('location-hud-version.txt')
        if ($null -ne $locationEntry) {
            if ($locationEntry.Length -ne 1) { throw 'Invalid Location HUD version marker.' }
            $reader = [IO.StreamReader]::new($locationEntry.Open())
            try { $locationHud = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($locationHud -cnotin @('1', '2', '3', '4', '5') -or $questTrackingHud -cnotin @('2', '3', '4')) {
                throw 'Location HUD requires version 1, 2, 3, 4 or 5 and Quest HUD assets version 2 or 3.'
            }
            $reader = [IO.StreamReader]::new($archive.GetEntry('assets/astralrecord/font/quest_hud_space.json').Open())
            try { $spaceFont = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            $spaceProvider = $spaceFont.providers | Where-Object { $_.type -ceq 'space' } | Select-Object -First 1
            $advances = if ($null -ne $spaceProvider) { $spaceProvider.PSObject.Properties['advances'] }
            $iconAdvance = if ($null -ne $advances -and $null -ne $advances.Value) {
                $advances.Value.PSObject.Properties[[string][char]0xE00B]
            }
            if ($null -eq $iconAdvance -or $iconAdvance.Value -isnot [ValueType] -or $iconAdvance.Value -is [bool] -or $iconAdvance.Value -ne -11) {
                throw 'Location HUD requires U+E00B with advance -11 in the Quest HUD spacing font.'
            }
            $locationAssets = @(
                'assets/astralrecord/font/location_hud_icons.json'
                'assets/astralrecord/textures/font/buff_hud.png'
            )
            if ($locationHud -cin @('2', '3', '4', '5')) {
                $locationAssets += @(
                    'assets/astralrecord/font/location_hud_text.json'
                    'assets/astralrecord/textures/font/location_hud_text.png'
                    'licenses/OFL-NotoSansJP.txt'
                )
                foreach ($shader in $questShaders) {
                    $reader = [IO.StreamReader]::new($archive.GetEntry($shader).Open())
                    try { $shaderSource = $reader.ReadToEnd() }
                    finally { $reader.Dispose() }
                    if (-not $shaderSource.Contains("// BEGIN ASTRALRECORD LOCATION HUD V$locationHud")) {
                        throw "Missing Location HUD version $locationHud relocation in Java pack shader: $shader"
                    }
                }
            }
            if ($locationHud -cin @('3', '4', '5')) {
                $locationAssets += 'location-hud-atlas.sha256'
            }
            foreach ($asset in $locationAssets) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing Location HUD asset in Java pack: $asset" }
            }
            if ($locationHud -cin @('3', '4', '5')) {
                $reader = [IO.StreamReader]::new($archive.GetEntry('location-hud-atlas.sha256').Open())
                try { $locationAtlasRevision = $reader.ReadToEnd() }
                finally { $reader.Dispose() }
                $atlasStream = $archive.GetEntry('assets/astralrecord/textures/font/location_hud_text.png').Open()
                try { $locationAtlasHash = (Get-FileHash -InputStream $atlasStream -Algorithm SHA256).Hash.ToLowerInvariant() }
                finally { $atlasStream.Dispose() }
                if ($locationAtlasHash -cne $locationAtlasRevision) {
                    throw 'Location HUD atlas SHA-256 differs in Java pack.'
                }
            }
        }
        $helmetEntry = $archive.GetEntry('worn-helmet-models.json')
        if ($null -ne $helmetEntry) {
            $reader = [IO.StreamReader]::new($helmetEntry.Open())
            try { $wornHelmetModels = ConvertFrom-Json -InputObject ($reader.ReadToEnd()) -NoEnumerate }
            finally { $reader.Dispose() }
            if ($wornHelmetModels -isnot [array] -or $wornHelmetModels.Count -eq 0) {
                throw 'Java worn helmet model list must be a non-empty JSON array.'
            }
            $previousModel = 0
            foreach ($model in $wornHelmetModels) {
                if (($model -isnot [int] -and $model -isnot [long]) -or
                    $model -le $previousModel -or $model -gt 16777215) {
                    throw 'Java worn helmet model numbers must be unique, ascending positive integers up to 16777215.'
                }
                $previousModel = $model
            }
        }
        $profileEntry = $archive.GetEntry('player-progress-hud-version.txt')
        if ($null -ne $profileEntry) {
            if ($profileEntry.Length -ne 1) { throw 'Invalid profile HUD version marker.' }
            $reader = [IO.StreamReader]::new($profileEntry.Open())
            try { $profileHud = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($profileHud -cnotin @('1', '2', '3', '4', '5', '6')) { throw 'Unsupported profile HUD version.' }
            $profileAssets = @(
                'assets/astralrecord/font/profile_hud_player.json'
                'assets/astralrecord/font/profile_hud_class.json'
            )
            if ($profileHud -cin @('2', '3', '4', '5', '6')) {
                $profileAssets += @(
                    'assets/astralrecord/font/gameplay_hud.json'
                    'assets/astralrecord/textures/font/gameplay_hud.png'
                )
            } else {
                $profileAssets += 'assets/astralrecord/textures/font/profile_hud.png'
            }
            if ($profileHud -cin @('3', '4', '5', '6')) {
                $profileAssets += @(
                    'assets/astralrecord/font/gameplay_hud_details.json'
                    'assets/astralrecord/font/profile_hud_points.json'
                )
            }
            if ($profileHud -cin @('4', '5', '6')) {
                $profileAssets += @(
                    'assets/astralrecord/font/profile_hud_class_name.json'
                    'assets/astralrecord/textures/font/profile_hud_class_name.png'
                    'assets/astralrecord/font/condition_hud.json'
                    'assets/astralrecord/textures/font/condition_hud.png'
                    'licenses/OFL-DotGothic16.txt'
                )
            }
            if ($profileHud -cin @('5', '6')) {
                $profileAssets += @(
                    'assets/astralrecord/font/buff_hud.json'
                    'assets/astralrecord/textures/font/buff_hud.png'
                )
            }
            if ($profileHud -ceq '6') {
                $profileAssets += @(
                    'assets/astralrecord/font/profile_hud_class_name_compact.json'
                    'assets/astralrecord/textures/font/profile_hud_class_name_compact.png'
                )
            }
            foreach ($asset in $profileAssets) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing profile HUD asset in Java pack: $asset" }
            }
        }
        $rodTipEntry = $archive.GetEntry('fishing-rod-tip-version.txt')
        if ($null -ne $rodTipEntry) {
            if ($rodTipEntry.Length -ne 1) { throw 'Invalid Java fishing rod tip version marker.' }
            $reader = [IO.StreamReader]::new($rodTipEntry.Open())
            try { $fishingRodTip = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($fishingRodTip -cne '1') { throw 'Unsupported Java fishing rod tip version.' }
        }
        $meterEntry = $archive.GetEntry('wide-combat-meter-version.txt')
        if ($null -ne $meterEntry) {
            if ($profileHud -cnotin @('2', '3', '4', '5', '6') -or $meterEntry.Length -ne 1) {
                throw 'Invalid wide combat meter version marker.'
            }
            $reader = [IO.StreamReader]::new($meterEntry.Open())
            try { $wideCombatMeter = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($wideCombatMeter -cne '1') { throw 'Unsupported wide combat meter version.' }
        }
        $tooltipsEntry = $archive.GetEntry('item-tooltips-version.txt')
        if ($null -ne $tooltipsEntry) {
            if ($tooltipsEntry.Length -ne 1) { throw 'Invalid item tooltips version marker.' }
            $reader = [IO.StreamReader]::new($tooltipsEntry.Open())
            try { $itemTooltips = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($itemTooltips -cne '1') { throw 'Unsupported item tooltips version.' }
            foreach ($asset in @(
                'assets/astralrecord/font/tooltip_icons.json'
                'assets/astralrecord/textures/font/tooltip_icons.png'
                'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_background.png'
                'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_background.png.mcmeta'
                'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_frame.png'
                'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_frame.png.mcmeta'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing item tooltip asset in Java pack: $asset" }
            }
        }
        $skillTooltipsEntry = $archive.GetEntry('skill-tooltips-version.txt')
        if ($null -ne $skillTooltipsEntry) {
            if ($itemTooltips -cne '1' -or $skillTooltipsEntry.Length -ne 1) {
                throw 'Invalid skill tooltips version marker.'
            }
            $reader = [IO.StreamReader]::new($skillTooltipsEntry.Open())
            try { $skillTooltips = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($skillTooltips -cne '1') { throw 'Unsupported skill tooltips version.' }
            foreach ($asset in @(
                'assets/astralrecord/font/skill_tooltip.json'
                'assets/astralrecord/textures/item/action_ring_icons.png'
                'assets/astralrecord/textures/item/custom/skill_gem_raw.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing skill tooltip asset in Java pack: $asset" }
            }
        }
        $skillEffectsEntry = $archive.GetEntry('skill-effects-version.txt')
        if ($null -ne $skillEffectsEntry) {
            if ($skillEffectsEntry.Length -ne 1) { throw 'Invalid skill effects version marker.' }
            $reader = [IO.StreamReader]::new($skillEffectsEntry.Open())
            try { $skillEffects = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($skillEffects -cne '1') { throw 'Unsupported skill effects version.' }
            foreach ($asset in @(
                'assets/astralrecord/lang/en_us.json'
                'assets/astralrecord/font/skill_effect.json'
                'assets/astralrecord/textures/font/skill_effect.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -le 0) { throw "Missing Java skill effect asset: $asset" }
            }
        }
        $projectilesEntry = $archive.GetEntry('weapon-attack-projectiles-version.txt')
        if ($null -ne $projectilesEntry) {
            if ($projectilesEntry.Length -ne 1) { throw 'Invalid weapon attack projectiles version marker.' }
            $reader = [IO.StreamReader]::new($projectilesEntry.Open())
            try { $weaponAttackProjectiles = $reader.ReadToEnd() }
            finally { $reader.Dispose() }
            if ($weaponAttackProjectiles -cne '1') { throw 'Unsupported weapon attack projectiles version.' }
            foreach ($asset in @(
                'assets/astralrecord/lang/en_us.json'
                'assets/astralrecord/font/weapon_attack.json'
                'assets/astralrecord/textures/font/weapon_attack.png'
            )) {
                $entry = $archive.GetEntry($asset)
                if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing weapon attack projectile asset in Java pack: $asset" }
            }
            $reader = [IO.StreamReader]::new($archive.GetEntry('assets/astralrecord/lang/en_us.json').Open())
            try { $language = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            if ($language.'astralrecord.weapon_attack.projectile' -cne '%s') {
                throw 'Missing weapon attack projectile translation in Java pack.'
            }
            $reader = [IO.StreamReader]::new($archive.GetEntry('assets/astralrecord/font/weapon_attack.json').Open())
            try { $font = $reader.ReadToEnd() | ConvertFrom-Json }
            finally { $reader.Dispose() }
            $atlas = @($font.providers | Where-Object {
                $_.type -ceq 'bitmap' -and $_.file -ceq 'astralrecord:font/weapon_attack.png'
            })
            $expectedGlyphs = (0xe100..0xe14d | ForEach-Object { [char] $_ }) -join ''
            if ($atlas.Count -ne 1 -or @($atlas[0].chars).Count -ne 13 -or
                @($atlas[0].chars | Where-Object { $_.Length -ne 6 }).Count -ne 0 -or
                ($atlas[0].chars -join '') -cne $expectedGlyphs) {
                throw 'Invalid weapon attack projectile glyphs in Java pack.'
            }
            $reader = [IO.BinaryReader]::new($archive.GetEntry('assets/astralrecord/textures/font/weapon_attack.png').Open())
            try { $header = $reader.ReadBytes(24) }
            finally { $reader.Dispose() }
            # PNG signature, IHDR chunk, and 6 by 13 cells of 32 px.
            if ([BitConverter]::ToString($header) -cne '89-50-4E-47-0D-0A-1A-0A-00-00-00-0D-49-48-44-52-00-00-00-C0-00-00-01-A0') {
                throw 'Invalid weapon attack projectile atlas dimensions in Java pack.'
            }
        }
    }
    finally {
        $archive.Dispose()
    }
    $javaName = "$sha1.zip"
    $bedrockName = "$sha256.mcpack"
    Move-Item -LiteralPath $java -Destination (Join-Path $output $javaName) -Force
    Move-Item -LiteralPath $bedrock -Destination (Join-Path $output $bedrockName) -Force

    $javaManifest = [ordered]@{
        sha1 = $sha1
        actionRingPanel = $panelRevision
        guiTheme = $guiTheme
        gameplaySounds = $gameplaySounds
    }
    if ($null -ne $combatRewardPlaque) {
        $javaManifest.combatRewardPlaque = $combatRewardPlaque
    }
    if ($null -ne $challengeCountdown) { $javaManifest.challengeCountdown = $challengeCountdown }
    if ($null -ne $dungeonClearSound) { $javaManifest.dungeonClearSound = $dungeonClearSound }
    if ($null -ne $receptionDisplay) {
        $javaManifest.receptionDisplay = $receptionDisplay
    }
    if ($null -ne $seleneaPresentation) {
        $javaManifest.seleneaPresentation = $seleneaPresentation
    }
    if ($null -ne $archmagePhoenixPresentation) {
        $javaManifest.archmagePhoenixPresentation = $archmagePhoenixPresentation
    }
    if ($null -ne $nyxeronPresentation) {
        $javaManifest.nyxeronPresentation = $nyxeronPresentation
    }
    if ($null -ne $mossZombiePresentation) {
        $javaManifest.mossZombiePresentation = $mossZombiePresentation
    }
    if ($null -ne $sunbirdPresentation) {
        $javaManifest.sunbirdPresentation = $sunbirdPresentation
    }
    if ($null -ne $itemTooltips) {
        $javaManifest.itemTooltips = $itemTooltips
    }
    if ($null -ne $skillTooltips) {
        $javaManifest.skillTooltips = $skillTooltips
    }
    if ($null -ne $weaponAttackProjectiles) {
        $javaManifest.weaponAttackProjectiles = $weaponAttackProjectiles
    }
    if ($null -ne $skillEffects) {
        $javaManifest.skillEffects = $skillEffects
    }
    if ($null -ne $questTrackingHud) {
        $javaManifest.questTrackingHud = $questTrackingHud
    }
    if ($null -ne $locationHud) {
        $javaManifest.locationHud = $locationHud
    }
    if ($null -ne $actionRingCooldownHud) {
        $javaManifest.actionRingCooldownHud = $actionRingCooldownHud
    }
    if ($null -ne $fishingRodTip) {
        $javaManifest.fishingRodTip = $fishingRodTip
    }
    if ($null -ne $profileHud) {
        $javaManifest.profileHud = $profileHud
    }
    if ($null -ne $wideCombatMeter) {
        $javaManifest.wideCombatMeter = $wideCombatMeter
    }
    if ($null -ne $wornHelmetModels) {
        $javaManifest.wornHelmetModels = $wornHelmetModels
    }
    [ordered]@{
        java = $javaManifest
        bedrock = [ordered]@{
            sha256 = $sha256
            uuid = $uuid
        }
    } | ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $output 'manifest.json') -Encoding UTF8

    Get-ChildItem -LiteralPath $output -File |
        Where-Object { $_.Name -cmatch $archiveNamePattern -and $_.Name -cnotin @($javaName, $bedrockName) } |
        Remove-Item -Force

    # Keep the previous hash available for clients already fetching a pack during deployment.
    $previousOutput = Join-Path $Component.deployPath 'resource-packs'
    $previousManifestPath = Join-Path $previousOutput 'manifest.json'
    if (Test-Path -LiteralPath $previousManifestPath -PathType Leaf) {
        $previousManifest = Read-JsonFile -Label 'Deployed resource pack manifest' -Path $previousManifestPath
        $previousNames = @("$($previousManifest.java.sha1).zip", "$($previousManifest.bedrock.sha256).mcpack")
        $previousArchives = Get-ChildItem -LiteralPath $previousOutput -File |
            Where-Object { $_.Name -cmatch $archiveNamePattern -and $_.Name -cnotin @($javaName, $bedrockName) }
        foreach ($extension in @('.zip', '.mcpack')) {
            $previousArchives | Where-Object Extension -CEQ $extension |
                Sort-Object -Property @{ Expression = { $_.Name -cin $previousNames }; Descending = $true }, Name |
                Select-Object -First 1 | Copy-Item -Destination $output
        }
    }
}

function Invoke-DatabaseMigrations {
    param($MigrationConfig)

    if ($null -eq $MigrationConfig -or -not $MigrationConfig.enabled) {
        return
    }

    Assert-PathExists -Label "Database migration project" -Path $MigrationConfig.projectPath
    Assert-PathExists -Label "Database migration config" -Path $MigrationConfig.configPath

    Write-Step "Applying and validating database migrations"
    & dotnet run --project $MigrationConfig.projectPath -- --config $MigrationConfig.configPath
    if ($LASTEXITCODE -ne 0) {
        throw "Database migration failed. API deployment was not started."
    }
}

function Invoke-ManagementDatabaseMigrations {
    param($MigrationConfig)

    if ($null -eq $MigrationConfig -or -not $MigrationConfig.enabled) {
        throw "ManagementDB migration must be enabled when API deployment is enabled."
    }

    Assert-PathExists -Label "ManagementDB migration project" -Path $MigrationConfig.projectPath
    Assert-PathExists -Label "ManagementDB migration config" -Path $MigrationConfig.configPath

    Write-Step "Applying and validating ManagementDB migrations"
    & dotnet run --project $MigrationConfig.projectPath -- --config $MigrationConfig.configPath
    if ($LASTEXITCODE -ne 0) {
        throw "ManagementDB migration failed. API and WEB deployment were not started."
    }
}

function Invoke-HistoryDatabaseMigrations {
    param($MigrationConfig)

    if ($null -eq $MigrationConfig -or -not $MigrationConfig.enabled) {
        throw "HistoryDB migration must be enabled when API deployment is enabled."
    }

    Assert-PathExists -Label "HistoryDB migration project" -Path $MigrationConfig.projectPath
    Assert-PathExists -Label "HistoryDB migration config" -Path $MigrationConfig.configPath

    Write-Step "Applying and validating HistoryDB migrations"
    & dotnet run --project $MigrationConfig.projectPath -- --config $MigrationConfig.configPath
    if ($LASTEXITCODE -ne 0) {
        throw "HistoryDB migration failed. API and WEB deployment were not started."
    }
}

<#
    Plugin の POM とソースを専用の一時フォルダへ複製してビルドし、配置用の設定を返す。
    元のチェックアウトの target/dist を共有せず、並行する Maven 実行の clean と競合させない。
    失敗した作業フォルダは調査用に保持する。
#>
function Build-Plugin {
    param(
        $Component,
        $TestSelection
    )

    Assert-PathExists -Label "Plugin project path" -Path $Component.projectPath
    Assert-PathExists -Label "Encoding normalizer script" -Path $encodingNormalizerPath
    $projectRoot = (Resolve-Path -LiteralPath $Component.projectPath).Path.TrimEnd('\','/')
    $outputRoot = [IO.Path]::GetFullPath($Component.buildOutputPath)
    if (!$outputRoot.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Plugin build output must be inside the plugin project.'
    }
    Assert-PathExists -Label "Plugin POM" -Path (Join-Path $projectRoot 'pom.xml')
    Assert-PathExists -Label "Plugin sources" -Path (Join-Path $projectRoot 'src')

    $buildRoot = Join-Path ([IO.Path]::GetTempPath()) ('ar-plugin-build-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $buildRoot | Out-Null
    Write-Step "Plugin build workspace: $buildRoot"
    $buildComponent = $Component.PSObject.Copy()
    $buildComponent.projectPath = $buildRoot
    $buildComponent.buildOutputPath = Join-Path $buildRoot $outputRoot.Substring($projectRoot.Length + 1)

    try {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'pom.xml') -Destination $buildRoot
        Copy-Item -LiteralPath (Join-Path $projectRoot 'src') -Destination $buildRoot -Recurse
        $mavenConfig = Join-Path $projectRoot '.mvn'
        if (Test-Path -LiteralPath $mavenConfig) {
            Copy-Item -LiteralPath $mavenConfig -Destination $buildRoot -Recurse
        }

        Write-Step "Normalizing plugin source encodings (UTF-8 no BOM)"
        & $encodingNormalizerPath -RootPath (Join-Path $buildRoot "src\main\java") | Out-Host

        $mavenArguments = $TestSelection.MavenArguments
        if ($TestSelection.Mode -eq 'Skip') {
            Write-Step "Building plugin without compiling or running tests"
        }
        elseif ($TestSelection.Mode -eq 'Selected') {
            Write-Step "Building plugin with selected tests: $($TestSelection.Tests)"
        }
        else {
            Write-Step "Building plugin with tests"
        }

        Push-Location $buildRoot
        try {
            & mvn @mavenArguments | Out-Host
            if ($LASTEXITCODE -ne 0) {
                throw "Plugin build failed: mvn $($mavenArguments -join ' ')"
            }
        }
        finally {
            Pop-Location
        }
        return $buildComponent
    }
    catch {
        Write-Step "Plugin build workspace retained for diagnosis: $buildRoot"
        throw
    }
}

<#
    配置が成功した Plugin ビルドの専用一時フォルダだけを削除する。
    想定した一時ルートと名前を検証し、元のチェックアウトには触れない。
#>
function Cleanup-PluginWorkspace {
    param($Component)

    Assert-PathExists -Label "Plugin project path" -Path $Component.projectPath

    $projectRoot = (Resolve-Path -LiteralPath $Component.projectPath).Path
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/')
    if (![IO.Path]::GetDirectoryName($projectRoot).Equals($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($projectRoot) -notmatch '^ar-plugin-build-[a-f0-9]{32}$' -or
        (Get-Item -LiteralPath $projectRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Unexpected plugin build workspace: $projectRoot"
    }

    Write-Step "Cleaning plugin build workspace $projectRoot"
    Remove-Item -LiteralPath $projectRoot -Recurse -Force
}

function Deploy-WebArtifact {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name build output" -Path $Component.buildOutputPath
    Assert-PathExists -Label "$Name deploy path" -Path $Component.deployPath

    Backup-DeployTarget -Component $Component
    $offlinePath = Enter-AppOffline -Component $Component -Name $Name

    try {
        $excludeDirectories = @()
        if ($Component.backup.enabled) {
            $excludeDirectories += (Join-Path $Component.deployPath $Component.backup.folderName)
        }

        if ($null -ne $Component.preserveDirectories) {
            foreach ($directoryName in @($Component.preserveDirectories)) {
                $excludeDirectories += (Join-Path $Component.deployPath $directoryName)
            }
        }

        $excludeFiles = @("app_offline.htm")
        if ($null -ne $Component.preserveFilePatterns) {
            $excludeFiles += @($Component.preserveFilePatterns)
        }

        Write-Step "Deploying $Name to $($Component.deployPath)"
        Invoke-RobocopyMirror -Source $Component.buildOutputPath -Destination $Component.deployPath -ExcludeDirectories $excludeDirectories -ExcludeFiles $excludeFiles
    }
    finally {
        Exit-AppOffline -Path $offlinePath
    }
}

function Deploy-FolderArtifact {
    param($Component, [string]$Name)

    Assert-PathExists -Label "$Name source path" -Path $Component.sourcePath
    Assert-PathExists -Label "$Name deploy path" -Path $Component.deployPath

    Backup-DeployTarget -Component $Component

    $excludeDirectories = @()
    if ($Component.backup.enabled) {
        $excludeDirectories += (Join-Path $Component.deployPath $Component.backup.folderName)
    }

    if ($null -ne $Component.preserveDirectories) {
        foreach ($directoryName in @($Component.preserveDirectories)) {
            $excludeDirectories += (Join-Path $Component.deployPath $directoryName)
        }
    }

    $excludeFiles = @()
    if ($null -ne $Component.preserveFilePatterns) {
        $excludeFiles += @($Component.preserveFilePatterns)
    }

    Write-Step "Deploying $Name to $($Component.deployPath)"
    Invoke-RobocopyMirror -Source $Component.sourcePath -Destination $Component.deployPath -ExcludeDirectories $excludeDirectories -ExcludeFiles $excludeFiles
}

function Deploy-PluginArtifact {
    param($Component)

    Assert-PathExists -Label "Plugin build output" -Path $Component.buildOutputPath
    Assert-PathExists -Label "Plugin deploy path" -Path $Component.deployPath

    $artifact = Get-ChildItem -LiteralPath $Component.buildOutputPath -Filter $Component.artifactPattern -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $artifact) {
        throw "Plugin artifact not found in $($Component.buildOutputPath) with pattern $($Component.artifactPattern)."
    }

    $destinationFileName = if ([string]::IsNullOrWhiteSpace($Component.deployFileName)) {
        $artifact.Name
    } else {
        [string]$Component.deployFileName
    }

    $cleanupPatterns = @()
    if ($null -ne $Component.cleanupPatterns) {
        $cleanupPatterns += @($Component.cleanupPatterns)
    }
    else {
        $cleanupPatterns += @($Component.artifactPattern, $destinationFileName)
    }

    $cleanupPatterns += $artifact.Name
    $cleanupPatterns = $cleanupPatterns |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique

    foreach ($pattern in $cleanupPatterns) {
        Get-ChildItem -LiteralPath $Component.deployPath -Filter $pattern -File -ErrorAction SilentlyContinue |
            Remove-Item -Force
    }

    $destination = Join-Path $Component.deployPath $destinationFileName

    Write-Step "Copying plugin artifact to $destination"
    Copy-Item -LiteralPath $artifact.FullName -Destination $destination -Force

    $paperRemappedPath = Join-Path $Component.deployPath ".paper-remapped"
    if (-not (Test-Path -LiteralPath $paperRemappedPath)) {
        return
    }

    foreach ($pattern in $cleanupPatterns) {
        Get-ChildItem -LiteralPath $paperRemappedPath -Filter $pattern -File -ErrorAction SilentlyContinue |
            ForEach-Object {
                Write-Step "Removing stale paper remapped artifact $($_.FullName)"
                Remove-Item -LiteralPath $_.FullName -Force
            }
    }

    $unknownOriginPath = Join-Path $paperRemappedPath "unknown-origin"
    if (Test-Path -LiteralPath $unknownOriginPath) {
        foreach ($pattern in $cleanupPatterns) {
            Get-ChildItem -LiteralPath $unknownOriginPath -Filter $pattern -File -ErrorAction SilentlyContinue |
                ForEach-Object {
                    Write-Step "Removing stale paper remapped artifact $($_.FullName)"
                    Remove-Item -LiteralPath $_.FullName -Force
                }
        }
    }
}

function Invoke-IisReset {
    param(
        [string]$ComputerName,
        [ValidateSet("start", "stop", "restart")]
        [string]$Action
    )

    Write-Step "IIS $Action on $ComputerName"
    & $script:iisResetCommand $ComputerName "/$Action"
    if ($LASTEXITCODE -ne 0) {
        throw "iisreset /$Action failed for $ComputerName."
    }
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw "Config file not found: $ConfigPath"
}

$config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
# Keep older local settings usable while an existing run still references its original config bytes.
$toolRoot = Split-Path -Parent $PSScriptRoot
foreach ($migration in @(
    @{ Name='databaseMigrations'; LegacyDirectory='db-migrate'; RunnerDirectory='13-db-migrate'; ConfigDirectory='13-db-migrate'; ConfigName='db-migrate'; Project='DbMigrateTool.csproj' },
    @{ Name='historyDatabaseMigrations'; LegacyDirectory='db-migrate'; RunnerDirectory='13-db-migrate'; ConfigDirectory='15-history-db-migrate'; ConfigName='history-db-migrate'; Project='DbMigrateTool.csproj' },
    @{ Name='managementDatabaseMigrations'; LegacyDirectory='management-db-migrate'; RunnerDirectory='14-management-db-migrate'; ConfigDirectory='14-management-db-migrate'; ConfigName='management-db-migrate'; Project='ManagementDbMigrateTool.csproj' }
)) {
    $migrationProperty = $config.PSObject.Properties[$migration.Name]
    if (!$migrationProperty -or !$migrationProperty.Value) { continue }
    $migrationConfig = $migrationProperty.Value
    foreach ($path in @(
        @{ Property='projectPath'; Directory=$migration.RunnerDirectory; File=$migration.Project },
        @{ Property='configPath'; Directory=$migration.ConfigDirectory; File="$($migration.ConfigName).config.json" },
        @{ Property='configPath'; Directory=$migration.ConfigDirectory; File="$($migration.ConfigName).local.json" }
    )) {
        $property = $migrationConfig.PSObject.Properties[$path.Property]
        $legacy = Join-Path $toolRoot (Join-Path $migration.LegacyDirectory $path.File)
        if ($property -and ([string]$property.Value).Replace('/', '\') -ieq $legacy -and !(Test-Path -LiteralPath $legacy -PathType Leaf)) {
            $property.Value = Join-Path $toolRoot (Join-Path $path.Directory $path.File)
        }
    }
}
Import-Module (Join-Path $PSScriptRoot '../control-gate/Control.Gate.psm1') -Force
$gateConfiguration=Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
$gateMode=Get-ControlGateMode $gateConfiguration
if ($gateMode -eq 'Managed' -and !$PreflightOnly) {
    $lease=[Environment]::GetEnvironmentVariable('ASTRALRECORD_CONTROL_GATE_LEASE','Process')
    if (!$WorkflowRunDirectory -or !$lease -or !(Test-Path -LiteralPath $lease -PathType Leaf)) {
        throw 'Managed direct deployment requires the guided 01/02/03 workflow and inherited Control gate lease.'
    }
    $backendPaths=@()
    if ($config.plugin.enabled -and !$MasterDataOnly) { $backendPaths+=[IO.Path]::GetFullPath([string]$config.plugin.deployPath) }
    foreach ($name in @('api','web','fileDatabase')) {
        if ($config.$name.enabled -and !$PluginOnly) { $backendPaths+=[IO.Path]::GetFullPath([string]$config.$name.deployPath) }
    }
    if ($config.fileDatabase.enabled -and !$PluginOnly) { $backendPaths+=[IO.Path]::GetFullPath([string]$config.fileDatabase.sourcePath) }
    $backendAdapter=if ($PluginOnly) { '02' } elseif ($MasterDataOnly) { '03' } else { '01' }
    $backendServerIds=@(Get-DeploymentGateRuntimeServerIds -Configuration $gateConfiguration `
        -RuntimeServerIds @($gateConfiguration.devWorkflow.migration.serverIds) -PluginOnly:$PluginOnly -MasterDataOnly:$MasterDataOnly)
    $backendGate=Enter-ControlGate -Configuration $gateConfiguration -Operation $backendAdapter `
        -RuntimeServerIds $backendServerIds -AffectedPaths $backendPaths `
        -RequiredState 'Stopped' -RunReference $WorkflowRunDirectory
}
if (!$config.plugin.enabled -and ($TestMode -or $PSBoundParameters.ContainsKey('Tests'))) { throw 'TestMode/Tests require plugin.enabled=true.' }

if ($PluginOnly) {
    $config.api.enabled = $false
    $config.web.enabled = $false
    $config.fileDatabase.enabled = $false
}
if ($MasterDataOnly) {
    $config.api.enabled = $false
    $config.web.enabled = $false
    $config.plugin.enabled = $false
}

if ($ReleaseManagementOnly) {
    $config.plugin.enabled = $false
    $config.fileDatabase.enabled = $false
    Assert-SkillTreeMigrationKeyReady -ApiComponent $config.api
    Assert-ReleaseManagementReady -ApiComponent $config.api -WebComponent $config.web
}
elseif ($config.api.enabled) {
    Assert-SkillTreeMigrationKeyReady -ApiComponent $config.api
}

if ($PreflightOnly) {
    Write-Step "Release management preflight completed successfully"
    return
}

if (($config.api.enabled -or $config.web.enabled) -and -not (Test-CommandExists -Name "dotnet")) {
    throw "dotnet command was not found."
}

if ($config.plugin.enabled -and -not (Test-CommandExists -Name "mvn")) {
    throw "mvn command was not found."
}

if (-not (Test-CommandExists -Name "robocopy")) {
    throw "robocopy command was not found."
}

$script:iisResetCommand = Resolve-IisResetCommand -IisConfig $config.iis

$iisStopped = $false
$progressLock=$null
$progressStage='Build'
$script:devBuildIsolated=if ($WorkflowRunDirectory) { Test-DevBuildIsolation $config } else { $false }

try {
    if ($WorkflowRunDirectory) {
        if (![IO.Path]::IsPathRooted($WorkflowRunDirectory) -or !(Test-Path -LiteralPath $WorkflowRunDirectory -PathType Container) -or
            $WorkflowFingerprint -notmatch '^[a-f0-9]{64}$') { throw 'Invalid workflow progress location or fingerprint.' }
        $progressLock=[IO.File]::Open((Join-Path $WorkflowRunDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None')
        if ((Test-Path -LiteralPath (Join-Path $WorkflowRunDirectory 'dev-deployment-progress.json')) -or
            (Test-Path -LiteralPath (Join-Path $WorkflowRunDirectory 'deploy-action-success.json'))) {
            throw 'This run already has deployment evidence. Use the guided recovery workflow.'
        }
        $workflowState=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $WorkflowRunDirectory 'workflow-state.json') | ConvertFrom-Json
        if ($workflowState.status -cne 'Deploying' -or $workflowState.label -cne 'Dev' -or $workflowState.configurationFingerprint -cne $WorkflowFingerprint) {
            throw 'Dev workflow state does not match this deployment.'
        }
        $mode=if ($PluginOnly) { 'PluginOnly' } elseif ($MasterDataOnly) { 'MasterDataOnly' } else { 'Full' }
        $actualFingerprint=Get-DevDeploymentFingerprint -ConfigPath $ConfigPath -Mode $mode -TestSelection $testSelection
        if ($actualFingerprint -cne $WorkflowFingerprint) { throw 'Deployment configuration changed before the backend started.' }
    }
    Write-DevDeploymentProgress $progressStage 'Running'
    try {
    if ($config.api.enabled) {
        Publish-DotNetProject -Component $config.api -Name "API"
        Publish-ResourcePacks -Component $config.api
    }

    if ($config.web.enabled) {
        Publish-DotNetProject -Component $config.web -Name "WEB"
    }

    if ($config.plugin.enabled) {
        $pluginBuildComponent = Build-Plugin -Component $config.plugin -TestSelection $testSelection
    }

    if ($config.api.enabled) {
        $progressStage='Database'; Write-DevDeploymentProgress $progressStage 'Running'
        Invoke-DatabaseMigrations -MigrationConfig $config.databaseMigrations
        Invoke-HistoryDatabaseMigrations -MigrationConfig $config.historyDatabaseMigrations
        Invoke-ManagementDatabaseMigrations -MigrationConfig $config.managementDatabaseMigrations
    }

    $progressStage='Files'; Write-DevDeploymentProgress $progressStage 'Running'
    if ($null -ne $script:iisResetCommand -and ($config.api.enabled -or $config.web.enabled)) {
        Invoke-IisReset -ComputerName $config.iis.host -Action "stop"
        $iisStopped = $true
    }

    if ($config.api.enabled) {
        Deploy-WebArtifact -Component $config.api -Name "API"
    }

    if ($config.web.enabled) {
        Deploy-WebArtifact -Component $config.web -Name "WEB"
    }

    if ($config.plugin.enabled) {
        Deploy-PluginArtifact -Component $pluginBuildComponent
        Cleanup-PluginWorkspace -Component $pluginBuildComponent
    }

    if ($config.fileDatabase.enabled) {
        Deploy-FolderArtifact -Component $config.fileDatabase -Name "FileDatabase"
    }
}
catch {
    Write-DevDeploymentProgress $progressStage 'Failed'
    throw
}
finally {
    if ($iisStopped) {
        try {
            Invoke-IisReset -ComputerName $config.iis.host -Action "start"
        }
        catch {
            Write-DevDeploymentProgress $progressStage 'Failed'
            Write-Error $_
            throw
        }
    }
}

Write-DevDeploymentProgress 'Completed' 'Completed'
Write-Step "Deployment completed successfully"
} finally {
    if ($progressLock) { $progressLock.Dispose() }
}
