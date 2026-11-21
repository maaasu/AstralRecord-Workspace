[CmdletBinding()]
param(
    [string]$Time = '12:30',
    [switch]$Unregister
)

$ErrorActionPreference = 'Stop'
$taskPath = '\AstralRecord\'
$taskName = 'WorktreeCleanup'

if ($Unregister) {
    Unregister-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Confirm:$false
    Write-Output "$taskPath$taskName を登録解除しました。"
    return
}

$startTime = [DateTime]::ParseExact($Time, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture)
$trigger = New-ScheduledTaskTrigger -Daily -DaysInterval 1 -At ([DateTime]::Today.Add($startTime.TimeOfDay))
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew

$runCleanup = @'
$process = Start-Process -FilePath $env:ComSpec -ArgumentList '/d /c .\18-worktree-cleanup.bat' -WindowStyle Hidden -Wait -PassThru -ErrorAction Stop
exit $process.ExitCode
'@
$encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($runCleanup))
$action = New-ScheduledTaskAction `
    -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -EncodedCommand $encodedCommand" `
    -WorkingDirectory (Split-Path -Parent $PSScriptRoot)

# タスクの登録先がなければ作成する。
$scheduler = New-Object -ComObject 'Schedule.Service'
$scheduler.Connect()
try {
    [void]$scheduler.GetFolder($taskPath.TrimEnd('\'))
} catch {
    if ($_.Exception.GetBaseException().HResult -notin (0x80070002, 0x80070003)) {
        throw
    }
    [void]$scheduler.GetFolder('\').CreateFolder('AstralRecord')
}

Register-ScheduledTask -TaskPath $taskPath -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Description 'Claude と Codex の作業ツリーを毎日整理します。' -Force |
    Out-Null
Write-Output "$taskPath$taskName を毎日 $Time に実行するよう登録しました。"
