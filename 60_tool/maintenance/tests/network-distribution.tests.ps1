#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '../Distribution.ps1')
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('ar-network-distribution-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
Start-Transcript -LiteralPath (Join-Path $fixture 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
function Put($Path,$Value) { New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($Path)) -Force | Out-Null; [IO.File]::WriteAllText($Path,$Value) }
function mvn { throw 'Network distribution must never build.' }
function dotnet { throw 'Network distribution must never build.' }
try {
    $output=Join-Path $fixture 'output'
    $lobby=Join-Path $fixture 'lobby/plugins'
    $proxy=Join-Path $fixture 'proxy/plugins'
    $extensions=Join-Path $proxy 'Geyser-Velocity/extensions'
    foreach ($name in @('AstralRecordLobby.jar','AstralRecordProxy.jar','AstralRecordGeyserExtension.jar')) { Put (Join-Path $output $name) "new-$name" }
    Put "$lobby/AstralRecordLobby.jar" 'old-lobby'
    Put "$proxy/AstralRecordProxy.jar" 'old-proxy'
    Put "$extensions/AstralRecordGeyserExtension.jar" 'old-extension'
    Put "$proxy/other.jar" 'other-plugin'
    Put "$proxy/AstralRecordProxy/config.yml" 'private-config'
    $config=@{schemaVersion=1;servers=@();distributions=@();networkPlugins=@{sourceDirectory=$output;destinations=@(
        @{artifact='AstralRecordLobby.jar';deployDirectory=$lobby},
        @{artifact='AstralRecordProxy.jar';deployDirectory=$proxy},
        @{artifact='AstralRecordGeyserExtension.jar';deployDirectory=$extensions}
    )}}
    $plan=@(Get-MaintenancePlan $config)
    Assert ($plan.Count -eq 3) 'Expected all three outputs.'
    Assert ($plan[1].root -eq $plan[2].root) 'Proxy and its extension must share the server lock.'
    $run=Join-Path $fixture 'run'; New-Item -ItemType Directory -Path $run | Out-Null
    Invoke-MaintenanceDeploy $config $run
    Assert ((Get-Content -Raw -LiteralPath "$lobby/AstralRecordLobby.jar") -eq 'new-AstralRecordLobby.jar') 'Lobby copy failed.'
    Assert ((Get-Content -Raw -LiteralPath "$proxy/AstralRecordProxy.jar") -eq 'new-AstralRecordProxy.jar') 'Proxy copy failed.'
    Assert ((Get-Content -Raw -LiteralPath "$extensions/AstralRecordGeyserExtension.jar") -eq 'new-AstralRecordGeyserExtension.jar') 'Extension copy failed.'
    Assert ((Get-Content -Raw -LiteralPath "$proxy/other.jar") -eq 'other-plugin') 'Unrelated JAR changed.'
    Assert ((Get-Content -Raw -LiteralPath "$proxy/AstralRecordProxy/config.yml") -eq 'private-config') 'Private config changed.'
    Restore-MaintenanceDeployment $run
    Assert ((Get-Content -Raw -LiteralPath "$extensions/AstralRecordGeyserExtension.jar") -eq 'old-extension') 'Extension restore failed.'
    $config.networkPlugins.destinations[1].deployDirectory=''
    Assert (@(Get-MaintenancePlan $config).Count -eq 2) 'Blank destination must be skipped.'
    $config.networkPlugins.destinations[2].deployDirectory=$lobby
    $rejected=$false
    try { Get-MaintenancePlan $config | Out-Null } catch { $rejected=$true }
    Assert $rejected 'Extension must not be placed as a normal Paper plugin.'
    $otherExtensions=Join-Path $proxy 'OtherPlugin/extensions'
    New-Item -ItemType Directory -Path $otherExtensions -Force | Out-Null
    $config.networkPlugins.destinations[2].deployDirectory=$otherExtensions
    $rejected=$false
    try { Get-MaintenancePlan $config | Out-Null } catch { $rejected=$_.Exception.Message -match 'Geyser-Velocity' }
    Assert $rejected 'Extension must not be placed under another plugin.'
    foreach ($destination in $config.networkPlugins.destinations) { $destination.deployDirectory='' }
    $config.networkPlugins.sourceDirectory=''
    Assert (@(Get-MaintenancePlan $config).Count -eq 0) 'Unconfigured network distribution must not require local outputs.'
    $config.networkPlugins.destinations[0].deployDirectory=$lobby
    $config.networkPlugins.sourceDirectory=Join-Path $fixture 'missing-output'
    $rejected=$false
    try { Get-MaintenancePlan $config | Out-Null } catch { $rejected=$true }
    Assert $rejected 'Configured missing source must fail without building.'
    Write-Host "PASS: three existing network JARs, correct destinations, no build, blank skip, validation and restore. Evidence: $fixture"
} finally { Stop-Transcript | Out-Null }
