#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar-maintenance-entry-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
$entry = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../maintenance.ps1'))
$pwsh = (Get-Command pwsh).Source
function Check-Exit([string[]]$Arguments, [bool]$Success) {
    $text = & $pwsh -NoProfile -File $entry @Arguments 2>&1
    $code = $LASTEXITCODE
    $text | ForEach-Object { Write-Host $_ }
    if (($code -eq 0) -ne $Success) { throw "Unexpected exit code $code" }
}
try {
    $dev = Join-Path $fixture 'dev'
    $channel = Join-Path $fixture 'channel'
    New-Item -ItemType Directory -Path "$dev/plugins", "$channel/plugins" | Out-Null
    [IO.File]::WriteAllText("$dev/plugins/AstralRecord.jar", 'new')
    [IO.File]::WriteAllText("$channel/plugins/AstralRecord.jar", 'old')
    $config = @{
        schemaVersion=1
        servers=@(@{id='dev';enabled=$true;rootPath=$dev},@{id='channel';enabled=$true;rootPath=$channel})
        distributions=@(@{enabled=$true;kind='Jar';source='dev';relativePath='plugins/AstralRecord.jar';targets=@('channel')})
        migration=@{enabled=$false;scope='ExplicitAccounts'}
    }
    $configPath = Join-Path $fixture 'config.json'
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    $run = Join-Path $fixture 'run'
    Check-Exit @('-ConfigPath',$configPath,'-Phase','Plan') $true
    if (Test-Path -LiteralPath (Join-Path $channel '.astral-maintenance')) { throw 'Plan wrote server data.' }
    Check-Exit @('-ConfigPath',$configPath,'-Phase','Deploy','-RunDirectory',$run) $false
    if (Test-Path -LiteralPath $run) { throw 'Unconfirmed Deploy wrote run data.' }
    New-Item -ItemType Directory -Path "$channel/.astral-maintenance" | Out-Null
    $locked = [IO.File]::Open("$channel/.astral-maintenance/distribution.lock", 'OpenOrCreate', 'ReadWrite', 'None')
    try { Check-Exit @('-ConfigPath',$configPath,'-Phase','Deploy','-RunDirectory',$run,'-ServersStopped') $false }
    finally { $locked.Dispose() }
    if ([IO.File]::ReadAllText("$channel/plugins/AstralRecord.jar") -ne 'old') { throw 'Concurrent run modified destination.' }
    Check-Exit @('-ConfigPath',$configPath,'-Phase','Deploy','-RunDirectory',$run,'-ServersStopped') $true
    Check-Exit @('-ConfigPath',$configPath,'-Phase','MigrateCommit','-RunDirectory',$run) $false
    Check-Exit @('-ConfigPath',$configPath,'-Phase','MigrateCommit','-RunDirectory',$run,'-AdmissionClosed') $false
    Check-Exit @('-ConfigPath',$configPath,'-Phase','Restore','-RunDirectory',$run,'-ServersStopped') $true
    if ([IO.File]::ReadAllText("$channel/plugins/AstralRecord.jar") -ne 'old') { throw 'Entry restore failed.' }
    $config.migration.scope='AllCandidates'
    $config | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $configPath
    Check-Exit @('-ConfigPath',$configPath,'-Phase','Restore','-RunDirectory',$run,'-ServersStopped') $false
    Write-Host "PASS: entry confirmation, run/config lock, server lock, deployment and restore. Evidence: $fixture"
} finally { Stop-Transcript | Out-Null }
