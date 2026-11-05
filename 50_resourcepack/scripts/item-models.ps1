. (Join-Path $PSScriptRoot 'item-model-grips.ps1')
. (Join-Path $PSScriptRoot 'item-models-generated.ps1')

function Get-PackJson {
    param([string] $Path)

    return ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($Path, $utf8))
}

function Add-PackJson {
    param($Entries, [string] $Name, $Value)

    $json = ConvertTo-Json -InputObject $Value -Depth 32 -Compress
    # Windows PowerShell escapes HTML characters in JSON strings, including Molang operators.
    $json = [regex]::Replace($json, '(?<!\\)((?:\\\\)*)\\u00(26|27|3c|3e)', {
        param($match)
        $match.Groups[1].Value + [char] [Convert]::ToInt32($match.Groups[2].Value, 16)
    })
    $Entries.Add($Name, $utf8.GetBytes($json + "`n"))
}

function New-JavaModelReference {
    param([string] $Model)

    return [ordered] @{
        type = 'minecraft:model'
        model = $Model
    }
}

function Get-ItemModelRegistry {
    $registry = Get-PackJson (Join-Path $root 'item-models.json')
    $version = $registry.format_version
    if (($version -isnot [int] -and $version -isnot [long]) -or $version -ne 1 -or $registry.items -isnot [array] -or
        ($registry.PSObject.Properties.Name | Where-Object { $_ -cnotin @('format_version', 'items') })) {
        throw 'item-models.json must declare integer format_version 1 and an items array.'
    }

    $ids = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $models = [System.Collections.Generic.SortedDictionary[int, object]]::new()
    foreach ($item in $registry.items) {
        $properties = @('id', 'base_item', 'custom_model_data', 'java_virtual_trident', 'grip', 'render_mode')
        $unknown = @($item.PSObject.Properties.Name | Where-Object { $_ -cnotin $properties })
        if ($unknown.Count -gt 0) {
            throw "Unrecognized item model fields: $($unknown -join ', ')"
        }
        if ($item.id -isnot [string] -or $item.id -cnotmatch '^[a-z0-9]+(?:_[a-z0-9]+)*\z') {
            throw "Invalid item model id: $($item.id)"
        }
        if (!$ids.Add($item.id)) {
            throw "Duplicate item model id: $($item.id)"
        }
        if ($item.base_item -isnot [string] -or $item.base_item -cnotmatch '^minecraft:[a-z0-9]+(?:_[a-z0-9]+)*\z') {
            throw "Invalid base_item for $($item.id): $($item.base_item)"
        }
        $modelData = $item.custom_model_data
        # The model ID and its reset threshold must be representable in the float component.
        if (($modelData -isnot [int] -and $modelData -isnot [long]) -or $modelData -lt 1 -or $modelData -gt 16777215) {
            throw "custom_model_data for $($item.id) must be an integer from 1 through 16777215."
        }
        if ($models.ContainsKey($modelData)) {
            throw "Duplicate custom_model_data: $modelData"
        }
        if ($item.java_virtual_trident -isnot [bool]) {
            throw "java_virtual_trident for $($item.id) is required and must be a boolean."
        }
        if (($item.java_virtual_trident -or $item.PSObject.Properties.Name -ccontains 'grip') -and
            !(Test-ItemGripVector $item.grip)) {
            throw "grip for $($item.id) must contain three model coordinates."
        }
        $null = Test-GeneratedItemModel $item
        $models.Add($modelData, $item)
    }
    return ,$models
}

function Add-JavaItemAssets {
    param($Entries, [string] $Id, [bool] $Bow, $Grip, $GripTargets)

    $texture = "assets/astralrecord/textures/item/custom/$Id.png"
    Add-PackEntries $Entries $root @($texture)

    $modelNames = @($Id)
    if ($Bow) {
        $modelNames += @("${Id}_pull_0", "${Id}_pull_1", "${Id}_pull_2")
    }
    foreach ($modelName in $modelNames) {
        $path = "assets/astralrecord/models/item/custom/$modelName.json"
        Add-PackEntries $Entries $root @($path)
        $model = ConvertFrom-Json -InputObject ($utf8.GetString($Entries[$path]))
        $textures = $model.textures.PSObject.Properties
        $expected = "astralrecord:item/custom/$Id"
        if (!$model.elements -or $textures.Value -cnotcontains $expected) {
            throw "$path must contain 3D elements and use $expected."
        }
        if ($Grip) { Confirm-JavaItemGrip $model $Grip $GripTargets $path }
        foreach ($reference in $textures.Value) {
            if ($reference -ceq $expected -or $reference -cmatch '^minecraft:[a-z0-9_/]+\z') {
                continue
            }
            if ($reference -cmatch '^#(.+)\z' -and $textures.Name -ccontains $Matches[1]) {
                continue
            }
            throw "Unresolved texture $reference in $path."
        }
        foreach ($element in $model.elements) {
            foreach ($face in $element.faces.PSObject.Properties) {
                if ($face.Value.texture -cnotmatch '^#(.+)\z' -or $textures.Name -cnotcontains $Matches[1]) {
                    throw "Unresolved face texture $($face.Value.texture) in $path."
                }
            }
        }
    }
}

function Add-BedrockItemAssets {
    param($Entries, [string] $Id, [bool] $CheckGrip)

    $attachablePath = "attachables/$Id.json"
    $geometryPath = "models/entity/items/$Id.geo.json"
    $animationPath = "animations/$Id.animation.json"
    $texture = "textures/entity/items/$Id"
    Add-PackEntries $Entries $bedrockRoot @(
        $attachablePath
        $geometryPath
        $animationPath
        "$texture.png"
        "textures/items/$Id.png"
    )

    $attachable = ConvertFrom-Json -InputObject ($utf8.GetString($Entries[$attachablePath]))
    $geometry = ConvertFrom-Json -InputObject ($utf8.GetString($Entries[$geometryPath]))
    $animation = ConvertFrom-Json -InputObject ($utf8.GetString($Entries[$animationPath]))
    $description = $attachable.'minecraft:attachable'.description
    if ($description.identifier -cne "astralrecord:$Id" -or $description.textures.default -cne $texture) {
        throw "$attachablePath must identify astralrecord:$Id and reference $texture."
    }
    $geometryIds = @($geometry.'minecraft:geometry' | ForEach-Object { $_.description.identifier })
    if (!$description.geometry.default -or $description.geometry.default -cnotin $geometryIds) {
        throw "$attachablePath must reference a geometry from $geometryPath."
    }
    $animationIds = $animation.animations.PSObject.Properties.Name
    $animationReferences = $description.animations.PSObject.Properties.Value
    if (!$animationReferences -or ($animationReferences | Where-Object { $_ -cnotin $animationIds })) {
        throw "$attachablePath must reference animations from $animationPath."
    }
    if ($CheckGrip) { Confirm-BedrockItemGrip $geometry.'minecraft:geometry' $description $animation.animations $Id }
    return $description
}

function Add-BedrockHeldWeaponEntries {
    param($Entries, $Weapons)

    if (!$Weapons.Count) { return }
    $attachable = Get-PackJson (Join-Path $root 'bedrock-attachable-fallbacks/trident.entity.json')
    $description = $attachable.'minecraft:attachable'.description
    $controllers = [ordered] @{}
    $model = 'variable.astralrecord_held_weapon_model'
    $selected = 'variable.astralrecord_rendered_weapon'
    $property = 'astralrecord:held_weapon_model'
    $preAnimation = @($description.scripts.pre_animation) + @(
        "$model = context.item_slot == 'main_hand' && ((context.owning_entity->query.has_property('$property')) ?? 0.0) ? ((context.owning_entity->query.property('$property')) ?? 0.0) : 0.0;"
        "$selected = 0.0;"
    )
    # Leave the vanilla trident active for zero, unrecognized numbers and other hands.
    $animate = @([ordered] @{ wield = "$selected == 0.0" })
    $render = @([ordered] @{ 'controller.render.item_default' = "$selected == 0.0" })

    foreach ($weapon in $Weapons.GetEnumerator()) {
        $number = $weapon.Key
        $source = $weapon.Value
        $id = $source.identifier.Substring('astralrecord:'.Length)
        $alias = "weapon_$id"
        $enchanted = "enchanted_weapon_$id"
        $controller = "controller.render.astralrecord.held_$id"
        $condition = "$selected == $number"
        $preAnimation += "$selected = $model == $number ? $number : $selected;"

        if (!$source.materials.default -or !$source.materials.enchanted -or !$source.textures.enchanted) {
            throw "Held weapon attachable $id must define its default and enchanted render materials and textures."
        }
        Add-Member -InputObject $description.geometry -NotePropertyName $alias -NotePropertyValue $source.geometry.default
        Add-Member -InputObject $description.textures -NotePropertyName $alias -NotePropertyValue $source.textures.default
        Add-Member -InputObject $description.materials -NotePropertyName $alias -NotePropertyValue $source.materials.default
        Add-Member -InputObject $description.materials -NotePropertyName $enchanted -NotePropertyValue $source.materials.enchanted
        Add-Member -InputObject $description.textures -NotePropertyName $enchanted -NotePropertyValue $source.textures.enchanted
        $controllers.Add($controller, [ordered] @{
            geometry = "geometry.$alias"
            materials = @([ordered] @{ '*' = "variable.is_enchanted ? material.$enchanted : material.$alias" })
            textures = @("texture.$alias", "texture.$enchanted")
        })
        $render += [ordered] @{ $controller = $condition }

        foreach ($line in $source.scripts.pre_animation) {
            # Run only the selected model's assignments, including when several models use the same variable.
            if ($line -cnotmatch '^((?:variable|v)\.[a-z_][a-z0-9_]*)\s*=\s*([^;]+);\s*\z') {
                throw "Invalid pre_animation for $id. Each line must assign one Molang variable."
            }
            $variable = $Matches[1]
            $value = $Matches[2]
            $preAnimation += "$variable = $condition ? ($value) : ($variable ?? 0.0);"
        }

        foreach ($aliasName in @('first_person', 'third_person', 'attack')) {
            $animation = $source.animations.PSObject.Properties[$aliasName]
            if (!$animation) { continue }
            $name = "${alias}_$aliasName"
            Add-Member -InputObject $description.animations -NotePropertyName $name -NotePropertyValue $animation.Value
            foreach ($trigger in $source.scripts.animate) {
                if ($trigger -is [string]) {
                    if ($trigger -ceq $aliasName) {
                        $animate += [ordered] @{ $name = $condition }
                    }
                } else {
                    $predicate = $trigger.PSObject.Properties[$aliasName]
                    if ($predicate) {
                        $animate += [ordered] @{ $name = "$condition && ($($predicate.Value))" }
                    }
                }
            }
        }
    }

    $description.scripts.pre_animation = $preAnimation
    $description.scripts.animate = $animate
    $description.render_controllers = $render
    Add-PackJson $Entries 'attachables/trident.entity.json' $attachable
    Add-PackJson $Entries 'render_controllers/held_weapon.render_controllers.json' ([ordered] @{
        format_version = '1.10'
        render_controllers = $controllers
    })
}

function Add-JavaItemDispatch {
    param($Entries, [string] $Base, $Mappings, [string] $Overlay = '')

    $prefix = if ($Overlay) { "$Overlay/" } else { '' }
    $template = "java-item-fallbacks/$prefix$Base.json"
    $definition = Get-PackJson (Join-Path $root $template)
    $fallback = $definition.model
    if (!$fallback.type) {
        throw "Missing vanilla item model in $template."
    }
    $dispatch = [System.Collections.Generic.SortedDictionary[double, object]]::new()
    foreach ($mapping in $Mappings.GetEnumerator()) {
        $number = [int] $mapping.Key
        $dispatch[[double] $number] = [ordered] @{ threshold = $number; model = $mapping.Value }

        # The very next float resets the range, including for non-integral component values.
        $bits = [BitConverter]::ToInt32([BitConverter]::GetBytes([single] $number), 0)
        $next = [double] [BitConverter]::ToSingle([BitConverter]::GetBytes($bits + 1), 0)
        if ($next -ne [Math]::Truncate($next) -or !$Mappings.Contains([string] [int] $next)) {
            # Decimal serialization is stable across PowerShell versions and round-trips to this float.
            $threshold = if ($next -eq [Math]::Truncate($next)) { [int] $next } else { [decimal] $next }
            $dispatch[$next] = [ordered] @{ threshold = $threshold; model = $fallback }
        }
    }
    $definition.model = [ordered] @{
        type = 'minecraft:range_dispatch'
        property = 'minecraft:custom_model_data'
        index = 0
        fallback = $fallback
        entries = @($dispatch.Values)
    }
    Add-PackJson $Entries "${prefix}assets/minecraft/items/$Base.json" $definition
}

function Add-ItemModelEntries {
    param($JavaEntries, $BedrockEntries)

    $models = Get-ItemModelRegistry
    $gripTargets = Get-PackJson (Join-Path $root 'item-model-grip-targets.json')
    $bedrockItems = @()
    $heldWeapons = [System.Collections.Generic.SortedDictionary[int, object]]::new()
    $iconTextures = [ordered] @{}
    $byBase = @{}
    foreach ($item in $models.Values) {
        if (Test-GeneratedItemModel $item) {
            $bedrockItems += Add-GeneratedItemAssets $JavaEntries $BedrockEntries $item $iconTextures $byBase
            continue
        }
        $id = $item.id
        $base = $item.base_item.Substring('minecraft:'.Length)
        $isBow = $base -ceq 'bow'
        $grip = if ($item.java_virtual_trident) { $item.grip }
        Add-JavaItemAssets $JavaEntries $id $isBow $grip $gripTargets.($item.base_item)
        $description = Add-BedrockItemAssets $BedrockEntries $id $item.java_virtual_trident
        if ($item.java_virtual_trident) {
            $heldWeapons.Add($item.custom_model_data, $description)
        }
        $iconTextures.Add("astralrecord.$id", [ordered] @{ textures = "textures/items/$id" })
        $bedrockItems += [ordered] @{
            id = $id
            base_item = $item.base_item
            custom_model_data = $item.custom_model_data
            held_weapon = $item.java_virtual_trident
        }

        $rest = New-JavaModelReference "astralrecord:item/custom/$id"
        $model = $rest
        if ($isBow) {
            # Match the vanilla bow's using_item, scale, and draw thresholds.
            $model = [ordered] @{
                type = 'minecraft:condition'
                property = 'minecraft:using_item'
                on_false = $rest
                on_true = [ordered] @{
                    type = 'minecraft:range_dispatch'
                    property = 'minecraft:use_duration'
                    scale = 0.05
                    fallback = New-JavaModelReference "astralrecord:item/custom/${id}_pull_0"
                    entries = @(
                        [ordered] @{ threshold = 0.65; model = New-JavaModelReference "astralrecord:item/custom/${id}_pull_1" }
                        [ordered] @{ threshold = 0.9; model = New-JavaModelReference "astralrecord:item/custom/${id}_pull_2" }
                    )
                }
            }
        }
        if (!$byBase.ContainsKey($base)) {
            $byBase[$base] = [ordered] @{}
        }
        $byBase[$base].Add([string] $item.custom_model_data, $model)
        if ($item.java_virtual_trident -and $base -cne 'trident') {
            if (!$byBase.ContainsKey('trident')) {
                $byBase.trident = [ordered] @{}
            }
            # The temporary trident is used for action-ring input, not bow draw.
            $byBase.trident.Add([string] $item.custom_model_data, $rest)
        }
    }

    foreach ($base in $byBase.Keys) {
        Add-JavaItemDispatch $JavaEntries $base $byBase[$base]
        Add-GeneratedItemOverlayDispatch $JavaEntries $base $byBase[$base]
    }
    if ($byBase.ContainsKey('trident')) {
        # Format 83 moved the native trident transform from the renderer into the item model.
        Add-JavaItemDispatch $JavaEntries 'trident' $byBase.trident 'transformed_item_models'
    }

    Add-BedrockHeldWeaponEntries $BedrockEntries $heldWeapons
    Add-PackJson $BedrockEntries 'textures/item_texture.json' ([ordered] @{
        resource_pack_name = 'astralrecord'
        texture_name = 'atlas.items'
        texture_data = $iconTextures
    })
    Add-PackJson $BedrockEntries 'astralrecord_item_models.json' ([ordered] @{
        format_version = 1
        items = $bedrockItems
    })
}
