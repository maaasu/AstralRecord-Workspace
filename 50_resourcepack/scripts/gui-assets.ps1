# GUI symbols are separate from the gameplay item registry and never require a filebase item.
function Get-GuiIconRegistry {
    param($ItemModels)

    $path = Join-Path $root 'gui-icons.json'
    $registry = Get-PackJson $path
    if (($registry.format_version -isnot [int] -and $registry.format_version -isnot [long]) -or
        $registry.format_version -ne 1 -or $registry.icons -isnot [array] -or !$registry.icons.Count -or
        ($registry.PSObject.Properties.Name | Where-Object { $_ -cnotin @('format_version', 'icons') })) {
        throw 'gui-icons.json must declare integer format_version 1 and a nonempty icons array.'
    }
    $pluginPath = Join-Path $root '../10_plugin/AstralRecord/src/main/resources/gui-icons.json'
    $sourceText = [System.IO.File]::ReadAllText($path, $utf8).Replace("`r`n", "`n")
    $pluginText = [System.IO.File]::ReadAllText($pluginPath, $utf8).Replace("`r`n", "`n")
    if ($sourceText -cne $pluginText) {
        throw 'The plugin gui-icons.json must be an exact copy of the pack GUI registry.'
    }
    $ids = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $numbers = [System.Collections.Generic.HashSet[int]]::new()
    $roles = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($item in $ItemModels.Values) {
        $null = $ids.Add($item.id)
        $null = $numbers.Add($item.custom_model_data)
    }
    $sorted = [System.Collections.Generic.SortedDictionary[int, object]]::new()
    foreach ($icon in $registry.icons) {
        if (($icon.PSObject.Properties.Name | Where-Object { $_ -cnotin @('id', 'role', 'base_item', 'custom_model_data') }) -or
            $icon.role -isnot [string] -or $icon.role -cnotmatch '^[a-z]+(?:_[a-z]+)*\z' -or
            $icon.base_item -isnot [string] -or $icon.base_item -cnotmatch '^minecraft:([a-z0-9]+(?:_[a-z0-9]+)*)\z') {
            throw 'GUI icons require only id, role, base_item and custom_model_data with lowercase asset names.'
        }
        $base = $icon.base_item.Substring('minecraft:'.Length)
        $expected = "gui_$($icon.role)_$base"
        $number = $icon.custom_model_data
        if ($icon.id -isnot [string] -or $icon.id -cne $expected -or !$ids.Add($icon.id) -or
            !$roles.Add("$($icon.role):$base") -or
            ($number -isnot [int] -and $number -isnot [long]) -or $number -lt 910001 -or $number -gt 919999 -or
            !$numbers.Add($number)) {
            throw "Invalid or duplicate GUI icon mapping: $($icon.id). GUI model numbers reserve 910001 through 919999."
        }
        if (!(Test-Path -LiteralPath (Join-Path $root "java-item-fallbacks/$base.json"))) {
            throw "GUI base material $base requires its complete vanilla item definition."
        }
        $sorted.Add($number, $icon)
    }
    return ,$sorted
}

function Confirm-GuiPng {
    param([byte[]] $Bytes, [string] $Path, [switch] $Icon)

    if ($Bytes.Length -lt 33 -or [BitConverter]::ToString($Bytes, 0, 8) -cne '89-50-4E-47-0D-0A-1A-0A' -or
        [System.Text.Encoding]::ASCII.GetString($Bytes, 12, 4) -cne 'IHDR') {
        throw "Missing PNG header: $Path"
    }
    $width = $Bytes[16] * 16777216 + $Bytes[17] * 65536 + $Bytes[18] * 256 + $Bytes[19]
    $height = $Bytes[20] * 16777216 + $Bytes[21] * 65536 + $Bytes[22] * 256 + $Bytes[23]
    if ($width -lt 1 -or $height -lt 1 -or $width -gt 512 -or $height -gt 512 -or
        $Bytes[24] -ne 8 -or $Bytes[25] -ne 6 -or
        ($Icon -and ($width -ne $height -or $width -cnotin @(16, 32)))) {
        throw "GUI PNG must be bounded 8-bit RGBA; icons must be square 16 or 32 pixels: $Path"
    }
}

function Add-GuiIconModelEntries {
    param($JavaEntries, $BedrockEntries, $IconTextures, $ByBase, $ItemModels)

    $icons = Get-GuiIconRegistry $ItemModels
    $catalog = @()
    foreach ($icon in $icons.Values) {
        $id = $icon.id
        $role = $icon.role
        $base = $icon.base_item.Substring('minecraft:'.Length)
        $javaPath = "assets/astralrecord/textures/gui/gui_$role.png"
        $bedrockPath = "textures/ui/astralrecord/gui/gui_$role.png"
        if (!$JavaEntries.ContainsKey($javaPath)) {
            Add-PackEntries $JavaEntries $root @($javaPath)
            Add-PackEntries $BedrockEntries $bedrockRoot @($bedrockPath)
            Confirm-GuiPng $JavaEntries[$javaPath] $javaPath -Icon
            Confirm-GuiPng $BedrockEntries[$bedrockPath] $bedrockPath -Icon
            if ([Convert]::ToBase64String($JavaEntries[$javaPath]) -cne [Convert]::ToBase64String($BedrockEntries[$bedrockPath])) {
                throw "Java and Bedrock GUI symbol PNGs differ: $role"
            }
        }
        # Geyser's existing generated-item validator requires this canonical icon alias.
        $BedrockEntries.Add("textures/items/$id.png", $BedrockEntries[$bedrockPath])
        $IconTextures.Add("astralrecord.$id", [ordered] @{ textures = "textures/items/$id" })
        Add-PackJson $JavaEntries "assets/astralrecord/models/item/gui/$id.json" ([ordered] @{
            parent = 'minecraft:item/generated'
            textures = [ordered] @{ layer0 = "astralrecord:gui/gui_$role" }
        })
        if (!$ByBase.ContainsKey($base)) { $ByBase[$base] = [ordered] @{} }
        $ByBase[$base].Add([string] $icon.custom_model_data, (New-JavaModelReference "astralrecord:item/gui/$id"))
        $catalog += [ordered] @{
            id = $id
            base_item = $icon.base_item
            custom_model_data = $icon.custom_model_data
            render_mode = 'generated'
        }
    }
    return $catalog
}

function Add-GuiThemeEntries {
    param($JavaEntries, $BedrockEntries)

    Add-PackEntries $JavaEntries $root @('assets/astralrecord/font/gui.json', 'NOTICE-GUI.txt')
    Add-PackEntries $BedrockEntries $root @('NOTICE-GUI.txt')
    $font = Get-PackJson (Join-Path $root 'assets/astralrecord/font/gui.json')
    if (!$font.providers -or $font.providers -isnot [array]) { throw 'GUI font requires a providers array.' }
    $panels = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($provider in $font.providers) {
        if ($provider.type -cne 'bitmap') { continue }
        if ($provider.file -isnot [string] -or $provider.file -cnotmatch '^astralrecord:gui/(panel_[a-z0-9_]+)\.png\z') {
            throw 'GUI font bitmap providers may reference only namespaced panel PNGs.'
        }
        $path = 'assets/astralrecord/textures/gui/' + $Matches[1] + '.png'
        if ($panels.Add($path)) {
            Add-PackEntries $JavaEntries $root @($path)
            Confirm-GuiPng $JavaEntries[$path] $path
        }
    }
    if (!$panels.Count) { throw 'GUI font must include at least one panel bitmap.' }
    $JavaEntries.Add('gui-theme-version.txt', $utf8.GetBytes('1'))
}

function Add-GuiIconOverlayDispatch {
    param($Entries, [string] $Base, $Mappings)

    # 26.3 moved filled-map color handling into the renderer; retain that vanilla fallback.
    $overlay = 'gui_modern_item_models'
    if (Test-Path -LiteralPath (Join-Path $root "java-item-fallbacks/$overlay/$Base.json")) {
        Add-JavaItemDispatch $Entries $Base $Mappings $overlay
    }
}
