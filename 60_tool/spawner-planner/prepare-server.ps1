param(
    [Parameter(Mandatory=$true)][string]$SourceServer,
    [Parameter(Mandatory=$true)][string]$SourceWorld,
    [Parameter(Mandatory=$true)][string]$ServerRoot,
    [Parameter(Mandatory=$true)][string]$ScannerJar,
    [string]$WorldName = 'eriva_supercontinent',
    [int]$Port = 25579
)
$ErrorActionPreference = 'Stop'
function Assert-NoLinkedAncestor([string]$CandidatePath) {
    $current = [IO.Path]::GetFullPath($CandidatePath)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -Force -LiteralPath $current
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked path is not allowed: $current" }
        }
        $parent = [IO.Directory]::GetParent($current)
        $current = if ($parent) { $parent.FullName } else { $null }
    }
}
Assert-NoLinkedAncestor $SourceServer
Assert-NoLinkedAncestor $SourceWorld
Assert-NoLinkedAncestor $ServerRoot
$source = (Resolve-Path -LiteralPath $SourceServer).Path
$world = (Resolve-Path -LiteralPath $SourceWorld).Path
$target = [IO.Path]::GetFullPath($ServerRoot)
if (-not $world.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'SourceWorld must be inside SourceServer.' }
if ($target.StartsWith($source + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($target + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or $target -eq $source) { throw 'Source and target servers must be separate.' }
if ($WorldName -notmatch '^[a-z0-9_]+$' -or $Port -lt 1024 -or $Port -gt 65535) { throw 'Invalid world name or port.' }
if (Test-Path -LiteralPath (Join-Path $target $WorldName)) { throw 'Target world already exists. Existing data is never overwritten.' }
if (Test-Path -LiteralPath $target) {
    $existing = @(Get-ChildItem -Force -LiteralPath $target | Where-Object { $_.Name -ne '.venv' })
    if ($existing.Count -gt 0) { throw 'Target must be empty except for its Python .venv. Existing servers are never overwritten or reused.' }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw 'Target port is already in use.' }
$lockPath = Join-Path $world 'session.lock'
$lock = [IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
try {
    if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw 'Missing source level.dat.' }
    $eula = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $source 'eula.txt')
    if ($eula -notmatch '(?m)^eula=true\s*$') { throw 'Source server has no existing accepted EULA.' }
    $jars = @(Get-ChildItem -LiteralPath $source -File -Filter 'purpur-*.jar')
    if ($jars.Count -ne 1) { throw 'Expected exactly one source Purpur JAR.' }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $target 'plugins\RegionTerrainScanner') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $target 'analysis') -Force | Out-Null
    Copy-Item -LiteralPath $jars[0].FullName -Destination (Join-Path $target 'server.jar')
    Copy-Item -LiteralPath (Join-Path $source 'eula.txt') -Destination $target
    foreach ($name in @('libraries','versions','cache')) {
        if (Test-Path -LiteralPath (Join-Path $source $name)) {
            Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $target $name) -Recurse
        }
    }
    $worldTarget = Join-Path $target $WorldName
    New-Item -ItemType Directory -Path $worldTarget | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $world) {
        if ($item.Name -notin @('session.lock','playerdata','advancements','stats','DIM-1','DIM1')) {
            Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $worldTarget $item.Name) -Recurse
        }
    }
    Copy-Item -LiteralPath $ScannerJar -Destination (Join-Path $target 'plugins\region-terrain-scanner.jar')
    $utf8 = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText((Join-Path $target 'server.properties'), @"
server-ip=127.0.0.1
server-port=$Port
level-name=$WorldName
online-mode=true
enable-rcon=false
enable-query=false
allow-nether=false
spawn-protection=0
view-distance=3
simulation-distance=3
max-players=1
gamemode=spectator
difficulty=peaceful
pause-when-empty-seconds=-1
motd=AstralRecord Terrain Analysis
"@, $utf8)
    [IO.File]::WriteAllText((Join-Path $target 'bukkit.yml'), "settings:`n  allow-end: false`n", $utf8)
    $scannerConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'scanner\src\main\resources\config.yml')
    $scannerConfig = $scannerConfig.Replace('worldName: eriva_supercontinent', "worldName: $WorldName").Replace('shutdownAfterScan: false', 'shutdownAfterScan: true')
    [IO.File]::WriteAllText((Join-Path $target 'plugins\RegionTerrainScanner\config.yml'), $scannerConfig, $utf8)
    $locations = Join-Path $source 'plugins\AstralRecord\mob_spawners.yml'
    if (Test-Path -LiteralPath $locations) { Copy-Item -LiteralPath $locations -Destination (Join-Path $target 'analysis\mob_spawners.source.yml') }
    $receipt = @{ sourceWorld=$world; targetWorld=$worldTarget; copiedAt=[DateTime]::UtcNow.ToString('o'); scannerSha256=(Get-FileHash -LiteralPath $ScannerJar).Hash; sourceLevelSha256=(Get-FileHash -LiteralPath (Join-Path $world 'level.dat')).Hash }
    [IO.File]::WriteAllText((Join-Path $target 'analysis\snapshot-receipt.json'), ($receipt | ConvertTo-Json), $utf8)
    Write-Output "Prepared isolated analysis server: $target (127.0.0.1:$Port)"
} finally { $lock.Dispose() }
