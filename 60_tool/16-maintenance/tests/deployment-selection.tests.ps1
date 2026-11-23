#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../UpdateWorkflow.ps1')
. (Join-Path $PSScriptRoot '../DeploymentSelection.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-selection-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Value,[string]$Message) { if(!$Value){throw "ASSERT: $Message"} }
function Reject([scriptblock]$Action,[string]$Pattern) { $failed=$false; try { & $Action | Out-Null } catch { $failed=$_.Exception.Message -match $Pattern }; Assert $failed "Expected rejection: $Pattern" }
function Put([string]$Path,[string]$Value) {New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null; [IO.File]::WriteAllText($Path,$Value)}
function New-Run([string]$Name) {$p=Join-Path $fixture $Name; New-Item -ItemType Directory -Path $p | Out-Null; return $p}
function Entry([string[]]$Arguments,[bool]$Success=$true) {
    & (Get-Process -Id $PID).Path -NoProfile -File (Join-Path $PSScriptRoot '../maintenance.ps1') @Arguments 2>&1 | Out-Host
    Assert (($LASTEXITCODE -eq 0) -eq $Success) 'entry exit code'
}
try {
    $choiceRun=New-Run 'choices'; $script:answers=[Collections.Generic.Queue[string]]::new()
    foreach($answer in @('invalid','2','1')){$script:answers.Enqueue($answer)}
    $choice=Resolve-MaintenanceDeploymentSelection $choiceRun -PromptForNewRun -PromptAction {$script:answers.Dequeue()}
    Assert ($choice.worldCopy -eq 'Skip' -and $choice.networkPlugins -eq 'Include' -and !$script:answers.Count) 'two independent prompts, invalid input retried'
    $again=Resolve-MaintenanceDeploymentSelection $choiceRun -PromptForNewRun -PromptAction {throw 'must not prompt on resume'}
    Assert ($again.worldCopy -eq 'Skip') 'resume preserves selection'
    Reject {Resolve-MaintenanceDeploymentSelection $choiceRun -WorldCopy Include} 'cannot change'
    $legacy=New-Run 'legacy'; Write-MaintenanceJson @{status='Deploying'} "$legacy/workflow-state.json"
    $old=Resolve-MaintenanceDeploymentSelection $legacy -PromptForNewRun -PromptAction {throw 'legacy must not prompt'}
    Assert ($old.worldCopy -eq 'Include' -and $old.networkPlugins -eq 'Include') 'legacy keeps original scope'
    $digestOnly=New-Run 'legacy-digest-only'; Write-MaintenanceJson @{sha256=('a'*64)} "$digestOnly/config-digest.json"
    Reject {Resolve-MaintenanceDeploymentSelection $digestOnly -WorldCopy Skip -NetworkPlugins Include} 'cannot change'
    Assert (!(Test-Path -LiteralPath "$digestOnly/deployment-selection.json")) 'legacy rejection does not change scope'
    $oldExplicit=Resolve-MaintenanceDeploymentSelection $digestOnly
    Assert ($oldExplicit.worldCopy -eq 'Include' -and $oldExplicit.networkPlugins -eq 'Include') 'digest-only legacy run keeps original scope'
    $lower=Resolve-MaintenanceDeploymentSelection (New-Run 'lowercase') -WorldCopy skip -NetworkPlugins include
    Assert ($lower.worldCopy -ceq 'Skip' -and $lower.networkPlugins -ceq 'Include') 'canonical options'
    $source=Join-Path $fixture 'source'; $target=Join-Path $fixture 'target'; $network=Join-Path $fixture 'network'; $lobby=Join-Path $fixture 'lobby'
    Put "$source/plugins/AstralRecord.jar" 'new-plugin'; Put "$source/world/level.dat" 'new-world'
    Put "$network/AstralRecordLobby.jar" 'new-lobby'
    New-Item -ItemType Directory -Path "$target/plugins","$lobby/plugins" -Force | Out-Null
    $config=@{schemaVersion=1;servers=@(@{id='source';rootPath=$source;enabled=$true},@{id='target';rootPath=$target;enabled=$true})
        distributions=@(@{enabled=$true;kind='Jar';source='source';relativePath='plugins/AstralRecord.jar';targets=@('target')},@{enabled=$true;kind='World';source='source';relativePath='world';targets=@('target')})
        networkPlugins=@{sourceDirectory=$network;destinations=@(@{artifact='AstralRecordLobby.jar';deployDirectory="$lobby/plugins"})};migration=@{enabled=$false;scope='AllCandidates'}}
    $path=Join-Path $fixture 'config.json'; Write-MaintenanceJson $config $path
    foreach($world in @('Include','Skip')) { foreach($net in @('Include','Skip')) {
        Put "$target/plugins/AstralRecord.jar" 'old-plugin'; Put "$target/world/level.dat" 'old-world'; Put "$lobby/plugins/AstralRecordLobby.jar" 'old-lobby'
        $run=New-Run "$world-$net"
        Entry @('-Phase','Deploy','-ConfigPath',$path,'-RunDirectory',$run,'-ServersStopped','-WorldCopy',$world,'-NetworkPlugins',$net)
        $j=Get-Content -Raw -Encoding UTF8 -LiteralPath "$run/deployment.json" | ConvertFrom-Json
        Assert ($j.status -eq 'Deployed' -and $j.operations.Count -eq (1+[int]($world -eq 'Include')+[int]($net -eq 'Include'))) 'selected operations only'
        Assert ([IO.File]::ReadAllText("$target/world/level.dat") -eq $(if($world -eq 'Include'){'new-world'}else{'old-world'})) 'world choice applied by child entry'
        Assert ([IO.File]::ReadAllText("$lobby/plugins/AstralRecordLobby.jar") -eq $(if($net -eq 'Include'){'new-lobby'}else{'old-lobby'})) 'network choice applied'
        Entry @('-Phase','Restore','-ConfigPath',$path,'-RunDirectory',$run,'-ServersStopped')
        Assert ([IO.File]::ReadAllText("$target/world/level.dat") -eq 'old-world' -and [IO.File]::ReadAllText("$lobby/plugins/AstralRecordLobby.jar") -eq 'old-lobby') 'restore uses actual journal'
    }}
    $selected=Get-MaintenanceSelectedConfig $config 'Skip' 'Skip'
    Assert ($config.distributions[1].enabled -and $config.ContainsKey('networkPlugins')) 'original config not mutated'
    $selected.distributions[1].relativePath='absent-world'
    Assert (@(Get-MaintenancePlan $selected).Count -eq 1) 'skipped world need not exist'
    Reject {Assert-MaintenanceNetworkRunBoundary $config "$network/runs"} 'overlaps'
    Reject {Assert-MaintenanceNetworkRunBoundary $config "$lobby/runs"} 'overlaps'

    $other = New-Run 'other-server'
    $otherLobby = New-Run 'other-lobby'
    $config.servers += @{id='other';rootPath=$other;enabled=$true}, @{id='disabled';enabled=$false}
    $config.distributions[0].targets = @('target','disabled','other')
    $config.distributions += @{enabled=$true;kind='Placement';source='source';relativePath='plugins/AstralRecord/npc_locations.yml';targets=@('target')}
    $config.networkPlugins.destinations += @{artifact='AstralRecordLobby.jar';deployDirectory="$otherLobby/plugins"}
    Put "$otherLobby/plugins/AstralRecordLobby.jar" 'old-other-lobby'
    Put "$other/plugins/AstralRecord.jar" 'old-other-plugin'
    Write-MaintenanceJson $config $path
    $digest = (Get-FileHash -LiteralPath $path).Hash
    $bomPath = Join-Path $fixture 'legacy-utf16.json'
    Get-Content -Raw -LiteralPath $path -Encoding UTF8 | Set-Content -LiteralPath $bomPath -Encoding unicode
    $bomRead = Read-MaintenanceConfiguration $bomPath
    Assert ($bomRead.config.schemaVersion -eq 1 -and $bomRead.digest -ceq (Get-FileHash -LiteralPath $bomPath).Hash) 'BOM encoded config and its raw digest remain supported'
    $options = @(Get-MaintenanceDistributionOptions $config)
    Assert (($options.id -join ',') -ceq 'd1-t1,d1-t3,d2-t1,d3-t1,n1,n2') 'catalog includes reachable choices without checking artifacts'
    $script:answers.Enqueue('2,2'); $script:answers.Enqueue('2,6')
    Assert ((@(Select-MaintenanceDistributionDetails $config {$script:answers.Dequeue()}) -join ',') -ceq 'd1-t3,n2') 'numbered menu retries duplicate numbers and selects two destinations'
    Reject { Select-MaintenanceDistributionDetails $config { '0' } } '中止'
    Entry @('-Phase','Plan','-ConfigPath',$path,'-ListDistributionOptions')
    $detailArgs = @('-ConfigPath',$path,'-DistributionSelection','n2,d1-t3','-DistributionConfigurationDigest',$digest)
    Entry (@('-Phase','Plan') + $detailArgs)
    foreach ($invalid in @('', 'd1-t2', 'd9-t1', 'n1,n1')) {
        Entry @('-Phase','Plan','-ConfigPath',$path,'-DistributionSelection',$invalid) $false
    }
    Entry (@('-Phase','Plan','-NetworkPlugins','Skip') + $detailArgs) $false
    Entry @('-Phase','Plan','-ConfigPath',$path,'-DistributionSelection','n2','-DistributionConfigurationDigest',('0'*64)) $false
    $failedRun = New-Run 'missing-source'
    $phaseDeploy = @('-Phase','Deploy','-ConfigPath',$path,'-RunDirectory',$failedRun,'-ServersStopped')
    Entry ($phaseDeploy + @('-DistributionSelection','d1-t3')) $false
    Entry ($phaseDeploy + @('-DistributionSelection','d3-t1','-DistributionConfigurationDigest',$digest)) $false
    Assert (!(Test-Path -LiteralPath "$failedRun/deployment-selection.json")) 'a failed preflight does not freeze the run selection'
    $detailRun = New-Run 'detailed-deploy'
    Entry (@('-Phase','Deploy','-RunDirectory',$detailRun,'-ServersStopped') + $detailArgs)
    $detailJournal = Get-Content -Raw -Encoding UTF8 -LiteralPath "$detailRun/deployment.json" | ConvertFrom-Json
    Assert ($detailJournal.operations.Count -eq 2) 'only two detailed operations reach the journal'
    Assert ([IO.File]::ReadAllText("$target/plugins/AstralRecord.jar") -eq 'old-plugin' -and [IO.File]::ReadAllText("$other/plugins/AstralRecord.jar") -eq 'new-plugin') 'same artifact is distributed only to the selected server'
    Assert ([IO.File]::ReadAllText("$lobby/plugins/AstralRecordLobby.jar") -eq 'old-lobby' -and [IO.File]::ReadAllText("$otherLobby/plugins/AstralRecordLobby.jar") -eq 'new-lobby') 'same network JAR is distributed only to the selected destination'
    $detail = Resolve-MaintenanceDeploymentSelection $detailRun -Config $config -ConfigurationDigest $digest -PromptForNewRun -PromptAction {throw 'must not prompt on resume'}
    $same = Resolve-MaintenanceDeploymentSelection $detailRun -Config $config -ConfigurationDigest $digest -DistributionIds @('n2','d1-t3')
    Assert (($detail.distributionIds -join ',') -ceq 'd1-t3,n2' -and (Get-MaintenanceSelectionFingerprint $detail) -ceq (Get-MaintenanceSelectionFingerprint $same)) 'v2 resumes with the same fingerprint regardless of input order'
    Reject { Resolve-MaintenanceDeploymentSelection $detailRun -Config $config -ConfigurationDigest $digest -DistributionIds @('n1') } 'cannot change'
    $childArgs = @('-Phase','Deploy','-ConfigPath',$path,'-RunDirectory',$detailRun,'-ServersStopped','-DistributionConfigurationDigest',$digest,'-ExpectedSelectionFingerprint',('0'*64))
    Entry $childArgs $false
    Entry (@('-Phase','Restore','-RunDirectory',$detailRun,'-ServersStopped') + $detailArgs) $false
    Entry @('-Phase','Restore','-ConfigPath',$path,'-RunDirectory',$detailRun,'-ServersStopped')
    Assert ([IO.File]::ReadAllText("$other/plugins/AstralRecord.jar") -eq 'old-other-plugin' -and [IO.File]::ReadAllText("$otherLobby/plugins/AstralRecordLobby.jar") -eq 'old-other-lobby') 'detailed restore uses the journal'

    # The workflow fixes selections under its root lock before baseline/API/deploy and binds them to state.
    $env:SELECTION_TEST_API='test-api'; $env:SELECTION_TEST_MIGRATION='test-migration'
    $workflowRoot=New-Run 'workflow'; $script:apiCalls=0; $script:deployCalls=0
    $arguments=@{WorkflowConfig=@{runRoot=$workflowRoot;startupTimeoutSeconds=1;pollIntervalSeconds=1;seedMasterData=$false};
        MigrationConfig=@{enabled=$true;baseUrl='http://127.0.0.1:5010';apiKeyEnvironmentVariable='SELECTION_TEST_API';migrationKeyEnvironmentVariable='SELECTION_TEST_MIGRATION';serverIds=@('test');scope='AllCandidates';accountIds=@()};
        ConfigurationFingerprint=('a'*64);ServerRoots=@($source,$target);ServersStopped=$true;AdmissionClosed=$true;Label='Channels';
        HttpInvoker={param($Method,$Uri,$Headers,$Body) $script:apiCalls++; return @{StatusCode=404;Body=$null}};
        DeployAction={param($RunDirectory) $script:deployCalls++; throw 'intentional deployment failure'};
        PrepareRunAction={param($RunDirectory)
            $locked=$false; try {$h=[IO.File]::Open("$workflowRoot/workflow.lock",'Open','ReadWrite','None');$h.Dispose()} catch [IO.IOException] {$locked=$true}
            Assert $locked 'selection is under workflow lock'
            $picked=Resolve-MaintenanceDeploymentSelection $RunDirectory -WorldCopy Skip -NetworkPlugins Skip
            Get-SkillTreeMigrationSha256 ($picked.worldCopy+'|'+$picked.networkPlugins)
        }}
    Reject {Invoke-UpdateWorkflow @arguments} 'Deployment action failed'
    Assert ($script:apiCalls -eq 1 -and $script:deployCalls -eq 1) 'workflow ordering'
    $active=Get-Content -Raw -Encoding UTF8 -LiteralPath "$workflowRoot/active-run.json" | ConvertFrom-Json
    $savedRun=Join-Path $workflowRoot $active.relativeRunDirectory
    Write-MaintenanceJson @{schemaVersion=1;worldCopy='Include';networkPlugins='Skip'} "$savedRun/deployment-selection.json"
    # Supply completed-deployment evidence so resume reaches the selection guard;
    # an interrupted deployment without a journal correctly stops at recovery first.
    Write-MaintenanceJson @{version=1;status='Deployed';operations=@()} "$savedRun/deployment.json"
    $arguments.PrepareRunAction={param($RunDirectory) $picked=Resolve-MaintenanceDeploymentSelection $RunDirectory; Get-SkillTreeMigrationSha256 ($picked.worldCopy+'|'+$picked.networkPlugins)}
    Reject {Invoke-UpdateWorkflow @arguments} 'selections changed'
    Assert ($script:apiCalls -eq 1 -and $script:deployCalls -eq 1) 'changed persisted selection blocked before API/deploy'

    Write-Host "PASS: group and detailed choices, per-destination deployment, legacy/resume, fingerprints, restore and boundaries. Evidence: $fixture"
} finally {
    Remove-Item Env:SELECTION_TEST_API,Env:SELECTION_TEST_MIGRATION -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}
