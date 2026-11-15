#requires -Version 7.0
[CmdletBinding()]
param([string]$DotNetPath = 'dotnet')

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceTools = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar-legacy-build-' + [Guid]::NewGuid().ToString('N'))
$toolRoot = Join-Path $fixture '60_tool'
$projectRoot = Join-Path $toolRoot '13-db-migrate'
[IO.Directory]::CreateDirectory($projectRoot) | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceTools 'Directory.Build.props') -Destination $toolRoot

function Write-FixtureFile([string]$RelativePath, [string]$Contents) {
    $path = Join-Path $projectRoot $RelativePath
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [IO.File]::WriteAllText($path, $Contents, [Text.UTF8Encoding]::new($false))
}

Write-FixtureFile 'Probe.csproj' '<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net10.0</TargetFramework></PropertyGroup></Project>'
Write-FixtureFile 'Current.cs' 'public sealed class CurrentTool {}'
Write-FixtureFile 'legacy-build/obj/Debug/net10.0/Old.AssemblyInfo.cs' '[assembly: System.Reflection.AssemblyCompany("Archived")]
[assembly: System.Runtime.Versioning.TargetFramework(".NETCoreApp,Version=v10.0")]'
Write-FixtureFile 'legacy-build/bin/Debug/net10.0/old-output.json' '{"archived":true}'
Write-FixtureFile 'legacy-build/obj/old.resx' 'This is an archived resource, not a valid current build input.'
Write-FixtureFile 'nested/legacy-build/obj/old.cs' 'This archived source must not be compiled.'
$archiveFiles = @(Get-ChildItem -LiteralPath $projectRoot -Recurse -File | Where-Object { $_.FullName -match '[\\/]legacy-build[\\/]' })
$originalHashes = @{}
foreach ($file in $archiveFiles) { $originalHashes[$file.FullName] = (Get-FileHash -LiteralPath $file.FullName).Hash }

$project = Join-Path $projectRoot 'Probe.csproj'
& $DotNetPath build $project --nologo
if ($LASTEXITCODE -ne 0) { throw 'Archived generated files broke the .NET build.' }
$evaluation = & $DotNetPath msbuild $project -nologo -getItem:Compile,EmbeddedResource,None
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect evaluated build inputs.' }
$items = ($evaluation -join "`n" | ConvertFrom-Json).Items
foreach ($kind in @('Compile', 'EmbeddedResource', 'None')) {
    foreach ($item in $items.$kind) {
        if ($item.Identity -match '(^|[\\/])legacy-build[\\/]') { throw "Archive remains in $kind inputs: $($item.Identity)" }
    }
}
if ('Current.cs' -notin @($items.Compile.Identity)) { throw 'Current source was incorrectly excluded.' }
foreach ($file in $archiveFiles) {
    if ((Get-FileHash -LiteralPath $file.FullName).Hash -ne $originalHashes[$file.FullName]) { throw 'Build changed archived files.' }
}
Write-Host "Legacy build exclusion passed; archived source/resources remain intact. Fixture: $fixture"
