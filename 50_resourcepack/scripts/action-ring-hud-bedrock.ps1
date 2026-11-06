function Get-RingHudTokenCondition {
    param([string] $Token)

    # JSON UI string subtraction removes a literal substring. Comparing the result
    # with the input lets the popup test one field without splitting the payload.
    '(not ($astralrecord_actionbar_payload = ($astralrecord_actionbar_payload - ''{0}'')))' -f $Token
}

function New-RingHudTextureControl {
    param([string] $Name, [int] $Layer, [array] $Size, [array] $Offset, [string] $Visible, $Choices,
        [string] $DefaultTexture)

    $control = [ordered]@{
        type = 'image'
        anchor_from = 'center'
        anchor_to = 'center'
        size = $Size
        offset = $Offset
        layer = $Layer
        visible = '({0} and $ring_has_texture)' -f $Visible
        texture = '$ring_texture'
        '$ring_texture' = if ($DefaultTexture) { $DefaultTexture } else { 'textures/ui/astralrecord/action_ring/opening_0' }
        '$ring_has_texture' = [bool]$DefaultTexture
        variables = @(
            foreach ($choice in $Choices) {
                [ordered]@{
                    requires = $choice.Condition
                    '$ring_texture' = $choice.Texture
                    '$ring_has_texture' = $true
                }
            }
        )
    }
    [ordered]@{ $Name = $control }
}

function New-RingHudLabelControl {
    param([string] $Name, [string] $Text, [array] $Offset, [array] $Color, [string] $Visible)

    [ordered]@{
        $Name = [ordered]@{
            type = 'label'
            anchor_from = 'center'
            anchor_to = 'center'
            offset = $Offset
            layer = 9
            text = $Text
            color = $Color
            visible = $Visible
            localize = $false
            shadow = $true
        }
    }
}

function Add-ActionRingHudBedrockEntries {
    param($Bedrock, $SkillIcons)

    if ($SkillIcons.HudRevision -cnotmatch '^[a-f0-9]{64}$') {
        throw 'Invalid action ring HUD revision.'
    }
    $Bedrock.Add('action-ring-hud.sha256', $utf8.GetBytes($SkillIcons.HudRevision))

    # This reserved catalog entry is also used by the RPG renderer for unavailable skills.
    $unavailable = if ($SkillIcons.Ids -contains 2) { 'textures/entity/action_ring_icons/2' }
    $textureRoot = 'textures/ui/astralrecord/action_ring'
    $sourceRoot = Join-Path $PSScriptRoot '../action-ring-hud'
    $textureNames = @(
        foreach ($frame in 0..7) {
            "opening_$frame"
            "confirmed_$frame"
            "selected_$frame"
            foreach ($pulse in 0..2) { "selected_${frame}_$pulse" }
            "cooldown_$frame"
            "mana_$frame"
            "energy_$frame"
            "blocked_$frame"
            foreach ($step in 1..10) { "gauge_${frame}_$step" }
        }
        foreach ($step in 0..24) { "timer_$step" }
    )
    foreach ($name in $textureNames) {
        $Bedrock.Add("$textureRoot/$name.png", [IO.File]::ReadAllBytes((Join-Path $sourceRoot "$name.png")))
    }

    $isRing = Get-RingHudTokenCondition 'arw1|'
    $hidden = Get-RingHudTokenCondition '|p0|'
    $armed = Get-RingHudTokenCondition '|a|'
    $aiming = "(not $armed)"
    $hold = Get-RingHudTokenCondition '|h|'
    $opacity = @(0, 0.2, 0.4, 0.65, 0.84, 1)
    $openingFrames = @(0, 1, 3, 4, 6, 7)
    $focusPulses = @(foreach ($pulse in 0..2) { Get-RingHudTokenCondition "|l$pulse|" })
    $focusSettled = '(not ({0}))' -f ($focusPulses -join ' or ')
    $artSize = @(96, 96)
    $controls = [Collections.Generic.List[object]]::new()
    $controls.Add((New-RingHudTextureControl 'rim' 1 $artSize @(0, 0) $aiming @(
        foreach ($step in 0..5) {
            @{ Condition = Get-RingHudTokenCondition "|p$step|"; Texture = "$textureRoot/opening_$($openingFrames[$step])" }
        }
    )))
    $controls.Add((New-RingHudTextureControl 'confirmed' 1 $artSize @(0, 0) $armed @(
        foreach ($step in 0..7) {
            @{ Condition = Get-RingHudTokenCondition "|q$step|"; Texture = "$textureRoot/confirmed_$step" }
        }
    )))
    $controls.Add((New-RingHudTextureControl 'timer' 3 $artSize @(0, 0) $aiming @(
        foreach ($step in 0..24) {
            @{ Condition = Get-RingHudTokenCondition ('|t{0:00}|' -f $step); Texture = "$textureRoot/timer_$step" }
        }
    )))
    $statusArt = [ordered]@{ c = 'cooldown'; m = 'mana'; e = 'energy'; b = 'blocked'; u = 'blocked' }
    for ($slot = 0; $slot -le 8; $slot++) {
        $position = @(0, -8)
        $iconSize = @(22, 22)
        $iconVisible = $armed
        if ($slot -lt 8) {
            $angle = [Math]::PI * $slot / 4
            $position = @([int][Math]::Round(30 * [Math]::Sin($angle)), [int][Math]::Round(-30 * [Math]::Cos($angle)))
            $iconSize = @(18, 18)
            $empty = Get-RingHudTokenCondition "|s${slot}x|"
            $iconVisible = "($aiming and (not $empty))"
            $focus = Get-RingHudTokenCondition "|f$slot|"
            $controls.Add((New-RingHudTextureControl "selected_$slot" 4 $artSize @(0, 0) $aiming @(
                @{ Condition = "($focus and $focusSettled)"; Texture = "$textureRoot/selected_$slot" }
                foreach ($pulse in 0..2) {
                    @{ Condition = "($focus and $($focusPulses[$pulse]))"; Texture = "$textureRoot/selected_${slot}_$pulse" }
                }
            )))
            $controls.Add((New-RingHudTextureControl "status_$slot" 6 $artSize @(0, 0) $aiming @(
                foreach ($code in $statusArt.Keys) {
                    @{ Condition = Get-RingHudTokenCondition "|s$slot$code|"; Texture = "$textureRoot/$($statusArt[$code])_$slot" }
                }
            )))
            $controls.Add((New-RingHudTextureControl "gauge_$slot" 5 $artSize @(0, 0) $aiming @(
                foreach ($step in 1..10) {
                    @{ Condition = Get-RingHudTokenCondition ('|g{0}{1:00}|' -f $slot, $step); Texture = "$textureRoot/gauge_${slot}_$step" }
                }
            )))
        }
        $icon = New-RingHudTextureControl "icon_$slot" 5 $iconSize $position $iconVisible @(
            foreach ($sprite in $SkillIcons.Ids) {
                @{
                    Condition = Get-RingHudTokenCondition ('|i{0}{1:000}|' -f $slot, $sprite)
                    Texture = "textures/entity/action_ring_icons/$sprite"
                }
            }
        ) $unavailable
        if ($slot -lt 8) {
            $dimmed = @(foreach ($code in $statusArt.Keys) { Get-RingHudTokenCondition "|s$slot$code|" })
            $iconControl = $icon["icon_$slot"]
            $iconControl.alpha = '$ring_icon_alpha'
            $iconControl['$ring_icon_alpha'] = 1
            $iconControl.variables += [ordered]@{
                requires = '({0})' -f ($dimmed -join ' or ')
                '$ring_icon_alpha' = 0.64
            }
        }
        $controls.Add($icon)
    }

    $hintColor = @(0.84, 0.92, 0.98)
    $controls.Add((New-RingHudLabelControl 'aim_hint' '視点で選択' @(0, -54) $hintColor $aiming))
    $controls.Add((New-RingHudLabelControl 'attack_hint' '攻撃で確定' @(0, 54) $hintColor "($aiming and (not $hold))"))
    $controls.Add((New-RingHudLabelControl 'hold_hint' '長押しを離して確定' @(0, 54) $hintColor "($aiming and $hold)"))
    $controls.Add((New-RingHudLabelControl 'cast_hint' '攻撃で発動' @(0, 36) @(1, 0.87, 0.59) $armed))

    $statusLabels = [ordered]@{ m = 'MP不足'; e = 'エネルギー不足'; b = '発動不可'; u = '使用不可' }
    foreach ($code in $statusLabels.Keys) {
        $focusedStatus = @(
            foreach ($slot in 0..7) {
                '({0} and {1})' -f (Get-RingHudTokenCondition "|f$slot|"), (Get-RingHudTokenCondition "|s$slot$code|")
            }
        ) -join ' or '
        $controls.Add((New-RingHudLabelControl "status_label_$code" $statusLabels[$code] @(0, 11) @(1, 0.76, 0.61) "($aiming and ($focusedStatus))"))
    }
    $cooldownColor = @(0.67, 0.84, 1)
    $hasCooldown = @(
        foreach ($digit in 0..9) { Get-RingHudTokenCondition "|d3$digit|" }
    ) -join ' or '
    $controls.Add((New-RingHudLabelControl 'cooldown_decimal' '.' @(2, 11) $cooldownColor "($aiming and ($hasCooldown))"))
    $controls.Add((New-RingHudLabelControl 'cooldown_unit' 's' @(12, 11) $cooldownColor "($aiming and ($hasCooldown))"))
    $largeCooldown = Get-RingHudTokenCondition '|big|'
    $controls.Add((New-RingHudLabelControl 'cooldown_overflow' '+' @(18, 11) $cooldownColor "($aiming and ($hasCooldown) and $largeCooldown)"))
    $digitOffsets = @(-15, -9, -3, 6)
    $hundredsZero = Get-RingHudTokenCondition '|d00|'
    foreach ($position in 0..3) {
        foreach ($digit in 0..9) {
            $showDigit = Get-RingHudTokenCondition "|d$position$digit|"
            if ($position -eq 0 -and $digit -eq 0) { continue }
            if ($position -eq 1 -and $digit -eq 0) {
                $showDigit = "($showDigit and (not $hundredsZero))"
            }
            $controls.Add((New-RingHudLabelControl "cooldown_digit_${position}_$digit" "$digit" @($digitOffsets[$position], 11) $cooldownColor "($aiming and $showDigit)"))
        }
    }
    $popup = [ordered]@{
        type = 'panel'
        size = $artSize
        anchor_from = 'center'
        anchor_to = 'center'
        layer = 28
        visible = "($isRing and (not $hidden))"
        alpha = '$ring_opacity'
        propagate_alpha = $true
        '$ring_opacity' = 0
        variables = @(
            foreach ($step in 0..5) {
                [ordered]@{
                    requires = Get-RingHudTokenCondition "|p$step|"
                    '$ring_opacity' = $opacity[$step]
                }
            }
        )
        controls = $controls.ToArray()
    }
    $ui = [ordered]@{ namespace = 'astralrecord_action_ring'; popup = $popup }
    Add-PackJson $Bedrock 'ui/astralrecord_action_ring.json' $ui
}
