[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [Parameter(Mandatory)][string]$KeystorePath,
    [string]$KeyAlias = 'astralrecord-editor',
    [string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist/android'),
    [ValidateRange(1, 2100000000)][int]$VersionCode = 1,
    [ValidatePattern('^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.]+)?$')][string]$VersionName = '1.0.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $SdkRoot) { throw 'Specify -SdkRoot or set ANDROID_HOME.' }
if (-not $JavaHome) { throw 'Specify -JavaHome or set JAVA_HOME to JDK 17 or newer.' }

function Get-RequiredPath([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "File not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Invoke-BuildTool([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Tool failed: $Tool (exit $LASTEXITCODE)"
    }
}

$sdkTools = Join-Path $SdkRoot 'build-tools/36.0.0'
$androidJar = Get-RequiredPath (Join-Path $SdkRoot 'platforms/android-36/android.jar')
$aapt2 = Get-RequiredPath (Join-Path $sdkTools 'aapt2.exe')
$zipalign = Get-RequiredPath (Join-Path $sdkTools 'zipalign.exe')
$d8 = Get-RequiredPath (Join-Path $sdkTools 'lib/d8.jar')
$apksigner = Get-RequiredPath (Join-Path $sdkTools 'lib/apksigner.jar')
$java = Get-RequiredPath (Join-Path $JavaHome 'bin/java.exe')
$javac = Get-RequiredPath (Join-Path $JavaHome 'bin/javac.exe')
$jar = Get-RequiredPath (Join-Path $JavaHome 'bin/jar.exe')
$keystore = Get-RequiredPath $KeystorePath

$main = Join-Path $PSScriptRoot 'src/main'
$manifest = Join-Path $main 'AndroidManifest.xml'
$buildRoot = (New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'build')).FullName
$build = (New-Item -ItemType Directory -Path (Join-Path $buildRoot ([guid]::NewGuid().ToString('n')))).FullName
try {
    foreach ($name in @('generated', 'classes', 'dex')) {
        New-Item -ItemType Directory -Path (Join-Path $build $name) | Out-Null
    }
    $compiledResources = Join-Path $build 'resources.zip'
    $unaligned = Join-Path $build 'unaligned.apk'
    $unsigned = Join-Path $build 'unsigned.apk'
    $signed = Join-Path $build 'signed.apk'
    $classArchive = Join-Path $build 'classes.jar'

    Invoke-BuildTool $aapt2 @('compile', '--dir', (Join-Path $main 'res'), '-o', $compiledResources)
    Invoke-BuildTool $aapt2 @(
        'link', '-I', $androidJar, '--manifest', $manifest, '--java', (Join-Path $build 'generated'),
        '--min-sdk-version', '28', '--target-sdk-version', '36',
        '--version-code', ([string]$VersionCode), '--version-name', $VersionName,
        '-o', $unaligned, $compiledResources
    )
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $main 'java'), (Join-Path $build 'generated') -Recurse -File -Filter '*.java' |
        Sort-Object FullName | ForEach-Object FullName)
    Invoke-BuildTool $javac (@(
        '-J-Duser.language=en', '-encoding', 'UTF-8', '--release', '8',
        '-Xlint:all,-options', '-Werror', '-classpath', $androidJar, '-d', (Join-Path $build 'classes')
    ) + $sources)
    Invoke-BuildTool $jar @('--create', '--file', $classArchive, '-C', (Join-Path $build 'classes'), '.')
    Invoke-BuildTool $java @('-cp', $d8, 'com.android.tools.r8.D8', '--release',
        '--min-api', '28', '--lib', $androidJar, '--output', (Join-Path $build 'dex'), $classArchive)
    Invoke-BuildTool $jar @('--update', '--file', $unaligned, '-C', (Join-Path $build 'dex'), 'classes.dex')
    Invoke-BuildTool $zipalign @('-f', '4', $unaligned, $unsigned)

    $signArguments = @('-jar', $apksigner, 'sign', '--ks', $keystore, '--ks-key-alias', $KeyAlias,
        '--v4-signing-enabled', 'false', '--out', $signed)
    if ($env:ASTRAL_ANDROID_KEYSTORE_PASSWORD) {
        $signArguments += @('--ks-pass', 'env:ASTRAL_ANDROID_KEYSTORE_PASSWORD')
    }
    if ($env:ASTRAL_ANDROID_KEY_PASSWORD) {
        $signArguments += @('--key-pass', 'env:ASTRAL_ANDROID_KEY_PASSWORD')
    }
    Invoke-BuildTool $java ($signArguments + $unsigned)
    Invoke-BuildTool $java @('-jar', $apksigner, 'verify', '--verbose', '--print-certs', $signed)
    Invoke-BuildTool $zipalign @('-c', '4', $signed)

    $dist = (New-Item -ItemType Directory -Force -Path $OutputDirectory).FullName
    $fileName = 'AstralRecordMasterEditor.apk'
    $apk = Join-Path $dist $fileName
    Copy-Item -LiteralPath $signed -Destination $apk -Force
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $fileName" | Set-Content -LiteralPath "$apk.sha256" -Encoding ascii
    Write-Host "APK: $apk"
    Write-Host "SHA-256: $hash"
} finally {
    $resolvedBuild = (Resolve-Path -LiteralPath $build).Path
    if ((Split-Path -Parent $resolvedBuild) -eq (Resolve-Path -LiteralPath $buildRoot).Path) {
        Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
    }
}
