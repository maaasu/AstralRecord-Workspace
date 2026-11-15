param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$MavenHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..\..\..')).Path
$pluginRoot = Join-Path $projectRoot '10_plugin\AstralRecord'
$fixture = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'combat-golden.json') | ConvertFrom-Json
foreach ($source in $fixture.sources) {
    $sourcePath = Join-Path $projectRoot ($source.path -replace '/', '\')
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourcePath).Hash.ToLowerInvariant()
    if ($actualHash -ne $source.sha256) { throw "Plugin source hash changed: $($source.path)" }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$(Join-Path $JavaHome 'bin');$env:PATH"
$maven = Join-Path $MavenHome 'bin\mvn.cmd'
Push-Location $pluginRoot
try {
    & $maven -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/combat-parity-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw 'Plugin test-compile failed.' }
    $classpath = Get-Content -Raw -Encoding UTF8 -LiteralPath 'target/combat-parity-classpath.txt'
    $classpath = $classpath.Trim()
    $harnessOutput = Join-Path $pluginRoot 'target/parity-harness'
    New-Item -ItemType Directory -Force -Path $harnessOutput | Out-Null
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    & $javac -proc:none -cp "target/classes;target/test-classes;$classpath" -d $harnessOutput (Join-Path $PSScriptRoot 'CombatGoldenHarness.java')
    if ($LASTEXITCODE -ne 0) { throw 'Combat harness compilation failed.' }
    $java = Join-Path $JavaHome 'bin\java.exe'
    $rows = @(& $java -XX:+EnableDynamicAgentLoading -cp "$harnessOutput;target/classes;target/test-classes;$classpath" 'io.github.maaasu.astralRecord.feature.combat.service.CombatGoldenHarness')
    if ($LASTEXITCODE -ne 0) { throw 'Combat harness execution failed.' }
    $actual = @{}
    foreach ($line in $rows) {
        if ($line.TrimStart().StartsWith('{')) { $row = $line | ConvertFrom-Json; $actual[$row.id] = $row }
    }
    if ($actual.Count -ne $fixture.cases.Count) {
        throw "Golden case count differs: harness=$($actual.Count), fixture=$($fixture.cases.Count)."
    }
    foreach ($case in $fixture.cases) {
        if (-not $actual.ContainsKey($case.id)) { throw "Missing golden case: $($case.id)" }
        foreach ($property in $case.expected.PSObject.Properties) {
            $actualValue = $actual[$case.id].($property.Name)
            if ($property.Value -is [double]) {
                if ([math]::Abs($actualValue - $property.Value) -gt 1e-10) { throw "Mismatch $($case.id).$($property.Name): $actualValue" }
            } elseif ($actualValue -ne $property.Value) { throw "Mismatch $($case.id).$($property.Name): $actualValue" }
        }
    }
    Write-Output "Plugin parity: $($fixture.cases.Count) cases passed; $($fixture.sources.Count) source hashes matched."
} finally {
    Pop-Location
}
