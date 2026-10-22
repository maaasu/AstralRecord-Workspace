#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot '..\Control.Deployment.psm1') -Force

$script:calls = [Collections.Generic.List[string]]::new()
$script:runnerRegistered = $true
$script:failCommand = ''
function global:Get-Service {
    param([string]$Name, $ErrorAction)
    if ($Name -eq 'AstralRecordControl.Runner' -and $script:runnerRegistered) { return [pscustomobject]@{ Name = $Name } }
    return $null
}
function global:sc.exe {
    $script:calls.Add(($args -join '|'))
    $global:LASTEXITCODE = if ($args[0] -eq $script:failCommand) { 5 } else { 0 }
}
function Assert-Failure([scriptblock]$Action, [string]$Expected) {
    try { & $Action; throw 'Expected service configuration failure was accepted.' }
    catch {
        if ($_.Exception.Message -ne $Expected) { throw }
    }
}

try {
    Set-ControlServiceStartup Web
    Set-ControlServiceStartup Runner
    Set-ControlServiceStartup Agent
    $expected = @(
        'config|AstralRecordControl.Web|start=|auto',
        'config|AstralRecordControl.Runner|start=|auto',
        'config|AstralRecordControl.Agent|start=|auto|depend=|AstralRecordControl.Runner'
    )
    if (($script:calls -join "`n") -ne ($expected -join "`n")) { throw 'Unexpected SCM startup configuration.' }

    $script:calls.Clear()
    $script:runnerRegistered = $false
    Assert-Failure { Set-ControlServiceStartup Agent } 'Register AstralRecordControl.Runner before configuring Agent startup.'
    if ($script:calls.Count -ne 0) { throw 'Agent startup was configured without Runner.' }

    $script:runnerRegistered = $true
    $script:failCommand = 'config'
    Assert-Failure { Set-ControlServiceStartup Agent } 'Automatic startup configuration failed for AstralRecordControl.Agent.'
    if ($script:calls.Count -ne 1) { throw 'Startup configuration failure was not detected.' }

    $updatePath = Join-Path $PSScriptRoot '..\Update-Control.ps1'
    $parseErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($updatePath, [ref]$null, [ref]$parseErrors)
    if ($parseErrors.Count) { throw 'Update script has PowerShell parse errors.' }
    $update = $ast.Extent.Text
    if ($update.IndexOf('Set-ControlServiceStartup $Component') -lt 0 -or
        $update.IndexOf('Set-ControlServiceStartup $Component') -ge $update.IndexOf('Stop-Service -Name $name') -or
        $update.IndexOf('if ($agentWasRunning) { Start-Service') -lt 0) {
        throw 'Service update no longer corrects startup before stopping or preserves Agent restart.'
    }
    Write-Output 'Service startup fixture passed (SCM calls mocked; no service changed).'
} finally {
    Remove-Item Function:\Get-Service, Function:\sc.exe -ErrorAction SilentlyContinue
}
