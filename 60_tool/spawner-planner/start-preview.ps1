param(
    [string]$ServerRoot = 'C:\server\AstralRecord-CodexDev',
    [string]$Filebase = (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) '40_filebase'),
    [int]$Port = 8765
)
$ErrorActionPreference = 'Stop'
$pythonPath = Join-Path $ServerRoot '.venv\Scripts\python.exe'
$terrainPath = Join-Path $ServerRoot 'analysis\terrain.json'
if (-not (Test-Path -LiteralPath $pythonPath)) { throw 'Create the Python .venv and install requirements.txt first.' }
if (-not (Test-Path -LiteralPath $terrainPath)) { throw 'Run the terrain scanner first.' }
$arguments = @((Join-Path $PSScriptRoot 'server.py'), '--terrain', $terrainPath, '--filebase', $Filebase, '--port', $Port)
$inventoryPath = Join-Path $ServerRoot 'analysis\mob_spawners.source.yml'
if (Test-Path -LiteralPath $inventoryPath) { $arguments += @('--existing', $inventoryPath) }
& $pythonPath @arguments
exit $LASTEXITCODE
