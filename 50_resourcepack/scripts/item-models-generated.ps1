function Test-GeneratedItemModel {
    param($Item)

    if ($Item.PSObject.Properties.Name -cnotcontains 'render_mode') { return $false }
    if ($Item.render_mode -isnot [string] -or $Item.render_mode -cnotin @('3d', 'generated')) {
        throw "render_mode for $($Item.id) must be 3d or generated."
    }
    if ($Item.render_mode -ceq '3d') { return $false }
    if ($Item.java_virtual_trident -or $Item.PSObject.Properties.Name -ccontains 'grip') {
        throw "Generated item $($Item.id) cannot declare a held weapon or a grip."
    }
    return $true
}

function Add-GeneratedItemAssets {
    param($JavaEntries, $BedrockEntries, $Item, $IconTextures, $ByBase)

    $id = $Item.id
    $base = $Item.base_item.Substring('minecraft:'.Length)
    $texture = "astralrecord:item/custom/$id"
    $javaTexture = "assets/astralrecord/textures/item/custom/$id.png"
    $bedrockTexture = "textures/items/$id.png"
    Add-PackEntries $JavaEntries $root @($javaTexture)
    Add-PackEntries $BedrockEntries $bedrockRoot @($bedrockTexture)
    foreach ($bytes in @($JavaEntries[$javaTexture], $BedrockEntries[$bedrockTexture])) {
        if ($bytes.Length -lt 24 -or [BitConverter]::ToString($bytes, 0, 8) -cne '89-50-4E-47-0D-0A-1A-0A') {
            throw "Generated item $id must provide PNG textures for both editions."
        }
    }
    Add-PackJson $JavaEntries "assets/astralrecord/models/item/custom/$id.json" ([ordered] @{
        parent = 'minecraft:item/generated'
        textures = [ordered] @{ layer0 = $texture }
    })
    $IconTextures.Add("astralrecord.$id", [ordered] @{ textures = "textures/items/$id" })
    if (!$ByBase.ContainsKey($base)) { $ByBase[$base] = [ordered] @{} }
    $ByBase[$base].Add([string] $Item.custom_model_data, (New-JavaModelReference "astralrecord:item/custom/$id"))
    return [ordered] @{
        id = $id
        base_item = $Item.base_item
        custom_model_data = $Item.custom_model_data
        render_mode = 'generated'
    }
}

function Add-GeneratedItemOverlayDispatch {
    param($Entries, [string] $Base, $Mappings)

    # Trident already has a dedicated overlay in the 3D item pipeline.
    if ($Base -cne 'trident' -and
        (Test-Path -LiteralPath (Join-Path $root "java-item-fallbacks/transformed_item_models/$Base.json"))) {
        Add-JavaItemDispatch $Entries $Base $Mappings 'transformed_item_models'
    }
}
