Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ControlOrigin = 'https://control.astralrecord.com'
$script:ControlPipe = 'AstralRecordControl.Runner.v1'
$script:Hosts = @{
    'DEV-01' = @{hostId='0604b62f-c10b-46e8-a0cf-cca70aab6b5f'; rootId='f9c06531-2e7e-40f2-b03a-ee2ee9ebe5aa'; rootPath='C:\AstralRecord-Workspace'}
    'MC-01' = @{hostId='1a61b33d-27fc-4a1a-8a68-4d61953f7229'}
    'APP-01' = @{hostId='4ff81871-2069-4a9a-9c98-43421de3b7e8'; rootId='99a1c3c4-dafc-4fc8-9a8b-b3aad1c1ecee'; rootPath='C:\app-01'}
}
$script:ExistingRoots = @{
    'DEV-01' = @{id='6a3ef4c4-386a-42ef-ac71-32b917de7023';path='C:\server\AstralRecord-Dev'}
    'MC-01' = @{id='5f051f6a-38dd-4bbb-a4ab-4dd0e2d80a7d';path='E:\mc-01'}
}

function Get-ControlCutoverHost([string]$ComputerName) {
    if (!$script:Hosts.ContainsKey($ComputerName)) { throw 'UnsupportedHost' }
    return $script:Hosts[$ComputerName]
}

function Get-ControlCutoverRoot([string]$ComputerName) {
    $hostSpec = Get-ControlCutoverHost $ComputerName
    if (!$hostSpec.ContainsKey('rootId')) { throw 'NoNewRoot' }
    return [ordered]@{id=$hostSpec.rootId;displayName=if ($ComputerName -eq 'APP-01') {'APP distribution'} else {'Repository Filebase source'};path=$hostSpec.rootPath;backupOnly=$false}
}

function Get-ControlCutoverServicePaths([string]$PathName,[string]$Component) {
    if ($Component -notin @('Agent','Runner')) { throw 'UnexpectedComponent' }
    $name="AstralRecordControl.$Component"
    $pattern='^"([^"]+'+[regex]::Escape($name)+'\.exe)"\s+--data-root\s+"([^"]+)"$'
    $match=[regex]::Match($PathName,$pattern)
    if (!$match.Success) { throw 'UnexpectedServicePath' }
    return @{binary=$match.Groups[1].Value;data=$match.Groups[2].Value}
}

function Get-ControlCutoverAppMetadataDirectories {
    return @('C:\app-01','C:\app-01\AstralRecordApi','C:\app-01\AstralRecordWeb',
        'C:\app-01\workspace','C:\app-01\workspace\40_filebase')
}

function New-ControlCutoverMetadataReadRule([string]$ServiceSid) {
    $sid=[Security.Principal.SecurityIdentifier]::new($ServiceSid)
    return [Security.AccessControl.FileSystemAccessRule]::new($sid,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        [Security.AccessControl.InheritanceFlags]::None,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow)
}

function Assert-ControlCutoverResourcesEqual($Agent, $Runner) {
    foreach ($kind in @('roots','executables')) {
        $agentItems=@($Agent['localResources'][$kind]); $runnerItems=@($Runner['localResources'][$kind])
        if ($agentItems.Count -ne $runnerItems.Count) { throw 'ResourceMismatch' }
        foreach ($item in $agentItems) {
            $matching=@($runnerItems | Where-Object { [string]$_['id'] -ieq [string]$item['id'] })
            if ($matching.Count -ne 1 -or [string]$matching[0]['path'] -ine [string]$item['path']) { throw 'ResourceMismatch' }
            if ($kind -eq 'roots' -and [bool]$matching[0]['backupOnly'] -ne [bool]$item['backupOnly']) { throw 'ResourceMismatch' }
            if ($kind -eq 'executables' -and [string]$matching[0]['kind'] -cne [string]$item['kind']) { throw 'ResourceMismatch' }
        }
    }
}

function Assert-ControlCutoverBinding($Agent, $Runner, [string]$ComputerName, [string]$AgentSid) {
    $hostSpec=Get-ControlCutoverHost $ComputerName
    if ([string]$Agent['hostId'] -ine $hostSpec.hostId -or [string]$Runner['hostId'] -ine $hostSpec.hostId -or
        [string]$Agent['serverUrl'] -ine $script:ControlOrigin -or [string]$Agent['runnerPipeName'] -cne $script:ControlPipe -or
        [string]$Runner['pipeName'] -cne $script:ControlPipe -or [string]$Runner['agentSid'] -cne $AgentSid -or
        [string]$Agent['tokenFile'] -cne 'credentials/token.bin') { throw 'HostBindingMismatch' }
    if ($Agent['enableFileControl'] -eq $true -or $Agent['enableSchedules'] -eq $true -or $Runner['enableFileControl'] -eq $true) { throw 'UnsupportedCapability' }
    if ($ComputerName -eq 'APP-01' -and ($Runner['enableProcessControl'] -eq $true -or @($Runner['localResources']['executables']).Count)) { throw 'UnsupportedAppConfiguration' }
    Assert-ControlCutoverResourcesEqual $Agent $Runner
    if ($ComputerName -eq 'APP-01') {
        $wanted=Get-ControlCutoverRoot 'APP-01'
        $roots=@($Agent['localResources']['roots'])
        if ($roots.Count -ne 1 -or [string]$roots[0]['id'] -ine $wanted.id -or
            [string]$roots[0]['path'] -ine $wanted.path -or [bool]$roots[0]['backupOnly']) { throw 'UnexpectedAppResources' }
    } else {
        $wanted=$script:ExistingRoots[$ComputerName]
        $matches=@($Agent['localResources']['roots'] | Where-Object { [string]$_['id'] -ieq $wanted.id -and [string]$_['path'] -ieq $wanted.path -and ![bool]$_['backupOnly'] })
        if ($matches.Count -ne 1 -or $Runner['enableProcessControl'] -ne $true) { throw 'UnexpectedExistingResources' }
    }
}

function Add-ControlCutoverRoot($Configuration, [string]$ComputerName) {
    $wanted=Get-ControlCutoverRoot $ComputerName
    if ($null -eq $Configuration['localResources']) { $Configuration['localResources']=@{roots=@();executables=@()} }
    $resources=$Configuration['localResources']
    if ($null -eq $resources['roots']) { $resources['roots']=@() }
    if ($null -eq $resources['executables']) { $resources['executables']=@() }
    $matches=@($resources['roots'] | Where-Object { [string]$_['id'] -ieq $wanted.id -or [string]$_['path'] -ieq $wanted.path })
    if ($matches.Count -gt 1 -or ($matches.Count -eq 1 -and
        ([string]$matches[0]['id'] -ine $wanted.id -or [string]$matches[0]['path'] -ine $wanted.path -or $matches[0]['backupOnly'] -eq $true))) { throw 'RootConflict' }
    if ($matches.Count -eq 0) { $resources['roots']=@($resources['roots']) + @($wanted) }
    return $Configuration
}

function Assert-ControlCutoverSnapshot($Snapshot) {
    if (!$Snapshot -or !$Snapshot['runnerEpoch'] -or $null -eq $Snapshot['servers'] -or $null -eq $Snapshot['holds']) { throw 'InvalidRunnerSnapshot' }
    if (@($Snapshot['holds']).Count) { throw 'RunnerHoldPresent' }
    foreach ($server in @($Snapshot['servers'])) {
        if (!$server['serverId'] -or $server['processState'] -cne 'Stopped' -or $server['currentSessionId']) { throw 'RunnerServerNotStopped' }
    }
}

function Invoke-ControlCutoverAgentEnrollment {
    param([string]$CliPath,[Guid]$HostId)
    if ($HostId -eq [Guid]::Empty) { throw 'AgentHostMissing' }
    & $CliPath login --server-url $script:ControlOrigin
    if ($LASTEXITCODE -ne 0) { throw 'ControlLoginFailed' }
    & $CliPath agent enroll --host-id $HostId.ToString('D') --expected-server-url $script:ControlOrigin --start-agent false
    if ($LASTEXITCODE -ne 0) { throw 'AgentEnrollmentFailed' }
}

Export-ModuleMember -Function Get-ControlCutoverHost,Get-ControlCutoverRoot,Get-ControlCutoverServicePaths,Get-ControlCutoverAppMetadataDirectories,New-ControlCutoverMetadataReadRule,Assert-ControlCutoverResourcesEqual,Assert-ControlCutoverBinding,Add-ControlCutoverRoot,Assert-ControlCutoverSnapshot,Invoke-ControlCutoverAgentEnrollment
