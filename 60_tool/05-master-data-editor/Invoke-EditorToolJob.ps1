#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Plan','Run','MaintenanceOptions')][string]$Mode,
    [Parameter(Mandatory)][string]$JobDirectory,
    [Parameter(Mandatory)][string]$WorkspaceRoot
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = [IO.Path]::GetFullPath($WorkspaceRoot)
$toolRoot = Join-Path $workspace '60_tool'
$editorJobContext = @{
    Mode = $Mode
    Directory = $null
    StatePath = $null
    Encoding = [Text.UTF8Encoding]::new($false)
    Secrets = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    Run = $null
    Log = $null
}
$toolLock = $null

function Write-JobJson($Value, [string]$Path) {
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $bytes = $editorJobContext.Encoding.GetBytes(($Value | ConvertTo-Json -Depth 20 -Compress))
        $stream = [IO.File]::Open($temporary, 'CreateNew', 'Write', 'None')
        try {
            $stream.Write($bytes, 0, $bytes.Length)
            $stream.Flush($true)
        } finally { $stream.Dispose() }
        [IO.File]::Move($temporary, $Path, $true)
    } finally { [IO.File]::Delete($temporary) }
}

function Read-JobJson([string]$Path) {
    # JSON parser errors can contain part of a secret-bearing setting.
    try { return Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json -AsHashtable }
    catch { throw "JSON 設定を読み取れません: $Path" }
}

function Add-JobSecret($Value) {
    if ($Value -is [string] -and ![string]::IsNullOrWhiteSpace($Value)) {
        foreach ($part in ($Value -split '\r?\n')) {
            if (![string]::IsNullOrWhiteSpace($part)) { $null = $editorJobContext.Secrets.Add($part.Trim()) }
        }
    }
}

function Add-JobConfigSecrets($Value, [bool]$Sensitive = $false) {
    if ($Value -is [Collections.IDictionary]) {
        foreach ($name in $Value.Keys) {
            $sensitiveName = $name -match '(?i)(password|passwd|secret|token|credential|connectionstring|api.?key|migration.?key|^key$)'
            $reference = $name -match '(?i)(path|file|filename|variable|environment|name)$'
            Add-JobConfigSecrets $Value[$name] ($Sensitive -or ($sensitiveName -and !$reference))
        }
    } elseif ($Value -is [array]) {
        foreach ($part in $Value) { Add-JobConfigSecrets $part $Sensitive }
    } elseif ($Sensitive) {
        Add-JobSecret $Value
    }
}

function Protect-JobText([string]$Text) {
    foreach ($secret in $editorJobContext.Secrets) {
        $Text = $Text.Replace($secret, '[伏字]', [StringComparison]::Ordinal)
        $encoded = [Uri]::EscapeDataString($secret)
        if ($encoded -cne $secret) { $Text = $Text.Replace($encoded, '[伏字]', [StringComparison]::OrdinalIgnoreCase) }
    }
    $Text = $Text -replace '\x1B\[[0-?]*[ -/]*[@-~]', ''
    $Text = $Text -replace '[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F]', ''
    $Text = $Text -replace '(?i)(https?://)[^/\s@]+@', '$1[伏字]@'
    $Text = $Text -replace '(?i)\b(authorization|x-api-key|password|passwd|access.token|client.secret|connectionstrings?|migrationkey)(["'' ]*[:=]).*', '$1$2 [伏字]'
    return $Text
}

function Add-JobLog([string]$Text, [string]$Stream = 'output') {
    if (!$editorJobContext.Log) { return }
    foreach ($line in ((Protect-JobText $Text) -split '\r?\n')) {
        if ($line.Length -gt 12000) { $line = $line.Substring(0, 12000) + ' …' }
        $entry = @{sequence=$editorJobContext.Log.Position + 1; stream=$Stream; text=$line}
        $bytes = $editorJobContext.Encoding.GetBytes(($entry | ConvertTo-Json -Compress) + [char]10)
        $editorJobContext.Log.Write($bytes, 0, $bytes.Length)
        $editorJobContext.Log.Flush()
    }
}

function Add-JobOutput($Item) {
    if ($Item -is [Management.Automation.ErrorRecord]) {
        Add-JobLog $Item.Exception.Message 'error'
    } elseif ($null -ne $Item) {
        Add-JobLog ([string]$Item)
    }
}

function Get-JobMaintenanceConfiguration([string]$ConfigPath) {
    Assert-MaintenanceNoLinks $ConfigPath
    if (!(Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        throw '60_tool/16-maintenance/maintenance.example.json を maintenance.local.json にコピーし、実行対象を設定してください。'
    }
    try { $configuration = Read-MaintenanceConfiguration -ConfigPath $ConfigPath }
    catch { throw 'メンテナンス配布の設定を読み取れません。maintenance.local.json を確認してください。' }
    Add-JobConfigSecrets $configuration.config
    return $configuration
}

function Get-JobSavedMaintenanceSelection([string]$RunDirectory, $Configuration) {
    try {
        $saved = Get-MaintenanceDeploymentSelectionRecord -RunDirectory $RunDirectory -ConfigurationDigest $Configuration.digest
        if ($saved -and $null -ne $saved.distributionIds) {
            $null = Get-MaintenanceSelectedConfig $Configuration.config $saved.worldCopy $saved.networkPlugins `
                -DistributionIds $saved.distributionIds
        }
        return $saved
    } catch {
        throw '前回の配布対象を確認できません。配布設定と未完了の実行記録を確認してください。'
    }
}

function Get-JobMaintenanceOptions {
    . (Join-Path $toolRoot '16-maintenance/Distribution.ps1')
    . (Join-Path $toolRoot '16-maintenance/DeploymentSelection.ps1')
    . (Join-Path $toolRoot '16-maintenance/WorkflowRunRoot.ps1')
    $configPath = Join-Path $toolRoot '16-maintenance/maintenance.local.json'
    $configuration = Get-JobMaintenanceConfiguration $configPath
    $options = @(Get-MaintenanceDistributionOptions -Config $configuration.config | ForEach-Object {
        @{
            id=$_.id; kind=$_.kind; label=(Protect-JobText $_.label)
            source=(Protect-JobText $_.source); destination=(Protect-JobText $_.destination)
        }
    })
    $result = @{configurationDigest=$configuration.digest;options=$options}
    $runRoot = Resolve-MaintenanceWorkflowRunRoot -ToolRoot $toolRoot -ConfigPath $configPath `
        -ConfiguredRunRoot $configuration.config['workflow']?['runRoot']
    $pending = Get-PendingUpdateRun -RunRoot $runRoot
    if ($pending) {
        $saved = Get-JobSavedMaintenanceSelection $pending.Directory $configuration
        if ($saved -and $null -ne $saved.distributionIds) { $result.savedSelection = @($saved.distributionIds) }
    }
    return $result
}

function Get-RequiredJobPlan($Options) {
    $scriptFiles = @($PSCommandPath, (Join-Path $toolRoot 'invoke-powershell7.ps1'))
    $settingsFiles = @((Join-Path $toolRoot 'powershell7.local.json'), (Join-Path $toolRoot 'build-tools.local.json'))
    $paths = [Collections.Generic.List[object]]::new()
    $confirmations = [Collections.Generic.List[object]]::new()
    $summary = [Collections.Generic.List[string]]::new()
    $warnings = [Collections.Generic.List[string]]::new()
    $configPath = $null
    $filebaseSource = $null
    $mismatch = $false
    $canRun = $true
    $invoke = @{}
    $secureJson = @()
    $tokenPath = $null
    $maintenanceDigest = $null

    if ($Options['toolId'] -ceq '12') {
        $entry = Join-Path $toolRoot '12-build-network-plugins/build-network-plugins.ps1'
        $output = Join-Path $toolRoot '12-build-network-plugins/output'
        if (Test-Path -LiteralPath $output -PathType Leaf) { throw "成果物ディレクトリがファイルです: $output" }
        $targets = [ordered]@{Lobby='AstralRecordLobby';Proxy='AstralRecordProxy';Extension='AstralRecordGeyserExtension'}
        foreach ($target in $targets.Keys) {
            if ($Options['networkTarget'] -cnotin @('All',$target)) { continue }
            $artifact = $targets[$target]
            $pom = Join-Path $workspace "10_plugin/$artifact/pom.xml"
            if (!(Test-Path -LiteralPath $pom -PathType Leaf)) { throw "Maven プロジェクトが見つかりません: $pom" }
            $settingsFiles += $pom
            $paths.Add(@{label=$artifact;source=$pom;destination=(Join-Path $output "$artifact.jar")})
        }
        if (!$paths.Count) { throw 'ビルド対象を選択してください。' }
        $invoke.Target = $Options['networkTarget']
        if ($Options['skipTests']) { $invoke.SkipTests = $true }
        $summary.Add('選択した Maven プロジェクトをビルドして JAR を生成します。停止が必要なサーバーはありません。')
        $summary.Add($(if ($Options['skipTests']) { 'Maven テストを省略します。' } else { 'Maven テストを実行します。' }))
        $confirmations.Add(@{id='build';label='対象プラグイン、テスト条件、成果物の出力先を確認した。'})
    } elseif ($Options['toolId'] -ceq '09') {
        $entry = Join-Path $toolRoot '09-astralarchitect-build-deploy/astralarchitect-deploy.ps1'
        if (!(Test-Path -LiteralPath $entry -PathType Leaf)) { throw "既存ツールの起動ファイルがありません: $entry" }
        if ($Options['architectMode'] -cnotin @('BuildOnly', 'Deploy')) { throw 'AstralArchitect の実行方法を選択してください。' }
        if ($Options['architectMode'] -ceq 'BuildOnly') { $invoke.BuildOnly = $true }
        $architectPlan = & $entry @invoke -Plan
        $configPath = $architectPlan.ConfigPath
        $settingsFiles += Join-Path $architectPlan.ProjectDirectory 'pom.xml'
        $paths.Add(@{label='JAR ビルド';source=$architectPlan.ProjectDirectory;destination=$architectPlan.ArtifactPattern})
        $summary.Add('Maven で AstralArchitect のテストとビルドを実行します。')
        if ($architectPlan.DestinationJar) {
            $settingsFiles += $configPath
            $secureJson += $configPath
            $invoke.PluginsDirectory = $architectPlan.PluginsDirectory
            $paths.Add(@{label='JAR 配置';source=$architectPlan.ArtifactPattern;destination=$architectPlan.DestinationJar})
            $summary.Add('ビルド成功後に JAR を配置します。サーバーは手動で再起動してください。')
            $confirmations.Add(@{id='deploy';label='AstralArchitect.jar の配置先を確認しました。'})
        }
    } else {
        $distribution = Join-Path $toolRoot '16-maintenance/Distribution.ps1'
        $runtime = Join-Path $toolRoot '16-maintenance/SkillTreeMigration.ps1'
        $selection = Join-Path $toolRoot '16-maintenance/DeploymentSelection.ps1'
        $workflow = Join-Path $toolRoot '16-maintenance/UpdateWorkflow.ps1'
        $workflowRunRoot = Join-Path $toolRoot '16-maintenance/WorkflowRunRoot.ps1'
        $devRunRoot = Join-Path $toolRoot '01-deploy-debug/DevRunRoot.ps1'
        $gateScript = Join-Path $toolRoot 'control-gate/Control.Gate.psm1'
        . $distribution
        . $workflowRunRoot
        . $devRunRoot
        . $runtime
        $gateModule = Import-Module $gateScript -Force -PassThru
        $scriptFiles += @($distribution,$runtime,$selection,$workflow,$workflowRunRoot,$devRunRoot,$gateScript,
            (Join-Path $toolRoot '16-maintenance/Diagnostics.ps1'),
            (Join-Path $toolRoot '16-maintenance/WorkflowRecovery.ps1'),
            (Join-Path $toolRoot 'control-gate/Control.DevLifecycle.ps1'))
        $settingsFiles += Join-Path $env:ProgramData 'AstralRecordControl/managed-operations.enabled'
        $automatic = $false

        if ($Options['toolId'] -ceq '16') {
            $entry = Join-Path $toolRoot '16-maintenance/maintenance.ps1'
            $configPath = Join-Path $toolRoot '16-maintenance/maintenance.local.json'
            . $selection
            $configuration = Get-JobMaintenanceConfiguration $configPath
            $config = $configuration.config
            $maintenanceDigest = $configuration.digest
            if (!$config['workflow'] -or $config['migration']['enabled'] -ne $true) {
                throw '通常のメンテナンスを実行するには workflow とパッチ公開を設定してください。'
            }
            $modeName = Get-ControlGateMode $config
            $migration = ConvertTo-SkillTreeRuntimeConfig $config.migration
            $distributionIds = $Options['distributionSelection']
            if ($null -ne $distributionIds) {
                if ($Options['distributionConfigurationDigest'] -cne $maintenanceDigest) {
                    throw '候補取得後に配布設定が変わりました。配布対象を再取得してください。'
                }
                $selectedConfig = Get-MaintenanceSelectedConfig $config 'Include' 'Include' -DistributionIds $distributionIds
                $invoke.DistributionSelection = $distributionIds -join ','
                $invoke.DistributionConfigurationDigest = $maintenanceDigest
            } else {
                $selectedConfig = Get-MaintenanceSelectedConfig $config $Options['worldCopy'] $Options['networkPlugins']
                $invoke.WorldCopy = $Options['worldCopy']
                $invoke.NetworkPlugins = $Options['networkPlugins']
            }
            $distributionPaths = @(Get-MaintenancePlan $selectedConfig 6>$null)
            if (!$distributionPaths.Count) {
                $canRun = $false
                $warnings.Add('有効な配布対象がありません。')
            }
            foreach ($op in $distributionPaths) {
                $paths.Add(@{label=$op.kind;source=$op.source;destination=$op.destination})
            }
            $serverList = @($config.servers | Where-Object enabled | ForEach-Object { [string]$_.id }) + @($migration.ServerIds)
            $gatePaths = @()
            if ($config['networkPlugins']) {
                foreach ($network in $config.networkPlugins.destinations) {
                    if (![string]$network.deployDirectory) { continue }
                    $networkPath = Get-MaintenanceAbsolutePath $network.deployDirectory
                    $gatePaths += $networkPath
                    if ($modeName -ceq 'Managed') {
                        $bindings = @($config.controlGate.bindings.paths | Where-Object { [string]$_.path -ieq $networkPath })
                        if ($bindings.Count -ne 1 -or ![string]$bindings[0].runtimeServerId) {
                            throw '配布先と Control のネットワークサーバーの対応を確認してください。'
                        }
                        $serverList += [string]$bindings[0].runtimeServerId
                    } else {
                        $serverList += $networkPath
                    }
                }
                $gatePaths += Get-MaintenanceAbsolutePath $(if ($config.networkPlugins['sourceDirectory']) {
                    $config.networkPlugins['sourceDirectory']
                } else { Join-Path $toolRoot '12-build-network-plugins/output' })
            }
            $runRoot = $config.workflow['runRoot']
            $summary.Add('配布から起動確認、パッチ公開までを既存のメンテナンス手順で実行します。サーバーの停止と起動は運用者が行います。')
            $summary.Add('配布済みの処理は既存の記録を使って再開します。再開時は前回と同じ配布対象を選択してください。')
        } elseif ($Options['toolId'] -cin @('01','03')) {
            $entry = Join-Path $toolRoot '01-deploy-debug/dev-update.ps1'
            $baseConfig = Join-Path $toolRoot '01-deploy-debug/deploy-debug.config.json'
            $localConfig = Join-Path $toolRoot '01-deploy-debug/deploy-debug.local.json'
            $configPath = if (Test-Path -LiteralPath $localConfig) { $localConfig } else { $baseConfig }
            $settingsFiles += @($localConfig, $baseConfig)
            $testScript = Join-Path $toolRoot '01-deploy-debug/PluginTestSelection.ps1'
            $scriptFiles += @($testScript,
                (Join-Path $toolRoot '01-deploy-debug/deploy-debug.ps1'),
                (Join-Path $toolRoot '01-deploy-debug/normalize-source-encoding.ps1'))
            . $testScript
            $config = Read-JobJson $configPath
            Add-JobConfigSecrets $config
            $modeName = Get-ControlGateMode $config
            if (!$config['devWorkflow']) { throw 'Dev 更新の設定に devWorkflow がありません。' }
            $migration = ConvertTo-SkillTreeRuntimeConfig $config.devWorkflow.migration
            $masterOnly = $Options['toolId'] -ceq '03'
            $running = $Options['serverAlreadyRunning'] -eq $true
            $gateOptions = @{Configuration=$config;RuntimeServerIds=$migration.ServerIds;MasterDataOnly=$masterOnly;ServerAlreadyRunning=$running}
            $serverList = @(Get-DeploymentGateRuntimeServerIds @gateOptions)
            $automatic = [bool](Get-ControlDevLifecycleConfiguration -Configuration $config -RuntimeServerIds $migration.ServerIds -ServerAlreadyRunning:$running)
            if ($masterOnly) { $invoke.MasterDataOnly = $true }
            if ($running) { $invoke.ServerAlreadyRunning = $true }
            if (!$masterOnly -and $Options['pluginTestMode'] -and ($config.plugin.enabled -or $Options['pluginTestMode'] -cne 'All')) {
                $invoke.TestMode = $Options['pluginTestMode']
                if ($Options['pluginTests']) { $invoke.Tests = $Options['pluginTests'] }
            }
            $testOptions = @{TestMode=$invoke['TestMode'];Tests=$invoke['Tests'];TestsSpecified=$invoke.ContainsKey('Tests');NoPlugin=($masterOnly -or !$config.plugin.enabled)}
            $pluginTests = Resolve-PluginTestSelection @testOptions
            if ($config.fileDatabase.enabled) {
                $filebaseSource = Get-MaintenanceAbsolutePath $config.fileDatabase.sourcePath
                if (!(Test-Path -LiteralPath $filebaseSource -PathType Container)) {
                    $canRun = $false
                    $warnings.Add("Filebase の同期元が見つかりません: $filebaseSource")
                }
                $target = Get-MaintenanceAbsolutePath $config.fileDatabase.deployPath
                $paths.Add(@{label='Filebase 同期';source=$filebaseSource;destination=$target})
                $editorSource = Get-MaintenanceAbsolutePath (Join-Path $workspace '40_filebase')
                $mismatch = $filebaseSource -ine $editorSource
                if ($mismatch) {
                    $warnings.Add("エディタの保存先 $editorSource と、配布する Filebase の同期元 $filebaseSource が異なります。現在の編集は、このままでは配布に含まれません。")
                    $confirmations.Add(@{id='workspaceMismatch';label='エディタの保存先と Filebase の同期元が異なることを確認し、表示されている同期元で実行する。'})
                }
            }
            if (!$masterOnly) {
                foreach ($name in @('plugin','api','web')) {
                    $component = $config[$name]
                    if (!$component.enabled) { continue }
                    $project = Get-MaintenanceAbsolutePath $component.projectPath
                    $projectFile = if ($name -ceq 'plugin') { Join-Path $project 'pom.xml' } else { $project }
                    if (!(Test-Path -LiteralPath $projectFile -PathType Leaf)) {
                        $canRun = $false
                        $warnings.Add("ビルド元のプロジェクトが見つかりません: $projectFile")
                    }
                    $settingsFiles += $projectFile
                    $output = Get-MaintenanceAbsolutePath $component.buildOutputPath
                    $destination = Get-MaintenanceAbsolutePath $component.deployPath
                    if ($name -ceq 'plugin') {
                        $relativeOutput = [IO.Path]::GetRelativePath($project, $output)
                        if ($relativeOutput -eq '.' -or $relativeOutput -match '^\.\.(?:[\\/]|$)' -or [IO.Path]::IsPathRooted($relativeOutput)) {
                            $canRun = $false
                            $warnings.Add('Plugin のビルド出力先をプロジェクト配下に設定してください。')
                        } else {
                            $buildDirectory = Join-Path ([IO.Path]::GetTempPath()) 'ar-plugin-build-<実行ID>'
                            $output = Join-Path $buildDirectory $relativeOutput
                        }
                        if (!(Test-Path -LiteralPath (Join-Path $project 'src') -PathType Container)) {
                            $canRun = $false
                            $warnings.Add("Plugin の src ディレクトリが見つかりません: $project")
                        }
                        $fileName = if ($component['deployFileName']) { $component.deployFileName } else { 'AstralRecord.jar' }
                        $destination = Join-Path $destination $fileName
                        $summary.Add("Plugin テスト: $($pluginTests.Mode)$(if ($pluginTests.Tests) { ' / ' + $pluginTests.Tests })")
                    }
                    $paths.Add(@{label="$name ビルド";source=$project;destination=$output})
                    $source = if ($name -ceq 'plugin' -and $component['artifactPattern']) {
                        Join-Path $output $component.artifactPattern
                    } else { $output }
                    $paths.Add(@{label="$name 配置";source=$source;destination=$destination})
                    if ($name -cin @('api','web')) { $secureJson += Join-Path $component.deployPath 'appsettings.json' }
                }
                if ($config['iis'] -and $config.iis.enabled -and ($config.api.enabled -or $config.web.enabled)) {
                    $summary.Add("IIS の停止・再起動を行うホスト: $($config.iis.host)")
                    $confirmations.Add(@{id='iis';label='表示されているホストの IIS 停止・再起動を確認した。'})
                }
                if ($config.api.enabled) {
                    if ($config.api['tokenFileName']) { $tokenPath = Join-Path $config.api.deployPath $config.api.tokenFileName }
                    foreach ($name in @('databaseMigrations','historyDatabaseMigrations','managementDatabaseMigrations')) {
                        if (!$config[$name] -or !$config[$name]['enabled']) { continue }
                        $configFile = Get-MaintenanceAbsolutePath $config[$name].configPath
                        $settingsFiles += $configFile
                        $secureJson += $configFile
                        $summary.Add("$name : $configFile")
                    }
                }
            }
            $gatePaths = @($paths | Where-Object { $_.label -in @('Filebase 同期','plugin 配置','api 配置','web 配置') } |
                ForEach-Object {
                    if ($_.label -ceq 'plugin 配置') { [IO.Path]::GetDirectoryName($_.destination) } else { $_.destination }
                })
            if ($filebaseSource) { $gatePaths += $filebaseSource }
            $runRoot = $config.devWorkflow['runRoot']
            $summary.Add($(if ($running) { '稼働中の Dev のままマスタを更新し、再読込とパッチ公開を確認します。' }
                elseif ($automatic) { 'Control が Dev の正常停止と起動を実行します。' }
                else { 'サーバーの停止と起動は運用者が行います。起動後にパッチを公開します。' }))
        } else { throw '対応する運用ツールを選択してください。' }

        if ($migration.ApiSettingsPath) { $secureJson += $migration.ApiSettingsPath }
        foreach ($name in @($migration.ApiKeyEnvironmentVariable, $migration.MigrationKeyEnvironmentVariable)) {
            if ($name) { Add-JobSecret ([Environment]::GetEnvironmentVariable($name)) }
        }
        $settingsFiles += $configPath
        $invoke.ConfigPath = $configPath
        if ($Options['recovery'] -cne 'Auto') { $invoke.Recovery = $Options['recovery'] }
        if ($Options['recovery'] -ceq 'Restart') { $invoke.RecoveryChecked = $true }

        $requiredState = if ($Options['serverAlreadyRunning']) { 'Running' } else { 'Stopped' }
        $runtimeNames = (@($serverList | Sort-Object -Unique) -join ', ')
        $summary.Add("対象範囲: $runtimeNames / $modeName")
        $summary.Add("公開先 API: $($migration.BaseUrl)")
        $runRoot = if ($Options['toolId'] -ceq '16') {
            Resolve-MaintenanceWorkflowRunRoot -ToolRoot $toolRoot -ConfigPath $configPath -ConfiguredRunRoot $runRoot
        } else {
            Resolve-DevRunRoot -ToolRoot $toolRoot -ConfigPath $configPath -ConfiguredRunRoot $runRoot `
                -Mode $(if ($Options['toolId'] -ceq '03') { 'MasterDataOnly' } else { 'Full' })
        }
        $summary.Add("既存フローの実行記録: $runRoot")
        $activePath = Join-Path $runRoot 'active-run.json'
        $settingsFiles += $activePath
        $active = if (Test-Path -LiteralPath $activePath -PathType Leaf) { Read-JobJson $activePath } else { $null }
        $runName = if ($active -and $active['status'] -cne 'Completed') { [string]$active['relativeRunDirectory'] } else { '' }
        if ($runName -match '^[a-zA-Z0-9_-]+$') {
            $summary.Add("前回の未完了記録: $runName。再開できる段階は既存フローが判定します。")
            if ($Options['toolId'] -ceq '16') {
                $previousRun = Join-Path $runRoot $runName
                Assert-MaintenanceNoLinks $previousRun
                $previous = Join-Path $previousRun 'deployment-selection.json'
                $settingsFiles += $previous
                $saved = Get-JobSavedMaintenanceSelection $previousRun $configuration
                if ($saved -and $null -ne $saved['distributionIds']) {
                    if ($null -eq $distributionIds -or
                        (@($distributionIds | Sort-Object) -join ',') -cne (@($saved['distributionIds'] | Sort-Object) -join ',')) {
                        $canRun = $false
                        $warnings.Add('配布対象の詳細設定を開き、前回の選択を適用してください。')
                    }
                } elseif ($saved) {
                    if ($Options['worldCopy'] -cne $saved['worldCopy'] -or $Options['networkPlugins'] -cne $saved['networkPlugins']) {
                        $canRun = $false
                        $worldMode = if ($saved['worldCopy'] -ceq 'Include') { '含める' } else { 'スキップ' }
                        $networkMode = if ($saved['networkPlugins'] -ceq 'Include') { '含める' } else { 'スキップ' }
                        $warnings.Add("前回と同じ簡易設定を指定してください。ワールド（$worldMode）、ネットワークプラグイン（$networkMode）。")
                    }
                } elseif (($Options['worldCopy'] -cne 'Include' -or $Options['networkPlugins'] -cne 'Include') -and (
                    (Test-Path -LiteralPath (Join-Path $previousRun 'workflow-state.json') -PathType Leaf) -or
                    (Test-Path -LiteralPath (Join-Path $previousRun 'deployment.json') -PathType Leaf) -or
                    (Test-Path -LiteralPath (Join-Path $previousRun 'config-digest.json') -PathType Leaf))) {
                    $canRun = $false
                    $warnings.Add('前回の実行記録には詳細選択がありません。ワールドとネットワークプラグインを両方含めてください。')
                }
            }
        } elseif ($Options['recovery'] -cne 'Auto') {
            $canRun = $false
            $warnings.Add('未完了の実行記録が見つかりません。既存フローに従う場合は自動判定を選択してください。')
        }

        if ($modeName -ceq 'Managed') {
            $null = & $gateModule {
                param($Gate, $Servers, $Paths, $State)
                $null = Assert-ControlGateCli $Gate
                Resolve-ControlGateResources $Gate $Servers $Paths $State
            } $config.controlGate $serverList $gatePaths $requiredState
            $settingsFiles += $config.controlGate.cliPath
        }
        $confirmations.Add(@{id='admission';label='入場を制限し、対象サーバーからプレイヤーが退場したことを確認した。'})
        $confirmations.Add(@{id='writers';label='対象ファイルやマスタを書き込む自動処理を停止し、他の更新処理が実行されていないことを確認した。'})
        if ($Options['serverAlreadyRunning']) {
            $invoke.AutomaticWritersStopped = $true
            $confirmations.Add(@{id='running';label='Dev が稼働中で、マスタの再読込を確認できる状態になっている。'})
        } elseif ($automatic -and $Options['recovery'] -ceq 'Auto') {
            $manual = @($serverList | Where-Object { $_ -cne 'Dev' })
            if ($manual.Count) { $confirmations.Add(@{id='serversStopped';label="自動停止の対象外のサーバーを停止した: $($manual -join ', ')"}) }
            $confirmations.Add(@{id='devLifecycle';label='実行フローに従って Control が Dev を停止・起動することを確認した。'})
        } else {
            $invoke.ServersStopped = $true
            $confirmations.Add(@{id='serversStopped';label="必要な配布元と配布先のサーバーを停止した: $runtimeNames"})
        }
        $invoke.AdmissionClosed = $true
        if ($Options['recovery'] -ceq 'Restart') {
            $confirmations.Add(@{id='recovery';label='前回のプロセス終了、DB 更新と配置先の状態、必要な復旧を確認した。既存の復旧判定に従い記録を保全して再実行する。'})
        } elseif ($Options['recovery'] -ceq 'Restore') {
            $confirmations.Add(@{id='recovery';label='復旧対象の配布記録を確認した。既存の復旧判定に従いバックアップから復元してから再実行する。'})
        }
    }

    if (!(Test-Path -LiteralPath $entry -PathType Leaf)) { throw "既存ツールの起動ファイルがありません: $entry" }
    $scriptFiles += $entry
    $settingsFiles += $secureJson | Where-Object { $_ -and [IO.Path]::IsPathFullyQualified($_) }
    $fingerprintFiles = @($scriptFiles + $settingsFiles | Sort-Object -Unique)
    $digest = foreach ($path in $fingerprintFiles) {
        if ($maintenanceDigest -and $path -ieq $configPath) { "$path|$maintenanceDigest" }
        elseif (Test-Path -LiteralPath $path -PathType Leaf) { "$path|$((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash)" }
        else { "$path|missing" }
    }
    # Convert each row with stable property order regardless of PowerShell hashtable order.
    $descriptor = [ordered]@{
        options = $Options
        paths = @($paths | ForEach-Object { [ordered]@{label=$_.label;source=$_.source;destination=$_.destination} })
        confirmations = @($confirmations | ForEach-Object { [ordered]@{id=$_.id;label=$_.label} })
        summary = $summary.ToArray()
    } | ConvertTo-Json -Depth 12 -Compress
    $fingerprint = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($editorJobContext.Encoding.GetBytes(($digest -join [char]10) + $descriptor)))
    return @{
        Entry=$entry; Invoke=$invoke; SecureJson=$secureJson; TokenPath=$tokenPath; ConfigPath=$configPath
        Details=@{configurationPath=$configPath;filebaseSource=$filebaseSource;workspaceMismatch=$mismatch;
            canRun=$canRun;warnings=$warnings.ToArray();summary=$summary.ToArray();paths=$paths.ToArray();
            confirmations=$confirmations.ToArray();fingerprint=$fingerprint}
    }
}

function Invoke-JobPlan($Plan, [string]$ToolId) {
    if ($ToolId -cin @('09', '12')) { return }
    $parameters = $Plan.Invoke.Clone()
    foreach ($name in @('ServersStopped','AutomaticWritersStopped','AdmissionClosed','Recovery','RecoveryChecked')) {
        $parameters.Remove($name)
    }
    if ($ToolId -ceq '16') { $parameters.Phase = 'Plan' }
    else { $parameters.Plan = $true }
    $global:LASTEXITCODE = 0
    # The original Plan entry provides the existing validation of options and deployment.
    $planOutput = @(& $Plan.Entry @parameters *>&1)
    if ($LASTEXITCODE -ne 0) {
        $failure = $planOutput | Where-Object { $_ -is [Management.Automation.ErrorRecord] } | Select-Object -Last 1
        if ($failure) { throw "既存の $ToolId 計画検査に失敗しました。$($failure.Exception.Message)" }
        throw "既存の $ToolId 計画検査に失敗しました。配布設定と運用条件を確認してください。"
    }
}

function Read-Host([string]$Prompt) {
    # Normal UI runs use the existing confirmation switches. Only the fixed
    # workflow questions can ask for input if a script takes an interactive branch.
    $choices = switch -Regex ($Prompt) {
        '^Confirm .+\. Type DEPLOY to continue$' {
            @(@{value='DEPLOY';label='確認して続行'},@{value='2';label='中止'}); break
        }
        '^1: バックアップから復旧して新規実行 / 2: 中止（既定）$' {
            @(@{value='1';label='復旧して続行'},@{value='2';label='中止'}); break
        }
        '^全配布元・配布先と自動書込みを停止し入場制限済みなら RESTORE と入力$' {
            @(@{value='RESTORE';label='停止・入場制限を確認し復旧'},@{value='2';label='中止'}); break
        }
        '^1: 外部状態を確認済みとして記録を保全し新規実行 / 2: 中止（既定）$' {
            @(@{value='1';label='確認して続行'},@{value='2';label='中止'}); break
        }
        '^上記確認と全対象停止・入場制限が完了している場合のみ RECOVERY-CHECKED と入力$' {
            @(@{value='RECOVERY-CHECKED';label='外部状態と停止を確認して続行'},@{value='2';label='中止'}); break
        }
        default { throw 'この対話入力はデスクトップ版で使用できません。' }
    }
    if ($editorJobContext.Mode -cne 'Run') { throw '計画の取得中に対話入力が発生しました。' }
    $id = [Guid]::NewGuid().ToString('D')
    $editorJobContext.Run.status = 'waitingForInput'
    $editorJobContext.Run.prompt = @{ id=$id; message=(Protect-JobText $Prompt); choices=@($choices) }
    Write-JobJson $editorJobContext.Run $editorJobContext.StatePath
    $answerPath = Join-Path $editorJobContext.Directory "input-$id.json"
    try {
        while (!(Test-Path -LiteralPath $answerPath -PathType Leaf)) { Start-Sleep -Milliseconds 400 }
        $answer = Read-JobJson $answerPath
        if ($answer.promptId -cne $id -or $answer.value -cnotin $choices.value) { throw '無効な確認応答です。' }
        return $answer.value
    } finally {
        $editorJobContext.Run.status = 'running'
        $editorJobContext.Run.prompt = $null
        Write-JobJson $editorJobContext.Run $editorJobContext.StatePath
    }
}

function Out-Host {
    [CmdletBinding()]
    param([Parameter(ValueFromPipeline)]$InputObject)
    process { if ($editorJobContext.Mode -ceq 'Run') { Add-JobOutput $InputObject } }
}

try {
    $root = [IO.Path]::GetFullPath((Join-Path $toolRoot '05-master-data-editor/.cache/tool-jobs'))
    $child = [IO.Path]::GetFullPath($JobDirectory)
    $expectedId = if ($Mode -cne 'Run') { '^plan-[0-9a-f-]{36}$' } else { '^[0-9a-f-]{36}$' }
    if ([IO.Path]::GetDirectoryName($child) -ine $root -or [IO.Path]::GetFileName($child) -cnotmatch $expectedId) {
        throw '実行記録のディレクトリが無効です。'
    }
    $JobDirectory = $child
    $editorJobContext.Directory = $JobDirectory
    $editorJobContext.StatePath = Join-Path $JobDirectory 'state.json'
    foreach ($environmentEntry in (Get-ChildItem Env:)) {
        if ($environmentEntry.Name -match '(?i)(password|passwd|secret|token|credential|connectionstring|api.?key|migration.?key|private.?key)') {
            Add-JobSecret $environmentEntry.Value
        }
    }

    if ($Mode -ceq 'MaintenanceOptions') {
        Write-JobJson (Get-JobMaintenanceOptions) (Join-Path $JobDirectory 'maintenance-options.json')
        exit 0
    }

    if ($Mode -ceq 'Run') {
        $editorJobContext.Run = Read-JobJson $editorJobContext.StatePath
        $process = Get-Process -Id $PID
        Write-JobJson @{pid=$PID;startedAtTicks=$process.StartTime.ToUniversalTime().Ticks} (Join-Path $JobDirectory 'worker.json')
        $editorJobContext.Log = [IO.File]::Open((Join-Path $JobDirectory 'log.jsonl'), 'Append', 'Write', 'Read')
        $toolId = $editorJobContext.Run.toolId
        if ($toolId -cnotin @('01', '03', '09', '12', '16')) { throw '対応する運用ツールを選択してください。' }
        try { $toolLock = [IO.File]::Open((Join-Path $root "active-$toolId.lock"), 'OpenOrCreate', 'ReadWrite', 'None') }
        catch [IO.IOException] { throw "$toolId は実行中です。完了後に再実行してください。" }
        $editorJobContext.Run.status = 'running'
        Write-JobJson $editorJobContext.Run $editorJobContext.StatePath
        Add-JobLog '計画と設定を再確認しています。'
    }

    $request = Read-JobJson (Join-Path $JobDirectory 'request.json')
    if ($Mode -ceq 'Run' -and $toolId -cne $request.options.toolId) { throw '実行設定と記録のツールが一致しません。' }
    $plan = Get-RequiredJobPlan $request.options
    if ($Mode -ceq 'Plan') {
        if ($plan.Details.canRun) { Invoke-JobPlan $plan $request.options.toolId }
        Write-JobJson $plan.Details (Join-Path $JobDirectory 'plan.json')
        exit 0
    }

    if (!$plan.Details.canRun -or $plan.Details.fingerprint -cne $request.fingerprint) {
        throw '計画の確認後に設定または実行ファイルが変わりました。計画を作り直してください。'
    }
    foreach ($path in $plan.SecureJson) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Add-JobConfigSecrets (Read-JobJson $path) }
    }
    if ($plan.TokenPath -and (Test-Path -LiteralPath $plan.TokenPath -PathType Leaf)) {
        Add-JobSecret (Get-Content -Raw -Encoding UTF8 -LiteralPath $plan.TokenPath)
    }

    # Keep the config stable while the original workflow and its children read it.
    $configLock = if ($plan.ConfigPath) { [IO.File]::Open($plan.ConfigPath, 'Open', 'Read', 'Read') } else { $null }
    try {
        $current = Get-RequiredJobPlan $request.options
        if (!$current.Details.canRun -or $current.Details.fingerprint -cne $request.fingerprint) {
            throw '実行直前に計画または設定が変わりました。計画を作り直してください。'
        }
        Add-JobLog "$($request.options.toolId) の既存実行フローを開始します。"
        $arguments = $current.Invoke
        $global:LASTEXITCODE = 0
        & $current.Entry @arguments *>&1 | ForEach-Object { Add-JobOutput $_ }
        $result = $LASTEXITCODE
        Add-JobLog "実行終了 (exit code $result)"
    } finally {
        if ($configLock) { $configLock.Dispose() }
    }
} catch {
    $message = Protect-JobText $_.Exception.Message
    if ($Mode -cne 'Run') {
        Write-JobJson @{message=$message} (Join-Path $JobDirectory 'error.json')
    } else { Add-JobLog $message 'error' }
    $result = 1
} finally {
    try {
        if ($toolLock) { $toolLock.Dispose() }
        if ($editorJobContext.Run) {
            $editorJobContext.Run.status = if ($result -eq 0) { 'succeeded' } else { 'failed' }
            $editorJobContext.Run.exitCode = $result
            $editorJobContext.Run.finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
            $editorJobContext.Run.prompt = $null
            Write-JobJson $editorJobContext.Run $editorJobContext.StatePath
        }
    } finally {
        if ($editorJobContext.Log) { $editorJobContext.Log.Dispose() }
    }
}

exit $result
