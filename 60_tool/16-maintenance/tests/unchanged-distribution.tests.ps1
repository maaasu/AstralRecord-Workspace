#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar-unchanged-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition, [string]$Message) { if (!$Condition) { throw "ASSERT: $Message" } }
function Put([string]$Path, [string]$Value) {
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null
    [IO.File]::WriteAllText($Path, $Value)
}
function Journal([string]$Run) { Get-Content -Raw -Encoding UTF8 -LiteralPath "$Run/deployment.json" | ConvertFrom-Json }
function New-Run([string]$Name) { $path=Join-Path $fixture $Name; New-Item -ItemType Directory -Path $path | Out-Null; return $path }
try {
    $source=Join-Path $fixture 'source'; $target=Join-Path $fixture 'target'
    foreach ($root in @($source,$target)) {
        Put "$root/plugins/AstralRecord.jar" 'same'
        Put "$root/plugins/AstralRecord/waystones.yml" 'same'
        Put "$root/filebase/config.yml" 'same'
        Put "$root/world/level.dat" 'same'
        Put "$root/world/region/r.0.0.mca" 'region-data'
        New-Item -ItemType Directory -Path "$root/world/empty" | Out-Null
    }
    Put "$source/world/uid.dat" 'source-id'; Put "$target/world/uid.dat" 'target-id'
    Put "$source/world/playerdata/source.dat" 'source-player'
    Put "$target/world/playerdata/target.dat" 'target-player'
    Put "$target/world/session.lock" 'target-lock'
    Put "$target/world/stats/target.json" 'target-stats'
    Put "$target/world/advancements/target.json" 'target-advancements'
    $config=@{schemaVersion=1;servers=@(@{id='source';enabled=$true;rootPath=$source},@{id='target';enabled=$true;rootPath=$target})
        distributions=@(
            @{enabled=$true;kind='Jar';source='source';relativePath='plugins/AstralRecord.jar';targets=@('target')},
            @{enabled=$true;kind='Placement';source='source';relativePath='plugins/AstralRecord/waystones.yml';targets=@('target')},
            @{enabled=$true;kind='Filebase';source='source';relativePath='filebase';targets=@('target')},
            @{enabled=$true;kind='World';source='source';relativePath='world';targets=@('target')})}
    $run=New-Run 'unchanged'
    Invoke-MaintenanceDeploy $config $run
    $journal=Journal $run
    Assert ($journal.status -eq 'Deployed' -and @($journal.operations | Where-Object status -ne 'Unchanged').Count -eq 0) 'all kinds skip identical content'
    Assert (Test-UpdateWorkflowDeploymentEvidence $run 'Channels') 'unchanged run remains valid workflow deployment evidence'
    foreach ($op in $journal.operations) {
        Assert (!(Test-Path -LiteralPath $op.stage) -and !(Test-Path -LiteralPath $op.backup)) 'unchanged creates no stage or backup'
    }
    Assert ([IO.File]::ReadAllText("$target/world/uid.dat") -eq 'target-id') 'world identity retained'
    Assert ([IO.File]::ReadAllText("$target/world/playerdata/target.dat") -eq 'target-player') 'local player state retained'
    Assert (!(Test-Path -LiteralPath "$target/world/playerdata/source.dat")) 'source player state not copied'
    $events=@(Get-ChildItem -LiteralPath $run -Filter 'diagnostics-Deploy-*.jsonl' | ForEach-Object { Get-Content -Encoding UTF8 -LiteralPath $_.FullName | ForEach-Object {$_ | ConvertFrom-Json} })
    $worldFile=[IO.Path]::GetFullPath("$target/world/region/r.0.0.mca")
    $reads=@($events | Where-Object { $_.event -eq 'hash.begin' -and $_.data.path -eq $worldFile })
    Assert ($reads.Count -eq 2) 'unchanged target shared data is read only for prepare and apply'
    Assert (@($events | Where-Object { $_.event -eq 'copy.begin' -and $_.data.destination.StartsWith($target) }).Count -eq 0) 'no transfer to unchanged target'
    Restore-MaintenanceDeployment $run
    Assert ((Journal $run).status -eq 'Restored' -and [IO.File]::ReadAllText($worldFile) -eq 'region-data') 'restore leaves untouched data in place'

    # Same size and timestamp do not imply same content; obsolete entries also force replacement.
    $jar="$target/plugins/AstralRecord.jar"; $stamp=(Get-Item -LiteralPath $jar).LastWriteTimeUtc
    Put $jar 'diff'; (Get-Item -LiteralPath $jar).LastWriteTimeUtc=$stamp
    (Get-Item -LiteralPath "$source/plugins/AstralRecord.jar").LastWriteTimeUtc=$stamp
    Put "$target/filebase/obsolete.yml" 'stale'
    Put $worldFile 'changed-map'
    $changed=New-Run 'mixed'
    Invoke-MaintenanceDeploy $config $changed
    $journal=Journal $changed
    Assert ($journal.operations[0].status -eq 'Applied' -and $journal.operations[1].status -eq 'Unchanged' -and $journal.operations[2].status -eq 'Applied' -and $journal.operations[3].status -eq 'Applied') 'mixed changed/unchanged plan'
    Assert (!(Test-Path -LiteralPath "$target/filebase/obsolete.yml")) 'obsolete data removed by exact replacement'
    Assert ([IO.File]::ReadAllText("$target/world/playerdata/target.dat") -eq 'target-player') 'changed world still preserves local data'
    Restore-MaintenanceDeployment $changed
    Assert ([IO.File]::ReadAllText($jar) -eq 'diff' -and (Test-Path -LiteralPath "$target/filebase/obsolete.yml")) 'changed artifacts restored'

    # A newly missing target is not treated as unchanged.
    $fresh=Join-Path $fixture 'fresh'; New-Item -ItemType Directory -Path $fresh | Out-Null
    $config.servers[1].rootPath=$fresh
    $newRun=New-Run 'fresh-run'; Invoke-MaintenanceDeploy $config $newRun
    Assert (@((Journal $newRun).operations | Where-Object status -ne 'Applied').Count -eq 0) 'fresh targets deployed'
    $config.servers[1].rootPath=$target

    # Mutation after preparation must fail, not report a stale unchanged decision as success.
    $script:savedJsonWriter=${function:Write-MaintenanceJson}
    foreach ($driftTarget in @($jar,$worldFile)) {
        Put $jar 'same'; Put $worldFile 'region-data'
        $driftRun=New-Run ('drift-'+[guid]::NewGuid().ToString('N'))
        try {
            function Write-MaintenanceJson($Value, [string]$Path) {
                & $script:savedJsonWriter $Value $Path
                if ($Value.status -eq 'Applying') { [IO.File]::WriteAllText($driftTarget, 'evil') }
            }
            $failed=$false
            try { Invoke-MaintenanceDeploy $config $driftRun } catch { $failed=$_.Exception.Message -match 'Unchanged destination contents changed' }
            Assert $failed 'unchanged drift detected by apply-time SHA256'
        } finally { Set-Item -Path Function:Write-MaintenanceJson -Value $script:savedJsonWriter }
        Assert ((Journal $driftRun).status -eq 'Failed') 'drift cannot become Deployed'
        Restore-MaintenanceDeployment $driftRun
        Assert ([IO.File]::ReadAllText($driftTarget) -eq 'evil') 'restore does not overwrite an artifact never modified by deployment'
    }
    $linkRoot=New-Run 'link-root'
    New-Item -ItemType Junction -Path "$linkRoot/nested" -Target $source | Out-Null
    $linkRejected=$false
    try { Assert-MaintenanceTreeNoLinks $linkRoot } catch { $linkRejected=$true }
    Assert $linkRejected 'metadata-only traversal still rejects nested junctions'
    Write-Host "PASS: unchanged artifacts, SHA256 drift checks, world-local state, mixed restore, fresh targets, link protection. Evidence: $fixture"
    Write-Host 'IO check: unchanged target region hashed twice, zero target copies/stages/backups.'
} finally { Stop-Transcript | Out-Null }
