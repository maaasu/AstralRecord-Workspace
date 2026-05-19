# sync-database-docs.ps1
# AstralRecord データベース定義を docs/database へ同期するスクリプト。
# 実行例: .\scripts\sync-database-docs.ps1

$sourcePath = "E:\AstralRecord-Workspace\40_database"
$destPath   = Join-Path $PSScriptRoot "..\docs\database"

if (-not (Test-Path $sourcePath)) {
    Write-Error "コピー元が見つかりません: $sourcePath"
    exit 1
}

# コピー先を一旦クリアして最新状態に同期
if (Test-Path $destPath) {
    Remove-Item -Path "$destPath\*" -Recurse -Force
}
else {
    New-Item -ItemType Directory -Path $destPath | Out-Null
}

Copy-Item -Path "$sourcePath\*" -Destination $destPath -Recurse -Force

$count = (Get-ChildItem -Path $destPath -Recurse -File).Count
Write-Host "Sync complete: $count file(s) copied to $destPath"

