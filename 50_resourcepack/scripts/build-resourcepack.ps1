param(
    [string] $OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist')
)

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
        if ($name.EndsWith('.json') -or $name.EndsWith('.mcmeta')) {
            # Keep tracked JSON byte-identical across LF and CRLF checkouts.
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
$java = Read-PackEntries $root @(
    'pack.mcmeta'
    'assets/astralrecord/font/damage_number.json'
    'assets/astralrecord/textures/font/damage_number.png'
    'assets/astralrecord/font/combat_hud.json'
    'assets/astralrecord/textures/font/combat_hud.png'
    'assets/astralrecord/font/weapon_attack.json'
    'assets/astralrecord/lang/en_us.json'
    'assets/astralrecord/textures/font/weapon_attack.png'
    'assets/astralrecord/items/action_ring.json'
    'assets/astralrecord/models/item/action_ring.json'
    'assets/astralrecord/textures/item/action_ring.png'
    'assets/astralrecord/textures/item/action_ring_icons.png'
    'assets/astralrecord/textures/item/action_ring_panel.png'
)
$bedrock = Read-PackEntries $bedrockRoot @(
    'manifest.json'
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
    'textures/ui/astralrecord/actionbar_plate.png'
    'textures/ui/astralrecord/actionbar_plate.json'
    'textures/ui/astralrecord/actionbar_ornament.png'
    'entity/combat_result.entity.json'
    'animations/combat_result.animation.json'
    'models/entity/combat_result.geo.json'
    'render_controllers/combat_result.render_controllers.json'
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
)
Add-ItemModelEntries $java $bedrock
Add-ActionRingPanelEntries $java $bedrock

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

$destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($destination) | Out-Null
Write-Pack (Join-Path $destination 'AstralRecordResourcePack.zip') $java
Write-Pack (Join-Path $destination 'AstralRecordResourcePack.mcpack') $bedrock
