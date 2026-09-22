#requires -Version 7.0
$ErrorActionPreference='Stop'
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar bootstrap '+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
Start-Transcript -LiteralPath (Join-Path $root 'verification.log') | Out-Null
$previousPath=$env:Path
try {
    $pwshPath=(Get-Command pwsh -CommandType Application).Source
    $bootstrap=Join-Path $root 'invoke-powershell7.ps1'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../invoke-powershell7.ps1') -Destination $bootstrap
    @{executablePath=$pwshPath} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root 'powershell7.local.json') -Encoding UTF8
    $child=Join-Path $root 'child script.ps1'
    [IO.File]::WriteAllText($child, @'
param([switch]$Plan,[string]$ConfigPath,[string]$OutputPath,[int]$ExitCode=0)
@{plan=[bool]$Plan;config=$ConfigPath;major=$PSVersionTable.PSVersion.Major} | ConvertTo-Json | Set-Content -LiteralPath $OutputPath
exit $ExitCode
'@)
    $env:Path=[Environment]::GetEnvironmentVariable('Path','Machine')+';'+[Environment]::GetEnvironmentVariable('Path','User')
    $output=Join-Path $root 'child output.json'
    $configPath=Join-Path $root 'config with spaces.json'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $bootstrap $child -Plan -ConfigPath $configPath -OutputPath $output -ExitCode 7
    if ($LASTEXITCODE -ne 7) { throw 'Bootstrap did not preserve child exit code.' }
    $result=Get-Content -Raw -LiteralPath $output | ConvertFrom-Json
    if (!$result.plan -or $result.config -cne $configPath -or $result.major -lt 7) { throw 'Bootstrap did not preserve arguments or select PowerShell 7.' }
    Write-Host "PASS: normal Windows PATH, explicit runtime, paths with spaces, child switches, exit code. Evidence: $root"
} finally { $env:Path=$previousPath; Stop-Transcript | Out-Null }
