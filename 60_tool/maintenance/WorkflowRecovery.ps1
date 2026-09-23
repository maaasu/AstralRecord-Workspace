# Invoked only while the guided workflow owns workflow.lock.
function Get-UpdateWorkflowRecoveryDecision([string]$RunDirectory, [string]$Label, [string]$Fingerprint) {
    foreach ($name in @('migration-commit-started.json','skilltree-migration-state.json','skilltree-migration-result.json','skilltree-migration-targets.json')) {
        if (Test-Path -LiteralPath (Join-Path $RunDirectory $name)) { throw '移行記録があります。新規実行へ切り替えず、同じrunの世代移行を再開してください。' }
    }
    if ($Label -eq 'Dev') {
        if (Test-Path -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json') -PathType Leaf) { return @{kind='Resume';reason='Dev配置完了済みです。再配布せず再開します。'} }
        $path=Join-Path $RunDirectory 'dev-deployment-progress.json'
        if (Test-Path -LiteralPath $path) {
            $progress=Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
            if ($progress.schemaVersion -ne 1 -or $progress.configurationFingerprint -cne $Fingerprint -or
                $progress.stage -cnotin @('Build','Database','Files','Completed') -or $progress.status -cnotin @('Running','Failed','Completed')) {
                throw 'Dev配置の進行記録が不正です。自動復旧せず記録を確認してください。'
            }
            if ($progress.stage -ceq 'Completed' -and $progress.status -ceq 'Completed') { return @{kind='Resume';reason='Dev配置完了を進行記録で確認しました。'} }
            if ($progress.stage -ceq 'Build' -and $progress.buildIsolated -eq $true -and $progress.status -ceq 'Failed') {
                return @{kind='Fresh';reason='DB更新・外部配置より前のビルド失敗が記録されています。'}
            }
            return @{kind='Manual';reason="前回は $($progress.stage) 段階で停止しました。DB更新・配置先の状態確認が必要です。"}
        }
        return @{kind='Manual';reason='旧形式のDev実行記録のため停止位置を自動判定できません。dev-deploy.logとDB・配置先を確認してください。'}
    }
    $path=Join-Path $RunDirectory 'deployment.json'
    if (!(Test-Path -LiteralPath $path)) { return @{kind='Manual';reason='配布journalがありません。外部配置と前回の子プロセス終了を確認してください。'} }
    $journal=Get-Content -Raw -Encoding UTF8 -LiteralPath $path | ConvertFrom-Json -AsHashtable
    if ($journal.version -ne 1 -or $journal.runId -notmatch '^[a-f0-9]{32}$' -or @($journal.operations).Count -eq 0) { throw '配布journalが不正です。自動復旧できません。' }
    $indices=[Collections.Generic.HashSet[long]]::new()
    foreach ($op in $journal.operations) {
        if (($op.index -isnot [int] -and $op.index -isnot [long]) -or $op.index -lt 0 -or !$indices.Add([long]$op.index)) { throw '配布journalの項目番号が不正です。' }
        $root=Get-MaintenanceAbsolutePath $op.root
        $relative=[IO.Path]::GetRelativePath($root,$op.destination)
        if ((Resolve-MaintenanceChild $root $relative) -ne $op.destination -or $relative -match '(^|[\\/])\.astral-maintenance([\\/]|$)') { throw '配布journalの配置先が不正です。' }
        $work=Resolve-MaintenanceChild $root ".astral-maintenance/$($journal.runId)/$($op.index)"
        foreach ($name in @('stage','backup','displaced')) {
            if ($op[$name] -ne (Join-Path $work $name)) { throw '配布journalの退避先が不正です。' }
            Assert-MaintenanceNoLinks $op[$name]
        }
    }
    if ($journal.status -ceq 'Deployed') { return @{kind='Resume';reason='配布完了済みです。'} }
    $allowed=@('Pending','Prepared','Unchanged','Applying','Applied','Restoring','Restored')
    if (@($journal.operations | Where-Object { $_.status -cnotin $allowed }).Count) { throw '配布項目に不明な状態があります。自動復旧できません。' }
    if ($journal.status -ceq 'Restored' -and !@($journal.operations | Where-Object { $_.status -cin @('Applying','Applied','Restoring') }).Count) {
        return @{kind='Fresh';reason='ファイル復旧が完了しています。'}
    }
    if ($journal.status -cnotin @('Preparing','Applying','Failed')) { throw '配布journalの状態を自動判定できません。' }
    if (!@($journal.operations | Where-Object { $_.status -cnotin @('Pending','Prepared','Unchanged') }).Count) {
        foreach ($op in $journal.operations) {
            foreach ($name in @('backup','displaced')) {
                if (Test-Path -LiteralPath $op[$name]) { throw '未適用記録に退避ファイルがあります。自動で新規実行へ切り替えません。' }
            }
        }
        return @{kind='Fresh';reason='配置先の置換は未着手です。準備データを保全してやり直せます。'}
    }
    return @{kind='Restore';reason='一部の配置先が更新されています。バックアップからの復旧成功後にだけ新規実行へ切り替えます。'}
}

function Invoke-UpdateWorkflowRecovery {
    param([string]$RunDirectory, [string]$ActivePath, [string]$Label, [string]$Fingerprint,
        [ValidateSet('Auto','Restart','Restore')][string]$Recovery='Auto', [switch]$RecoveryChecked,
        [switch]$ServersStopped, [switch]$AdmissionClosed, [scriptblock]$RestoreAction,
        [switch]$ConfigurationChanged)
    $lock=$null
    try {
        try { $lock=[IO.File]::Open((Join-Path $RunDirectory 'run.lock'),'OpenOrCreate','ReadWrite','None') }
        catch [IO.IOException] { throw '前回の処理が実行中、または記録がロックされています。終了を待ってから再実行してください。' }
        $decision=Get-UpdateWorkflowRecoveryDecision $RunDirectory $Label $Fingerprint
        Write-Host "復旧判定: $($decision.reason)"
        Write-Host "前回の記録: $RunDirectory"
        if ($decision.kind -eq 'Resume') {
            if ($ConfigurationChanged) { throw '配置完了後に設定が変わっています。元の設定に戻して再開してください。' }
            if ($Label -eq 'Dev' -and !(Test-Path -LiteralPath (Join-Path $RunDirectory 'deploy-action-success.json'))) {
                Write-MaintenanceJson @{completedAtUtc=[DateTime]::UtcNow.ToString('o');recoveredFromProgress=$true} (Join-Path $RunDirectory 'deploy-action-success.json')
            }
            return $false
        }
        if ($decision.kind -eq 'Restore') {
            if ($Recovery -eq 'Restart') { throw '部分配布のため直接やり直せません。-Recovery Restoreを使用してください。' }
            if (!$RestoreAction -or $ConfigurationChanged) { throw '元の配布設定を戻し、16の復旧操作でファイルを復元してください。' }
            if ($Recovery -eq 'Auto') {
                if ([Console]::IsInputRedirected -or ($ServersStopped -and $AdmissionClosed)) { throw '復旧が必要です。全対象停止・入場制限後、-Recovery Restore -ServersStopped -AdmissionClosedで実行してください。' }
                $answer=Read-Host '1: バックアップから復旧して新規実行 / 2: 中止（既定）'
                if ($answer -cne '1') { throw '復旧を中止しました。記録は保持しています。' }
            }
            if (!$ServersStopped -or !$AdmissionClosed) {
                if ([Console]::IsInputRedirected) { throw '復旧には-ServersStoppedと-AdmissionClosedが必要です。' }
                if ((Read-Host '全配布元・配布先と自動書込みを停止し入場制限済みなら RESTORE と入力') -cne 'RESTORE') { throw '復旧の停止確認がありません。' }
            }
            foreach ($name in @('deployment.json','workflow-state.json')) {
                $source=Join-Path $RunDirectory $name
                if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination ($source+'.before-recovery-'+[Guid]::NewGuid().ToString('N')) -ErrorAction Stop }
            }
            # The existing child Restore takes run.lock and all server locks itself.
            $lock.Dispose(); $lock=$null
            & $RestoreAction $RunDirectory | Out-Host
            $lock=[IO.File]::Open((Join-Path $RunDirectory 'run.lock'),'Open','ReadWrite','None')
            $restored=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RunDirectory 'deployment.json') | ConvertFrom-Json -AsHashtable
            if ($restored.status -cne 'Restored') { throw '復旧完了を確認できません。前回記録を保持します。' }
            $decision=Get-UpdateWorkflowRecoveryDecision $RunDirectory $Label $Fingerprint
            if ($decision.kind -ne 'Fresh') { throw '復旧後の状態が不正です。前回記録を保持します。' }
        } elseif ($decision.kind -eq 'Manual') {
            if ($Recovery -eq 'Restore') { throw 'この実行には自動ファイル復旧を適用できません。DB・配置先を確認してください。' }
            if (!($Recovery -eq 'Restart' -and $RecoveryChecked -and $ServersStopped -and $AdmissionClosed)) {
                if ([Console]::IsInputRedirected -or ($ServersStopped -and $AdmissionClosed)) { throw 'Deployment will not redeploy automatically. 外部状態を確認した後だけ -Recovery Restart -RecoveryChecked -ServersStopped -AdmissionClosed を指定してください。' }
                Write-Host 'DB更新の適用結果・必要な復旧・配置先・前回の子プロセス終了を確認してください。これはDBや配置ファイルを復元する操作ではありません。'
                if ($Recovery -eq 'Auto' -and (Read-Host '1: 外部状態を確認済みとして記録を保全し新規実行 / 2: 中止（既定）') -cne '1') { throw '復旧を中止しました。記録は保持しています。' }
                if ((Read-Host '上記確認と全対象停止・入場制限が完了している場合のみ RECOVERY-CHECKED と入力') -cne 'RECOVERY-CHECKED') { throw '復旧確認がありません。記録は保持しています。' }
            }
        }
        $active=Get-Content -Raw -Encoding UTF8 -LiteralPath $ActivePath | ConvertFrom-Json -AsHashtable
        if ($active.relativeRunDirectory -cne [IO.Path]::GetFileName($RunDirectory)) { throw '実行記録の参照先が変わりました。中止します。' }
        $archive=Join-Path $RunDirectory ('active-run.before-recovery-'+[Guid]::NewGuid().ToString('N')+'.json')
        Write-MaintenanceJson @{reason=$decision.reason;action='ArchiveForNewRun';atUtc=[DateTime]::UtcNow.ToString('o')} ($archive+'.reason.json')
        $hash=(Get-FileHash -LiteralPath $ActivePath -Algorithm SHA256).Hash
        Move-Item -LiteralPath $ActivePath -Destination $archive -ErrorAction Stop
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -cne $hash) { throw '記録退避の確認に失敗しました。' }
        Write-Host "前回の記録を保全しました: $archive"
        Write-Host '新しい実行を開始します。'
        return $true
    } finally { if ($lock) { $lock.Dispose() } }
}
