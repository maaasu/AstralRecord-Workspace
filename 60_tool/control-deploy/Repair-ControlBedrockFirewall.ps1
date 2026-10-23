#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$NewJava = 'C:\Program Files\AstralRecordControl\Java\jdk-25.0.4.1+1-00c847d804f4\bin\java.exe'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:BedrockRuleGroup = 'AstralRecordControl.MC-01.Bedrock.Java25.UDP19132'
$script:OldJavaRuleName = 'UDP Query User{5DAD1F4F-42BA-45AA-BB7E-315097D18BC5}C:\program files\java\jdk-21.0.10\bin\java.exe'
$script:OldJavaPath = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'

function Get-BedrockComputerName { [Environment]::MachineName }
function Test-BedrockAdministrator {
    if (!$IsWindows) { return $false }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}
function Test-BedrockJavaFile([string]$Path) { Test-Path -LiteralPath $Path -PathType Leaf }
function Get-BedrockEndpointOwners {
    $endpoints = @(Get-NetUDPEndpoint -LocalPort 19132 -ErrorAction Stop)
    foreach ($endpoint in $endpoints) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($endpoint.OwningProcess)" -ErrorAction Stop
        [pscustomobject]@{ ProcessId = $endpoint.OwningProcess; Executable = $process.ExecutablePath }
    }
}
function Get-BedrockRules([string]$Name, [string]$Group = '') {
    $rules = @(Get-NetFirewallRule -PolicyStore ActiveStore -ErrorAction Stop | Where-Object {
        if ($Group) { $_.Group -ieq $Group } else { $_.Name -ieq $Name }
    })
    foreach ($rule in $rules) {
        $application = $rule | Get-NetFirewallApplicationFilter -ErrorAction Stop
        $port = $rule | Get-NetFirewallPortFilter -ErrorAction Stop
        $address = $rule | Get-NetFirewallAddressFilter -ErrorAction Stop
        $service = $rule | Get-NetFirewallServiceFilter -ErrorAction Stop
        $security = $rule | Get-NetFirewallSecurityFilter -ErrorAction Stop
        [pscustomobject]@{
            Name = [string]$rule.Name; Group = [string]$rule.Group; Enabled = [string]$rule.Enabled
            Direction = [string]$rule.Direction; Action = [string]$rule.Action
            Profile = [string]$rule.Profile; PolicySource = [string]$rule.PolicyStoreSourceType
            Program = [string]$application.Program; Protocol = [string]$port.Protocol
            LocalPorts = @($port.LocalPort); RemotePorts = @($port.RemotePort)
            LocalAddresses = @($address.LocalAddress); RemoteAddresses = @($address.RemoteAddress)
            Service = [string]$service.Service
            LocalUser = [string]$security.LocalUser; RemoteUser = [string]$security.RemoteUser
            RemoteMachine = [string]$security.RemoteMachine
            Authentication = [string]$security.Authentication; Encryption = [string]$security.Encryption
            OverrideBlockRules = [string]$security.OverrideBlockRules
        }
    }
}
function Add-BedrockRule([string]$Name, [string]$Java) {
    $created = @(New-NetFirewallRule -Name $Name -Group $script:BedrockRuleGroup -DisplayName 'AstralRecord MC-01 Bedrock Java 25 UDP 19132' `
        -PolicyStore PersistentStore -Enabled True -Direction Inbound -Action Allow -Profile Public `
        -Program $Java -Protocol UDP -LocalPort 19132 -RemotePort Any `
        -LocalAddress Any -RemoteAddress Any -Service Any -LocalUser Any -RemoteUser Any -RemoteMachine Any `
        -Authentication NotRequired -Encryption NotRequired -OverrideBlockRules:$false -ErrorAction Stop)
    if ($created.Count -ne 1 -or [string]$created[0].Name -cne $Name) { throw 'Firewall creation did not return the requested rule identity.' }
    return [string]$created[0].Name
}
function Remove-BedrockRule([string]$Name) {
    Remove-NetFirewallRule -Name $Name -PolicyStore PersistentStore -ErrorAction Stop
}
function Assert-BedrockRule($Rule, [string]$Java, [string]$Port) {
    if ($null -eq $Rule -or $Rule.Enabled -notin @('True', '1') -or $Rule.Direction -ine 'Inbound' -or
        $Rule.Action -ine 'Allow' -or $Rule.Profile -ine 'Public' -or $Rule.PolicySource -ine 'Local' -or
        $Rule.Program -ine $Java -or $Rule.Protocol -notin @('UDP', '17') -or
        @($Rule.LocalPorts).Count -ne 1 -or [string]$Rule.LocalPorts[0] -ine $Port -or
        @($Rule.RemotePorts).Count -ne 1 -or [string]$Rule.RemotePorts[0] -ine 'Any' -or
        @($Rule.LocalAddresses).Count -ne 1 -or [string]$Rule.LocalAddresses[0] -ine 'Any' -or
        @($Rule.RemoteAddresses).Count -ne 1 -or [string]$Rule.RemoteAddresses[0] -ine 'Any' -or
        $Rule.Service -ine 'Any' -or $Rule.LocalUser -ine 'Any' -or $Rule.RemoteUser -ine 'Any' -or
        $Rule.RemoteMachine -ine 'Any' -or $Rule.Authentication -ine 'NotRequired' -or
        $Rule.Encryption -ine 'NotRequired' -or $Rule.OverrideBlockRules -notin @('False', '0')) {
        throw 'Firewall rule scope does not match the approved MC-01 diagnosis.'
    }
}
function Invoke-ControlBedrockFirewallRepair {
    [CmdletBinding(SupportsShouldProcess)]
    param([string]$Java)
    if ((Get-BedrockComputerName) -ine 'MC-01') { throw 'This repair is limited to MC-01.' }
    if (!(Test-BedrockAdministrator)) { throw 'Run this script from an elevated PowerShell session on MC-01.' }
    if ($Java -cnotmatch '^[A-Za-z]:\\' -or [IO.Path]::GetFileName($Java) -ine 'java.exe' -or
        !(Test-BedrockJavaFile $Java)) { throw 'The specified Java executable does not exist at an absolute local path.' }
    $javaPath = [IO.Path]::GetFullPath($Java)
    $owners = @(Get-BedrockEndpointOwners)
    if ($owners.Count -eq 0 -or @($owners | Where-Object { !$_.Executable -or [IO.Path]::GetFullPath($_.Executable) -ine $javaPath }).Count -gt 0) {
        throw 'UDP 19132 is not exclusively owned by the specified Java executable.'
    }
    $oldRules = @(Get-BedrockRules $script:OldJavaRuleName)
    if ($oldRules.Count -ne 1) { throw 'The diagnosed Java 21 UDP allow rule was not found exactly once.' }
    Assert-BedrockRule $oldRules[0] $script:OldJavaPath 'Any'

    $existing = @(Get-BedrockRules '' $script:BedrockRuleGroup)
    if ($existing.Count -gt 1) { throw 'Multiple managed Bedrock firewall rules were found.' }
    $status = 'Existing'
    $ruleName = $null
    if ($existing.Count -eq 1) {
        Assert-BedrockRule $existing[0] $javaPath '19132'
        $ruleName = $existing[0].Name
    } elseif (!$PSCmdlet.ShouldProcess($script:BedrockRuleGroup, "Allow inbound Public UDP 19132 only for $javaPath")) {
        $status = 'WhatIf'
    } else {
        $proposedName = $script:BedrockRuleGroup + '.' + [guid]::NewGuid().ToString('N')
        $createdName = $null
        try {
            $createdName = Add-BedrockRule $proposedName $javaPath
            if ($createdName -cne $proposedName) { throw 'Firewall creation returned an unexpected rule identity.' }
            $created = @(Get-BedrockRules $createdName)
            $managed = @(Get-BedrockRules '' $script:BedrockRuleGroup)
            if ($created.Count -ne 1 -or $managed.Count -ne 1 -or $created[0].Group -ine $script:BedrockRuleGroup) {
                throw 'The newly created firewall rule was not found exclusively in its managed group.'
            }
            Assert-BedrockRule $created[0] $javaPath '19132'
            $ruleName = $createdName
            $status = 'Created'
        } catch {
            $failure = $_
            if ($createdName -and $createdName -ceq $proposedName) {
                try {
                    $rollback = @(Get-BedrockRules $createdName)
                    if ($rollback.Count -ne 1 -or $rollback[0].Group -ine $script:BedrockRuleGroup) {
                        throw 'Created rule identity could not be confirmed for rollback.'
                    }
                    Remove-BedrockRule $createdName
                } catch { throw "Firewall repair failed and rollback failed: $($failure.Exception.Message); $($_.Exception.Message)" }
            }
            throw "Firewall repair failed: $($failure.Exception.Message)"
        }
    }
    [ordered]@{
        status = $status; computer = 'MC-01'; ruleName = $ruleName; ruleGroup = $script:BedrockRuleGroup
        program = $javaPath; direction = 'Inbound'; action = 'Allow'; profile = 'Public'
        protocol = 'UDP'; localPort = 19132
    } | ConvertTo-Json -Compress
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-ControlBedrockFirewallRepair -Java $NewJava
}
