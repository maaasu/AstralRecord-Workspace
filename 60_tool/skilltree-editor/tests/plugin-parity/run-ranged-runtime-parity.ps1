param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome,
    [switch]$Generate,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
if ($Generate -and $Check) { throw 'Generate と Check は同時に指定できません。' }
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$pluginRoot = Join-Path $repoRoot '10_plugin/AstralRecord'
$fixturePath = Join-Path $PSScriptRoot 'ranged-runtime-golden.json'
if ($Generate -and (Test-Path -LiteralPath $fixturePath)) { throw "既存fixtureを上書きしません: $fixturePath" }
if (-not $Generate -and -not (Test-Path -LiteralPath $fixturePath)) { throw "fixtureがありません: $fixturePath" }
$sourcePaths = @(
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/phantomarcher/PhantomArcherRuntime.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/phantomarcher/PhantomArcherSkillExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/phantomarcher/PhantomArcherPassiveExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/support/PlayerActiveSkillContext.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/model/SkillParamReader.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/hunter/HunterFadeShotExecutor.java',
    '10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/executor/active/hunter/HunterArrowRainExecutor.java'
)
$sources = @($sourcePaths | ForEach-Object {
    [ordered]@{ path = $_; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repoRoot $_)).Hash.ToLowerInvariant() }
})
if (-not $Generate) {
    $fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath $fixturePath | ConvertFrom-Json
    if ($fixture.schemaVersion -ne 1 -or $fixture.sources.Count -ne $sources.Count) { throw 'fixtureの形式が一致しません。' }
    for ($index = 0; $index -lt $sources.Count; $index++) {
        if ($fixture.sources[$index].path -ne $sources[$index].path -or $fixture.sources[$index].sha256 -ne $sources[$index].sha256) {
            throw "Plugin source hash changed: $($sources[$index].path)"
        }
    }
}
$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
Push-Location $pluginRoot
try {
    & (Join-Path $MavenHome 'bin/mvn.cmd') -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/ranged-runtime-parity-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Plugin test-compile failed.' }
    $classpath = (Get-Content -Raw -Encoding UTF8 -LiteralPath 'target/ranged-runtime-parity-classpath.txt').Trim()
    $output = Join-Path $pluginRoot 'target/ranged-runtime-harness'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    & (Join-Path $JavaHome 'bin/javac.exe') -proc:none -cp "target/classes;$classpath" -d $output (Join-Path $PSScriptRoot 'RangedRuntimeGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Ranged runtime harness compilation failed.' }
    $rows = @(& (Join-Path $JavaHome 'bin/java.exe') -XX:+EnableDynamicAgentLoading -Xshare:off -cp "$output;target/classes;$classpath" 'io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher.RangedRuntimeGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Ranged runtime harness execution failed.' }
} finally { Pop-Location }
$cases = @($rows | Where-Object { $_.TrimStart().StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
if ($cases.Count -ne 4 -or $cases[0].id -ne 'phantom_marks' -or $cases[1].id -ne 'phantom_ratios' -or
    $cases[2].id -ne 'pellet_directions' -or $cases[3].id -ne 'rain_ballistic') { throw '実runtimeの固定ケースが不完全です。' }
if ($Generate) {
    $record = [ordered]@{
        schemaVersion = 1
        description = '実PhantomArcherRuntimeの印付与・失効・消費と、実executorの着弾倍率を呼ぶ。Bukkit clockと依存serviceは固定し、サーバー・filebaseは使用しない。'
        sources = $sources
        cases = $cases
    }
    [System.IO.File]::WriteAllText($fixturePath, ($record | ConvertTo-Json -Depth 20) + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
    Write-Output "Ranged runtime fixture created: $($cases.Count) cases/$($sources.Count) hashes."
    return
}
if (($cases | ConvertTo-Json -Depth 20 -Compress) -cne ($fixture.cases | ConvertTo-Json -Depth 20 -Compress)) { throw 'Ranged runtime fixed trace changed.' }
Write-Output "Ranged runtime parity: $($cases.Count) cases passed; $($sources.Count) source hashes matched."
