[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RootPath,

    [string[]]$IncludeExtensions = @(".java", ".kt"),

    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $RootPath)) {
    throw "Root path not found: $RootPath"
}

$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$cp932 = [System.Text.Encoding]::GetEncoding(932)

$normalized = 0
$invalidUtf8 = 0
$bomDetected = 0
$targets = Get-ChildItem -LiteralPath $RootPath -Recurse -File | Where-Object {
    $ext = [System.IO.Path]::GetExtension($_.Name)
    $IncludeExtensions -contains $ext
}

foreach ($file in $targets) {
    $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
    $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF
    $decoded = $null
    $needsNormalize = $false

    try {
        $decoded = $utf8Strict.GetString($bytes)
        if ($hasBom) {
            $bomDetected++
            $needsNormalize = $true
        }
    }
    catch {
        $invalidUtf8++
        $decoded = $cp932.GetString($bytes)
        $needsNormalize = $true
    }

    if (-not $needsNormalize) {
        continue
    }

    if ($CheckOnly) {
        continue
    }

    [System.IO.File]::WriteAllText($file.FullName, $decoded, $utf8NoBom)
    $normalized++
}

Write-Host "Encoding check completed:"
Write-Host "  Files scanned: $($targets.Count)"
Write-Host "  Invalid UTF-8 detected: $invalidUtf8"
Write-Host "  UTF-8 BOM detected: $bomDetected"
Write-Host "  Files normalized: $normalized"

if ($CheckOnly -and ($invalidUtf8 -gt 0 -or $bomDetected -gt 0)) {
    throw "Encoding issues detected. Run without -CheckOnly to normalize files."
}
