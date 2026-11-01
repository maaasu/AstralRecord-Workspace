#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Java = 'C:\Program Files\AstralRecordControl\Java\jdk-25.0.4.1+1-00c847d804f4\bin\java.exe'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:NetherNetRuleGroup = 'AstralRecordControl.MC-01.NetherNet.TCP19132'

function Get-NetherNetComputerName { [Environment]::MachineName }
function Test-NetherNetAdministrator {
    if (!$IsWindows) { return $false }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}
function Test-NetherNetJavaFile([string]$Path) { Test-Path -LiteralPath $Path -PathType Leaf }
function Get-NetherNetLocalIPs { Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop | Select-Object -ExpandProperty IPAddress }
function Test-NetherNetIPv4Listener([string]$Address, [int]$Port) {
    $client = [Net.Sockets.TcpClient]::new([Net.Sockets.AddressFamily]::InterNetwork)
    try {
        $pending = $client.ConnectAsync([Net.IPAddress]::Parse($Address), $Port)
        $pending.Wait(2000) -and $client.Connected
    } catch { $false }
    finally { $client.Dispose() }
}
function Get-NetherNetListeners {
    $endpoints = @(
        Get-NetTCPConnection -LocalPort 19132 -State Listen -ErrorAction Stop | ForEach-Object {
            [pscustomobject]@{ Protocol = 'TCP'; Address = $_.LocalAddress; ProcessId = $_.OwningProcess }
        }
        Get-NetUDPEndpoint -LocalPort 19132 -ErrorAction Stop | ForEach-Object {
            [pscustomobject]@{ Protocol = 'UDP'; Address = $_.LocalAddress; ProcessId = $_.OwningProcess }
        }
    )
    foreach ($endpoint in $endpoints) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($endpoint.ProcessId)" -ErrorAction Stop
        [pscustomobject]@{
            Protocol = $endpoint.Protocol; Address = $endpoint.Address
            ProcessId = $endpoint.ProcessId; Executable = $process.ExecutablePath
        }
    }
}
function Get-NetherNetRules([string]$Group = '', [string]$Name = '', [string]$UdpJava = '') {
    $rules = @(Get-NetFirewallRule -PolicyStore ActiveStore -ErrorAction Stop | Where-Object {
        if ($Name) { $_.Name -ieq $Name }
        elseif ($UdpJava) { $_.Enabled -in @('True', '1') -and $_.Direction -ieq 'Inbound' -and $_.Action -ieq 'Allow' -and $_.Profile -ieq 'Public' }
        else { $_.Group -ieq $Group }
    })
    foreach ($rule in $rules) {
        $application = $rule | Get-NetFirewallApplicationFilter -ErrorAction Stop
        $port = $rule | Get-NetFirewallPortFilter -ErrorAction Stop
        if ($UdpJava -and ($application.Program -ine $UdpJava -or $port.Protocol -notin @('UDP', '17') -or
            @($port.LocalPort).Count -ne 1 -or [string](@($port.LocalPort)[0]) -ine '19132')) { continue }
        $address = $rule | Get-NetFirewallAddressFilter -ErrorAction Stop
        $service = $rule | Get-NetFirewallServiceFilter -ErrorAction Stop
        $security = $rule | Get-NetFirewallSecurityFilter -ErrorAction Stop
        $interface = $rule | Get-NetFirewallInterfaceFilter -ErrorAction Stop
        $interfaceType = $rule | Get-NetFirewallInterfaceTypeFilter -ErrorAction Stop
        [pscustomobject]@{
            Name = [string]$rule.Name; Group = [string]$rule.Group; Enabled = [string]$rule.Enabled
            Direction = [string]$rule.Direction; Action = [string]$rule.Action; Profile = [string]$rule.Profile
            PolicySource = [string]$rule.PolicyStoreSourceType; EdgeTraversal = [string]$rule.EdgeTraversalPolicy
            Program = [string]$application.Program; Package = [string]$application.Package
            Protocol = [string]$port.Protocol; LocalPorts = @($port.LocalPort); RemotePorts = @($port.RemotePort)
            LocalAddresses = @($address.LocalAddress); RemoteAddresses = @($address.RemoteAddress)
            InterfaceAliases = @($interface.InterfaceAlias); InterfaceTypes = @($interfaceType.InterfaceType)
            Service = [string]$service.Service; LocalUser = [string]$security.LocalUser
            RemoteUser = [string]$security.RemoteUser; RemoteMachine = [string]$security.RemoteMachine
            Authentication = [string]$security.Authentication; Encryption = [string]$security.Encryption
            OverrideBlockRules = [string]$security.OverrideBlockRules
        }
    }
}
function Assert-NetherNetRule($Rule, [string]$JavaPath, [string]$Protocol) {
    $protocolValues = if ($Protocol -eq 'TCP') { @('TCP', '6') } else { @('UDP', '17') }
    if ($Rule.Enabled -notin @('True', '1') -or $Rule.Direction -ine 'Inbound' -or
        $Rule.Action -ine 'Allow' -or $Rule.Profile -ine 'Public' -or $Rule.PolicySource -ine 'Local' -or
        $Rule.Program -ine $JavaPath -or $Rule.Protocol -notin $protocolValues -or
        $Rule.EdgeTraversal -ine 'Block' -or ([string]$Rule.Package -notin @('', 'Any')) -or
        @($Rule.LocalPorts).Count -ne 1 -or [string]$Rule.LocalPorts[0] -ine '19132' -or
        $Rule.Service -ine 'Any' -or $Rule.LocalUser -ine 'Any' -or $Rule.RemoteUser -ine 'Any' -or
        $Rule.RemoteMachine -ine 'Any' -or $Rule.Authentication -ine 'NotRequired' -or
        $Rule.Encryption -ine 'NotRequired' -or $Rule.OverrideBlockRules -notin @('False', '0')) {
        throw 'Firewall scope differs from the approved Public Java 19132 rule.'
    }
    foreach ($property in @('RemotePorts', 'LocalAddresses', 'RemoteAddresses', 'InterfaceAliases', 'InterfaceTypes')) {
        if (@($Rule.$property).Count -ne 1 -or [string]$Rule.$property[0] -ine 'Any') {
            throw "Restricted or ambiguous firewall condition requires manual review: $property"
        }
    }
}
function Add-NetherNetRule([string]$Name, [string]$JavaPath) {
    $created = @(New-NetFirewallRule -Name $Name -Group $script:NetherNetRuleGroup -DisplayName 'AstralRecord MC-01 NetherNet TCP 19132' `
        -PolicyStore PersistentStore -Enabled True -Direction Inbound -Action Allow -Profile Public `
        -Program $JavaPath -Protocol TCP -LocalPort 19132 -RemotePort Any -LocalAddress Any -RemoteAddress Any `
        -InterfaceAlias Any -InterfaceType Any -EdgeTraversalPolicy Block -Service Any -LocalUser Any -RemoteUser Any `
        -RemoteMachine Any -Authentication NotRequired -Encryption NotRequired -OverrideBlockRules:$false -ErrorAction Stop)
    if ($created.Count -ne 1 -or [string]$created[0].Name -cne $Name) { throw 'Firewall creation returned an unexpected identity.' }
    [string]$created[0].Name
}
function Remove-NetherNetRule([string]$Name) { Remove-NetFirewallRule -Name $Name -PolicyStore PersistentStore -ErrorAction Stop }
function Invoke-ControlNetherNetFirewallRepair {
    [CmdletBinding(SupportsShouldProcess)]
    param([string]$Java)
    $listeners = @()
    try {
    if ((Get-NetherNetComputerName) -ine 'MC-01') { throw 'This repair is limited to MC-01.' }
    if (!(Test-NetherNetAdministrator)) { throw 'Run an elevated PowerShell session on MC-01.' }
    if ($Java -cnotmatch '^[A-Za-z]:\\' -or [IO.Path]::GetFileName($Java) -ine 'java.exe' -or
        !(Test-NetherNetJavaFile $Java)) { throw 'Java must be an existing absolute local java.exe path.' }
    $javaPath = [IO.Path]::GetFullPath($Java)
    $localIPs = @(Get-NetherNetLocalIPs)
    if ($localIPs -notcontains '192.168.0.89') { throw 'The diagnosed MC-01 local IPv4 address 192.168.0.89 is missing.' }
    $listeners = @(Get-NetherNetListeners)
    $ownerIds = @($listeners | Select-Object -ExpandProperty ProcessId -Unique)
    if ($ownerIds.Count -ne 1 -or $ownerIds[0] -le 0 -or
        @($listeners | Where-Object { !$_.Executable -or [IO.Path]::GetFullPath($_.Executable) -ine $javaPath }).Count -gt 0) {
        throw 'TCP and UDP 19132 must belong exclusively to the same specified Java process.'
    }
    foreach ($protocol in @('TCP', 'UDP')) {
        $directIPv4 = @($listeners | Where-Object { $_.Protocol -eq $protocol -and $_.Address -in @('0.0.0.0', '192.168.0.89') }).Count -gt 0
        # Java's dual-stack TCP wildcard may be reported as ::. Verify IPv4 instead of inferring it.
        $dualStackIPv4 = !$directIPv4 -and $protocol -eq 'TCP' -and
            @($listeners | Where-Object { $_.Protocol -eq 'TCP' -and $_.Address -eq '::' }).Count -gt 0 -and
            (Test-NetherNetIPv4Listener -Address '192.168.0.89' -Port 19132)
        # This repair adds TCP only. UDP IPv4 reachability remains unverified.
        $udpWildcard = $protocol -eq 'UDP' -and @($listeners | Where-Object { $_.Protocol -eq 'UDP' -and $_.Address -eq '::' }).Count -gt 0
        if (!$directIPv4 -and !$dualStackIPv4 -and !$udpWildcard) {
            throw "$protocol 19132 has no listener reachable at 192.168.0.89."
        }
    }
    $udpRules = @(Get-NetherNetRules -UdpJava $javaPath)
    if ($udpRules.Count -ne 1) { throw 'An existing Public UDP 19132 allow rule for this Java was not found exactly once.' }
    Assert-NetherNetRule $udpRules[0] $javaPath 'UDP'
    $existing = @(Get-NetherNetRules -Group $script:NetherNetRuleGroup)
    if ($existing.Count -gt 1) { throw 'Multiple managed NetherNet rules were found.' }
    $status = 'Existing'; $ruleName = $null
    if ($existing.Count -eq 1) {
        Assert-NetherNetRule $existing[0] $javaPath 'TCP'
        $ruleName = $existing[0].Name
    } elseif (!$PSCmdlet.ShouldProcess($script:NetherNetRuleGroup, "Allow Public inbound TCP 19132 only for $javaPath")) {
        $status = 'WhatIf'
    } else {
        $proposedName = $script:NetherNetRuleGroup + '.' + [guid]::NewGuid().ToString('N')
        $createdName = $null
        try {
            $createdName = Add-NetherNetRule $proposedName $javaPath
            if ($createdName -cne $proposedName) { throw 'Unexpected created rule identity.' }
            $created = @(Get-NetherNetRules -Name $createdName)
            $managed = @(Get-NetherNetRules -Group $script:NetherNetRuleGroup)
            if ($created.Count -ne 1 -or $managed.Count -ne 1 -or $created[0].Group -ine $script:NetherNetRuleGroup) {
                throw 'Created rule was not found exclusively in its managed group.'
            }
            Assert-NetherNetRule $created[0] $javaPath 'TCP'
            $status = 'Created'; $ruleName = $createdName
        } catch {
            $failure = $_
            if ($createdName -and $createdName -ceq $proposedName) {
                try {
                    $rollback = @(Get-NetherNetRules -Name $createdName)
                    if ($rollback.Count -ne 1 -or $rollback[0].Group -ine $script:NetherNetRuleGroup) {
                        throw 'Could not confirm newly created rule identity for rollback.'
                    }
                    Remove-NetherNetRule $createdName
                } catch { throw "Firewall repair and rollback failed: $($failure.Exception.Message); $($_.Exception.Message)" }
            }
            throw "Firewall repair failed: $($failure.Exception.Message)"
        }
    }
    [ordered]@{
        status = $status; ruleName = $ruleName; Java = $javaPath; listenerPID = $ownerIds[0]; localIP = '192.168.0.89'
        ruleScope = [ordered]@{
            group = $script:NetherNetRuleGroup; profile = 'Public'; direction = 'Inbound'; action = 'Allow'
            protocol = 'TCP'; localPort = 19132; remotePort = 'Any'; localAddress = 'Any'; remoteAddress = 'Any'
            interfaceAlias = 'Any'; interfaceType = 'Any'; edgeTraversal = 'Block'; authentication = 'NotRequired'
            sourceUdpRuleName = $udpRules[0].Name
        }
        routerVerification = 'NotVerified'
        udpIPv4Verification = 'NotVerified'
        routerNotice = 'Router configuration was not verified. TCP 19132 -> 192.168.0.89:19132 forwarding is required; retain existing UDP 19132 forwarding.'
    } | ConvertTo-Json -Depth 4 -Compress
    } catch {
        $addresses = (@($listeners | ForEach-Object { "$($_.Protocol)=$($_.Address) (PID $($_.ProcessId))" }) -join '; ')
        if (!$addresses) { $addresses = '<none collected>' }
        throw "$($_.Exception.Message) Listeners19132: $addresses"
    }
}
if ($MyInvocation.InvocationName -ne '.') { Invoke-ControlNetherNetFirewallRepair -Java $Java }
