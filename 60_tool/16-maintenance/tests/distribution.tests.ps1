#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../Distribution.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar-maintenance-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition, [string]$Message) { if (!$Condition) { throw "ASSERT: $Message" } }
function Write-Fixture([string]$Path, [string]$Content) {
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Content)
}
function Reject([scriptblock]$Action, [string]$Name) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    Assert $rejected $Name
}
try {
    $dev = Join-Path $fixture 'dev'
    $build = Join-Path $fixture 'build'
    $channel = Join-Path $fixture 'channel'
    $run = Join-Path $fixture 'run'
    New-Item -ItemType Directory -Path $run | Out-Null
    Write-Fixture "$dev/plugins/AstralRecord.jar" 'new-jar'
    Write-Fixture "$channel/plugins/AstralRecord.jar" 'old-jar'
    Write-Fixture "$channel/plugins/AstralRecord/config.yml" 'channel-private-config'
    Write-Fixture "$dev/plugins/AstralRecord/waystones.yml" 'waystones'
    Write-Fixture "$dev/filebase/config.yml" 'filebase-config'
    Write-Fixture "$dev/filebase/nodes/new.json" '{}'
    Write-Fixture "$channel/filebase/obsolete.yml" 'removed-master'
    Write-Fixture "$build/world/level.dat" 'new-level'
    Write-Fixture "$build/world/region/r.0.0.mca" 'new-region'
    Write-Fixture "$build/world/uid.dat" 'build-uuid'
    Write-Fixture "$build/world/playerdata/build.dat" 'build-player'
    Write-Fixture "$channel/world/level.dat" 'old-level'
    Write-Fixture "$channel/world/region/stale.mca" 'obsolete-region'
    Write-Fixture "$channel/world/uid.dat" 'channel-uuid'
    Write-Fixture "$channel/world/playerdata/player.dat" 'channel-player'
    $config = @{
        schemaVersion=1
        servers=@(@{id='dev';enabled=$true;rootPath=$dev}, @{id='build';enabled=$true;rootPath=$build}, @{id='channel';enabled=$true;rootPath=$channel})
        distributions=@(
            @{enabled=$true;kind='Jar';source='dev';relativePath='plugins/AstralRecord.jar';targets=@('channel','build')},
            @{enabled=$true;kind='Placement';source='dev';relativePath='plugins/AstralRecord/waystones.yml';targets=@('channel')},
            @{enabled=$true;kind='Filebase';source='dev';relativePath='filebase';targets=@('channel')},
            @{enabled=$true;kind='World';source='build';relativePath='world';targets=@('channel','dev')}
        )
    }
    Assert (@(Get-MaintenancePlan $config).Count -eq 6) 'plan count'
    Assert (!(Test-Path "$build/plugins/AstralRecord.jar")) 'plan is read only'
    Invoke-MaintenanceDeploy $config $run
    Assert ((Get-Content -Raw "$channel/plugins/AstralRecord.jar") -eq 'new-jar') 'jar deployed'
    Assert ((Get-Content -Raw "$build/plugins/AstralRecord.jar") -eq 'new-jar') 'identical jar to build'
    Assert ((Get-Content -Raw "$channel/plugins/AstralRecord/config.yml") -eq 'channel-private-config') 'server settings preserved'
    Assert (!(Test-Path "$channel/filebase/obsolete.yml")) 'filebase is replaced, not merged'
    Assert (!(Test-Path "$channel/world/region/stale.mca")) 'obsolete world regions removed by replacement'
    Assert ((Get-Content -Raw "$channel/world/uid.dat") -eq 'channel-uuid') 'destination identity preserved'
    Assert ((Get-Content -Raw "$channel/world/playerdata/player.dat") -eq 'channel-player') 'destination player data preserved'
    Assert (!(Test-Path "$channel/world/playerdata/build.dat")) 'build player data not distributed'
    Assert (!(Test-Path "$dev/world/uid.dat")) 'fresh destination does not clone build uuid'
    Reject { Invoke-MaintenanceDeploy $config $run } 'same run cannot redeploy'
    Restore-MaintenanceDeployment $run
    Assert ((Get-Content -Raw "$channel/plugins/AstralRecord.jar") -eq 'old-jar') 'jar restored'
    Assert ((Get-Content -Raw "$channel/world/level.dat") -eq 'old-level') 'world restored'
    Assert (Test-Path "$channel/world/region/stale.mca") 'old regions restored'
    Assert (!(Test-Path "$dev/world")) 'new world removed recoverably'
    Restore-MaintenanceDeployment $run
    Write-MaintenanceJson @{ started=$true } (Join-Path $run 'migration-commit-started.json')
    Reject { Restore-MaintenanceDeployment $run } 'restore after possible DB migration blocked'
    $missingRun = Join-Path $fixture 'missing-backup-run'
    New-Item -ItemType Directory -Path $missingRun | Out-Null
    Invoke-MaintenanceDeploy $config $missingRun
    $missingJournalPath = Join-Path $missingRun 'deployment.json'
    $missingJournal = Get-Content -Raw -Encoding utf8 -LiteralPath $missingJournalPath | ConvertFrom-Json -AsHashtable
    $missingOperation = $missingJournal.operations[0]
    $backupPath = [IO.Path]::GetFullPath($missingOperation.backup)
    if (!$backupPath.StartsWith($fixture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture backup escaped test root.' }
    Move-Item -LiteralPath $backupPath -Destination (Join-Path $missingRun 'saved-original')
    Reject { Restore-MaintenanceDeployment $missingRun } 'missing backup must reject restore'
    $missingJournal = Get-Content -Raw -Encoding utf8 -LiteralPath $missingJournalPath | ConvertFrom-Json -AsHashtable
    Assert ($missingJournal.status -ne 'Restored') 'missing backup cannot be reported restored'
    Assert ((Get-Content -Raw "$channel/plugins/AstralRecord.jar") -eq 'new-jar') 'failed restore leaves recoverable new data'
    Reject { Resolve-MaintenanceChild $dev '../channel' } 'traversal rejected'
    Reject { Resolve-MaintenanceChild $dev 'plugins/*.jar' } 'wildcard rejected'
    Reject { Resolve-MaintenanceChild $dev 'plugins/AstralRecord.jar:stream' } 'ADS rejected'
    Reject { Get-MaintenanceAbsolutePath 'E:\' } 'volume root rejected'
    $config.distributions[0].relativePath='plugins/AstralRecord/config.yml'
    Reject { Get-MaintenancePlan $config } 'generic config distribution rejected'
    $config.distributions[0].relativePath='plugins/AstralRecord.jar'
    $config.distributions[0].targets=@('dev')
    Reject { Get-MaintenancePlan $config } 'self copy rejected'
    $config.distributions[0].targets=@('channel','channel')
    Reject { Get-MaintenancePlan $config } 'duplicate target rejected'
    $config.distributions[0].targets=@('channel')
    $config.servers[2].rootPath=''
    Reject { Get-MaintenancePlan $config } 'enabled missing path rejected'
    $config.servers[2].enabled=$false
    Assert (@(Get-MaintenancePlan $config).Count -eq 1) 'disabled server skipped'
    $config.servers[2].rootPath=$channel
    $config.servers[2].enabled=$true
    $junction = Join-Path $dev 'linked'
    New-Item -ItemType Junction -Path $junction -Target $channel | Out-Null
    Reject { Resolve-MaintenanceChild $dev 'linked/plugins/AstralRecord.jar' } 'junction escape rejected'
    Write-Host 'PASS: distribution, world isolation, exact replacement, restore, migration fence, path boundaries, disabled targets.'
    Write-Host "Fixtures and transcript: $fixture"
} finally { Stop-Transcript | Out-Null }
