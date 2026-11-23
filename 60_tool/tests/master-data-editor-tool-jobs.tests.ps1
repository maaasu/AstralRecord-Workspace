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

function Invoke-TestRequest($Options, [string]$Mode, [string]$ResultFile) {
    $job = New-TestJob $Options
    & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $adapter $Mode $job $workspace
    if ($LASTEXITCODE -ne 0) {
        $errorPath = Join-Path $job 'error.json'
        if (Test-Path -LiteralPath $errorPath) { throw (Get-Content -Raw -Encoding UTF8 -LiteralPath $errorPath) }
        throw "Plan failed with exit code $LASTEXITCODE"
    }
    return Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $job $ResultFile) | ConvertFrom-Json
}

function Invoke-TestPlan($Options) {
    Invoke-TestRequest $Options Plan 'plan.json'
}

function Get-TestMaintenanceOptions {
    Invoke-TestRequest $null MaintenanceOptions 'maintenance-options.json'
}

function Start-TestWorker([string]$Job) {
    $launch = [Diagnostics.ProcessStartInfo]::new($windowsPowerShell)
    $launch.CreateNoWindow = $true
    $launch.UseShellExecute = $false
    foreach ($argument in @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$bootstrap,$adapter,'Run',$Job,$workspace)) {
        $launch.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::Start($launch)
    $workers.Add($process)
    return $process
}

function Read-TestState([string]$Job) {
    $stream = [IO.File]::Open((Join-Path $Job 'state.json'), 'Open', 'Read', 'ReadWrite,Delete')
    $reader = [IO.StreamReader]::new($stream)
    try { return $reader.ReadToEnd() | ConvertFrom-Json }
    finally { $reader.Dispose() }
}

function Wait-TestPrompt([string]$Job, [Diagnostics.Process]$Process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 100
        $state = Read-TestState $Job
    } until ($state.status -eq 'waitingForInput' -or $Process.HasExited -or [DateTime]::UtcNow -gt $deadline)
    if ($state.status -ne 'waitingForInput') {
        $logPath = Join-Path $Job 'log.jsonl'
        if (Test-Path -LiteralPath $logPath) { Get-Content -Encoding UTF8 -LiteralPath $logPath | Write-Host }
        throw "Expected durable confirmation for tool $($state.toolId), got $($state.status)."
    }
    return $state.prompt
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
$oldPath = $env:PATH
$workers = [Collections.Generic.List[Diagnostics.Process]]::new()
try {
    foreach ($folder in @($jobRoot, (Join-Path $testTool '12-build-network-plugins'), (Join-Path $workspace '40_filebase'),
        (Join-Path $workspace 'source-filebase'), (Join-Path $workspace '10_plugin/AstralRecordLobby'),
        (Join-Path $workspace 'fixture-program-data'), (Join-Path $workspace 'dev/plugins'),
        (Join-Path $workspace 'channel/plugins'), (Join-Path $workspace 'alternate/plugins'),
        (Join-Path $workspace '10_plugin/AstralArchitect'),
        (Join-Path $workspace 'fixture-bin'))) {
        $null = New-Item -ItemType Directory -Path $folder -Force
    }
    foreach ($folder in @('01-deploy-debug','09-astralarchitect-build-deploy','16-maintenance','control-gate')) {
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
    $architectDir = Join-Path $workspace '10_plugin/AstralArchitect'
    $architectDist = Join-Path $architectDir 'dist'
    $architectJar = Join-Path $dev 'plugins/AstralArchitect.jar'
    $architectLocal = Join-Path $testTool '09-astralarchitect-build-deploy/astralarchitect-deploy.local.json'
    $fixtureBin = Join-Path $workspace 'fixture-bin'
    $env:PATH = "$fixtureBin;$oldPath"
    Set-Content -LiteralPath (Join-Path $architectDir 'pom.xml') -Value '<project/>'
    @'
@echo off
if not "%*"=="clean package" exit /b 42
if not exist dist mkdir dist
echo test artifact>dist\AstralArchitect-test.jar
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $fixtureBin 'mvn.cmd') -Encoding ascii

    $options09Build = @{toolId='09';architectMode='BuildOnly'}
    $options09Deploy = @{toolId='09';architectMode='Deploy'}
    Write-TestJson @{pluginsDirectory='relative/plugins'} $architectLocal
    $plan09Build = Invoke-TestPlan $options09Build
    Assert-JobTest ($plan09Build.canRun -and !$plan09Build.configurationPath -and $plan09Build.paths.Count -eq 1) '09 build-only must ignore invalid deployment settings.'
    Assert-JobTest (!(Test-Path -LiteralPath $architectDist)) '09 preview must not start the build or create its output directory.'
    $blocked09 = New-TestJob $options09Deploy
    & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $adapter Plan $blocked09 $workspace
    $blocked09Error = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $blocked09 'error.json')
    Assert-JobTest ($LASTEXITCODE -ne 0 -and $blocked09Error.Contains('absolute drive or UNC path')) '09 must reject invalid deployment paths in the preview.'

    $build09Job = New-TestJob $options09Build $plan09Build.fingerprint
    $build09 = Start-TestWorker $build09Job
    Assert-JobTest ($build09.WaitForExit(20000) -and $build09.ExitCode -eq 0) "09 build-only should run the existing build. $(Get-Content -Raw -LiteralPath (Join-Path $build09Job 'log.jsonl') -ErrorAction SilentlyContinue)"
    Assert-JobTest ((Test-Path -LiteralPath (Join-Path $architectDist 'AstralArchitect-test.jar')) -and !(Test-Path -LiteralPath $architectJar)) '09 build-only must produce only a local artifact.'
    Write-TestJson @{pluginsDirectory=(Join-Path $dev 'plugins')} $architectLocal
    $plan09Deploy = Invoke-TestPlan $options09Deploy
    Assert-JobTest ($plan09Deploy.configurationPath -eq $architectLocal -and $plan09Deploy.paths[1].destination -eq $architectJar) '09 deployment preview must show the actual configured local destination.'
    Assert-JobTest ('deploy' -in $plan09Deploy.confirmations.id -and !(Test-Path -LiteralPath $architectJar)) '09 deployment preview must request confirmation without copying anything.'

    Write-TestJson @{pluginsDirectory=(Join-Path $channel 'plugins')} $architectLocal
    $stale09Job = New-TestJob $options09Deploy $plan09Deploy.fingerprint
    $stale09 = Start-TestWorker $stale09Job
    Assert-JobTest ($stale09.WaitForExit(10000) -and $stale09.ExitCode -eq 1) '09 deployment must reject a plan after its destination changed.'
    $stale09Log = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $stale09Job 'log.jsonl')
    Assert-JobTest ($stale09Log.Contains('変わりました') -and !(Test-Path -LiteralPath (Join-Path $channel 'plugins/AstralArchitect.jar'))) 'A stale deployment must not write to the changed destination.'
    Write-TestJson @{pluginsDirectory=(Join-Path $dev 'plugins')} $architectLocal
    $deploy09Job = New-TestJob $options09Deploy $plan09Deploy.fingerprint
    $deploy09 = Start-TestWorker $deploy09Job
    Assert-JobTest ($deploy09.WaitForExit(20000) -and $deploy09.ExitCode -eq 0) "09 build and deploy should complete. $(Get-Content -Raw -LiteralPath (Join-Path $deploy09Job 'log.jsonl') -ErrorAction SilentlyContinue)"
    Assert-JobTest ((Get-Content -Raw -LiteralPath $architectJar).Trim() -eq 'test artifact') '09 must publish AstralArchitect.jar at the confirmed destination.'

    $migration = @{enabled=$true;baseUrl='https://localhost:1';apiKeyEnvironmentVariable='ASTRAL_JOB_TEST_API_KEY';
        migrationKeyEnvironmentVariable='ASTRAL_JOB_TEST_MIGRATION_KEY';serverIds=@('Dev')}
    $deploy = @{
        plugin=@{enabled=$false;deployPath=(Join-Path $dev 'plugins')}
        api=@{enabled=$false};web=@{enabled=$false}
        fileDatabase=@{enabled=$true;sourcePath=(Join-Path $workspace 'source-filebase');deployPath=(Join-Path $dev 'filebase')}
        devWorkflow=@{seedMasterData=$true;migration=$migration}
    }
    Write-TestJson $deploy $config

    $plan03 = Invoke-TestPlan @{toolId='03';recovery='Auto'}
    Assert-JobTest ($plan03.canRun -and $plan03.workspaceMismatch) '03 must disclose the effective source mismatch.'
    Assert-JobTest ($plan03.paths[0].source -eq $deploy.fileDatabase.sourcePath) '03 must show the configured source.'
    Assert-JobTest (($plan03.summary -join ' ').Contains((Join-Path $testTool '03-master-data-reload/runs'))) 'An omitted runRoot must resolve to the 03 output directory.'
    Assert-JobTest ('workspaceMismatch' -in $plan03.confirmations.id) '03 must require a separate mismatch confirmation.'
    $plan01 = Invoke-TestPlan @{toolId='01';pluginTestMode='All';recovery='Auto'}
    Assert-JobTest ($plan01.canRun -and $plan01.paths[0].destination -eq $deploy.fileDatabase.deployPath) '01 plan should use its actual destination.'
    Assert-JobTest (($plan01.summary -join ' ').Contains((Join-Path $testTool '01-deploy-debug/runs'))) '01 and 03 must use separate output directories.'
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
    $networkOutput = Join-Path $testTool '12-build-network-plugins/output'
    New-Item -ItemType Directory -Path $networkOutput | Out-Null
    $lobbyJar = Join-Path $networkOutput 'AstralRecordLobby.jar'
    Set-Content -LiteralPath $lobbyJar -Value 'fixture'
    $maintenance = @{
        schemaVersion=1;workflow=@{seedMasterData=$false};migration=$migration
        servers=@(@{id='Dev';enabled=$true;rootPath=$dev},@{id='Channel';enabled=$true;rootPath=$channel},
            @{id='Alternate';enabled=$true;rootPath=(Join-Path $workspace 'alternate')})
        distributions=@(@{enabled=$true;kind='Jar';source='Dev';relativePath='plugins/Fixture.jar';targets=@('Channel','Alternate')})
        networkPlugins=@{destinations=@(@{artifact='AstralRecordLobby.jar';deployDirectory=(Join-Path $channel 'plugins')})}
    }
    $maintenancePath = Join-Path $testTool '16-maintenance/maintenance.local.json'
    Write-TestJson $maintenance $maintenancePath
    $plan16 = Invoke-TestPlan @{toolId='16';worldCopy='Skip';networkPlugins='Include';recovery='Auto'}
    Assert-JobTest ($plan16.canRun -and $plan16.paths[0].destination -eq (Join-Path $channel 'plugins/Fixture.jar')) '16 preview must use existing selection and distribution paths.'
    Assert-JobTest ($lobbyJar -in $plan16.paths.source) '16 must use the default network output when sourceDirectory is omitted.'
    Assert-JobTest (($plan16.summary -join ' ').Contains((Join-Path $testTool '16-maintenance/runs'))) 'An omitted runRoot must resolve to the 16 output directory.'
    Assert-JobTest (!(Test-Path -LiteralPath (Join-Path $channel 'plugins/Fixture.jar'))) '16 plan must not copy the artifact.'

    $maintenance.distributions += @{enabled=$true;kind='Jar';source='Dev';relativePath='plugins/Missing.jar';targets=@('Channel')}
    Write-TestJson $maintenance $maintenancePath
    $maintenanceOptions = Get-TestMaintenanceOptions
    Assert-JobTest ($maintenanceOptions.options.Count -eq 4 -and $maintenanceOptions.options.id -ccontains 'n1') 'The catalog must list each target and network JAR without checking that all sources exist.'
    Assert-JobTest ($maintenanceOptions.configurationDigest -ceq (Get-FileHash -LiteralPath $maintenancePath).Hash) 'The catalog digest must identify its exact configuration.'
    $options16 = @{toolId='16';distributionSelection=@('d1-t2');distributionConfigurationDigest=$maintenanceOptions.configurationDigest;recovery='Auto'}
    $detailedPlan = Invoke-TestPlan $options16
    Assert-JobTest ($detailedPlan.canRun -and $detailedPlan.paths.Count -eq 1 -and
        $detailedPlan.paths[0].destination -eq (Join-Path $workspace 'alternate/plugins/Fixture.jar')) 'Detailed planning must include exactly the selected server and tolerate unselected missing files.'
    try {
        $staleOptions = $options16.Clone()
        $staleOptions.distributionConfigurationDigest = '0' * 64
        $null = Invoke-TestPlan $staleOptions
        throw 'Stale catalog accepted.'
    } catch {
        Assert-JobTest ($_.Exception.Message.Contains('候補取得後に配布設定が変わりました')) 'The worker must reject stale catalog IDs before planning.'
    }

    $runName = 'run-20261123T000000Z-0123456789abcdef0123456789abcdef'
    $runRoot = Join-Path $testTool '16-maintenance/runs'
    $pendingRun = Join-Path $runRoot $runName
    $null = New-Item -ItemType Directory -Path $pendingRun -Force
    Write-TestJson @{status='WaitingForStartup';relativeRunDirectory=$runName} (Join-Path $runRoot 'active-run.json')
    Write-TestJson @{schemaVersion=2;worldCopy='Skip';networkPlugins='Skip';configurationDigest=$maintenanceOptions.configurationDigest;
        distributionIds=@('d1-t2')} (Join-Path $pendingRun 'deployment-selection.json')
    $resumeOptions = Get-TestMaintenanceOptions
    Assert-JobTest ($resumeOptions.savedSelection.Count -eq 1 -and $resumeOptions.savedSelection[0] -ceq 'd1-t2') 'The catalog must expose the saved selection for an unfinished run.'
    $differentSelection = $options16.Clone()
    $differentSelection.distributionSelection = @('n1')
    $blocked16 = Invoke-TestPlan $differentSelection
    Assert-JobTest (!$blocked16.canRun -and ($blocked16.warnings -join ' ').Contains('前回の選択を適用')) 'The editor must not start a plan with a different selection from the unfinished run.'
    Assert-JobTest ((Invoke-TestPlan $options16).canRun) 'The editor must allow the saved targets even when the original CLI run skipped whole groups.'

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
    $worker = Start-TestWorker $job
    $prompt = Wait-TestPrompt $job $worker
    Assert-JobTest ($prompt.choices.value -contains '1') 'Only predefined values can be confirmed.'

    $fakeMaintenance = Join-Path $testTool '16-maintenance/maintenance.ps1'
    @'
param($Phase, $ConfigPath, $DistributionSelection, $DistributionConfigurationDigest, [switch]$ServersStopped, [switch]$AdmissionClosed)
if ($DistributionSelection -cne 'd1-t2' -or $DistributionConfigurationDigest -cne (Get-FileHash -LiteralPath $ConfigPath).Hash) { exit 9 }
if ($Phase -eq 'Plan') { exit 0 }
Write-Host 'Fixture maintenance'
$answer = Read-Host 'Confirm fixture deployment. Type DEPLOY to continue'
if ($answer -cne 'DEPLOY') { exit 2 }
exit 0
'@ | Set-Content -LiteralPath $fakeMaintenance -Encoding utf8
    $fakePlan16 = Invoke-TestPlan $options16
    $job16 = New-TestJob $options16 $fakePlan16.fingerprint
    $worker16 = Start-TestWorker $job16
    $prompt16 = Wait-TestPrompt $job16 $worker16
    Assert-JobTest ($prompt16.id -ne $prompt.id) 'Parallel jobs have separate prompts.'

    $duplicateJob = New-TestJob $options12 $plan12.fingerprint
    $duplicate = Start-TestWorker $duplicateJob
    Assert-JobTest ($duplicate.WaitForExit(10000) -and $duplicate.ExitCode -eq 1) 'A second worker for the same tool must be rejected.'
    $duplicateLog = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $duplicateJob 'log.jsonl')
    Assert-JobTest ($duplicateLog.Contains('12 は実行中') -and !$duplicateLog.Contains('開始 Lobby')) 'The duplicate guard must run before the build script.'

    Write-TestJson @{promptId=$prompt16.id;value='DEPLOY'} (Join-Path $job16 "input-$($prompt16.id).json")
    Assert-JobTest ($worker16.WaitForExit(10000) -and $worker16.ExitCode -eq 0) 'Maintenance can finish while the build waits.'
    Assert-JobTest ((Read-TestState $job).prompt.id -eq $prompt.id) 'Answering a prompt must not affect the other run.'

    Write-TestJson @{promptId=$prompt.id;value='1'} (Join-Path $job "input-$($prompt.id).json")
    Assert-JobTest ($worker.WaitForExit(10000) -and $worker.ExitCode -eq 7) 'The bootstrap must return the runner exit code.'
    $state = Read-TestState $job
    Assert-JobTest ($state.status -eq 'failed' -and $state.exitCode -eq 7 -and !$state.prompt) 'The durable status must capture failure and clear its prompt.'
    $log = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $job 'log.jsonl')
    Assert-JobTest ($log.Contains('開始 Lobby True') -and $log.Contains('スルーパス') -and !$log.Contains($env:ASTRAL_JOB_TEST_API_KEY)) 'Logs should contain UTF-8 output and redact known secrets.'

    Add-Content -LiteralPath $fakeBuild -Value '# changed after plan'
    $staleJob = New-TestJob $options12 $plan12.fingerprint
    & $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $bootstrap $adapter Run $staleJob $workspace
    $staleLog = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $staleJob 'log.jsonl')
    Assert-JobTest ($LASTEXITCODE -ne 0 -and $staleLog.Contains('変わりました') -and !$staleLog.Contains('開始 Lobby')) 'Run must refuse a stale plan before it executes.'
} finally {
    foreach ($worker in $workers) {
        if (!$worker.HasExited) { $worker.Kill($true); $worker.WaitForExit() }
        $worker.Dispose()
    }
    $env:ProgramData = $oldProgramData
    $env:ASTRAL_JOB_TEST_API_KEY = $oldKey
    $env:PATH = $oldPath
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
    if ([IO.Path]::GetFullPath($workspace).StartsWith($temp + 'Astral tool ジョブ ', [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $workspace)) {
        Remove-Item -LiteralPath $workspace -Recurse -Force
    }
}
Write-Host 'Desktop tool plans, concurrent workers, duplicate prevention, isolated prompts and logs passed.'
