$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$output = Join-Path $dist 'AstralRecordResourcePack.zip'

New-Item -ItemType Directory -Force -Path $dist | Out-Null
if (Test-Path $output) {
    Remove-Item -LiteralPath $output -Force
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open(
    $output,
    [System.IO.Compression.ZipArchiveMode]::Create
)

try {
    $files = @(
        Get-Item -LiteralPath (Join-Path $root 'pack.mcmeta')
    )

    $packIcon = Join-Path $root 'pack.png'
    if (Test-Path $packIcon) {
        $files += Get-Item -LiteralPath $packIcon
    }

    $files += Get-ChildItem -LiteralPath (Join-Path $root 'assets') -Recurse -File

    foreach ($file in $files) {
        $rootPrefix = $root.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
        $relativePath = $file.FullName.Substring($rootPrefix.Length)
        $entryName = $relativePath.Replace('\', '/')

        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip,
            $file.FullName,
            $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
}
finally {
    $zip.Dispose()
}

Write-Host "Created $output"
