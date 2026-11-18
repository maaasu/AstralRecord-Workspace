#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'dist/desktop'),
    [string]$DotnetPath,
    [string]$NpmPath,
    [string]$WebView2RuntimeDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))

if (-not $DotnetPath) {
    $configuredDotnet = $null
    $toolsConfiguration = Join-Path $workspaceRoot '60_tool/build-tools.local.json'
    if (Test-Path -LiteralPath $toolsConfiguration) {
        $configuration = Get-Content -LiteralPath $toolsConfiguration -Raw | ConvertFrom-Json -AsHashtable
        if ($configuration['dotnetRoot']) { $configuredDotnet = Join-Path $configuration['dotnetRoot'] 'dotnet.exe' }
    }
    $onPath = Get-Command dotnet.exe -ErrorAction SilentlyContinue
    $candidates = @(
        $env:ASTRALRECORD_DOTNET_EXE,
        $configuredDotnet,
        $(if ($env:DOTNET_ROOT) { Join-Path $env:DOTNET_ROOT 'dotnet.exe' }),
        $(Join-Path $env:USERPROFILE '.astralrecord/tools/dotnet/dotnet.exe'),
        $(Join-Path $env:LOCALAPPDATA 'AstralRecord/tools/dotnet/dotnet.exe'),
        $(if ($onPath) { $onPath.Source })
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            Push-Location $PSScriptRoot
            try {
                $version = & $candidate --version 2>&1
                if ($LASTEXITCODE -eq 0 -and ($version -match '^10\.')) { $DotnetPath = $candidate; break }
            } finally { Pop-Location }
        }
    }
}
if (-not $DotnetPath) { throw '.NET 10 SDK が見つかりません。-DotnetPath で dotnet.exe を指定してください。' }
if (-not $NpmPath) {
    $npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($npm) { $NpmPath = $npm.Source }
}
if (-not $NpmPath) { throw 'Node.js と npm が必要です。-NpmPath で npm.cmd を指定してください。' }
$DotnetPath = (Resolve-Path -LiteralPath $DotnetPath).Path
$NpmPath = (Resolve-Path -LiteralPath $NpmPath).Path
$nodeDirectory = Split-Path -Parent $NpmPath
if (-not (Test-Path -LiteralPath (Join-Path $nodeDirectory 'node.exe'))) { throw 'npm.cmd と同じフォルダーに node.exe が必要です。' }
if ($WebView2RuntimeDirectory) {
    $WebView2RuntimeDirectory = (Resolve-Path -LiteralPath $WebView2RuntimeDirectory).Path
    if (-not (Test-Path -LiteralPath (Join-Path $WebView2RuntimeDirectory 'msedgewebview2.exe'))) {
        throw 'WebView2RuntimeDirectory は Fixed Version Runtime x64 の msedgewebview2.exe があるフォルダーを指定してください。'
    }
}

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($outputRoot) | Out-Null
$buildId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 6))
$packageName = 'AstralRecordMasterEditor-win-x64'
$temporaryRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).Path
$stagingRoot = Join-Path $temporaryRoot "$packageName-$buildId"
$publishDirectory = Join-Path $stagingRoot $packageName
$zipPath = Join-Path $outputRoot "$packageName-$buildId.zip"
$logPath = Join-Path $outputRoot "$packageName-$buildId.log"

$previousPath = $env:PATH
$transcribing = $false
New-Item -ItemType Directory -Path $stagingRoot -ErrorAction Stop | Out-Null
try {
    Start-Transcript -Path $logPath | Out-Null
    $transcribing = $true
    [IO.Directory]::CreateDirectory($publishDirectory) | Out-Null
    $env:PATH = "$nodeDirectory;$previousPath"
    Push-Location (Join-Path $PSScriptRoot 'src/SkillTreeEditor.Client')
    try {
        & $NpmPath ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci に失敗しました。' }
        & $NpmPath run build
        if ($LASTEXITCODE -ne 0) { throw '画面のビルドに失敗しました。' }
    } finally { Pop-Location }
    Push-Location $PSScriptRoot
    try {
        & $DotnetPath publish 'src/SkillTreeEditor.Desktop/SkillTreeEditor.Desktop.csproj' -c Release -r win-x64 --self-contained true -o $publishDirectory -p:BuildDesktopPackage=true
        if ($LASTEXITCODE -ne 0) { throw 'デスクトップアプリの publish に失敗しました。' }
    } finally { Pop-Location }

    if (-not (Test-Path -LiteralPath (Join-Path $publishDirectory 'AstralRecordMasterEditor.exe'))) { throw 'EXE が生成されませんでした。' }
    if (-not (Test-Path -LiteralPath (Join-Path $publishDirectory 'wwwroot/index.html'))) { throw '画面が同梱されませんでした。' }
    if ($WebView2RuntimeDirectory) {
        Copy-Item -LiteralPath $WebView2RuntimeDirectory -Destination (Join-Path $publishDirectory 'webview2') -Recurse
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'DESKTOP.md') -Destination (Join-Path $publishDirectory 'README.md')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'ANDROID.md') -Destination $publishDirectory
    foreach ($package in @('dockview-react', 'dockview-core')) {
        $license = Join-Path $PSScriptRoot "src/SkillTreeEditor.Client/node_modules/$package/LICENCE.md"
        Copy-Item -LiteralPath $license -Destination (Join-Path $publishDirectory "licenses/$package-LICENSE.txt")
    }
    # Local configuration is never part of the portable distribution.
    Get-ChildItem -LiteralPath $publishDirectory -Filter 'appsettings.*.json' -File | Remove-Item
    [IO.Compression.ZipFile]::CreateFromDirectory($stagingRoot, $zipPath, [IO.Compression.CompressionLevel]::Optimal, $false)
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    [IO.File]::WriteAllText("$zipPath.sha256", "$hash  $([IO.Path]::GetFileName($zipPath))`n")
    Write-Host "ZIP: $zipPath"
    Write-Host "SHA256: $hash"
} finally {
    $env:PATH = $previousPath
    try {
        if ($transcribing) { Stop-Transcript | Out-Null }
    } finally {
        $staging = Get-Item -LiteralPath $stagingRoot -Force
        if ($staging.FullName -ne $stagingRoot -or ($staging.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "一時フォルダーの位置を確認できませんでした。 $stagingRoot"
        }
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}
