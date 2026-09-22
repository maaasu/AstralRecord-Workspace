# Windows PowerShell 5 compatible bootstrap; remaining arguments are forwarded without evaluation.
param([string]$ScriptPath)
$ErrorActionPreference='Stop'
try {
    $localConfig=Join-Path $PSScriptRoot 'powershell7.local.json'
    if (Test-Path -LiteralPath $localConfig -PathType Leaf) {
        $settings=Get-Content -Raw -Encoding UTF8 -LiteralPath $localConfig | ConvertFrom-Json
        $executable=[string]$settings.executablePath
        if (![IO.Path]::IsPathRooted($executable) -or !(Test-Path -LiteralPath $executable -PathType Leaf) -or
            [IO.Path]::GetFileName($executable) -ine 'pwsh.exe') { throw 'powershell7.local.json must point to an existing absolute pwsh.exe path.' }
    } else {
        $command=Get-Command pwsh -CommandType Application -ErrorAction SilentlyContinue
        if (!$command) { throw 'PowerShell 7 is required. Configure 60_tool/powershell7.local.json with executablePath or install pwsh on PATH.' }
        $executable=$command.Source
    }
    if ([string]::IsNullOrWhiteSpace($ScriptPath) -or !(Test-Path -LiteralPath $ScriptPath -PathType Leaf)) { throw 'The update entry script was not found.' }
    & $executable -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @args
    exit $LASTEXITCODE
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
