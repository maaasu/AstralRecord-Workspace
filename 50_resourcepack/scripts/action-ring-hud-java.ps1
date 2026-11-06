function Add-ActionRingHudJavaEntries {
    param($Entries, $SkillIcons)

    $source = Join-Path $root 'action-ring-hud/java'
    $manifestPath = Join-Path $source 'skill_tiles.sha256.json'
    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
    $regenerate = 'Regenerate the action-ring HUD with scripts/generate-action-ring-hud.py after changing skill sprites.'
    if (@($manifest.PSObject.Properties).Count -ne $SkillIcons.Ids.Count) {
        throw $regenerate
    }

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($id in $SkillIcons.Ids) {
            $tile = [Convert]::FromBase64String($SkillIcons.Tiles.PSObject.Properties[[string]$id].Value)
            $digest = [BitConverter]::ToString($sha256.ComputeHash($tile)).Replace('-', '').ToLowerInvariant()
            $property = $manifest.PSObject.Properties[[string]$id]
            if (!$property -or $property.Value -cne $digest) {
                throw "Skill sprite $id changed. $regenerate"
            }
        }
    } finally {
        $sha256.Dispose()
    }

    $font = [IO.File]::ReadAllText((Join-Path $source 'action_ring_hud.json'), $utf8).Replace("`r`n", "`n")
    $texturePrefix = 'assets/astralrecord/textures/font/action_ring_hud'
    foreach ($atlas in @('ring') + @(0..8 | ForEach-Object { "skills_$_" })) {
        $Entries.Add("$texturePrefix/$atlas.png", [IO.File]::ReadAllBytes((Join-Path $source "$atlas.png")))
    }
    $Entries.Add('assets/astralrecord/font/action_ring_hud.json', $utf8.GetBytes($font))
}
