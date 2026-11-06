# Assemble positioned quads from the shared skill sprite catalog without tracking one model per slot and sprite.

function Convert-RingJsonNumbers {
    param([double[]] $Values)

    # PowerShell 5.1 omits .0 for whole doubles, while PowerShell 7 preserves it.
    return ,@(foreach ($value in $Values) {
        if ($value -ge [int]::MinValue -and $value -le [int]::MaxValue -and $value -eq [math]::Truncate($value)) {
            [int] $value
        } else {
            # Preserve the double's round-trip value without runtime-specific shortest formatting.
            [decimal]::Parse($value.ToString('G17', [Globalization.CultureInfo]::InvariantCulture),
                [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture)
        }
    })
}

function New-RingRectangle {
    param([double] $X, [double] $Y, [double] $Width, [double] $Height)
    return @([math]::Round($X - $Width / 2, 4), [math]::Round($Y - $Height / 2, 4),
             [math]::Round($X + $Width / 2, 4), [math]::Round($Y + $Height / 2, 4))
}

function New-RingJavaQuad {
    param([double[]] $Rect, [double[]] $UV, [double] $Depth, [int] $Tint = -1)
    $face = [ordered]@{ texture = '#panel'; uv = (Convert-RingJsonNumbers $UV) }
    if ($Tint -ge 0) { $face.tintindex = $Tint }
    return [ordered]@{
        from = Convert-RingJsonNumbers @(($Rect[0] / 8), (16 - $Rect[3] / 8), $Depth)
        to = Convert-RingJsonNumbers @(($Rect[2] / 8), (16 - $Rect[1] / 8), $Depth)
        shade = $false
        faces = @{ south = $face }
    }
}

function Add-RingJavaModel {
    param($Entries, [string] $Name, [string] $Texture, [object[]] $Quads, [int] $Tint = -1)
    $path = "item/action_ring_panel/$Name"
    Add-PackJson $Entries "assets/astralrecord/models/$path.json" ([ordered]@{
        gui_light = 'front'
        textures = @{ panel = "astralrecord:item/$Texture" }
        elements = $Quads
    })
    $model = New-JavaModelReference "astralrecord:$path"
    if ($Tint -ge 0) {
        $model.tints = @([ordered]@{ type = 'minecraft:custom_model_data'; index = $Tint; default = 16777215 })
    }
    return $model
}

function New-RingDispatch {
    param([int] $Index, [object[]] $Entries)
    return [ordered]@{
        type = 'minecraft:range_dispatch'
        property = 'minecraft:custom_model_data'
        index = $Index
        fallback = @{ type = 'minecraft:empty' }
        entries = $Entries
    }
}

function Add-RingJavaPanel {
    param($Entries, [int[]] $Ids, $Slots)
    $models = [Collections.Generic.List[object]]::new()
    $models.Add(($utf8.GetString($Entries['assets/astralrecord/items/action_ring.json']) | ConvertFrom-Json).model)
    foreach ($slot in $Slots) {
        $variants = foreach ($id in $Ids) {
            $u = $id % 16
            $v = [math]::Floor($id / 16)
            $quad = New-RingJavaQuad $slot.Icon @($u, $v, ($u + 1), ($v + 1)) 8.12 0
            $model = Add-RingJavaModel $Entries "icon_$($slot.Index)_$id" 'action_ring_icons' @($quad) (8 + $slot.Index)
            [ordered]@{ threshold = $id; model = $model }
        }
        $models.Add((New-RingDispatch $slot.Index @($variants)))
    }
    foreach ($slot in $Slots) {
        $timer = $slot.Index -eq 8
        $bar = $slot.Gauge
        $steps = if ($timer) { 24 } else { 10 }
        $fillU = if ($timer) { 8 } else { 4 }
        $track = New-RingJavaQuad $bar @(0, 0, 4, 16) 8.09
        $variants = foreach ($step in 1..$steps) {
            $width = ($bar[2] - $bar[0] - 2) * $step / $steps
            $fill = @((1 + $bar[0]), ($bar[1] + 0.5), ($bar[0] + 1 + $width), ($bar[3] - 0.5))
            $quad = New-RingJavaQuad $fill @($fillU, 0, ($fillU + 4), 16) 8.13
            $model = Add-RingJavaModel $Entries "gauge_$($slot.Index)_$step" 'action_ring_panel' @($track, $quad)
            [ordered]@{ threshold = $step; model = $model }
        }
        $models.Add((New-RingDispatch (9 + $slot.Index) @($variants)))
    }
    $markers = foreach ($slot in $Slots | Where-Object Index -lt 8) {
        $quad = New-RingJavaQuad $slot.Marker @(12, 0, 16, 16) 8.13
        $model = Add-RingJavaModel $Entries "marker_$($slot.Index)" 'action_ring_panel' @($quad)
        [ordered]@{ threshold = $slot.Index + 1; model = $model }
    }
    $models.Add((New-RingDispatch 18 @($markers)))
    Add-PackJson $Entries 'assets/astralrecord/items/action_ring_panel.json' @{
        model = [ordered]@{ type = 'minecraft:composite'; models = $models.ToArray() }
    }
}

function New-RingBedrockBone {
    param([string] $Name, [double[]] $Rect, [double[]] $UV, [double] $Depth,
          [switch] $AnchorLeft)
    # The ring pose faces the viewer along north, with negative model X to screen-right.
    $minX = 8 - $Rect[2] / 8
    $y = 8 - $Rect[3] / 8
    $face = [ordered]@{
        uv = (Convert-RingJsonNumbers @($UV[0], $UV[1]))
        uv_size = (Convert-RingJsonNumbers @($UV[2], $UV[3]))
    }
    # Keep the screen-left edge (maximum model X) fixed as the gauge scales.
    $pivot = if ($AnchorLeft) { @((8 - $Rect[0] / 8), $y, 0) } else { @(0, 0, 0) }
    return [ordered]@{
        name = $Name
        parent = 'wheel'
        pivot = Convert-RingJsonNumbers $pivot
        cubes = @([ordered]@{
            origin = Convert-RingJsonNumbers @($minX, $y, (-$Depth))
            size = Convert-RingJsonNumbers @((($Rect[2] - $Rect[0]) / 8), (($Rect[3] - $Rect[1]) / 8), 0.02)
            uv = @{ north = $face }
        })
    }
}

function New-RingBedrockGeometry {
    param([string] $Name, [int] $Width, [int] $Height, [object[]] $Parts)
    return [ordered]@{
        description = [ordered]@{
            identifier = "geometry.astralrecord.action_ring_panel.$Name"
            texture_width = $Width
            texture_height = $Height
            visible_bounds_width = 5
            visible_bounds_height = 5
            visible_bounds_offset = @(0, 0, 0)
        }
        bones = @(
            [ordered]@{ name = 'yaw'; pivot = @(0, 0, 0) }
            [ordered]@{ name = 'pitch'; parent = 'yaw'; pivot = @(0, 0, 0) }
            [ordered]@{ name = 'wheel'; parent = 'pitch'; pivot = @(0, 0, 0) }
        ) + $Parts
    }
}

function New-RingController {
    param([string] $Name, [string] $Texture)
    return [ordered]@{
        geometry = "Geometry.$Name"
        materials = @(@{ '*' = 'Material.default' })
        textures = @($Texture)
        ignore_lighting = $true
    }
}

function Add-RingBedrockPanel {
    param($Entries, [int[]] $Ids, $Tiles, $Slots)
    $rootController = 'controller.render.astralrecord.action_ring_panel'
    $textures = [ordered]@{
        rim = 'textures/entity/action_ring_rim'
        decor = 'textures/entity/action_ring_panel'
    }
    foreach ($id in $Ids) {
        $textures.Add("icon_$id", "textures/entity/action_ring_icons/$id")
        $Entries.Add("textures/entity/action_ring_icons/$id.png", [Convert]::FromBase64String($Tiles.PSObject.Properties[[string]$id].Value))
    }
    $tileArray = for ($i = 0; $i -le $Ids[-1]; $i++) {
        if ($textures.Contains("icon_$i")) { "Texture.icon_$i" } else { 'Texture.decor' }
    }
    $geometry = [ordered]@{
        rim = 'geometry.astralrecord.action_ring_panel.rim'
        decor = 'geometry.astralrecord.action_ring_panel.decor'
    }
    $controllers = [ordered]@{}
    $controllers.Add("$rootController.rim", (New-RingController 'rim' 'Texture.rim'))
    $controllers.Add("$rootController.decor", (New-RingController 'decor' 'Texture.decor'))
    $names = [Collections.Generic.List[object]]::new()
    $names.Add("$rootController.rim")
    $names.Add("$rootController.decor")
    $geometries = [Collections.Generic.List[object]]::new()
    $geometries.Add((New-RingBedrockGeometry 'rim' 128 128 @(
        (New-RingBedrockBone 'rim' @(0, 0, 128, 128) @(0, 0, 128, 128) 0)
    )))
    $animation = [ordered]@{}
    $decor = [Collections.Generic.List[object]]::new()
    $visible = [Collections.Generic.List[object]]::new()
    foreach ($slot in $Slots) {
        $index = $slot.Index
        $icon = "icon_$index"
        $property = "query.property('astralrecord:panel_icon_$index')"
        $normalIcon = $index -lt 8
        $geometries.Add((New-RingBedrockGeometry $icon 32 32 @(
            (New-RingBedrockBone $icon $slot.Icon @(0, 0, 32, 32) 0.12)
        )))
        $geometry.Add($icon, "geometry.astralrecord.action_ring_panel.$icon")
        $controller = New-RingController $icon "Array.icons[$property]"
        $controller.arrays = @{ textures = @{ 'Array.icons' = @($tileArray) } }
        if ($normalIcon) {
            # The existing pose property carries dimmed slots. The gauge properties still carry cooldown progress.
            $bit = 1 -shl $index
            $shade = "math.mod(math.floor(query.property('astralrecord:ring_cooldowns') / $bit), 2) == 1 ? 0.62 : 1.0"
            $controller.color = [ordered]@{ r = $shade; g = $shade; b = $shade; a = 1 }
        }
        $controllers.Add("$rootController.$icon", $controller)
        $phase = if ($normalIcon) { "query.property('astralrecord:panel_confirmed') == 0" } else { "query.property('astralrecord:panel_confirmed') > 0" }
        $names.Add(@{ "$rootController.$icon" = "$property > 0 && $phase" })

        $gauge = if ($normalIcon) { "query.property('astralrecord:panel_cd_$index')" } else { "query.property('astralrecord:panel_time')" }
        $bar = $slot.Gauge
        $fillU = if ($normalIcon) { 8 } else { 16 }
        $steps = if ($normalIcon) { 10 } else { 24 }
        $fill = @((1 + $bar[0]), ($bar[1] + 0.5), ($bar[2] - 1), ($bar[3] - 0.5))
        $decor.Add((New-RingBedrockBone "track_$index" $bar @(0, 0, 8, 8) 0.09))
        $decor.Add((New-RingBedrockBone "fill_$index" $fill @($fillU, 0, 8, 8) 0.13 -AnchorLeft))
        $visible.Add(@{ "track_$index" = "$gauge > 0" })
        $visible.Add(@{ "fill_$index" = "$gauge > 0" })
        $animation.Add("fill_$index", @{ scale = @("math.max(0.001, $gauge / $steps)", 1, 1) })
        if ($normalIcon) {
            $decor.Add((New-RingBedrockBone "marker_$index" $slot.Marker @(24, 0, 8, 8) 0.13))
            $visible.Add(@{ "marker_$index" = "query.property('astralrecord:ring_selected') == $($index + 1)" })
        }
    }
    $controllers["$rootController.decor"].part_visibility = $visible.ToArray()
    $geometries.Add((New-RingBedrockGeometry 'decor' 32 8 $decor.ToArray()))
    Add-PackJson $Entries 'models/entity/action_ring_panel.geo.json' ([ordered]@{
        format_version = '1.12.0'
        'minecraft:geometry' = $geometries.ToArray()
    })
    Add-PackJson $Entries 'render_controllers/action_ring_panel.render_controllers.json' ([ordered]@{
        format_version = '1.8.0'; render_controllers = $controllers
    })
    Add-PackJson $Entries 'animations/action_ring_panel.animation.json' ([ordered]@{
        format_version = '1.8.0'; animations = @{ 'animation.astralrecord.action_ring_panel' = [ordered]@{ loop = $true; bones = $animation } }
    })
    Add-PackJson $Entries 'entity/action_ring_panel.entity.json' ([ordered]@{
        format_version = '1.10.0'
        'minecraft:client_entity' = @{ description = [ordered]@{
            identifier = 'astralrecord:action_ring_panel'
            materials = @{ default = 'entity_alphatest' }
            textures = $textures
            geometry = $geometry
            animations = [ordered]@{
                pose = 'animation.astralrecord.action_ring'
                gauges = 'animation.astralrecord.action_ring_panel'
            }
            scripts = @{ animate = @('pose', 'gauges') }
            render_controllers = $names.ToArray()
        }}
    })
}

function Get-ActionRingIcons {
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root 'resourcepack.config.json') | ConvertFrom-Json
    $pluginResources = if ($config.absolutePathOverrides.astralRecordPluginResources) {
        $config.absolutePathOverrides.astralRecordPluginResources
    } else {
        Join-Path $root $config.references.astralRecordPluginResources
    }
    $catalogPath = Join-Path $pluginResources 'action-ring-icons.json'
    $catalogText = $utf8.GetString([IO.File]::ReadAllBytes($catalogPath)).Replace("`r`n", "`n")
    $catalog = ConvertFrom-Json -InputObject $catalogText
    $tiles = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $bedrockRoot 'action-ring-icon-tiles.json') | ConvertFrom-Json
    $ids = @($catalog.PSObject.Properties.Value | Sort-Object -Unique)
    if ($ids.Count -eq 0 -or $ids.Count -ne $catalog.PSObject.Properties.Name.Count -or
        $ids.Count -ne $tiles.PSObject.Properties.Name.Count -or $ids[0] -lt 1 -or $ids[-1] -gt 254) {
        throw 'Invalid action ring sprite catalog.'
    }
    foreach ($id in $ids) {
        if (($id -isnot [int] -and $id -isnot [long]) -or !$tiles.PSObject.Properties[[string]$id]) {
            throw "Missing or invalid action ring sprite $id."
        }
    }
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $hudRevision = [BitConverter]::ToString($hasher.ComputeHash($utf8.GetBytes("action-ring-hud-v1`n" + $catalogText))).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
    return [PSCustomObject]@{ Ids = $ids; Tiles = $tiles; HudRevision = $hudRevision }
}

function Add-SkillIconModelEntries {
    param($Java, $Bedrock, $IconTextures, $ByBase, $ItemModels, $SkillIcons)

    if (!$ByBase.ContainsKey('paper')) { $ByBase.paper = [ordered] @{} }
    foreach ($sprite in $SkillIcons.Ids) {
        # Keep this range synchronized with SkillIconCatalog.MODEL_DATA_BASE.
        $number = 920000 + $sprite
        $id = "skill_icon_$sprite"
        if ($ItemModels.ContainsKey($number) -or $IconTextures.Contains("astralrecord.$id")) {
            throw "Reserved skill icon mapping collides with an item: $id ($number)."
        }
        $png = [Convert]::FromBase64String($SkillIcons.Tiles.PSObject.Properties[[string]$sprite].Value)
        $javaPath = "assets/astralrecord/textures/item/skill/$id.png"
        Confirm-GuiPng $png $javaPath -Icon
        $Java.Add($javaPath, $png)
        $Bedrock.Add("textures/items/$id.png", $png)
        $IconTextures.Add("astralrecord.$id", [ordered] @{ textures = "textures/items/$id" })
        Add-PackJson $Java "assets/astralrecord/models/item/skill/$id.json" ([ordered] @{
            parent = 'minecraft:item/generated'
            textures = [ordered] @{ layer0 = "astralrecord:item/skill/$id" }
        })
        $ByBase.paper.Add([string] $number, (New-JavaModelReference "astralrecord:item/skill/$id"))
        [ordered] @{
            id = $id
            base_item = 'minecraft:paper'
            custom_model_data = $number
            render_mode = 'generated'
        }
    }
}

function Add-ActionRingPanelEntries {
    param($Java, $Bedrock, $SkillIcons)

    $Java.Add('action-ring-panel.sha256', $utf8.GetBytes($SkillIcons.HudRevision))

    $slots = for ($index = 0; $index -le 8; $index++) {
        $angle = [Math]::PI * $index / 4
        $radius = if ($index -eq 8) { 0 } else { 42 }
        $x = 64 + [Math]::Sin($angle) * $radius
        $y = 64 - [Math]::Cos($angle) * $radius
        $gaugeY = if ($index -eq 4) { $y - 15 } else { $y + 15 }
        $gauge = if ($index -eq 8) { New-RingRectangle 64 76 24 3 } else { New-RingRectangle $x $gaugeY 17 3 }
        [PSCustomObject]@{
            Index = $index
            Icon = (New-RingRectangle $x $y 24 24)
            Gauge = @($gauge)
            Marker = (New-RingRectangle (64 + [Math]::Sin($angle) * 57) (64 - [Math]::Cos($angle) * 57) 8 8)
        }
    }
    Add-RingJavaPanel $Java $SkillIcons.Ids $slots
    Add-RingBedrockPanel $Bedrock $SkillIcons.Ids $SkillIcons.Tiles $slots
}
