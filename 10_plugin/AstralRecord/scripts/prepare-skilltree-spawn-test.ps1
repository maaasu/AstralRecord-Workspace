[CmdletBinding()]
param(
    [string]$ServerRoot = "",
    [int]$ServerPort = 25577,
    [switch]$UseLiveServerClone,
    [switch]$RefreshLiveServerClone
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pluginRoot = Split-Path -Parent $scriptRoot
$workspaceRoot = Split-Path -Parent (Split-Path -Parent $pluginRoot)
$startScript = Join-Path $scriptRoot "start-dev-server.ps1"

if ([string]::IsNullOrWhiteSpace($ServerRoot)) {
    $ServerRoot = Join-Path $pluginRoot ".dev-server\\integration-live-clone-skilltree"
}

& $startScript `
    -UseLiveServerClone:$UseLiveServerClone `
    -RefreshLiveServerClone:$RefreshLiveServerClone `
    -ServerRoot $ServerRoot `
    -NoStart

$configPath = Join-Path $ServerRoot "plugins\\AstralRecord\\config.yml"
if (-not (Test-Path -LiteralPath $configPath)) {
    throw "config.yml was not found: $configPath"
}

$configLines = [System.Collections.Generic.List[string]]::new()
$configLines.AddRange([string[]](Get-Content -LiteralPath $configPath))

$sqlserverIndex = -1
for ($i = 0; $i -lt $configLines.Count; $i++) {
    if ($configLines[$i] -match '^\s{2}sqlserver:\s*$') {
        $sqlserverIndex = $i
        break
    }
}

if ($sqlserverIndex -lt 0) {
    throw "sqlserver section was not found: $configPath"
}

$sectionEnd = $configLines.Count
for ($i = $sqlserverIndex + 1; $i -lt $configLines.Count; $i++) {
    if ($configLines[$i] -match '^\s{2}\S') {
        $sectionEnd = $i
        break
    }
}

$enabledUpdated = $false
for ($i = $sqlserverIndex + 1; $i -lt $sectionEnd; $i++) {
    if ($configLines[$i] -match '^\s{4}enabled:\s*') {
        $configLines[$i] = '    enabled: false'
        $enabledUpdated = $true
        break
    }
}

if (-not $enabledUpdated) {
    $configLines.Insert($sqlserverIndex + 1, '    enabled: false')
}

$fileSectionIndex = -1
for ($i = 0; $i -lt $configLines.Count; $i++) {
    if ($configLines[$i] -match '^\s{2}file:\s*$') {
        $fileSectionIndex = $i
        break
    }
}

if ($fileSectionIndex -lt 0) {
    throw "file section was not found: $configPath"
}

$fileSectionEnd = $configLines.Count
for ($i = $fileSectionIndex + 1; $i -lt $configLines.Count; $i++) {
    if ($configLines[$i] -match '^\s{2}\S') {
        $fileSectionEnd = $i
        break
    }
}

$fileRootPath = Join-Path $workspaceRoot "40_filebase"
$fileRootUpdated = $false
for ($i = $fileSectionIndex + 1; $i -lt $fileSectionEnd; $i++) {
    if ($configLines[$i] -match '^\s{4}rootPath:\s*') {
        $escapedPath = $fileRootPath.Replace('\', '\\')
        $configLines[$i] = "    rootPath: `"$escapedPath`""
        $fileRootUpdated = $true
        break
    }
}

if (-not $fileRootUpdated) {
    $escapedPath = $fileRootPath.Replace('\', '\\')
    $configLines.Insert($fileSectionIndex + 1, "    rootPath: `"$escapedPath`"")
}

Set-Content -LiteralPath $configPath -Value $configLines -Encoding UTF8

$serverPropertiesPath = Join-Path $ServerRoot "server.properties"
if (Test-Path -LiteralPath $serverPropertiesPath) {
    $serverProperties = Get-Content -LiteralPath $serverPropertiesPath
    $serverPortUpdated = $false
    for ($i = 0; $i -lt $serverProperties.Count; $i++) {
        if ($serverProperties[$i] -match '^server-port=') {
            $serverProperties[$i] = "server-port=$ServerPort"
            $serverPortUpdated = $true
            break
        }
    }
    if (-not $serverPortUpdated) {
        $serverProperties += "server-port=$ServerPort"
    }
    Set-Content -LiteralPath $serverPropertiesPath -Value $serverProperties -Encoding ASCII
}

$resourcePackPromptUpdated = $false
for ($i = 0; $i -lt $configLines.Count; $i++) {
    if ($configLines[$i] -match '^\s{2}prompt:\s*') {
        $configLines[$i] = '  prompt: "AstralRecord resource pack"'
        $resourcePackPromptUpdated = $true
        break
    }
}
if ($resourcePackPromptUpdated) {
    Set-Content -LiteralPath $configPath -Value $configLines -Encoding UTF8
}

Write-Host "[AstralRecord skilltree-test] Prepared: $ServerRoot"
Write-Host "[AstralRecord skilltree-test] SQL Server disabled in: $configPath"
Write-Host "[AstralRecord skilltree-test] Filebase root set to: $fileRootPath"
Write-Host "[AstralRecord skilltree-test] Server port set to: $ServerPort"
Write-Host "[AstralRecord skilltree-test] Next:"
Write-Host "  1. Start server with start-dev-server.ps1 -ServerRoot `"$ServerRoot`" -SkipBuild"
Write-Host "  2. Join the server and run /testskilltree"
