#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param([Parameter(Mandatory)][string]$PackageDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ControlHostUpdate {
    [CmdletBinding(SupportsShouldProcess)]
    param(
        [Parameter(Mandatory)][string]$PackageDirectory,
        [Parameter(Mandatory)][string]$ComputerName,
        [Parameter(Mandatory)][string]$DeploymentDirectory
    )
    $components = switch ($ComputerName.ToUpperInvariant()) {
        'APP-01' { @('Runner', 'Agent', 'Web') }
        'MC-01' { @('Runner', 'Agent') }
        'DEV-01' { @('Runner', 'Agent') }
        default { throw 'この更新はAPP-01・MC-01・DEV-01用です。' }
    }
    $package = Get-ControlAbsolutePath $PackageDirectory
    $cli = Join-Path $package 'Cli\AstralRecordControl.Cli.exe'
    $null = Assert-ControlPackage (Join-Path $package 'Cli') 'Cli'
    $update = Join-Path $DeploymentDirectory 'Update-Control.ps1'
    if (!(Test-Path -LiteralPath $update -PathType Leaf)) { throw '更新スクリプトがありません。フォルダ全体を配置してください。' }
    foreach ($component in $components) {
        $null = Assert-ControlPackage (Join-Path $package $component) $component
    }
    foreach ($component in $components) {
        Write-Host "[$ComputerName] $component の事前確認を行います（変更なし）。"
        $preflightArguments = @{ Component = $component; PackageDirectory = Join-Path $package $component; Confirm = $false; WhatIf = $true }
        if ($component -eq 'Runner') { $preflightArguments.CliPath = $cli }
        $global:LASTEXITCODE = 0
        & $update @preflightArguments
        if ($LASTEXITCODE -ne 0) { throw "$component の事前確認が失敗しました。更新を中断します。" }
    }
    if (!$PSCmdlet.ShouldProcess($ComputerName, 'Update Control Runner, Agent and installed Web')) { return }
    foreach ($component in $components) {
        Write-Host "[$ComputerName] $component を更新します。"
        $updateArguments = @{ Component = $component; PackageDirectory = Join-Path $package $component; Confirm = $false }
        if ($component -eq 'Runner') { $updateArguments.CliPath = $cli }
        $global:LASTEXITCODE = 0
        & $update @updateArguments
        if ($LASTEXITCODE -ne 0) { throw "$component の更新が失敗しました。後続の更新を中断します。" }
        $service = Get-Service -Name "AstralRecordControl.$component" -ErrorAction Stop
        $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
    }
    Write-Host '更新が完了しました。Control画面でPCの接続状態を確認してください。'
}

if ($MyInvocation.InvocationName -ne '.') {
    $transcribing = $false
    try {
        if (-not $IsWindows) { throw 'Windows上で実行してください。' }
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            throw 'Update-Control.batを右クリックし「管理者として実行」を選択してください。'
        }
        $logDirectory = Join-Path (Split-Path -Parent $PackageDirectory) 'Logs'
        New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
        $log = Join-Path $logDirectory ($env:COMPUTERNAME + '-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.log')
        Start-Transcript -LiteralPath $log -ErrorAction Stop | Out-Null
        $transcribing = $true
        Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force
        Invoke-ControlHostUpdate -PackageDirectory $PackageDirectory -ComputerName $env:COMPUTERNAME -DeploymentDirectory $PSScriptRoot -WhatIf:$WhatIfPreference
    } catch {
        Write-Host "更新を中断しました: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host 'Runner更新には全管理サーバーの正常停止が必要です。稼働中・管理外・保守中の場合はControl画面で状態を確認してください。'
        exit 1
    } finally {
        if ($transcribing) { Stop-Transcript | Out-Null }
    }
}
