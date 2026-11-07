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
        if ($name.EndsWith('.json') -or $name.EndsWith('.mcmeta') -or $name.EndsWith('.txt') -or $name.EndsWith('.material')) {
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
. (Join-Path $PSScriptRoot 'buff-hud-java.ps1')
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
    'selenea-presentation-version.txt'
    'fishing-visuals-version.txt'
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
    'assets/astralrecord/font/gameplay_hud_details.json'
    'assets/astralrecord/font/profile_hud_class_name.json'
    'assets/astralrecord/textures/font/profile_hud_class_name.png'
    'assets/astralrecord/font/condition_hud.json'
    'assets/astralrecord/textures/font/condition_hud.png'
    'licenses/OFL-DotGothic16.txt'
    'assets/astralrecord/font/profile_hud_points.json'
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
    'assets/minecraft/items/black_concrete.json'
    'assets/minecraft/items/tripwire_hook.json'
    'assets/minecraft/items/light_blue_carpet.json'
    'assets/astralrecord/models/item/fishing_line.json'
    'assets/astralrecord/models/item/fishing_hook.json'
    'assets/astralrecord/models/item/fishing_splash.json'
    'assets/astralrecord/models/item/fishing_wake.json'
    'assets/astralrecord/textures/item/fishing_visual.png'
    'assets/astralrecord/textures/item/world/blast_furnace.png'
    'assets/astralrecord/font/weapon_attack.json'
    'assets/astralrecord/font/skill_effect.json'
    'assets/astralrecord/lang/en_us.json'
    'assets/astralrecord/textures/font/weapon_attack.png'
    'assets/astralrecord/textures/font/skill_effect.png'
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
    'assets/astralrecord/items/selenea.json'
    'assets/astralrecord/models/item/selenea.json'
    'assets/astralrecord/models/item/selenea_stride_left.json'
    'assets/astralrecord/models/item/selenea_stride_right.json'
    'assets/astralrecord/models/item/selenea_cast_low.json'
    'assets/astralrecord/models/item/selenea_cast_high.json'
    'assets/astralrecord/models/item/selenea_release.json'
    'assets/astralrecord/models/item/selenea_recover.json'
    'assets/astralrecord/textures/item/selenea.png'
    'assets/astralrecord/textures/item/selenea_skill.png'
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
    'entity/skill_effect.entity.json'
    'animations/skill_effect.animation.json'
    'models/entity/skill_effect_v2.geo.json'
    'render_controllers/skill_effect.render_controllers.json'
    'textures/entity/skill_effect.png'
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
    'entity/selenea.entity.json'
    'animations/selenea.animation.json'
    'animation_controllers/selenea.animation_controllers.json'
    'render_controllers/selenea.render_controllers.json'
    'models/entity/selenea.geo.json'
    'textures/entity/selenea.png'
    'entity/selenea_skill.entity.json'
    'animations/selenea_skill.animation.json'
    'render_controllers/selenea_skill.render_controllers.json'
    'models/entity/selenea_skill.geo.json'
    'textures/entity/selenea_skill.png'
    'entity/fishing_visual.entity.json'
    'animations/fishing_visual.animation.json'
    'models/entity/fishing_visual.geo.json'
    'render_controllers/fishing_visual.render_controllers.json'
    'textures/entity/fishing_visual.png'
)
$seleneaSkillStyles = @(
    'warning_circle', 'warning_line', 'warning_boundary', 'warning_safe', 'impact', 'impact_line',
    'wave', 'bolt', 'warning_edge', 'warning_grimoire', 'impact_grimoire'
)
foreach ($style in $seleneaSkillStyles) {
    $name = "selenea_$style"
    $paths = @("assets/astralrecord/items/$name.json", "assets/astralrecord/models/item/$name.json")
    if ($style -notin @('warning_boundary', 'warning_edge')) {
        $paths += 1..3 | ForEach-Object { "assets/astralrecord/models/item/${name}_frame_$_.json" }
    }
    Add-PackEntries $java $root $paths
}
$seleneaVersionMarker = 'selenea-presentation-version.txt'
if ($utf8.GetString($java[$seleneaVersionMarker]) -cne '1') {
    throw 'Invalid Selenea presentation version marker in resource pack.'
}
$bedrock.Add($seleneaVersionMarker, $java[$seleneaVersionMarker])

$fishingVersionMarker = 'fishing-visuals-version.txt'
if ($utf8.GetString($java[$fishingVersionMarker]) -cne '1') {
    throw 'Invalid fishing visuals version marker in resource pack.'
}
$bedrock.Add($fishingVersionMarker, $java[$fishingVersionMarker])

$skillIcons = Get-ActionRingIcons
Add-ItemModelEntries $java $bedrock $skillIcons
if ($utf8.GetString($java['player-progress-hud-version.txt']) -cne '5') {
    throw 'Invalid profile HUD version marker in resource pack.'
}
Add-BuffHudJavaEntries $java

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

$skillEffectSlots = @(Get-Content -LiteralPath (Join-Path $root 'skill-effect-slots.txt') -Encoding UTF8)
$skillEffectProfiles = @(ConvertFrom-Json -InputObject (
    Get-Content -LiteralPath (Join-Path $root 'skill-effects.json') -Encoding UTF8 -Raw))
if ($skillEffectSlots.Count -eq 0 -or $skillEffectSlots.Count -gt 170 -or
    ($skillEffectSlots | Sort-Object -CaseSensitive -Unique).Count -ne $skillEffectSlots.Count -or
    ($skillEffectSlots -join "`n") -cne ($skillEffectProfiles.id -join "`n")) {
    throw 'Skill effect slot list and art catalog must have matching, unique IDs in slot order.'
}
$pluginSlotFile = Join-Path $root '../10_plugin/AstralRecord/src/main/resources/skill-effect-slots.txt'
if (Test-Path -LiteralPath $pluginSlotFile) {
    $pluginSlots = Get-Content -LiteralPath $pluginSlotFile -Encoding UTF8
    if (($skillEffectSlots -join "`n") -cne ($pluginSlots -join "`n")) {
        throw 'Skill effect slot list differs from the plugin slot list.'
    }
}

$skillFont = ConvertFrom-Json -InputObject ($utf8.GetString($java['assets/astralrecord/font/skill_effect.json']))
$skillFontProvider = $skillFont.providers[0]
if ($skillFontProvider.type -cne 'bitmap' -or $skillFontProvider.file -cne 'astralrecord:font/skill_effect.png' -or
    $skillFontProvider.height -ne 16 -or $skillFontProvider.ascent -ne 14 -or $skillFontProvider.chars.Count -ne 64) {
    throw 'Invalid skill effect font provider.'
}
for ($row = 0; $row -lt 64; $row++) {
    $glyphs = $skillFontProvider.chars[$row]
    if ($glyphs.Length -ne 64) { throw 'Skill effect glyph row has an invalid length.' }
    for ($column = 0; $column -lt 64; $column++) {
        if ([int][char]$glyphs[$column] -ne (0xe400 + $row * 64 + $column)) {
            throw 'Skill effect glyphs are out of slot order.'
        }
    }
}
foreach ($atlas in @($java['assets/astralrecord/textures/font/skill_effect.png'], $bedrock['textures/entity/skill_effect.png'])) {
    # PNG IHDR stores both 2048px dimensions in network byte order.
    if ($atlas.Length -lt 24 -or [BitConverter]::ToString($atlas, 0, 8) -cne '89-50-4E-47-0D-0A-1A-0A' -or
        [BitConverter]::ToString($atlas, 16, 8) -cne '00-00-08-00-00-00-08-00') {
        throw 'Skill effect atlas must be a 2048x2048 PNG.'
    }
}
$skillEntity = (ConvertFrom-Json -InputObject ($utf8.GetString(
    $bedrock['entity/skill_effect.entity.json']))).'minecraft:client_entity'.description
$skillAnimation = (ConvertFrom-Json -InputObject ($utf8.GetString(
    $bedrock['animations/skill_effect.animation.json']))).animations
$skillGeometries = (ConvertFrom-Json -InputObject ($utf8.GetString(
    $bedrock['models/entity/skill_effect_v2.geo.json']))).'minecraft:geometry'
$skillControllerId = 'controller.render.astralrecord.skill_effect'
$skillController = (ConvertFrom-Json -InputObject ($utf8.GetString(
    $bedrock['render_controllers/skill_effect.render_controllers.json']))).render_controllers.$skillControllerId
$skillStyles = $skillEffectSlots.Count * 4
$skillGeometryRefs = $skillController.arrays.geometries.'Array.styles'
if ($skillEntity.identifier -cne 'astralrecord:skill_effect' -or
    $skillEntity.materials.default -cne 'entity_emissive_alpha' -or
    $skillEntity.textures.default -cne 'textures/entity/skill_effect' -or
    $null -eq $skillAnimation.($skillEntity.animations.motion) -or
    $skillEntity.render_controllers[0].$skillControllerId -cne "variable.style >= 0 && variable.style < $skillStyles" -or
    $skillController.geometry -cne 'Array.styles[variable.style]' -or
    $null -ne $skillController.uv_anim -or $skillController.part_visibility.Count -ne 6 -or
    $skillGeometryRefs.Count -ne $skillStyles -or $skillGeometries.Count -ne $skillStyles) {
    throw 'Invalid Bedrock skill effect entity, geometry array, animation or controller.'
}
for ($frame = 0; $frame -lt 6; $frame++) {
    if ($skillController.part_visibility[$frame].("frame_$frame") -cne "variable.frame == $frame") {
        throw "Invalid Bedrock skill effect frame visibility at frame $frame."
    }
}
for ($style = 0; $style -lt $skillStyles; $style++) {
    $alias = "style_$style"
    $geometry = $skillGeometries[$style]
    if ($skillGeometryRefs[$style] -cne "Geometry.$alias" -or
        $skillEntity.geometry.$alias -cne "geometry.astralrecord.skill_effect.$alias" -or
        $skillEntity.geometry.$alias -cne $geometry.description.identifier -or
        $geometry.description.texture_width -ne 2048 -or $geometry.description.texture_height -ne 2048 -or
        $geometry.bones.Count -ne 8 -or $geometry.bones[0].name -cne 'billboard' -or
        $geometry.bones[1].name -cne 'sprite' -or $geometry.bones[1].parent -cne 'billboard') {
        throw "Invalid Bedrock skill effect geometry at style $style."
    }
    for ($frame = 0; $frame -lt 6; $frame++) {
        $cell = $style * 6 + $frame
        $expectedU = ($cell % 64) * 32
        $expectedV = [Math]::Floor($cell / 64) * 32
        $bone = $geometry.bones[$frame + 2]
        $face = $bone.cubes[0].uv.south
        if ($bone.name -cne "frame_$frame" -or $bone.parent -cne 'sprite' -or $bone.cubes.Count -ne 1 -or
            ($face.uv -join ',') -cne "$expectedU,$expectedV" -or
            ($face.uv_size -join ',') -cne '32,32') {
            throw "Invalid Bedrock skill effect UV at style $style frame $frame."
        }
    }
}
$java.Add('skill-effects-version.txt', $utf8.GetBytes('1'))
$bedrock.Add('skill-effects-version.txt', $utf8.GetBytes('2'))

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
