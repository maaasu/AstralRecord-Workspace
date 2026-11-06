param(
    [string] $OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist')
)

# Pack validators read optional JSON properties as $null, including when invoked by deploy-debug.
Set-StrictMode -Off
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$bedrockRoot = Join-Path $root 'bedrock'
$utf8 = [System.Text.UTF8Encoding]::new($false)

if (-not ('AstralRecordPackZip' -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot 'pack-zip.cs')
}

function Read-PackEntries {
    param([string] $BasePath, [string[]] $Names)

    $entries = [System.Collections.Generic.SortedDictionary[string, byte[]]]::new([StringComparer]::Ordinal)
    Add-PackEntries $entries $BasePath $Names
    return ,$entries
}

function Add-PackEntries {
    param($Entries, [string] $BasePath, [string[]] $Names)

    foreach ($name in $Names) {
        $bytes = [System.IO.File]::ReadAllBytes((Join-Path $BasePath $name))
        if ($name.EndsWith('.json') -or $name.EndsWith('.mcmeta') -or $name.EndsWith('.txt')) {
            # Keep tracked metadata and notices identical across LF and CRLF checkouts.
            $bytes = $utf8.GetBytes($utf8.GetString($bytes).Replace("`r`n", "`n"))
        }
        $entries.Add($name, $bytes)
    }
}

function Get-ContentUuid {
    param([string] $Namespace, [string] $Fingerprint)

    # RFC 9562 UUIDv5 with the namespace bytes in network order.
    $hex = $Namespace.Replace('-', '')
    $namespaceBytes = [byte[]]::new(16)
    for ($i = 0; $i -lt 16; $i++) {
        $namespaceBytes[$i] = [Convert]::ToByte($hex.Substring($i * 2, 2), 16)
    }
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = $sha1.ComputeHash([byte[]] ($namespaceBytes + $utf8.GetBytes($Fingerprint)))
        $hash[6] = ($hash[6] -band 0x0f) -bor 0x50
        $hash[8] = ($hash[8] -band 0x3f) -bor 0x80
        $uuidHex = [BitConverter]::ToString($hash, 0, 16).Replace('-', '').ToLowerInvariant()
        return [Guid]::ParseExact($uuidHex, 'N').ToString('D')
    }
    finally {
        $sha1.Dispose()
    }
}

function Write-Pack {
    param([string] $Path, $Entries)

    [AstralRecordPackZip]::Write($Path, $Entries)
    Write-Host "Created $Path"
}

# Package damage numbers plus the custom items selected by the registry.
. (Join-Path $PSScriptRoot 'item-models.ps1')
. (Join-Path $PSScriptRoot 'action-ring-panel.ps1')
. (Join-Path $PSScriptRoot 'action-ring-hud-java.ps1')
. (Join-Path $PSScriptRoot 'action-ring-hud-bedrock.ps1')
. (Join-Path $PSScriptRoot 'armor-assets.ps1')
. (Join-Path $PSScriptRoot 'gui-assets.ps1')
$java = Read-PackEntries $root @(
    'pack.mcmeta'
    'assets/minecraft/textures/gui/container/inventory.png'
    'assets/minecraft/textures/gui/sprites/hud/hotbar.png'
    'assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png'
    'assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png.mcmeta'
    'assets/minecraft/textures/gui/sprites/hud/hotbar_offhand_left.png'
    'assets/minecraft/textures/gui/sprites/hud/hotbar_offhand_right.png'
    'sunbird-presentation-version.txt'
    'assets/astralrecord/font/damage_number.json'
    'assets/astralrecord/textures/font/damage_number.png'
    'assets/astralrecord/font/combat_hud.json'
    'assets/astralrecord/textures/font/combat_hud.png'
    'player-progress-hud-version.txt'
    'wide-combat-meter-version.txt'
    'assets/astralrecord/font/profile_hud_player.json'
    'assets/minecraft/textures/gui/sprites/hud/food_empty.png'
    'assets/minecraft/textures/gui/sprites/hud/food_empty_hunger.png'
    'assets/minecraft/textures/gui/sprites/hud/food_full.png'
    'assets/minecraft/textures/gui/sprites/hud/food_full_hunger.png'
    'assets/minecraft/textures/gui/sprites/hud/food_half.png'
    'assets/minecraft/textures/gui/sprites/hud/food_half_hunger.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_hardcore_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_hardcore_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_hardcore_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/absorbing_hardcore_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/container.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/container_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/container_hardcore.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/container_hardcore_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_hardcore_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_hardcore_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_hardcore_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/frozen_hardcore_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/hardcore_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/hardcore_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/hardcore_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/hardcore_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_hardcore_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_hardcore_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_hardcore_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/poisoned_hardcore_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_half_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_hardcore_full.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_hardcore_full_blinking.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_hardcore_half.png'
    'assets/minecraft/textures/gui/sprites/hud/heart/withered_hardcore_half_blinking.png'
    'assets/astralrecord/font/profile_hud_class.json'
    'assets/astralrecord/font/gameplay_hud.json'
    'assets/astralrecord/textures/font/gameplay_hud.png'
    'assets/astralrecord/font/tooltip_icons.json'
    'assets/astralrecord/textures/font/tooltip_icons.png'
    'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_background.png'
    'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_background.png.mcmeta'
    'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_frame.png'
    'assets/astralrecord/textures/gui/sprites/tooltip/shared_item_frame.png.mcmeta'
    'assets/astralrecord/models/world/waystone.json'
    'assets/astralrecord/models/world/waystone_locked.json'
    'assets/astralrecord/models/world/waystone_unlocked.json'
    'assets/astralrecord/models/world/storage.json'
    'assets/astralrecord/models/world/blast_furnace.json'
    'assets/astralrecord/textures/item/world/waystone_locked.png'
    'assets/astralrecord/textures/item/world/waystone_unlocked.png'
    'assets/astralrecord/textures/item/world/storage.png'
    'assets/astralrecord/textures/item/world/blast_furnace.png'
    'assets/astralrecord/font/weapon_attack.json'
    'assets/astralrecord/lang/en_us.json'
    'assets/astralrecord/textures/font/weapon_attack.png'
    'assets/astralrecord/items/action_ring.json'
    'assets/astralrecord/models/item/action_ring.json'
    'assets/astralrecord/textures/item/action_ring.png'
    'assets/astralrecord/textures/item/action_ring_icons.png'
    'assets/astralrecord/textures/item/action_ring_panel.png'
    'assets/astralrecord/items/sunbird.json'
    'assets/astralrecord/items/sunbird_warning_circle.json'
    'assets/astralrecord/items/sunbird_warning_line.json'
    'assets/astralrecord/items/sunbird_warning_boundary.json'
    'assets/astralrecord/items/sunbird_impact.json'
    'assets/astralrecord/items/sunbird_warning_safe.json'
    'assets/astralrecord/items/sunbird_impact_line.json'
    'assets/astralrecord/models/item/sunbird.json'
    'assets/astralrecord/models/item/sunbird_wing_up.json'
    'assets/astralrecord/models/item/sunbird_wing_down.json'
    'assets/astralrecord/models/item/sunbird_wing_tuck.json'
    'assets/astralrecord/models/item/sunbird_strike.json'
    'assets/astralrecord/models/item/sunbird_warning_circle.json'
    'assets/astralrecord/models/item/sunbird_warning_line.json'
    'assets/astralrecord/models/item/sunbird_warning_boundary.json'
    'assets/astralrecord/models/item/sunbird_impact.json'
    'assets/astralrecord/models/item/sunbird_warning_safe.json'
    'assets/astralrecord/models/item/sunbird_impact_line.json'
    'assets/astralrecord/models/item/sunbird_warning_circle_frame_1.json'
    'assets/astralrecord/models/item/sunbird_warning_circle_frame_2.json'
    'assets/astralrecord/models/item/sunbird_warning_circle_frame_3.json'
    'assets/astralrecord/models/item/sunbird_warning_line_frame_1.json'
    'assets/astralrecord/models/item/sunbird_warning_line_frame_2.json'
    'assets/astralrecord/models/item/sunbird_warning_line_frame_3.json'
    'assets/astralrecord/models/item/sunbird_warning_boundary_frame_1.json'
    'assets/astralrecord/models/item/sunbird_warning_boundary_frame_2.json'
    'assets/astralrecord/models/item/sunbird_warning_boundary_frame_3.json'
    'assets/astralrecord/models/item/sunbird_impact_frame_1.json'
    'assets/astralrecord/models/item/sunbird_impact_frame_2.json'
    'assets/astralrecord/models/item/sunbird_impact_frame_3.json'
    'assets/astralrecord/models/item/sunbird_warning_safe_frame_1.json'
    'assets/astralrecord/models/item/sunbird_warning_safe_frame_2.json'
    'assets/astralrecord/models/item/sunbird_warning_safe_frame_3.json'
    'assets/astralrecord/models/item/sunbird_impact_line_frame_1.json'
    'assets/astralrecord/models/item/sunbird_impact_line_frame_2.json'
    'assets/astralrecord/models/item/sunbird_impact_line_frame_3.json'
    'assets/astralrecord/textures/item/sunbird.png'
    'assets/astralrecord/textures/item/sunbird_skill.png'
)
$bedrock = Read-PackEntries $bedrockRoot @(
    'manifest.json'
    'font/glyph_E6.png'
    'textures/ui/absorption_heart.png'
    'textures/ui/absorption_heart_half.png'
    'textures/ui/freeze_heart.png'
    'textures/ui/freeze_heart_flash.png'
    'textures/ui/freeze_heart_flash_half.png'
    'textures/ui/freeze_heart_half.png'
    'textures/ui/hardcore/absorption_heart.png'
    'textures/ui/hardcore/absorption_heart_half.png'
    'textures/ui/hardcore/freeze_heart.png'
    'textures/ui/hardcore/freeze_heart_flash.png'
    'textures/ui/hardcore/freeze_heart_flash_half.png'
    'textures/ui/hardcore/freeze_heart_half.png'
    'textures/ui/hardcore/heart.png'
    'textures/ui/hardcore/heart_flash.png'
    'textures/ui/hardcore/heart_flash_half.png'
    'textures/ui/hardcore/heart_half.png'
    'textures/ui/hardcore/poison_heart.png'
    'textures/ui/hardcore/poison_heart_flash.png'
    'textures/ui/hardcore/poison_heart_flash_half.png'
    'textures/ui/hardcore/poison_heart_half.png'
    'textures/ui/hardcore/wither_heart.png'
    'textures/ui/hardcore/wither_heart_flash.png'
    'textures/ui/hardcore/wither_heart_flash_half.png'
    'textures/ui/hardcore/wither_heart_half.png'
    'textures/ui/heart.png'
    'textures/ui/heart_background.png'
    'textures/ui/heart_blink.png'
    'textures/ui/heart_flash.png'
    'textures/ui/heart_flash_half.png'
    'textures/ui/heart_half.png'
    'textures/ui/hunger_background.png'
    'textures/ui/hunger_blink.png'
    'textures/ui/hunger_effect_background.png'
    'textures/ui/hunger_effect_flash_full.png'
    'textures/ui/hunger_effect_flash_half.png'
    'textures/ui/hunger_effect_full.png'
    'textures/ui/hunger_effect_half.png'
    'textures/ui/hunger_flash_full.png'
    'textures/ui/hunger_flash_half.png'
    'textures/ui/hunger_full.png'
    'textures/ui/hunger_half.png'
    'textures/ui/poison_heart.png'
    'textures/ui/poison_heart_flash.png'
    'textures/ui/poison_heart_flash_half.png'
    'textures/ui/poison_heart_half.png'
    'textures/ui/wither_heart.png'
    'textures/ui/wither_heart_flash.png'
    'textures/ui/wither_heart_flash_half.png'
    'textures/ui/wither_heart_half.png'
    'textures/ui/purpleBorder.png'
    'entity/damage_number.entity.json'
    'animations/damage_number.animation.json'
    'models/entity/damage_number.geo.json'
    'render_controllers/damage_number.render_controllers.json'
    'textures/entity/damage_number/normal.png'
    'textures/entity/damage_number/critical.png'
    'textures/entity/damage_number/shield.png'
    'textures/entity/damage_number/burning.png'
    'textures/entity/damage_number/shocked.png'
    'textures/entity/damage_number/poison.png'
    'textures/entity/damage_number/condition.png'
    'textures/entity/damage_number/pet.png'
    'entity/action_ring.entity.json'
    'animations/action_ring.animation.json'
    'models/entity/action_ring.geo.json'
    'render_controllers/action_ring.render_controllers.json'
    'textures/entity/action_ring.png'
    'textures/entity/action_ring_rim.png'
    'textures/entity/action_ring_panel.png'
    'ui/_ui_defs.json'
    'ui/hud_screen.json'
    'ui/scoreboards.json'
    'ui/astralrecord_hud.json'
    'ui/inventory_screen.json'
    'ui/inventory_screen_pocket.json'
    'ui/ui_common.json'
    'textures/ui/astralrecord/inventory_panel.png'
    'textures/ui/astralrecord/inventory_panel.json'
    'textures/ui/astralrecord/hotbar_selection.png'
    'textures/ui/hotbar_0.png'
    'textures/ui/hotbar_1.png'
    'textures/ui/hotbar_2.png'
    'textures/ui/hotbar_3.png'
    'textures/ui/hotbar_4.png'
    'textures/ui/hotbar_5.png'
    'textures/ui/hotbar_6.png'
    'textures/ui/hotbar_7.png'
    'textures/ui/hotbar_8.png'
    'textures/ui/hotbar_start_cap.png'
    'textures/ui/hotbar_end_cap.png'
    'textures/ui/astralrecord/actionbar_plate.png'
    'textures/ui/astralrecord/actionbar_plate.json'
    'textures/ui/astralrecord/actionbar_ornament.png'
    'entity/combat_result.entity.json'
    'animations/combat_result.animation.json'
    'models/entity/combat_result.geo.json'
    'render_controllers/combat_result.render_controllers.json'
    'entity/world_display.entity.json'
    'models/entity/world_display.geo.json'
    'render_controllers/world_display.render_controllers.json'
    'textures/entity/world/waystone_locked.png'
    'textures/entity/world/waystone_unlocked.png'
    'textures/entity/world/storage.png'
    'textures/entity/world/blast_furnace.png'
    'textures/entity/combat_result.png'
    'entity/weapon_attack.entity.json'
    'animations/weapon_attack.animation.json'
    'models/entity/weapon_attack.geo.json'
    'render_controllers/weapon_attack.render_controllers.json'
    'textures/entity/weapon_attack/sword.png'
    'textures/entity/weapon_attack/hammer.png'
    'textures/entity/weapon_attack/spear.png'
    'textures/entity/weapon_attack/bow.png'
    'textures/entity/weapon_attack/wand.png'
    'textures/entity/weapon_attack/staff.png'
    'textures/entity/weapon_attack/bow_trail.png'
    'textures/entity/weapon_attack/wand_trail.png'
    'textures/entity/weapon_attack/staff_trail.png'
    'textures/entity/weapon_attack/bow_impact.png'
    'textures/entity/weapon_attack/wand_impact.png'
    'textures/entity/weapon_attack/staff_impact.png'
    'entity/sunbird.entity.json'
    'animations/sunbird.animation.json'
    'animation_controllers/sunbird.animation_controllers.json'
    'render_controllers/sunbird.render_controllers.json'
    'models/entity/sunbird.geo.json'
    'textures/entity/sunbird.png'
    'entity/sunbird_skill.entity.json'
    'animations/sunbird_skill.animation.json'
    'models/entity/sunbird_skill.geo.json'
    'render_controllers/sunbird_skill.render_controllers.json'
    'textures/entity/sunbird_skill.png'
)
$skillIcons = Get-ActionRingIcons
Add-ItemModelEntries $java $bedrock $skillIcons
if ($utf8.GetString($java['player-progress-hud-version.txt']) -cne '2') {
    throw 'Invalid profile HUD version marker in resource pack.'
}

if ($utf8.GetString($java['wide-combat-meter-version.txt']) -cne '1') {
    throw 'Invalid wide combat meter version marker in Java resource pack.'
}

Add-ActionRingPanelEntries $java $bedrock $skillIcons
Add-ActionRingHudJavaEntries $java $skillIcons
Add-ActionRingHudBedrockEntries $bedrock $skillIcons
Add-ArmorAssetEntries $java $bedrock
Add-GuiThemeEntries $java $bedrock

$tooltipVersion = $utf8.GetBytes('1')
$java.Add('item-tooltips-version.txt', $tooltipVersion)
$bedrock.Add('item-tooltips-version.txt', $tooltipVersion)

$weaponAttackVersion = $utf8.GetBytes('1')
$java.Add('weapon-attack-projectiles-version.txt', $weaponAttackVersion)
$bedrock.Add('weapon-attack-projectiles-version.txt', $weaponAttackVersion)

# Derive the Bedrock event list and package only the Ogg files referenced by Java.
$javaSoundPath = 'assets/astralrecord/sounds.json'
Add-PackEntries $java $root @($javaSoundPath)
$javaSounds = ConvertFrom-Json -InputObject ($utf8.GetString($java[$javaSoundPath]))
$bedrockSounds = [ordered]@{}
foreach ($event in $javaSounds.PSObject.Properties) {
    if ($event.Name -cnotmatch '^gui\.[a-z_]+$|^combat\.attack\.[a-z_]+$' -or !$event.Value.sounds) {
        throw "Invalid gameplay sound event: $($event.Name)"
    }
    $category = if ($event.Name.StartsWith('gui.')) { 'ui' } else { 'player' }
    $paths = foreach ($sound in $event.Value.sounds) {
        if ($sound -isnot [string] -or $sound -cnotmatch '^astralrecord:(gui|combat/attack)/[a-z0-9_]+$') {
            throw "Invalid gameplay sound asset in event: $($event.Name)"
        }
        $path = $sound.Substring('astralrecord:'.Length)
        $source = "assets/astralrecord/sounds/$path.ogg"
        $destination = "sounds/astralrecord/$path"
        if (!$java.ContainsKey($source)) {
            Add-PackEntries $java $root @($source)
            $bedrock.Add("$destination.ogg", $java[$source])
        }
        $destination
    }
    # Geyser forwards custom sound IDs with their namespace intact.
    $bedrockSounds.Add("astralrecord:$($event.Name)", [ordered]@{
        category = $category
        sounds = @($paths)
    })
}
$bedrockSoundJson = ConvertTo-Json -Depth 5 -Compress -InputObject ([ordered]@{
    format_version = '1.20.20'
    sound_definitions = $bedrockSounds
})
$bedrock.Add('sounds/sound_definitions.json', $utf8.GetBytes($bedrockSoundJson + "`n"))
$soundVersion = $utf8.GetBytes('1')
$java.Add('gameplay-sounds-version.txt', $soundVersion)
$bedrock.Add('gameplay-sounds-version.txt', $soundVersion)

# Include the template and file names in the fingerprint. Any distributed
# Bedrock asset change results in a new pack ID, bypassing client pack caching.
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $fingerprint = ($bedrock.GetEnumerator() | ForEach-Object {
        $_.Key + ':' + [BitConverter]::ToString($sha256.ComputeHash($_.Value)).Replace('-', '')
    }) -join "`n"
}
finally {
    $sha256.Dispose()
}
$template = $utf8.GetString($bedrock['manifest.json'])
$manifest = ConvertFrom-Json -InputObject $template
$headerUuid = $manifest.header.uuid
$moduleUuid = $manifest.modules[0].uuid
$bedrock['manifest.json'] = $utf8.GetBytes($template.Replace(
    $headerUuid, (Get-ContentUuid $headerUuid $fingerprint)
).Replace(
    $moduleUuid, (Get-ContentUuid $moduleUuid $fingerprint)
))

# World displays use a string selector. Preserve the generated item and GUI
# mappings (including format-specific overlays) as the unmarked fallback.
$worldModelsByItem = [ordered] @{
    lodestone = @('waystone_locked', 'waystone_unlocked')
    chest = @('storage')
    blast_furnace = @('blast_furnace')
}
foreach ($base in $worldModelsByItem.Keys) {
    $itemPath = "assets/minecraft/items/$base.json"
    $overlays = @($java.Keys | Where-Object { $_.EndsWith("/$itemPath", [StringComparison]::Ordinal) })
    foreach ($path in (@($itemPath) + $overlays)) {
        $definition = if ($java.ContainsKey($path)) {
            ConvertFrom-Json -InputObject ($utf8.GetString($java[$path]))
        } else {
            Get-PackJson (Join-Path $root "java-item-fallbacks/$base.json")
        }
        $definition.model = [ordered] @{
            type = 'minecraft:select'
            property = 'minecraft:custom_model_data'
            index = 0
            cases = @($worldModelsByItem[$base] | ForEach-Object {
                [ordered] @{ when = "astralrecord:world/$_"; model = New-JavaModelReference "astralrecord:world/$_" }
            })
            fallback = $definition.model
        }
        [void] $java.Remove($path)
        Add-PackJson $java $path $definition
    }
}

$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($destination) | Out-Null
Write-Pack (Join-Path $destination 'AstralRecordResourcePack.zip') $java
Write-Pack (Join-Path $destination 'AstralRecordResourcePack.mcpack') $bedrock
