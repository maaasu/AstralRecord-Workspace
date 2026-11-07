function Add-BuffHudJavaEntries {
    param($Entries)

    $config = Get-PackJson (Join-Path $root 'resourcepack.config.json')
    $resources = if ($config.absolutePathOverrides.astralRecordPluginResources) {
        $config.absolutePathOverrides.astralRecordPluginResources
    } else {
        Join-Path $root $config.references.astralRecordPluginResources
    }
    $icons = Get-PackJson (Join-Path $resources 'buff-hud-icons.json')
    if ($icons -isnot [array] -or $icons.Count -lt 2 -or $icons.Count -gt 256 -or
        $icons[0] -cne 'astralrecord:buff_hud/general_buff.png' -or
        $icons[1] -cne 'astralrecord:buff_hud/general_debuff.png') {
        throw 'buff-hud-icons.json must start with both fallback icons and reserve U+EA00 through U+EAFF.'
    }
    $atlas = 'assets/astralrecord/textures/font/buff_hud.png'
    $font = 'assets/astralrecord/font/buff_hud.json'
    Add-PackEntries $Entries $root @($atlas, $font)

    # The generator verifies the pixels. Check the ordered inputs and generated outputs
    # together so normal pack builds can reject stale assets without requiring Pillow.
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hashes = foreach ($icon in $icons) {
            if ($icon -isnot [string] -or $icon -cnotmatch '^astralrecord:(buff_hud/[a-z0-9]+(?:_[a-z0-9]+)*\.png)\z' -or !$seen.Add($icon)) {
                throw "Invalid or duplicate buff HUD texture: $icon"
            }
            $source = Join-Path $root ('assets/astralrecord/textures/' + $Matches[1])
            $digest = $sha256.ComputeHash([IO.File]::ReadAllBytes($source))
            "$icon " + [BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
        }
        foreach ($path in @($atlas, $font)) {
            $hashes += [BitConverter]::ToString($sha256.ComputeHash($Entries[$path])).Replace('-', '').ToLowerInvariant()
        }
        $digest = $sha256.ComputeHash($utf8.GetBytes($hashes -join "`n"))
        $fingerprint = [BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant()
        if ($fingerprint -cne [IO.File]::ReadAllText((Join-Path $root 'buff-hud-assets.sha256'), $utf8)) {
            throw 'Buff HUD assets differ from their inputs. Run scripts/generate-buff-hud.py to regenerate them.'
        }
    } finally {
        $sha256.Dispose()
    }
}
