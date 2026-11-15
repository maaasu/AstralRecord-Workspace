param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome,
    [switch]$Generate,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
if ($Generate -and $Check) { throw 'Generate と Check は同時に指定できません。' }
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$pluginRoot = Join-Path $taskRoot '10_plugin\AstralRecord'
$fixturePath = Join-Path $PSScriptRoot 'magic-runtime-golden.json'
if ($Generate -and (Test-Path -LiteralPath $fixturePath)) { throw '既存のmagic fixtureを上書きしません。' }
if (-not $Generate -and -not (Test-Path -LiteralPath $fixturePath)) { throw '初回はGenerateでfixtureを作成してください。' }
$skillRoot = '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/'
$sourcePaths = @(
    'active/service/ElementalPrismRuntimeService.java',
    'executor/active/mage/MageFrostBlizzardExecutor.java',
    'executor/active/mage/MageSparkingExecutor.java',
    'executor/active/wizard/WizardEmulateSparkExecutor.java',
    'service/ArcaneFlowSkillRuntimeService.java',
    'service/MeditationSkillRuntimeService.java',
    'model/PassiveSkillContext.java',
    'model/SkillDefinition.kt',
    'model/SkillParamReader.java'
) | ForEach-Object { $skillRoot + $_ }
$sources = @($sourcePaths | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $_)).Hash.ToLowerInvariant() }
})
if (-not $Generate) {
    $fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixturePath | ConvertFrom-Json
    if ($fixture.schemaVersion -ne 1 -or $fixture.sources.Count -ne $sources.Count) { throw 'Magic fixtureのschema/source件数が一致しません。' }
    for ($index = 0; $index -lt $sources.Count; $index++) {
        if ($fixture.sources[$index].path -cne $sources[$index].path -or $fixture.sources[$index].sha256 -cne $sources[$index].sha256) {
            throw "Magic runtime source changed: $($sources[$index].path)"
        }
    }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
Push-Location $pluginRoot
try {
    & (Join-Path $MavenHome 'bin\mvn.cmd') -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/magic-runtime-parity-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Plugin test-compile failed.' }
    $dependencyClasspath = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'target/magic-runtime-parity-classpath.txt').Trim()
    $harnessOutput = Join-Path $pluginRoot 'target\magic-runtime-parity-harness'
    New-Item -ItemType Directory -Path $harnessOutput -Force | Out-Null
    & (Join-Path $JavaHome 'bin\javac.exe') -proc:none -cp "target/classes;target/test-classes;$dependencyClasspath" -d $harnessOutput (Join-Path $PSScriptRoot 'MagicRuntimeGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Magic runtime harness compilation failed.' }
    $rows = @(& (Join-Path $JavaHome 'bin\java.exe') -Xshare:off -XX:+EnableDynamicAgentLoading -cp "$harnessOutput;target/classes;target/test-classes;$dependencyClasspath" 'io.github.maaasu.astralRecord.feature.skill.service.MagicRuntimeGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Magic runtime harness execution failed.' }
} finally { Pop-Location }
$cases = @($rows | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
$expectedIds = @('magic_prism_intersection', 'magic_vortex_velocity', 'magic_spark_spiral', 'magic_emulate_bounce', 'magic_arcane_history', 'magic_meditation_multiplier')
if ($cases.Count -ne $expectedIds.Count) { throw 'Magic runtime固定ケース件数が一致しません。' }
for ($index = 0; $index -lt $expectedIds.Count; $index++) {
    if ($cases[$index].id -cne $expectedIds[$index]) { throw "Magic runtime固定ケースのIDが違います: $($expectedIds[$index])" }
}
if ($Generate) {
    $record = [ordered]@{
        schemaVersion = 1
        note = 'Plugin実メソッドを呼ぶ固定入力。空間全体やBukkitの実tickの照合ではない。Meditationは効果開始境界だけをreflectionで設定する。'
        sources = $sources
        cases = @($cases | ForEach-Object {
            $values = [ordered]@{}
            foreach ($property in $_.PSObject.Properties) { if ($property.Name -ne 'id') { $values[$property.Name] = $property.Value } }
            [ordered]@{ id = $_.id; expected = $values }
        })
    }
    [System.IO.File]::WriteAllText($fixturePath, ($record | ConvertTo-Json -Depth 30) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Write-Output "Magic runtime fixture created: $($cases.Count) cases/$($sources.Count) source hashes."
    return
}

function Assert-SameValue($actual, $expected, [string]$path) {
    if ($actual -is [System.Array] -or $expected -is [System.Array]) {
        if ($actual.Count -ne $expected.Count) { throw "Magic runtime array differs: $path" }
        for ($element = 0; $element -lt $actual.Count; $element++) { Assert-SameValue $actual[$element] $expected[$element] "$path[$element]" }
    } elseif ($actual -is [bool] -or $expected -is [bool]) {
        if ($actual -ne $expected) { throw "Magic runtime boolean differs: $path" }
    } elseif ([Math]::Abs([double]$actual - [double]$expected) -gt 1e-12) { throw "Magic runtime value differs: $path" }
}
if ($fixture.cases.Count -ne $cases.Count) { throw 'Magic fixtureのケース件数が一致しません。' }
for ($index = 0; $index -lt $cases.Count; $index++) {
    $actual = $cases[$index]; $gold = $fixture.cases[$index]
    if ($actual.id -cne $gold.id) { throw "Magic case differs: $($actual.id)" }
    foreach ($property in $actual.PSObject.Properties | Where-Object { $_.Name -ne 'id' }) {
        $expectedProperty = $gold.expected.PSObject.Properties[$property.Name]
        if ($null -eq $expectedProperty) { throw "Magic runtime expected field missing: $($actual.id).$($property.Name)" }
        Assert-SameValue $property.Value $expectedProperty.Value "$($actual.id).$($property.Name)"
    }
}
Write-Output "Magic runtime parity: $($cases.Count) cases passed; $($sources.Count) source hashes matched."
