#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$SourceRoot,[Parameter(Mandatory)][string]$OutputPath)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ApiFilebaseCutover.psm1') -Force
Assert-CutoverLocalPath $OutputPath
if (Test-Path -LiteralPath $OutputPath) { throw 'The manifest output must be new.' }
$manifest=Get-CutoverFileManifest $SourceRoot
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OutputPath -Encoding utf8NoBOM
Write-Host "Filebase manifest: $OutputPath"
Write-Host "Files: $($manifest.fileCount); SHA256: $((Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash)"
