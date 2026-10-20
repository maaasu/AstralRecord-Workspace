Set-StrictMode -Version Latest

function Get-PublicControlFacts {
    param([Collections.IDictionary]$Configuration)
    $result=[ordered]@{}
    foreach ($key in @('hostId','pipeName','runnerPipeName','enableProcessControl','enableFileControl','enableSchedules')) {
        if ($Configuration.Contains($key)) { $result[$key]=$Configuration[$key] }
    }
    foreach ($key in @('serverUrl','controllerUrl')) {
        if (!$Configuration.Contains($key)) { continue }
        $uri=$null
        if ([Uri]::TryCreate([string]$Configuration[$key],[UriKind]::Absolute,[ref]$uri) -and !$uri.UserInfo -and !$uri.Query -and !$uri.Fragment) {
            $result[$key]=$uri.GetLeftPart([UriPartial]::Authority)
        }
    }
    $resources=$Configuration['localResources']
    if ($resources -is [Collections.IDictionary]) {
        $result.localResources=[ordered]@{roots=@();executables=@()}
        foreach ($entry in @($resources['roots'])) {
            if ($null -eq $entry) { continue }
            $result.localResources.roots+=@{id=$entry['id'];path=$entry['path'];backupOnly=($entry['backupOnly'] -eq $true)}
        }
        foreach ($entry in @($resources['executables'])) {
            if ($null -eq $entry) { continue }
            $result.localResources.executables+=@{id=$entry['id'];path=$entry['path'];kind=$entry['kind']}
        }
    }
    return $result
}
Export-ModuleMember -Function Get-PublicControlFacts
