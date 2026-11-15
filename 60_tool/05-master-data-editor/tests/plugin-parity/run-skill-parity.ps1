param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome,
    [switch]$Generate,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
if ($Generate -and $Check) { throw 'Generate と Check は同時に指定できません。' }
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$pluginRoot = Join-Path $projectRoot '10_plugin\AstralRecord'
$fixturePath = Join-Path $PSScriptRoot 'skill-executor-golden.json'
$mechanicsFixturePath = Join-Path $PSScriptRoot 'skill-mechanics-golden.json'
$sourcePaths = @(
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/swordsman/SwordsmanFlameRushExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/swordmaster/SwordmasterBladeTempestExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/swordmaster/SwordmasterCrescentDuetExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/swordmaster/SwordmasterSkillSupport.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/support/PlayerActiveSkillContext.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/ActiveSkillServices.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillTaskService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillCombatService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillTargetingService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/SkillParamReader.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/SkillDefinition.kt',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/PlayerSkillCaster.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/DamageResult.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/AstEntity.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/support/PlayerActiveSkillExecutor.java'
)
$mechanicsSourcePaths = @(
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/service/ConditionService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/service/ConditionTickService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/condition/model/ActiveCondition.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/model/SkillProjectileSpec.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillTargetingService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillProjectileService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/DamageCalculator.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/DamageContext.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/model/DamageComponent.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/service/BuffService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/model/BuffType.kt',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/model/BuffModifier.kt'
)

function Get-Sources([string[]]$paths) {
    @($paths | ForEach-Object {
        $sourcePath = Join-Path $projectRoot ($_ -replace '/', '\')
        [ordered]@{ path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash.ToLowerInvariant() }
    })
}

function Assert-Sources($fixture, $current, [string]$suite) {
    if ($fixture.sources.Count -ne $current.Count) { throw "$suite fixtureのソース一覧件数が一致しません。" }
    for ($index = 0; $index -lt $current.Count; $index++) {
        if ($fixture.sources[$index].path -ne $current[$index].path -or
            $fixture.sources[$index].sha256 -ne $current[$index].sha256) {
            throw "$suite Plugin source hash changed: $($current[$index].path)"
        }
    }
}

if ($Generate -and ((Test-Path -LiteralPath $fixturePath) -or (Test-Path -LiteralPath $mechanicsFixturePath))) {
    throw "既存fixtureを上書きしません: $fixturePath / $mechanicsFixturePath"
}
if (-not $Generate -and (-not (Test-Path -LiteralPath $fixturePath) -or
        -not (Test-Path -LiteralPath $mechanicsFixturePath))) {
    throw "fixtureがありません。初回のみ -Generate を指定してください: $fixturePath / $mechanicsFixturePath"
}

$sources = Get-Sources $sourcePaths
$mechanicsSources = Get-Sources $mechanicsSourcePaths
if (-not $Generate) {
    $fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixturePath | ConvertFrom-Json
    if ($fixture.modelVersion -ne 'skill-executor-trace-v1') { throw 'fixtureのmodelVersionが一致しません。' }
    Assert-Sources $fixture $sources 'Skill executor'
    $mechanicsFixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $mechanicsFixturePath | ConvertFrom-Json
    if ($mechanicsFixture.schemaVersion -ne 1) { throw 'mechanics fixtureのschemaVersionが一致しません。' }
    Assert-Sources $mechanicsFixture $mechanicsSources 'Skill mechanics'
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
$maven = Join-Path $MavenHome 'bin\mvn.cmd'
Push-Location $pluginRoot
try {
    & $maven -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/skill-executor-parity-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Plugin test-compile failed.' }
    $classpath = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'target/skill-executor-parity-classpath.txt').Trim()
    $harnessOutput = Join-Path $pluginRoot 'target/skill-executor-parity-harness'
    New-Item -ItemType Directory -Force -Path $harnessOutput | Out-Null
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    & $javac -proc:none -cp "target/classes;target/test-classes;$classpath" -d $harnessOutput (Join-Path $PSScriptRoot 'SkillExecutorGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Skill executor harness compilation failed.' }
    $java = Join-Path $JavaHome 'bin\java.exe'
    $rows = @(& $java -XX:+EnableDynamicAgentLoading -cp "$harnessOutput;target/classes;target/test-classes;$classpath" 'io.github.maaasu.astralRecord.feature.skill.executor.active.support.SkillExecutorGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Skill executor harness execution failed.' }

    $mechanicsOutput = Join-Path $pluginRoot 'target/skill-mechanics-parity-harness'
    New-Item -ItemType Directory -Force -Path $mechanicsOutput | Out-Null
    & $javac -proc:none -cp "target/classes;target/test-classes;$classpath" -d $mechanicsOutput (Join-Path $PSScriptRoot 'SkillMechanicsGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Skill mechanics harness compilation failed.' }
    $mechanicsRows = @(& $java -XX:+EnableDynamicAgentLoading -cp "$mechanicsOutput;target/classes;target/test-classes;$classpath" 'io.github.maaasu.astralRecord.feature.combat.service.SkillMechanicsGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Skill mechanics harness execution failed.' }
} finally {
    Pop-Location
}

$cases = @($rows | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
$mechanicsCases = @($mechanicsRows | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
if ($cases.Count -ne 3) { throw "実executorの固定ケース件数が違います: $($cases.Count)" }
if ($mechanicsCases.Count -ne 5) { throw "実mechanicsの固定ケース件数が違います: $($mechanicsCases.Count)" }
$expectedIds = @('swordsman_flame_rush', 'swordmaster_blade_tempest', 'swordmaster_crescent_duet')
for ($index = 0; $index -lt $expectedIds.Count; $index++) {
    if ($cases[$index].id -ne $expectedIds[$index] -or $cases[$index].hits.Count -lt 2) {
        throw "実executorの固定ケースが不完全です: $($expectedIds[$index])"
    }
}
$mechanicsIds = @('condition_chance', 'projectile_collision', 'burning_ticks', 'thermal_components', 'build_up_scalar')
for ($index = 0; $index -lt $mechanicsIds.Count; $index++) {
    if ($mechanicsCases[$index].id -ne $mechanicsIds[$index]) {
        throw "実mechanicsの固定ケースが不完全です: $($mechanicsIds[$index])"
    }
}

if ($Generate) {
    $record = [ordered]@{
        modelVersion = 'skill-executor-trace-v1'
        note = '実Plugin executorと仮想tickを実行。Bukkit依存の表示定数のみハーネスで置換。'
        sources = $sources
        cases = @($cases | ForEach-Object { [ordered]@{ id = $_.id; expected = [ordered]@{ hits = $_.hits } } })
    }
    $mechanicsInputs = @(
        [ordered]@{ burningChance = 24.5; applyIncrease = 20; resistance = 25; capChance = 50; capIncrease = 150; fullResistance = 150 },
        [ordered]@{ targetBounds = @(-0.3, 1, 4.1, 0.3, 2, 4.7); originY = 1.6; originOffset = 0.8; speed = 1.6; hitRadius = 0.6; range = 16 },
        [ordered]@{ nextTickAtMs = 1000; intervalTicks = 20; snapshotPower = 8; timesMs = @(999, 1000, 1999, 2000) },
        [ordered]@{ rangedAttack = 40; mobDefense = 8; components = @([ordered]@{ element = 'FIRE'; ratio = 0.6 }, [ordered]@{ element = 'ICE'; ratio = 0.6 }); neutralRatio = 1.2 },
        [ordered]@{ baseAttack = 40; modifierType = 'SCALAR'; modifier = 0.11; buffDurationTicks = 200 }
    )
    $mechanicsRecordCases = @()
    for ($index = 0; $index -lt $mechanicsCases.Count; $index++) {
        $expected = [ordered]@{}
        foreach ($property in $mechanicsCases[$index].PSObject.Properties) {
            if ($property.Name -ne 'id') { $expected[$property.Name] = $property.Value }
        }
        $mechanicsRecordCases += [ordered]@{
            id = $mechanicsCases[$index].id
            input = $mechanicsInputs[$index]
            expected = $expected
        }
    }
    $mechanicsRecord = [ordered]@{
        schemaVersion = 1
        description = 'SkillMechanicsGoldenHarness.java がPluginの実クラスから出力した固定ケース。Bukkitサーバーは起動しない。'
        sources = $mechanicsSources
        cases = $mechanicsRecordCases
    }
    $json = $record | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($fixturePath, $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
    $mechanicsJson = $mechanicsRecord | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($mechanicsFixturePath, $mechanicsJson + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
    Write-Output "Skill parity fixtures created: executor $($cases.Count) cases/$($sources.Count) hashes; mechanics $($mechanicsCases.Count) cases/$($mechanicsSources.Count) hashes."
    return
}

if ($fixture.cases.Count -ne $cases.Count) { throw 'fixtureのケース件数が一致しません。' }
for ($index = 0; $index -lt $cases.Count; $index++) {
    $actual = $cases[$index]
    $expected = $fixture.cases[$index]
    if ($actual.id -ne $expected.id -or $actual.hits.Count -ne $expected.expected.hits.Count) {
        throw "Skill executor case differs: $($actual.id)"
    }
    for ($hitIndex = 0; $hitIndex -lt $actual.hits.Count; $hitIndex++) {
        $hit = $actual.hits[$hitIndex]
        $gold = $expected.expected.hits[$hitIndex]
        if ($hit.tick -ne $gold.tick -or $hit.element -ne $gold.element -or
            [math]::Abs([double]$hit.ratio - [double]$gold.ratio) -gt 1e-12) {
            throw "Skill executor hit differs: $($actual.id) hit $hitIndex"
        }
    }
}
Write-Output "Skill executor parity: $($cases.Count) cases passed; $($sources.Count) source hashes matched."

if ($mechanicsFixture.cases.Count -ne $mechanicsCases.Count) { throw 'mechanics fixtureのケース件数が一致しません。' }
for ($index = 0; $index -lt $mechanicsCases.Count; $index++) {
    $actual = $mechanicsCases[$index]
    $gold = $mechanicsFixture.cases[$index]
    if ($actual.id -ne $gold.id) { throw "Skill mechanics case differs: $($actual.id)" }
    $actualFields = @($actual.PSObject.Properties | Where-Object { $_.Name -ne 'id' })
    $goldFields = @($gold.expected.PSObject.Properties)
    if ($actualFields.Count -ne $goldFields.Count) { throw "Skill mechanics field count differs: $($actual.id)" }
    foreach ($field in $actualFields) {
        $expectedField = $gold.expected.PSObject.Properties[$field.Name]
        if ($null -eq $expectedField) { throw "Skill mechanics field missing: $($actual.id).$($field.Name)" }
        if ($field.Value -is [string] -or $expectedField.Value -is [string]) {
            if ($field.Value -cne $expectedField.Value) { throw "Skill mechanics field differs: $($actual.id).$($field.Name)" }
        } elseif ([math]::Abs([double]$field.Value - [double]$expectedField.Value) -gt 1e-12) {
            throw "Skill mechanics field differs: $($actual.id).$($field.Name)"
        }
    }
}
Write-Output "Skill mechanics parity: $($mechanicsCases.Count) cases passed; $($mechanicsSources.Count) source hashes matched."
