function Test-ArmorAssetId {
    param($Value)

    return ($Value -is [string] -and $Value.Length -le 160 -and
        $Value -cmatch '^astralrecord:[a-z0-9_]+(?:/[a-z0-9_]+)*\z')
}

function Add-UniqueArmorEntry {
    param($Entries, [string] $BasePath, [string] $Name)

    if (!$Entries.ContainsKey($Name)) { Add-PackEntries $Entries $BasePath @($Name) }
}

function Get-ArmorPngSize {
    param([byte[]] $Bytes, [string] $Path)

    $signature = @(137, 80, 78, 71, 13, 10, 26, 10)
    if ($Bytes.Length -lt 33) { throw "Armor texture is not a PNG: $Path" }
    for ($index = 0; $index -lt 8; $index++) {
        if ($Bytes[$index] -ne $signature[$index]) { throw "Armor texture is not a PNG: $Path" }
    }
    $width = 0L
    $height = 0L
    for ($index = 0; $index -lt 4; $index++) {
        $width = $width * 256 + $Bytes[16 + $index]
        $height = $height * 256 + $Bytes[20 + $index]
    }
    if ($width -notin @(64, 128, 256) -or $height * 2 -ne $width) {
        throw "Armor texture must use the humanoid 2:1 UV layout at 64, 128 or 256 pixels wide: $Path"
    }
    return @($width, $height)
}

function Get-ArmorAssetRegistry {
    $catalog = Get-PackJson (Join-Path $root 'armor-assets.json')
    if (($catalog.format_version -isnot [int] -and $catalog.format_version -isnot [long]) -or
        $catalog.format_version -ne 1 -or $catalog.items -isnot [array] -or
        ($catalog.PSObject.Properties.Name | Where-Object { $_ -cnotin @('format_version', 'items') })) {
        throw 'armor-assets.json must declare integer format_version 1 and an items array.'
    }
    $models = Get-ItemModelRegistry
    $ids = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $baseSlots = @{
        HEAD = '^minecraft:(?:(?:leather|chainmail|iron|golden|diamond|netherite|copper)_helmet|turtle_helmet)\z'
        CHEST = '^minecraft:(?:leather|chainmail|iron|golden|diamond|netherite|copper)_chestplate\z'
        LEGS = '^minecraft:(?:leather|chainmail|iron|golden|diamond|netherite|copper)_leggings\z'
        FEET = '^minecraft:(?:leather|chainmail|iron|golden|diamond|netherite|copper)_boots\z'
    }
    foreach ($item in $catalog.items) {
        if (($item.PSObject.Properties.Name | Where-Object {
            $_ -cnotin @('id', 'base_item', 'custom_model_data', 'slot', 'equipment_asset')
        }) -or $item.id -isnot [string] -or $item.id -cnotmatch '^[a-z0-9]+(?:_[a-z0-9]+)*\z' -or
            !$ids.Add($item.id) -or !(Test-ArmorAssetId $item.equipment_asset) -or
            $item.slot -isnot [string] -or $item.slot -cnotin @('HEAD', 'CHEST', 'LEGS', 'FEET')) {
            throw "Invalid or duplicate armor asset entry: $($item.id)"
        }
        if (($item.custom_model_data -isnot [int] -and $item.custom_model_data -isnot [long]) -or
            !$models.ContainsKey($item.custom_model_data)) {
            throw "Armor $($item.id) must reference a registered integer custom_model_data."
        }
        $registered = $models[$item.custom_model_data]
        if ($registered.id -cne $item.id -or $registered.base_item -cne $item.base_item -or
            $registered.render_mode -ceq 'generated' -or $registered.java_virtual_trident -eq $true -or
            $item.base_item -cnotmatch $baseSlots[$item.slot]) {
            throw "Armor $($item.id) must match the item registry and a native base item for $($item.slot)."
        }
        if (@($models.Values | Where-Object { $_.id -ceq "hidden_armor_$($item.id)" }).Count -gt 0) {
            throw "Armor $($item.id) has a hidden identifier that collides with a registered item."
        }
    }
    return ,$catalog.items
}

function Confirm-BedrockArmorRenderers {
    param($Description, [string] $id)

    if (@($Description.render_controllers).Count -ne 2) {
        throw "Armor attachable $id must use exactly one conditional held and one conditional worn renderer."
    }
    foreach ($entry in $Description.render_controllers) {
        if ($entry -is [string] -or @($entry.PSObject.Properties.Name).Count -ne 1) {
            throw "Armor attachable $id must declare exactly one renderer per condition."
        }
    }
    $renderer = @($Description.render_controllers | Where-Object {
        $_ -isnot [string] -and $_.PSObject.Properties.Name -ccontains 'controller.render.astralrecord.armor_worn'
    })
    $condition = "context.item_slot != 'main_hand' && context.item_slot != 'off_hand'"
    if ($renderer.Count -ne 1 -or $renderer[0].'controller.render.astralrecord.armor_worn' -isnot [string] -or
        $renderer[0].'controller.render.astralrecord.armor_worn' -cne $condition) {
        throw "Armor attachable $id must render its worn geometry only outside the hand slots."
    }
    $held = @($Description.render_controllers | Where-Object {
        $_ -isnot [string] -and $_.PSObject.Properties.Name -ccontains 'controller.render.item_default'
    })
    $heldCondition = "context.item_slot == 'main_hand' || context.item_slot == 'off_hand'"
    if ($held.Count -ne 1 -or $held[0].'controller.render.item_default' -isnot [string] -or
        $held[0].'controller.render.item_default' -cne $heldCondition) {
        throw "Armor attachable $id must render its held geometry only in the hand slots."
    }
}

function Confirm-BedrockArmorAsset {
    param($Entries, $Item)

    $id = $Item.id
    $geometryPath = "models/entity/armor/$id.geo.json"
    $texturePath = "textures/models/armor/$id.png"
    Add-UniqueArmorEntry $Entries $bedrockRoot $geometryPath
    Add-UniqueArmorEntry $Entries $bedrockRoot $texturePath
    $size = Get-ArmorPngSize $Entries[$texturePath] $texturePath
    $attachable = ConvertFrom-Json -InputObject ($utf8.GetString($Entries["attachables/$id.json"]))
    $description = $attachable.'minecraft:attachable'.description
    $geometry = Get-PackJson (Join-Path $bedrockRoot $geometryPath)
    $worn = @($geometry.'minecraft:geometry' | Where-Object {
        $_.description.identifier -ceq $description.geometry.worn
    })
    if ($worn.Count -ne 1 -or $description.geometry.worn -isnot [string] -or
        $description.textures.armor -isnot [string] -or $worn[0].description.identifier -isnot [string] -or
        $description.textures.armor -cne "textures/models/armor/$id" -or
        $worn[0].description.texture_width -ne $size[0] -or $worn[0].description.texture_height -ne $size[1]) {
        throw "Armor attachable $id must reference its worn geometry and humanoid armor texture."
    }
    $bindings = @($worn[0].bones | Where-Object { $_.cubes } | ForEach-Object { $_.binding })
    $required = switch ($Item.slot) {
        'HEAD' { @("'head'") }
        'CHEST' { @("'body'", "'rightarm'", "'leftarm'") }
        'LEGS' { @("'body'", "'rightleg'", "'leftleg'") }
        'FEET' { @("'rightleg'", "'leftleg'") }
    }
    foreach ($binding in $required) {
        if ($bindings -cnotcontains $binding) {
            throw "Armor geometry $id must bind visible cuboids to $binding for $($Item.slot)."
        }
    }
    Confirm-BedrockArmorRenderers $description $id
    # A second static Bedrock item represents the sender's explicit armor-hidden flag.
    # It keeps the same held geometry/texture/animation and can never render on the body.
    $heldCondition = "context.item_slot == 'main_hand' || context.item_slot == 'off_hand'"
    $hidden = ConvertFrom-Json -InputObject (ConvertTo-Json -InputObject $attachable -Depth 32)
    $hiddenDescription = $hidden.'minecraft:attachable'.description
    $hiddenDescription.identifier = "astralrecord:hidden_armor_$id"
    $hiddenDescription.geometry.PSObject.Properties.Remove('worn')
    $hiddenDescription.textures.PSObject.Properties.Remove('armor')
    $hiddenDescription.render_controllers = @([ordered] @{
        'controller.render.item_default' = $heldCondition
    })
    Add-PackJson $Entries "attachables/hidden_armor_$id.json" $hidden
}

function Add-ArmorAssetEntries {
    param($JavaEntries, $BedrockEntries)

    $items = Get-ArmorAssetRegistry
    foreach ($item in $items) {
        $assetPath = $item.equipment_asset.Substring('astralrecord:'.Length)
        $definitionPath = "assets/astralrecord/equipment/$assetPath.json"
        Add-UniqueArmorEntry $JavaEntries $root $definitionPath
        $definition = Get-PackJson (Join-Path $root $definitionPath)
        $requiredLayer = if ($item.slot -ceq 'LEGS') { 'humanoid_leggings' } else { 'humanoid' }
        if (!$definition.layers.$requiredLayer -or
            ($definition.layers.PSObject.Properties.Name | Where-Object { $_ -cnotin @('humanoid', 'humanoid_leggings') })) {
            throw "Armor $($item.id) must define its $requiredLayer equipment texture layer."
        }
        foreach ($type in $definition.layers.PSObject.Properties) {
            if ($type.Value -isnot [array] -or $type.Value.Count -eq 0) {
                throw "Armor layer $($type.Name) in $definitionPath must be a non-empty array."
            }
            foreach ($layer in $type.Value) {
                if (!(Test-ArmorAssetId $layer.texture)) {
                    throw "Armor layer in $definitionPath must reference an astralrecord texture."
                }
                $textureId = $layer.texture.Substring('astralrecord:'.Length)
                $texturePath = "assets/astralrecord/textures/entity/equipment/$($type.Name)/$textureId.png"
                Add-UniqueArmorEntry $JavaEntries $root $texturePath
                $null = Get-ArmorPngSize $JavaEntries[$texturePath] $texturePath
            }
        }
        Confirm-BedrockArmorAsset $BedrockEntries $item
    }
    if ($items.Count -gt 0) {
        $catalogPath = 'astralrecord_item_models.json'
        $catalog = ConvertFrom-Json -InputObject ($utf8.GetString($BedrockEntries[$catalogPath]))
        foreach ($item in $items) {
            $mapping = @($catalog.items | Where-Object { $_.id -ceq $item.id })
            if ($mapping.Count -ne 1) { throw "Armor $($item.id) has no unique distributed item mapping." }
            Add-Member -InputObject $mapping[0] -NotePropertyName 'armor' -NotePropertyValue $true
        }
        $BedrockEntries[$catalogPath] = $utf8.GetBytes((ConvertTo-Json -InputObject $catalog -Depth 32 -Compress) + "`n")
        $controllerPath = 'render_controllers/armor_items.render_controllers.json'
        Add-UniqueArmorEntry $BedrockEntries $bedrockRoot $controllerPath
        $controllers = Get-PackJson (Join-Path $bedrockRoot $controllerPath)
        $controller = $controllers.render_controllers.'controller.render.astralrecord.armor_worn'
        if ($controller.geometry -isnot [string] -or $controller.geometry -cne 'Geometry.worn' -or
            $controller.textures -isnot [array] -or $controller.textures.Count -ne 1 -or
            $controller.textures[0] -isnot [string] -or $controller.textures[0] -cne 'Texture.armor') {
            throw 'The armor worn render controller must use Geometry.worn and Texture.armor.'
        }
    }
}
