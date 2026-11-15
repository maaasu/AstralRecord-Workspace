#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../Distribution.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar-maintenance-diagnostics-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition, [string]$Message) { if (!$Condition) { throw "ASSERT: $Message" } }
function Read-Events([string]$Directory) {
    @(Get-ChildItem -LiteralPath $Directory -Filter 'diagnostics-*.jsonl' | ForEach-Object {
        Get-Content -LiteralPath $_.FullName -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json }
    })
}
try {
    $source = Join-Path $fixture 'source'; $target = Join-Path $fixture 'target'
    $run = Join-Path $fixture 'success'; $failureRun = Join-Path $fixture 'failure'
    New-Item -ItemType Directory -Path "$source/plugins","$target/plugins",$run,$failureRun | Out-Null
    [IO.File]::WriteAllText("$source/plugins/AstralRecord.jar", 'new')
    [IO.File]::WriteAllText("$target/plugins/AstralRecord.jar", 'old')
    $config = @{schemaVersion=1; servers=@(@{id='source';enabled=$true;rootPath=$source},@{id='target';enabled=$true;rootPath=$target})
        distributions=@(@{enabled=$true;kind='Jar';source='source';targets=@('target');relativePath='plugins/AstralRecord.jar'})}
    $output = @(Invoke-MaintenanceDeploy $config $run)
    Assert ($output.Count -eq 0) 'logger must not pollute success pipeline'
    $events = @(Read-Events $run)
    foreach ($name in @('deploy.begin','capture.begin','hash.begin','hash.end','copy.begin','copy.end','capture.end','prepare.begin','prepare.end','apply.begin','backup.begin','backup.end','apply.end','deploy.completed','deploy.finally')) {
        Assert ($name -in $events.event) "missing $name"
    }
    Assert (@($events | Where-Object { !$_.utc -or $_.pid -ne $PID }).Count -eq 0) 'timestamp and PID present'
    $copy = ${function:Copy-MaintenanceArtifact}
    try {
        function Copy-MaintenanceArtifact { throw [IO.IOException]::new('SECRET-api-key-do-not-log', [Exception]::new('SECRET-inner-token')) }
        $rejected = $false
        try { Invoke-MaintenanceDeploy $config $failureRun } catch { $rejected = $true }
        Assert $rejected 'copy failure preserved'
    } finally { Set-Item -Path Function:Copy-MaintenanceArtifact -Value $copy }
    $events = @(Read-Events $failureRun)
    $failure = @($events | Where-Object event -eq 'deploy.failed')
    Assert ($failure.Count -eq 1 -and $failure[0].error.exceptions.Count -ge 2) 'nested error types persisted'
    Assert ($failure[0].error.line -gt 0 -and $failure[0].error.stack) 'failure location persisted'
    Assert (($events | ConvertTo-Json -Depth 20) -notmatch 'SECRET-') 'exception secrets omitted'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath "$failureRun/deployment.json" | ConvertFrom-Json).status -eq 'Failed') 'journal semantics unchanged'
    Assert ([IO.File]::ReadAllText("$target/plugins/AstralRecord.jar") -eq 'new') 'failed capture did not replace target'
    # The expected warning must remain non-terminating even under caller Stop preference.
    $maintenanceDiagnosticFile = Join-Path $fixture 'missing-directory/log.jsonl'
    $previousWarningPreference = $WarningPreference
    try {
        $WarningPreference = 'Stop'
        $warnings = @(Write-MaintenanceDiagnostic 'test.unwritable' 3>&1)
        Assert ($warnings.Count -eq 1 -and $warnings[0] -is [System.Management.Automation.WarningRecord]) 'log failure is a warning, not a transaction failure'
    } finally { $WarningPreference = $previousWarningPreference; $maintenanceDiagnosticFile = $null }
    # A killed helper cannot run finally; already appended events must remain readable.
    $killedRun = Join-Path $fixture 'killed'
    New-Item -ItemType Directory -Path $killedRun | Out-Null
    $module = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../Diagnostics.ps1')).Replace("'","''")
    $escapedRun = $killedRun.Replace("'","''")
    $command = ". '$module'; `$maintenanceDiagnosticFile = New-MaintenanceDiagnosticLog '$escapedRun' 'KilledTest'; Write-MaintenanceDiagnostic 'test.begin'; Start-Sleep -Seconds 60; Write-MaintenanceDiagnostic 'test.end'"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $child = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-EncodedCommand',$encoded) -WindowStyle Hidden -PassThru
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do { Start-Sleep -Milliseconds 100; $events = @(Read-Events $killedRun) } while (!$events.Count -and [DateTime]::UtcNow -lt $deadline)
        Assert ($events.Count -eq 1) 'child event persisted before termination'
    } finally {
        if (!$child.HasExited) { Stop-Process -Id $child.Id -Force; $child.WaitForExit() }
        $child.Dispose()
    }
    $events = @(Read-Events $killedRun)
    Assert ($events.Count -eq 1 -and $events[0].event -eq 'test.begin') 'interruption preserves last event'
    Write-Host "PASS: structured diagnostics, failures, secret omission, forced interruption. Evidence: $fixture"
} finally { Stop-Transcript | Out-Null }
