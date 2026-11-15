#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-JobTest([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}

function Write-TestJson($Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding utf8
}

function New-TestJob($Options, [string]$Fingerprint) {
    $id = [Guid]::NewGuid().ToString('D')
    $name = if ($Fingerprint) { $id } else { "plan-$id" }
    $directory = Join-Path $jobRoot $name
    $null = New-Item -ItemType Directory -Path $directory
    Write-TestJson @{options=$Options;fingerprint=$Fingerprint} (Join-Path $directory 'request.json')
    if ($Fingerprint) {
        Write-TestJson @{id=$id;toolId=$Options.toolId;status='starting';startedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')} (Join-Path $directory 'state.json')
    }
    return $directory
}

function Invoke-TestPlan($Options) {
    $job = New-TestJob $Options
    & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $adapter Plan $job $workspace
    if ($LASTEXITCODE -ne 0) {
        $errorPath = Join-Path $job 'error.json'
        if (Test-Path -LiteralPath $errorPath) { throw (Get-Content -Raw -Encoding UTF8 -LiteralPath $errorPath) }
        throw "Plan failed with exit code $LASTEXITCODE"
    }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $job 'plan.json') | ConvertFrom-Json
}

$source = Split-Path -Parent $PSScriptRoot
$workspace = Join-Path ([IO.Path]::GetTempPath()) "Astral tool ジョブ $([Guid]::NewGuid().ToString('D'))"
$jobRoot = Join-Path $workspace '60_tool/05-master-data-editor/.cache/tool-jobs'
$testTool = Join-Path $workspace '60_tool'
$adapter = Join-Path $source '05-master-data-editor/Invoke-EditorToolJob.ps1'
$bootstrap = Join-Path $testTool 'invoke-powershell7.ps1'
$windowsPowerShell = Join-Path ([Environment]::SystemDirectory) 'WindowsPowerShell/v1.0/powershell.exe'
$oldProgramData = $env:ProgramData
$oldKey = $env:ASTRAL_JOB_TEST_API_KEY
$worker = $null
try {
    foreach ($folder in @($jobRoot, (Join-Path $testTool '12-build-network-plugins'), (Join-Path $workspace '40_filebase'),
        (Join-Path $workspace 'source-filebase'), (Join-Path $workspace '10_plugin/AstralRecordLobby'),
        (Join-Path $workspace 'fixture-program-data'), (Join-Path $workspace 'dev/plugins'),
        (Join-Path $workspace 'channel/plugins'))) {
        $null = New-Item -ItemType Directory -Path $folder -Force
    }
    foreach ($folder in @('01-deploy-debug','16-maintenance','control-gate')) {
        $null = New-Item -ItemType Directory -Path (Join-Path $testTool $folder)
        Get-ChildItem -LiteralPath (Join-Path $source $folder) -File |
            Where-Object Extension -in @('.ps1','.psm1','.json') |
            Where-Object Name -notmatch '\.local\.' |
            ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $testTool $folder) }
    }
    Copy-Item -LiteralPath (Join-Path $source 'invoke-powershell7.ps1') -Destination $bootstrap
    Write-TestJson @{executablePath=(Get-Process -Id $PID).Path} (Join-Path $testTool 'powershell7.local.json')
    $env:ProgramData = Join-Path $workspace 'fixture-program-data'
    $env:ASTRAL_JOB_TEST_API_KEY = 'secret-fixture-api-key'
    $config = Join-Path $testTool '01-deploy-debug/deploy-debug.local.json'
    $dev = Join-Path $workspace 'dev'
    $channel = Join-Path $workspace 'channel'
    $migration = @{enabled=$true;baseUrl='https://localhost:1';apiKeyEnvironmentVariable='ASTRAL_JOB_TEST_API_KEY';
        migrationKeyEnvironmentVariable='ASTRAL_JOB_TEST_MIGRATION_KEY';serverIds=@('Dev')}
    $deploy = @{
        plugin=@{enabled=$false;deployPath=(Join-Path $dev 'plugins')}
        api=@{enabled=$false};web=@{enabled=$false}
        fileDatabase=@{enabled=$true;sourcePath=(Join-Path $workspace 'source-filebase');deployPath=(Join-Path $dev 'filebase')}
        devWorkflow=@{runRoot='';seedMasterData=$true;migration=$migration}
    }
    Write-TestJson $deploy $config

    $plan03 = Invoke-TestPlan @{toolId='03';recovery='Auto'}
    Assert-JobTest ($plan03.canRun -and $plan03.workspaceMismatch) '03 must disclose the effective source mismatch.'
    Assert-JobTest ($plan03.paths[0].source -eq $deploy.fileDatabase.sourcePath) '03 must show the configured source.'
    Assert-JobTest ('workspaceMismatch' -in $plan03.confirmations.id) '03 must require a separate mismatch confirmation.'
    $plan01 = Invoke-TestPlan @{toolId='01';pluginTestMode='All';recovery='Auto'}
    Assert-JobTest ($plan01.canRun -and $plan01.paths[0].destination -eq $deploy.fileDatabase.deployPath) '01 plan should use its actual destination.'
    $deploy.plugin = @{
        enabled=$true;deployPath=(Join-Path $dev 'plugins');projectPath=(Join-Path $workspace 'missing-plugin')
        buildOutputPath=(Join-Path $workspace 'missing-plugin/dist');artifactPattern='AstralRecord-*.jar'
    }
    Write-TestJson $deploy $config
    $blocked01 = Invoke-TestPlan @{toolId='01';pluginTestMode='All';recovery='Auto'}
    Assert-JobTest (!$blocked01.canRun -and ($blocked01.warnings -join '').Contains('pom.xml')) '01 must fail preflight before Dev can stop for an absent build project.'
    $deploy.plugin.enabled = $false
    $deploy.fileDatabase.sourcePath = Join-Path $workspace 'missing-filebase'
    Write-TestJson $deploy $config
    $blocked03 = Invoke-TestPlan @{toolId='03';recovery='Auto'}
    Assert-JobTest (!$blocked03.canRun -and $blocked03.warnings.Count -ge 1) 'A missing source must prevent execution.'
    Assert-JobTest (!(Test-Path -LiteralPath $deploy.fileDatabase.deployPath)) '01/03 plans must not copy master files.'

    Set-Content -LiteralPath (Join-Path $dev 'plugins/Fixture.jar') -Value 'fixture'
    $maintenance = @{
        schemaVersion=1;workflow=@{runRoot='';seedMasterData=$false};migration=$migration
        servers=@(@{id='Dev';enabled=$true;rootPath=$dev},@{id='Channel';enabled=$true;rootPath=$channel})
        distributions=@(@{enabled=$true;kind='Jar';source='Dev';relativePath='plugins/Fixture.jar';targets=@('Channel')})
    }
    Write-TestJson $maintenance (Join-Path $testTool '16-maintenance/maintenance.local.json')
    $plan16 = Invoke-TestPlan @{toolId='16';worldCopy='Skip';networkPlugins='Skip';recovery='Auto'}
    Assert-JobTest ($plan16.canRun -and $plan16.paths[0].destination -eq (Join-Path $channel 'plugins/Fixture.jar')) '16 preview must use existing selection and distribution paths.'
    Assert-JobTest (!(Test-Path -LiteralPath (Join-Path $channel 'plugins/Fixture.jar'))) '16 plan must not copy the artifact.'

    Set-Content -LiteralPath (Join-Path $workspace '10_plugin/AstralRecordLobby/pom.xml') -Value '<project/>'
    $fakeBuild = Join-Path $testTool '12-build-network-plugins/build-network-plugins.ps1'
    @'
param([string]$Target,[switch]$SkipTests)
Write-Host "開始 $Target $SkipTests $env:ASTRAL_JOB_TEST_API_KEY"
'スルーパス' | Out-Host
$answer = Read-Host '1: 外部状態を確認済みとして記録を保全し新規実行 / 2: 中止（既定）'
if ($answer -cne '1') { exit 2 }
exit 7
'@ | Set-Content -LiteralPath $fakeBuild -Encoding utf8
    $options12 = @{toolId='12';networkTarget='Lobby';skipTests=$true}
    $plan12 = Invoke-TestPlan $options12
    Assert-JobTest ($plan12.canRun -and $plan12.paths.Count -eq 1 -and $plan12.paths[0].destination.EndsWith('AstralRecordLobby.jar')) '12 should plan only the selected artifact.'
    $job = New-TestJob $options12 $plan12.fingerprint
    # Start only the isolated fixture worker; the real build and update paths are never executed.
    $launch = [Diagnostics.ProcessStartInfo]::new($windowsPowerShell)
    $launch.CreateNoWindow = $true
    $launch.UseShellExecute = $false
    foreach ($argument in @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$bootstrap,$adapter,'Run',$job,$workspace)) {
        $launch.ArgumentList.Add($argument)
    }
    $worker = [Diagnostics.Process]::Start($launch)
    $statePath = Join-Path $job 'state.json'
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 100
        $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json
    } until ($state.status -eq 'waitingForInput' -or $worker.HasExited -or [DateTime]::UtcNow -gt $deadline)
    if ($state.status -ne 'waitingForInput') {
        $logPath = Join-Path $job 'log.jsonl'
        if (Test-Path -LiteralPath $logPath) { Get-Content -Encoding UTF8 -LiteralPath $logPath | Write-Host }
        throw "Expected durable confirmation, got $($state.status)."
    }
    Assert-JobTest ($state.prompt.choices.value -contains '1') 'Only predefined values can be confirmed.'
    Write-TestJson @{promptId=$state.prompt.id;value='1'} (Join-Path $job "input-$($state.prompt.id).json")
    Assert-JobTest ($worker.WaitForExit(10000) -and $worker.ExitCode -eq 7) 'The bootstrap must return the runner exit code.'
    $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json
    Assert-JobTest ($state.status -eq 'failed' -and $state.exitCode -eq 7 -and !$state.prompt) 'The durable status must capture failure and clear its prompt.'
    $log = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $job 'log.jsonl')
    Assert-JobTest ($log.Contains('開始 Lobby True') -and $log.Contains('スルーパス') -and !$log.Contains($env:ASTRAL_JOB_TEST_API_KEY)) 'Logs should contain UTF-8 output and redact known secrets.'

    Add-Content -LiteralPath $fakeBuild -Value '# changed after plan'
    $staleJob = New-TestJob $options12 $plan12.fingerprint
    & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $adapter Run $staleJob $workspace
    $staleLog = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $staleJob 'log.jsonl')
    Assert-JobTest ($LASTEXITCODE -ne 0 -and $staleLog.Contains('変わりました') -and !$staleLog.Contains('開始 Lobby')) 'Run must refuse a stale plan before it executes.'
} finally {
    if ($worker) {
        if (!$worker.HasExited) { $worker.Kill($true); $worker.WaitForExit() }
        $worker.Dispose()
    }
    $env:ProgramData = $oldProgramData
    $env:ASTRAL_JOB_TEST_API_KEY = $oldKey
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ([IO.Path]::GetFullPath($workspace).StartsWith($temp + 'Astral tool ジョブ ', [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $workspace)) {
        Remove-Item -LiteralPath $workspace -Recurse -Force
    }
}
Write-Host 'Desktop tool plan, settings fingerprint, worker input, exit code and log checks passed.'
