function Test-ItemGripNumber {
    param($Number)

    return (($Number -is [int] -or $Number -is [long] -or $Number -is [double] -or $Number -is [decimal]) -and
        ![double]::IsNaN($Number) -and ![double]::IsInfinity($Number))
}

function Test-ItemGripVector {
    param($Value)

    if ($Value -isnot [array] -or $Value.Count -ne 3) { return $false }
    foreach ($number in $Value) {
        if (!(Test-ItemGripNumber $number)) { return $false }
    }
    return $true
}

function Rotate-ItemGripPoint {
    param([double[]] $Point, [int] $Axis, [double] $Degrees)

    $radians = $Degrees * [Math]::PI / 180.0
    $cosine = [Math]::Cos($radians)
    $sine = [Math]::Sin($radians)
    $first = ($Axis + 1) % 3
    $second = ($Axis + 2) % 3
    $result = [double[]] $Point.Clone()
    $result[$first] = $Point[$first] * $cosine - $Point[$second] * $sine
    $result[$second] = $Point[$first] * $sine + $Point[$second] * $cosine
    return ,$result
}

function Test-ItemGripInsideCuboid {
    param([double[]] $Point, [double[]] $From, [double[]] $To)

    for ($axis = 0; $axis -lt 3; $axis++) {
        if ($To[$axis] -le $From[$axis] -or $Point[$axis] -lt $From[$axis] -or $Point[$axis] -gt $To[$axis]) {
            return $false
        }
    }
    return $true
}

function Test-JavaItemGripGeometry {
    param($Elements, [double[]] $Grip)

    $axes = @('x', 'y', 'z')
    foreach ($element in $Elements) {
        $point = $Grip
        if ($element.rotation -and $element.rotation.angle -ne 0) {
            $axis = [Array]::IndexOf($axes, $element.rotation.axis)
            if ($axis -lt 0 -or !(Test-ItemGripVector $element.rotation.origin)) { continue }
            $origin = $element.rotation.origin
            $point = [double[]] @(for ($i = 0; $i -lt 3; $i++) { $Grip[$i] - $origin[$i] })
            if ($element.rotation.rescale) {
                # Match the perpendicular rescale of a baked Java block element.
                $angle = if ([Math]::Abs($element.rotation.angle) -eq 22.5) { 22.5 } else { 45.0 }
                $cosine = [Math]::Cos($angle * [Math]::PI / 180.0)
                $point[($axis + 1) % 3] *= $cosine
                $point[($axis + 2) % 3] *= $cosine
            }
            $point = Rotate-ItemGripPoint $point $axis (-$element.rotation.angle)
            for ($i = 0; $i -lt 3; $i++) { $point[$i] += $origin[$i] }
        }
        if (Test-ItemGripInsideCuboid $point $element.from $element.to) { return $true }
    }
    return $false
}

function Get-JavaItemHandGrip {
    param([double[]] $Grip, $Transform, [bool] $Left)

    $scale = if ($null -eq $Transform.scale) { @(1, 1, 1) } else { $Transform.scale }
    $rotation = if ($null -eq $Transform.rotation) { @(0, 0, 0) } else { $Transform.rotation }
    $translation = if ($null -eq $Transform.translation) { @(0, 0, 0) } else { $Transform.translation }
    if (!(Test-ItemGripVector $scale) -or !(Test-ItemGripVector $rotation) -or !(Test-ItemGripVector $translation)) {
        throw 'Hand display rotation, scale and translation must each be three numbers.'
    }

    $hand = if ($Left) { -1 } else { 1 }
    $point = [double[]] @(for ($i = 0; $i -lt 3; $i++) {
        ($Grip[$i] - 8.0) * [Math]::Max(-4.0, [Math]::Min(4.0, $scale[$i]))
    })
    # Minecraft applies the display quaternion rotationXYZ to column vectors (Z, then Y, then X).
    $point = Rotate-ItemGripPoint $point 2 ($rotation[2] * $hand)
    $point = Rotate-ItemGripPoint $point 1 ($rotation[1] * $hand)
    $point = Rotate-ItemGripPoint $point 0 $rotation[0]
    $point[0] += [Math]::Max(-80.0, [Math]::Min(80.0, $translation[0])) * $hand
    $point[1] += [Math]::Max(-80.0, [Math]::Min(80.0, $translation[1]))
    $point[2] += [Math]::Max(-80.0, [Math]::Min(80.0, $translation[2]))
    return ,$point
}

function Confirm-JavaItemGrip {
    param($Model, [double[]] $Grip, $Targets, [string] $Path)

    if (!(Test-JavaItemGripGeometry $Model.elements $Grip)) {
        throw "$Path must contain its registered grip point ($($Grip -join ', ')) inside a cuboid."
    }
    foreach ($view in @('thirdperson_righthand', 'thirdperson_lefthand', 'firstperson_righthand', 'firstperson_lefthand')) {
        $target = $Targets.$view
        if (!(Test-ItemGripVector $target.position) -or !(Test-ItemGripVector $target.up)) {
            throw "Missing or invalid grip target for $view in item-model-grip-targets.json ($Path)."
        }
        $display = $Model.display.$view
        if (!$display) { throw "$Path must define the $view display transform." }

        $left = $view.EndsWith('_lefthand')
        $center = Get-JavaItemHandGrip $Grip $display $left
        $above = Get-JavaItemHandGrip @($Grip[0], ($Grip[1] + 1.0), $Grip[2]) $display $left
        $distanceSquared = 0.0
        $headingLengthSquared = 0.0
        $targetLengthSquared = 0.0
        $dot = 0.0
        for ($axis = 0; $axis -lt 3; $axis++) {
            $offset = $center[$axis] - $target.position[$axis]
            $distanceSquared += $offset * $offset
            $heading = $above[$axis] - $center[$axis]
            $headingLengthSquared += $heading * $heading
            $targetLengthSquared += $target.up[$axis] * $target.up[$axis]
            $dot += $heading * $target.up[$axis]
        }
        if ($distanceSquared -gt (0.5 * 0.5)) {
            throw "$Path $view grip is more than 0.5 model pixels from its target ($($center -join ', '))."
        }
        if ($headingLengthSquared -eq 0.0 -or $targetLengthSquared -eq 0.0 -or
            $dot / [Math]::Sqrt($headingLengthSquared * $targetLengthSquared) -lt 0.95) {
            throw "$Path $view points away from its target grip direction."
        }
        if ($null -ne $target.front) {
            if (!(Test-ItemGripVector $target.front)) { throw "Invalid front direction for $Path $view." }
            $front = Get-JavaItemHandGrip @($Grip[0], $Grip[1], ($Grip[2] - 1.0)) $display $left
            $dot = $facingLengthSquared = $frontTargetLengthSquared = 0.0
            for ($axis = 0; $axis -lt 3; $axis++) {
                $facing = $front[$axis] - $center[$axis]
                $dot += $facing * $target.front[$axis]
                $facingLengthSquared += $facing * $facing
                $frontTargetLengthSquared += $target.front[$axis] * $target.front[$axis]
            }
            $length = [Math]::Sqrt($facingLengthSquared * $frontTargetLengthSquared)
            if ($length -eq 0.0 -or $dot / $length -lt 0.95) {
                throw "$Path $view turns the front face away from its target."
            }
        }
    }
}

function Confirm-BedrockItemGrip {
    param($Geometry, $Description, $Animations, [string] $Id, [string] $GripStyle)

    $bones = ($Geometry | Where-Object { $_.description.identifier -ceq $Description.geometry.default }).bones
    $grip = @($bones | Where-Object { $_.name -ceq 'grip' })
    if ($grip.Count -ne 1 -or $grip[0].parent -or !(Test-ItemGripVector $grip[0].pivot) -or
        ($grip[0].pivot | Where-Object { $_ -ne 0 }) -or
        ($grip[0].rotation | Where-Object { $_ -ne 0 }) -or
        $grip[0].binding -cnotmatch '^(?:q|query)\.item_slot_to_bone_name\(\s*(?:c|context)\.item_slot\s*\)$') {
        throw "Bedrock geometry for $Id must bind a root grip bone at [0, 0, 0] to the current item slot."
    }
    $gripBones = @($grip[0])
    $gripBones += @($bones | Where-Object {
        $_.parent -ceq 'grip' -and (Test-ItemGripVector $_.pivot) -and !($_.pivot | Where-Object { $_ -ne 0 }) -and
        !($_.rotation | Where-Object { $_ -ne 0 })
    })
    $containsGrip = $false
    foreach ($cube in $gripBones.cubes) {
        if (($cube.rotation | Where-Object { $_ -ne 0 }) -or
            !(Test-ItemGripVector $cube.origin) -or !(Test-ItemGripVector $cube.size)) { continue }
        $end = @(for ($axis = 0; $axis -lt 3; $axis++) { $cube.origin[$axis] + $cube.size[$axis] })
        if (Test-ItemGripInsideCuboid @(0, 0, 0) $cube.origin $end) {
            $containsGrip = $true
            break
        }
    }
    if (!$containsGrip) {
        throw "Bedrock geometry for $Id must contain the wrist anchor in an unrotated grip cuboid."
    }
    if ($GripStyle -ceq 'shield') {
        Confirm-BedrockShieldPose $Description $Animations $Id
        return
    }
    $triggers = foreach ($trigger in $Description.scripts.animate) {
        if ($trigger -is [string]) {
            @{ name = $trigger; condition = '1.0' }
        } else {
            foreach ($property in $trigger.PSObject.Properties) {
                @{ name = $property.Name; condition = $property.Value }
            }
        }
    }
    $holdConditions = [ordered] @{
        first_person = 'context.is_first_person'
        third_person = '!context.is_first_person'
    }
    if ($GripStyle -ceq 'utility') {
        $holdConditions.first_person = "context.is_first_person && context.item_slot == 'main_hand'"
        $holdConditions.Add('first_person_off', "context.is_first_person && context.item_slot == 'off_hand'")
    }
    foreach ($view in $holdConditions.Keys) {
        $active = @($triggers | Where-Object { $_.name -ceq $view })
        if ($active.Count -ne 1 -or $active[0].condition -isnot [string] -or
            $active[0].condition.Trim() -cne $holdConditions[$view]) {
            throw "Bedrock attachable for $Id must activate $view once with $($holdConditions[$view])."
        }
        $name = $Description.animations.$view
        $animation = $Animations.$name
        $pose = $animation.bones.grip
        $position = $pose.position
        # The third-person item bone sits at Z=1 inside the vanilla arm, whose front is Z=-2.
        $minDepth = if ($view -ceq 'third_person') { -3.0 } else { -0.5 }
        if ($animation.loop -isnot [bool] -or !$animation.loop -or
            !(Test-ItemGripVector $position) -or [Math]::Abs($position[0]) -gt 0.5 -or
            [Math]::Abs($position[1] - 24.0) -gt 2.0 -or $position[2] -lt $minDepth -or $position[2] -gt 0.5) {
            throw "Bedrock animation for $Id $view must loop and keep the grip within the hand."
        }
        if ($pose.PSObject.Properties.Name -ccontains 'scale') {
            $scale = @($pose.scale)
            if ($null -eq $pose.scale -or $scale.Count -notin @(1, 3) -or
                @($scale | Where-Object { !(Test-ItemGripNumber $_) -or $_ -lt 0.5 -or $_ -gt 2.0 }).Count -gt 0) {
                throw "Bedrock animation for $Id $view grip scale must be between 0.5 and 2.0 on all axes."
            }
        }
        if ($GripStyle -ceq 'utility') {
            $rotation = @($pose.rotation)
            $handYaw = $view -ceq 'third_person' -and $rotation[1] -is [string] -and
                $rotation[1] -cmatch "^context\.item_slot == 'main_hand' \? ([0-9]+) : -\1$"
            if (!(Test-ItemGripVector $rotation) -and
                !($rotation.Count -eq 3 -and $handYaw -and (Test-ItemGripNumber $rotation[0]) -and
                    (Test-ItemGripNumber $rotation[2]))) {
                throw "Bedrock animation for $Id $view has an invalid hand rotation."
            }
            $maxScale = if ($view -ceq 'third_person') { 1.0 } else { 0.75 }
            if (!(Test-ItemGripNumber $pose.scale) -or $pose.scale -gt $maxScale) {
                throw "Bedrock animation for $Id $view must use a reduced handheld scale."
            }
        } elseif (!(Test-ItemGripVector $pose.rotation)) {
            throw "Bedrock animation for $Id $view grip rotation must contain three numeric angles."
        }
        foreach ($bone in $gripBones | Where-Object { $_.name -cne 'grip' }) {
            if ($null -ne $animation.bones.($bone.name)) {
                throw "Bedrock animation for $Id $view must set its held pose on grip only."
            }
        }
        if ($GripStyle -ceq 'utility') { continue }
        # Bedrock applies the grip rotation, then the neutral right arm for the first-person view.
        $direction = [double[]] @(0, 1, 0)
        $rotations = ,$pose.rotation
        if ($view -ceq 'first_person') { $rotations += ,@(95, -45, 115) }
        foreach ($rotation in $rotations) {
            for ($axis = 0; $axis -lt 3; $axis++) {
                $direction = Rotate-ItemGripPoint $direction $axis $rotation[$axis]
            }
        }
        if ($view -ceq 'first_person') {
            if ($direction[1] -lt 0.8 -or [Math]::Abs($direction[2]) -gt 0.5) {
                throw "Bedrock animation for $Id first_person must aim the weapon up across the view."
            }
        } elseif ($direction[2] -lt 0.8) {
            throw "Bedrock animation for $Id third_person must aim the weapon forward in the hand."
        }
    }
    foreach ($trigger in $triggers | Where-Object { $_.name -cnotin $holdConditions.Keys }) {
        $name = $Description.animations.($trigger.name)
        $animatedBones = $Animations.$name.bones
        if ($null -ne $animatedBones.grip) {
            throw "Bedrock animation for $Id $($trigger.name) must preserve the grip pose."
        }
        foreach ($bone in $gripBones) {
            if ($null -ne $animatedBones.($bone.name).position) {
                throw "Bedrock animation for $Id $($trigger.name) must keep $($bone.name) centered on the wrist."
            }
        }
    }
}

function Confirm-BedrockShieldPose {
    param($Description, $Animations, [string] $Id)

    $conditions = [ordered] @{
        first_person = "context.is_first_person && context.item_slot == 'main_hand' && !variable.blocking_main"
        first_person_off = "context.is_first_person && context.item_slot == 'off_hand' && !query.blocking"
        first_person_block = "context.is_first_person && context.item_slot == 'main_hand' && variable.blocking_main"
        first_person_off_block = "context.is_first_person && context.item_slot == 'off_hand' && query.blocking"
        third_person = '!context.is_first_person'
    }
    $aliases = @($Description.animations.PSObject.Properties.Value | Sort-Object -Unique)
    if ($Description.scripts.animate.Count -ne $conditions.Count -or $aliases.Count -ne $conditions.Count) {
        throw "Bedrock shield $Id must select exactly one of its held poses."
    }
    foreach ($view in $conditions.Keys) {
        $triggers = @($Description.scripts.animate | ForEach-Object { $_.PSObject.Properties[$view] } |
            Where-Object { $null -ne $_ })
        $name = $Description.animations.$view
        $animation = $Animations.$name
        $pose = $animation.bones.grip
        $maxScale = if ($view -ceq 'third_person') { 1.0 } else { 0.75 }
        if ($triggers.Count -ne 1 -or $triggers[0].Value -cne $conditions[$view] -or
            $animation.loop -isnot [bool] -or !$animation.loop -or !$pose -or
            $animation.bones.PSObject.Properties.Name.Count -ne 1 -or
            !(Test-ItemGripNumber $pose.scale) -or $pose.scale -lt 0.5 -or $pose.scale -gt $maxScale) {
            throw "Bedrock shield $Id must define a single reduced hold pose for $view."
        }
        $position = @($pose.position)
        $rotation = @($pose.rotation)
        if ($position.Count -ne 3 -or $rotation.Count -ne 3) {
            throw "Bedrock shield $Id $view must define its position and rotation."
        }
        if ($view -ceq 'third_person') {
            # The vanilla shield's hand pivot rebased to the handle at our model origin.
            if ($position[0] -cne "context.item_slot == 'main_hand' ? 0.6 : -0.6" -or
                $position[1] -ne 24.5 -or $position[2] -ne -0.2 -or
                $rotation[0] -ne 90 -or $rotation[1] -ne 0 -or
                $rotation[2] -cne "context.item_slot == 'main_hand' ? 90 : -90") {
                throw "Bedrock shield $Id third_person must mirror the shield face around the hand."
            }
        } elseif (!(Test-ItemGripVector $rotation) -or !(Test-ItemGripNumber $position[0]) -or
            !(Test-ItemGripNumber $position[1]) -or $position[1] -lt 20 -or $position[1] -gt 35) {
            throw "Bedrock shield $Id $view must stay near the first-person hand."
        } elseif ($view.StartsWith('first_person_off')) {
            if ([Math]::Abs($rotation[1] - 176) -gt 10 -or
                $position[2] -cnotmatch "^query\.get_equipped_item_name == 'bow' && query\.main_hand_item_use_duration > 0 \? -[0-9.]+ : [0-9.]+$") {
                throw "Bedrock shield $Id $view must use the offhand pose and bow clearance."
            }
        } elseif ([Math]::Abs($rotation[0] - 91) -gt 10 -or !(Test-ItemGripNumber $position[2])) {
            throw "Bedrock shield $Id $view must use the main hand pose."
        }
    }
}
