#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Repair-ControlBedrockFirewall.ps1')

function Assert-Test([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Expect-Rejection([scriptblock]$Action, [string]$Message) {
    try { & $Action | Out-Null; throw "Expected rejection: $Message" }
    catch { if ($_.Exception.Message -like 'Expected rejection:*') { throw } }
}
function New-TestRule([string]$Name, [string]$Program, [string]$Port, [string]$Group = '') {
    [pscustomobject]@{
        Name = $Name; Group = $Group; Enabled = 'True'; Direction = 'Inbound'; Action = 'Allow'
        Profile = 'Public'; PolicySource = 'Local'; Program = $Program; Protocol = 'UDP'
        LocalPorts = @($Port); RemotePorts = @('Any'); LocalAddresses = @('Any')
        RemoteAddresses = @('Any'); Service = 'Any'; LocalUser = 'Any'; RemoteUser = 'Any'
        RemoteMachine = 'Any'; Authentication = 'NotRequired'; Encryption = 'NotRequired'
        OverrideBlockRules = 'False'
    }
}

$fixtureParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$fixtureLeaf = 'bedrock-firewall-test-' + [guid]::NewGuid().ToString('N')
$fixture = Join-Path $fixtureParent $fixtureLeaf
$java = Join-Path $fixture 'java.exe'
$script:hostName = 'MC-01'
$script:admin = $true
$script:owners = @()
$script:oldRule = $null
$script:managedRules = [Collections.Generic.List[object]]::new()
$script:addCalls = 0
$script:removeCalls = 0
$script:failAdd = $false
$script:failVerify = $false
$script:injectCollision = $false
$script:createdName = $null

function Get-BedrockComputerName { $script:hostName }
function Test-BedrockAdministrator { $script:admin }
function Test-BedrockJavaFile([string]$Path) { Test-Path -LiteralPath $Path -PathType Leaf }
function Get-BedrockEndpointOwners { $script:owners }
function Get-BedrockRules([string]$Name, [string]$Group = '') {
    if ($Name -ieq $script:OldJavaRuleName) { if ($null -ne $script:oldRule) { $script:oldRule }; return }
    foreach ($rule in $script:managedRules) {
        if (($Group -and $rule.Group -ieq $Group) -or (!$Group -and $rule.Name -ieq $Name)) {
            if ($script:failVerify -and $rule.Name -ceq $script:createdName) { $rule.Profile = 'Private' }
            $rule
        }
    }
}
function Add-BedrockRule([string]$Name, [string]$Java) {
    $script:addCalls++
    if ($script:injectCollision) {
        $script:managedRules.Add((New-TestRule ($script:BedrockRuleGroup + '.other-run') $Java '19132' $script:BedrockRuleGroup))
    }
    $script:createdName = $Name
    $script:managedRules.Add((New-TestRule $Name $Java '19132' $script:BedrockRuleGroup))
    if ($script:failAdd) { throw 'Injected creation failure after rule creation' }
    return $Name
}
function Remove-BedrockRule([string]$Name) {
    Assert-Test ($Name -ceq $script:createdName) 'Rollback targeted another rule.'
    $script:removeCalls++
    $match = @($script:managedRules | Where-Object { $_.Name -ceq $Name })
    Assert-Test ($match.Count -eq 1) 'Rollback identity was ambiguous.'
    $null = $script:managedRules.Remove($match[0])
}
function Reset-TestState {
    $script:hostName = 'MC-01'; $script:admin = $true
    $script:owners = @([pscustomobject]@{ ProcessId = 100; Executable = $java })
    $script:oldRule = New-TestRule $script:OldJavaRuleName $script:OldJavaPath 'Any'
    $script:managedRules.Clear(); $script:addCalls = 0; $script:removeCalls = 0
    $script:failAdd = $false; $script:failVerify = $false; $script:injectCollision = $false
    $script:createdName = $null
}

try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    [IO.File]::WriteAllText($java, 'fixture')
    Reset-TestState
    $created = Invoke-ControlBedrockFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($created.status -eq 'Created' -and $created.localPort -eq 19132 -and $script:addCalls -eq 1 -and
        $created.ruleName -match '^AstralRecordControl\.MC-01\.Bedrock\.Java25\.UDP19132\.[0-9a-f]{32}$') 'Scoped creation failed.'
    $again = Invoke-ControlBedrockFirewallRepair -Java $java -Confirm:$false | ConvertFrom-Json
    Assert-Test ($again.status -eq 'Existing' -and $script:addCalls -eq 1) 'Idempotent replay failed.'

    Reset-TestState; $script:hostName = 'DEV-01'
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Wrong host'
    Reset-TestState; $script:admin = $false
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Non-admin'
    Reset-TestState
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java (Join-Path $fixture 'missing-java.exe') } 'Missing executable'
    Reset-TestState; $script:owners[0].Executable = 'C:\Other\java.exe'
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Wrong UDP owner'
    Reset-TestState; $script:owners = @()
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'No UDP owner'
    Reset-TestState; $script:owners += [pscustomobject]@{ ProcessId = 101; Executable = 'C:\Other\java.exe' }
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Mixed UDP owners'
    Reset-TestState; $script:oldRule.Profile = 'Private'
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Changed diagnosis rule'
    Reset-TestState; $script:oldRule = $null
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Missing diagnosis rule'
    foreach ($property in @('LocalUser', 'RemoteUser', 'RemoteMachine', 'Authentication', 'Encryption', 'OverrideBlockRules')) {
        Reset-TestState
        $script:oldRule.$property = if ($property -eq 'OverrideBlockRules') { 'True' } else { 'Restricted' }
        Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } "Changed security condition: $property"
        Assert-Test ($script:addCalls -eq 0) "Changed $property caused a rule creation."
    }
    Reset-TestState
    $script:managedRules.Add((New-TestRule ($script:BedrockRuleGroup + '.existing') $java 'Any' $script:BedrockRuleGroup))
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java } 'Managed rule collision'
    Assert-Test ($script:removeCalls -eq 0) 'Existing managed rule was removed.'

    Reset-TestState
    $preview = @(Invoke-ControlBedrockFirewallRepair -Java $java -WhatIf | Where-Object { $_ -is [string] -and $_.StartsWith('{') })[-1] | ConvertFrom-Json
    Assert-Test ($preview.status -eq 'WhatIf' -and $script:addCalls -eq 0 -and $script:removeCalls -eq 0) 'WhatIf changed a rule.'
    Reset-TestState; $script:failAdd = $true
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java -Confirm:$false } 'Partial creation failure'
    Assert-Test ($script:removeCalls -eq 0 -and $script:managedRules.Count -eq 1) 'Unknown creation identity was deleted.'
    Reset-TestState; $script:failVerify = $true
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java -Confirm:$false } 'Post-creation verification failure'
    Assert-Test ($script:removeCalls -eq 1 -and $script:managedRules.Count -eq 0) 'Verification failure rollback failed.'
    Reset-TestState; $script:injectCollision = $true
    Expect-Rejection { Invoke-ControlBedrockFirewallRepair -Java $java -Confirm:$false } 'Concurrent managed rule creation'
    Assert-Test ($script:removeCalls -eq 1 -and $script:managedRules.Count -eq 1 -and
        $script:managedRules[0].Name -like '*.other-run') 'Rollback removed another run rule.'
    Write-Output 'Bedrock firewall fixture passed: scoped creation, replay, preflight, security conditions, collision, WhatIf, owned rollback.'
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $target = [IO.Path]::GetFullPath($fixture)
        if ([IO.Path]::GetDirectoryName($target) -ine $fixtureParent -or
            [IO.Path]::GetFileName($target) -cne $fixtureLeaf) {
            throw 'Fixture cleanup path escaped the generated temporary directory.'
        }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
