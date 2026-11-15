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
$fixturePath = Join-Path $PSScriptRoot 'melee-runtime-golden.json'
if ($Generate -and (Test-Path -LiteralPath $fixturePath)) { throw '既存の近接runtime fixtureは上書きしません。' }
$sourcePaths = @(
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/paladin/PaladinGuardRuntimeService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SeijakuIssenSkillRuntimeService.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/SkillParamReader.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/PassiveSkillContext.java'
)
$sources = @($sourcePaths | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $projectRoot $_)).Hash.ToLowerInvariant() }
})
if (-not $Generate) {
    $fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixturePath | ConvertFrom-Json
    if ($fixture.schemaVersion -ne 1 -or $fixture.sources.Count -ne $sources.Count) { throw '近接runtime fixture契約が違います。' }
    for ($index = 0; $index -lt $sources.Count; $index++) {
        if ($fixture.sources[$index].path -cne $sources[$index].path -or $fixture.sources[$index].sha256 -cne $sources[$index].sha256) {
            throw "Plugin source hash changed: $($sources[$index].path)"
        }
    }
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
Push-Location $pluginRoot
try {
    & (Join-Path $MavenHome 'bin\mvn.cmd') -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/melee-runtime-parity-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Plugin test-compile failed.' }
    $classpath = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'target/melee-runtime-parity-classpath.txt').Trim()
    $harnessOutput = Join-Path $pluginRoot 'target\melee-runtime-harness'
    New-Item -ItemType Directory -Force -Path $harnessOutput | Out-Null
    & (Join-Path $JavaHome 'bin\javac.exe') -proc:none -cp "target/classes;target/test-classes;$classpath" -d $harnessOutput (Join-Path $PSScriptRoot 'MeleeRuntimeGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Melee runtime harness compilation failed.' }
    $rows = @(& (Join-Path $JavaHome 'bin\java.exe') -Xshare:off -XX:+EnableDynamicAgentLoading -cp "$harnessOutput;target/classes;target/test-classes;$classpath" 'io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.MeleeRuntimeGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Melee runtime harness execution failed.' }
} finally { Pop-Location }
$cases = @($rows | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
if ($cases.Count -ne 7) { throw "近接runtime固定ケース数が違います: $($cases.Count)" }
if ($Generate) {
    $record = [ordered]@{ schemaVersion = 1; description = '実PluginのGUARD状態と剣気runtimeを呼ぶ固定ケース。描画・空間・被弾処理全体の照合ではない。'; sources = $sources; cases = $cases }
    [System.IO.File]::WriteAllText($fixturePath, ($record | ConvertTo-Json -Depth 20) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Write-Output "Melee runtime fixtures created: $($cases.Count) cases/$($sources.Count) source hashes."
    return
}
if ($fixture.cases.Count -ne $cases.Count) { throw '近接runtime fixtureケース数が違います。' }
for ($index = 0; $index -lt $cases.Count; $index++) {
    $actual = $cases[$index]; $expected = $fixture.cases[$index]
    if ($actual.id -cne $expected.id) { throw "近接runtime case id differs: $($actual.id)" }
    foreach ($field in $actual.PSObject.Properties) {
        $expectedField = $expected.PSObject.Properties[$field.Name]
        if ($null -eq $expectedField) { throw "近接runtime field missing: $($actual.id).$($field.Name)" }
        if ($field.Value -is [string] -or $field.Value -is [bool]) {
            if ($field.Value -cne $expectedField.Value) { throw "近接runtime field differs: $($actual.id).$($field.Name)" }
        } elseif ([Math]::Abs([double]$field.Value - [double]$expectedField.Value) -gt 1e-12) {
            throw "近接runtime value differs: $($actual.id).$($field.Name)"
        }
    }
}
Write-Output "Melee runtime parity: $($cases.Count) cases passed/$($sources.Count) source hashes matched."
