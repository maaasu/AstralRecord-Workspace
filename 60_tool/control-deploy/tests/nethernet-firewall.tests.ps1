#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Repair-ControlNetherNetFirewall.ps1')
$script:realGetNetherNetRules = ${function:Get-NetherNetRules}
$script:realGetNetherNetListeners = ${function:Get-NetherNetListeners}

function Assert-Test([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Expect-Rejection([scriptblock]$Action, [string]$Message, [string]$ExpectedMessage = '') {
    try { & $Action | Out-Null; throw "Expected rejection: $Message" }
    catch {
        if ($_.Exception.Message -like 'Expected rejection:*') { throw }
        if ($ExpectedMessage) { Assert-Test ($_.Exception.Message -like $ExpectedMessage) "Missing rejection diagnosis: $Message" }
    }
}
function New-TestRule([string]$Name, [string]$Protocol, [string]$Group) {
    [pscustomobject]@{
        Name = $Name; Group = $Group; Enabled = 'True'; Direction = 'Inbound'; Action = 'Allow'
        Profile = 'Public'; PolicySource = 'Local'; Program = $java; Package = ''; Protocol = $Protocol
        LocalPorts = @('19132'); RemotePorts = @('Any'); LocalAddresses = @('Any'); RemoteAddresses = @('Any')
        InterfaceAliases = @('Any'); InterfaceTypes = @('Any'); EdgeTraversal = 'Block'
        Service = 'Any'; LocalUser = 'Any'; RemoteUser = 'Any'; RemoteMachine = 'Any'
        Authentication = 'NotRequired'; Encryption = 'NotRequired'; OverrideBlockRules = 'False'
    }
}
$fixtureParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$fixtureLeaf = 'nethernet-firewall-test-' + [guid]::NewGuid().ToString('N')
$fixture = Join-Path $fixtureParent $fixtureLeaf
$java = Join-Path $fixture 'java.exe'
$script:managed = [Collections.Generic.List[object]]::new()
function Get-NetherNetComputerName { $script:hostName }
function Test-NetherNetAdministrator { $script:admin }
function Get-NetherNetLocalIPs { $script:localIPs }
function Get-NetherNetListeners {
    if ($script:useRawAdapters) { & $script:realGetNetherNetListeners } else { $script:listeners }
}
function Get-NetTCPConnection {
    param($LocalPort, $State, $ErrorAction)
    $script:listeners | Where-Object { $_.Protocol -eq 'TCP' } | ForEach-Object {
        [pscustomobject]@{ LocalAddress = $_.Address; OwningProcess = $_.ProcessId }
    }
}
function Get-NetUDPEndpoint {
    param($LocalPort, $ErrorAction)
    $script:listeners | Where-Object { $_.Protocol -eq 'UDP' } | ForEach-Object {
        [pscustomobject]@{ LocalAddress = $_.Address; OwningProcess = $_.ProcessId }
    }
}
function Get-CimInstance {
    param($ClassName, $Filter, $ErrorAction)
    $ownerId = [int]($Filter -replace '^ProcessId=', '')
    [pscustomobject]@{ ExecutablePath = ($script:listeners | Where-Object { $_.ProcessId -eq $ownerId } | Select-Object -First 1).Executable }
}
function Test-NetherNetIPv4Listener([string]$Address, [int]$Port) {
    $script:probeCalls++
    Assert-Test ($Address -eq '192.168.0.89' -and $Port -eq 19132) 'Probe used an unexpected destination.'
    $script:probeSuccess
}
# Exercise the real read adapter separately with scalar CIM filter values.
function Get-NetFirewallRule {
    param($PolicyStore, $ErrorAction)
    $script:adapterRule
    if ($script:useRawAdapters) {
        foreach ($rule in $script:managed) {
            [pscustomobject]@{
                Name = $rule.Name; Group = $rule.Group; Enabled = $rule.Enabled; Direction = $rule.Direction
                Action = $rule.Action; Profile = $rule.Profile; PolicyStoreSourceType = $rule.PolicySource
                EdgeTraversalPolicy = $rule.EdgeTraversal; Protocol = $rule.Protocol
                LocalPort = [string](@($rule.LocalPorts)[0])
            }
        }
    }
}
function Get-NetFirewallApplicationFilter { param($ErrorAction) process { [pscustomobject]@{ Program = $java; Package = $null } } }
function Get-NetFirewallPortFilter {
    param([Parameter(ValueFromPipeline)]$InputObject)
    process {
        if ($script:useRawAdapters) {
            [pscustomobject]@{ Protocol = $InputObject.Protocol; LocalPort = $InputObject.LocalPort; RemotePort = 'Any' }
        } else { $script:adapterPort }
    }
}
function Get-NetFirewallAddressFilter { param($ErrorAction) process { [pscustomobject]@{ LocalAddress = 'Any'; RemoteAddress = 'Any' } } }
function Get-NetFirewallServiceFilter { param($ErrorAction) process { [pscustomobject]@{ Service = 'Any' } } }
function Get-NetFirewallSecurityFilter {
    param($ErrorAction)
    process {
        [pscustomobject]@{
            LocalUser = 'Any'; RemoteUser = 'Any'; RemoteMachine = 'Any'; Authentication = 'NotRequired'
            Encryption = 'NotRequired'; OverrideBlockRules = 'False'
        }
    }
}
function Get-NetFirewallInterfaceFilter { param($ErrorAction) process { [pscustomobject]@{ InterfaceAlias = 'Any' } } }
function Get-NetFirewallInterfaceTypeFilter { param($ErrorAction) process { [pscustomobject]@{ InterfaceType = 'Any' } } }
function Get-NetherNetRules([string]$Group = '', [string]$Name = '', [string]$UdpJava = '') {
    if ($script:useRawAdapters) { & $script:realGetNetherNetRules -Group $Group -Name $Name -UdpJava $UdpJava; return }
    if ($UdpJava) { if ($null -ne $script:udpRule) { $script:udpRule }; return }
    foreach ($rule in $script:managed) {
        if (($Name -and $rule.Name -eq $Name) -or (!$Name -and $rule.Group -eq $Group)) {
            if ($script:failVerify -and $rule.Name -ceq $script:createdName) { $rule.Profile = 'Private' }
            $rule
        }
    }
}
# Keep Add-NetherNetRule real: inspect the actual New-NetFirewallRule arguments without accessing Windows Firewall.
function New-NetFirewallRule {
    param($Name, $Group, $DisplayName, $PolicyStore, $Enabled, $Direction, $Action, $Profile,
        $Program, $Protocol, $LocalPort, $RemotePort, $LocalAddress, $RemoteAddress, $InterfaceAlias,
        $InterfaceType, $EdgeTraversalPolicy, $Service, $LocalUser, $RemoteUser, $RemoteMachine,
        $Authentication, $Encryption, $OverrideBlockRules, $ErrorAction)
    $script:addCalls++
    Assert-Test ($Protocol -eq 'TCP' -and $LocalPort -eq 19132 -and $Profile -eq 'Public' -and
        $Program -eq $java -and $PolicyStore -eq 'PersistentStore' -and $Direction -eq 'Inbound' -and
        $Action -eq 'Allow' -and $Enabled -eq 'True' -and $Group -eq $script:NetherNetRuleGroup) 'Creation widened the port/program/profile.'
    foreach ($value in @($RemotePort, $LocalAddress, $RemoteAddress, $InterfaceAlias, $InterfaceType,
        $Service, $LocalUser, $RemoteUser, $RemoteMachine)) { Assert-Test ($value -eq 'Any') 'Unexpected firewall filter.' }
    Assert-Test ($EdgeTraversalPolicy -eq 'Block' -and $Authentication -eq 'NotRequired' -and
        $Encryption -eq 'NotRequired' -and !$OverrideBlockRules) 'Creation widened security conditions.'
    $script:createdName = $Name
    $script:managed.Add((New-TestRule $Name 'TCP' $Group))
    if ($script:injectCollision) { $script:managed.Add((New-TestRule 'other-run' 'TCP' $Group)) }
    if ($script:failAdd) { throw 'Injected partial creation failure' }
    [pscustomobject]@{ Name = $Name }
}
function Remove-NetherNetRule([string]$Name) {
    Assert-Test ($Name -ceq $script:createdName) 'Rollback targeted a rule this run did not create.'
    $matches = @($script:managed | Where-Object { $_.Name -ceq $Name })
    Assert-Test ($matches.Count -eq 1) 'Ambiguous rollback identity.'
    $script:removeCalls++
    $null = $script:managed.Remove($matches[0])
}
function Reset-TestState {
    $script:hostName = 'MC-01'; $script:admin = $true; $script:localIPs = @('127.0.0.1', '192.168.0.89')
    $script:listeners = @(
        [pscustomobject]@{ Protocol = 'TCP'; Address = '0.0.0.0'; ProcessId = 100; Executable = $java }
        [pscustomobject]@{ Protocol = 'UDP'; Address = '0.0.0.0'; ProcessId = 100; Executable = $java }
    )
    $script:udpRule = New-TestRule 'existing-udp-rule' 'UDP' 'ExistingUdpAllowGroup'
    $script:managed.Clear(); $script:addCalls = 0; $script:removeCalls = 0; $script:createdName = $null
    $script:failVerify = $false; $script:failAdd = $false; $script:injectCollision = $false
    $script:probeCalls = 0; $script:probeSuccess = $false
    $script:useRawAdapters = $false
}
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    [IO.File]::WriteAllText($java, 'fixture')
    Reset-TestState
    $script:adapterRule = [pscustomobject]@{
        Name = 'real-shape-udp'; Group = ''; Enabled = 'True'; Direction = 'Inbound'; Action = 'Allow'
        Profile = 'Public'; PolicyStoreSourceType = 'Local'; EdgeTraversalPolicy = 'Block'
        Protocol = 'UDP'; LocalPort = '19132'
    }
    $script:adapterPort = [pscustomobject]@{ Protocol = 'UDP'; LocalPort = '19132'; RemotePort = 'Any' }
    $adapted = @(& $script:realGetNetherNetRules -UdpJava $java)
    Assert-Test ($adapted.Count -eq 1 -and $adapted[0].LocalPorts[0] -eq '19132' -and $adapted[0].Package -eq '') 'Scalar UDP port/null Package was not adapted.'
    Assert-NetherNetRule $adapted[0] $java 'UDP'
    $script:adapterPort.LocalPort = '19133'
    Assert-Test (@(& $script:realGetNetherNetRules -UdpJava $java).Count -eq 0) 'Wrong scalar UDP port was selected.'
    $script:adapterPort.LocalPort = @('19132', '19133')
    Assert-Test (@(& $script:realGetNetherNetRules -UdpJava $java).Count -eq 0) 'Multiple UDP ports were selected.'
    # Full repair through both real adapters: scalar LocalPort, null Package, TCP/UDP ::.
    Reset-TestState; $script:useRawAdapters = $true; $script:probeSuccess = $true
    $script:listeners | ForEach-Object { $_.Address = '::' }
    $rawCreated = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($rawCreated.status -eq 'Created' -and $rawCreated.udpIPv4Verification -eq 'NotVerified' -and
        $rawCreated.listenerPID -eq 100 -and $script:addCalls -eq 1 -and $script:probeCalls -eq 1) 'Full raw dual-wildcard flow failed.'
    $rawAgain = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($rawAgain.status -eq 'Existing' -and $script:addCalls -eq 1) 'Raw dual-wildcard replay added a rule.'
    Reset-TestState; $script:useRawAdapters = $true
    $script:listeners | ForEach-Object { $_.Address = '::' }
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Dual wildcard TCP probe failure' '*Listeners19132: TCP=::*UDP=::*'
    Assert-Test ($script:addCalls -eq 0) 'Failed raw TCP probe changed Firewall.'
    Reset-TestState; $script:useRawAdapters = $true; $script:probeSuccess = $true
    $script:listeners | ForEach-Object { $_.Address = '::' }; $script:listeners[1].ProcessId = 101
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Raw dual wildcard different PID'
    Assert-Test ($script:addCalls -eq 0 -and $script:probeCalls -eq 0) 'Different raw PID bypassed preflight.'
    Reset-TestState; $script:useRawAdapters = $true; $script:probeSuccess = $true
    $script:listeners | ForEach-Object { $_.Address = '::'; $_.Executable = 'C:\Other\java.exe' }
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Raw dual wildcard different Java'
    Assert-Test ($script:addCalls -eq 0) 'Different raw Java bypassed preflight.'
    Reset-TestState
    $created = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($created.status -eq 'Created' -and $script:addCalls -eq 1 -and $created.listenerPID -eq 100 -and
        $created.localIP -eq '192.168.0.89' -and $created.Java -eq $java -and $created.ruleScope.protocol -eq 'TCP' -and
        $created.routerNotice -like '*TCP 19132 -> 192.168.0.89:19132*' -and $created.routerVerification -eq 'NotVerified' -and
        $created.ruleName -match '^AstralRecordControl\.MC-01\.NetherNet\.TCP19132\.[0-9a-f]{32}$') 'Creation/result failed.'
    $again = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($again.status -eq 'Existing' -and $again.ruleName -eq $created.ruleName -and $script:addCalls -eq 1) 'Replay was not idempotent.'
    Assert-Test ($script:udpRule.Name -eq 'existing-udp-rule' -and $script:udpRule.Protocol -eq 'UDP') 'Existing UDP changed.'
    Assert-Test ($script:probeCalls -eq 0) 'Direct IPv4 listener unexpectedly needed a probe.'
    Reset-TestState; $script:listeners[0].Address = '::'; $script:probeSuccess = $true
    $dual = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($dual.status -eq 'Created' -and $script:probeCalls -eq 1 -and $script:addCalls -eq 1) 'Proven dual-stack TCP listener was rejected.'
    Reset-TestState; $script:listeners[0].Address = '::'
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'IPv6-only or failed IPv4 probe' '*Listeners19132: TCP=::*UDP=0.0.0.0*'
    Assert-Test ($script:probeCalls -eq 1 -and $script:addCalls -eq 0) 'Failed dual-stack probe changed Firewall.'
    foreach ($address in @('127.0.0.1', '::1', '192.168.0.90')) {
        Reset-TestState; $script:listeners[0].Address = $address; $script:probeSuccess = $true
        Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Non-wildcard TCP address' '*Listeners19132:*'
        Assert-Test ($script:probeCalls -eq 0 -and $script:addCalls -eq 0) 'Non-wildcard TCP address used the dual-stack exception.'
    }

    foreach ($package in @($null, '', 'Any')) {
        Reset-TestState; $script:udpRule.Package = $package
        $packageResult = Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
        Assert-Test ($packageResult.status -eq 'Created') 'An unconstrained Win32 Package was rejected.'
    }
    Reset-TestState; $script:udpRule.Package = 'Microsoft.MinecraftUWP_8wekyb3d8bbwe'
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Package-specific source rule'
    Assert-Test ($script:addCalls -eq 0) 'Package-specific source created a broader rule.'
    Reset-TestState; $script:managed.Add((New-TestRule 'preexisting-package' 'TCP' $script:NetherNetRuleGroup))
    $script:managed[0].Package = 'Microsoft.MinecraftUWP_8wekyb3d8bbwe'
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Package-specific existing TCP rule'
    Assert-Test ($script:removeCalls -eq 0) 'Package-specific existing rule was removed.'

    Reset-TestState; $script:hostName = 'DEV-01'
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Wrong host'
    Reset-TestState; $script:admin = $false
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Non-admin'
    Reset-TestState
    foreach ($path in @('relative\java.exe', '\\MC-01\mc-01\java.exe', (Join-Path $fixture 'missing\java.exe'))) {
        Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $path } 'Invalid Java path'
    }
    Reset-TestState; $script:localIPs = @('127.0.0.1')
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Missing diagnosed IP'
    Reset-TestState; $script:listeners = @()
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'No listeners'
    foreach ($protocol in @('TCP', 'UDP')) {
        Reset-TestState; $script:listeners = @($script:listeners | Where-Object { $_.Protocol -ne $protocol })
        Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } "Missing $protocol"
        $rejectedAddresses = @('127.0.0.1', '::1', '192.168.0.90')
        if ($protocol -eq 'TCP') { $rejectedAddresses += '::' }
        foreach ($address in $rejectedAddresses) {
            Reset-TestState; ($script:listeners | Where-Object { $_.Protocol -eq $protocol }).Address = $address
            Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } "Unreachable $protocol $address"
        }
    }
    Reset-TestState; $script:listeners[1].ProcessId = 101
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Same Java binary but different PID'
    Reset-TestState; $script:listeners[1].Executable = 'C:\Other\java.exe'
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Different Java executable'
    Reset-TestState; $script:listeners += [pscustomobject]@{ Protocol = 'TCP'; Address = '::'; ProcessId = 101; Executable = $java }
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Additional different listener PID'
    Reset-TestState; $script:udpRule = $null
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Missing source rule'
    foreach ($property in @('Profile', 'PolicySource', 'Program', 'Protocol', 'LocalPorts', 'RemotePorts',
        'LocalAddresses', 'RemoteAddresses', 'InterfaceAliases', 'InterfaceTypes', 'EdgeTraversal', 'Package',
        'Service', 'LocalUser', 'RemoteUser', 'RemoteMachine', 'Authentication', 'Encryption', 'OverrideBlockRules',
        'Enabled', 'Direction', 'Action')) {
        Reset-TestState; $script:udpRule.$property = 'Restricted'
        Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } "Changed source scope: $property"
        Assert-Test ($script:addCalls -eq 0) "Changed $property created a rule."
    }
    Reset-TestState; $script:udpRule.RemoteAddresses = @('Any', 'LocalSubnet')
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Ambiguous source address'
    Reset-TestState; $script:managed.Add((New-TestRule 'preexisting' 'TCP' $script:NetherNetRuleGroup)); $script:managed[0].LocalPorts = @('Any')
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java } 'Invalid existing TCP scope'
    Assert-Test ($script:removeCalls -eq 0) 'Existing TCP rule was removed.'
    Reset-TestState
    $preview = Invoke-ControlNetherNetFirewallRepair -Java $java -WhatIf | ConvertFrom-Json
    Assert-Test ($preview.status -eq 'WhatIf' -and $null -eq $preview.ruleName -and $script:addCalls -eq 0 -and
        $script:removeCalls -eq 0) 'WhatIf changed Firewall.'
    Reset-TestState; $script:failVerify = $true
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false } 'Failed post-create verification'
    Assert-Test ($script:removeCalls -eq 1 -and $script:managed.Count -eq 0) 'Owned rollback failed.'
    Reset-TestState; $script:injectCollision = $true
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false } 'Concurrent rule'
    Assert-Test ($script:removeCalls -eq 1 -and $script:managed.Count -eq 1 -and
        $script:managed[0].Name -eq 'other-run') 'Rollback removed another run rule.'
    Reset-TestState; $script:failAdd = $true
    Expect-Rejection { Invoke-ControlNetherNetFirewallRepair -Java $java -Confirm:$false } 'Unconfirmed partial creation'
    Assert-Test ($script:removeCalls -eq 0 -and $script:managed.Count -eq 1) 'Unconfirmed creation identity was deleted.'
    Write-Output 'NetherNet firewall fixture passed: TCP scope, same PID, reachable listeners, UDP scope, security/interface restrictions, replay, WhatIf, owned rollback.'
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $target = [IO.Path]::GetFullPath($fixture)
        if ([IO.Path]::GetDirectoryName($target) -ine $fixtureParent -or [IO.Path]::GetFileName($target) -cne $fixtureLeaf) {
            throw 'Fixture cleanup escaped the generated temporary directory.'
        }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
