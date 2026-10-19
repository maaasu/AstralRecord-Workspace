#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [ValidateSet('Validate','Hosts','Servers')][string]$Phase = 'Validate',
    [string]$ExpectedPublicOrigin,
    [string]$ReceiptPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Require-Fields([System.Collections.IDictionary]$Value, [string[]]$Required, [string[]]$Optional, [string]$Where) {
    if ($null -eq $Value) { throw "$Where must be an object." }
    foreach ($name in $Required) { if (-not $Value.Contains($name) -or $null -eq $Value[$name]) { throw "$Where.$name is required." } }
    foreach ($name in @($Value.Keys)) { if ($name -cnotin ($Required + $Optional)) { throw "$Where.$name is unsupported." } }
}
function Require-Guid([object]$Value, [string]$Where) {
    $parsed = [guid]::Empty
    if ($Value -isnot [string] -or -not [guid]::TryParse($Value, [ref]$parsed) -or $parsed -eq [guid]::Empty) { throw "$Where must be a nonempty GUID." }
    return $parsed.ToString('D')
}
function Require-Key([object]$Value, [string]$Where) {
    if ($Value -isnot [string] -or $Value -cnotmatch '^[a-z0-9][a-z0-9-]{0,47}$') { throw "$Where must be a Control key." }
    return $Value
}
function Require-Text([object]$Value, [string]$Where, [int]$Maximum = 128) {
    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $Maximum -or $Value -match '[\x00-\x1f]') { throw "$Where is invalid." }
    return $Value
}
function Require-LocalPath([object]$Value, [string]$Where) {
    if ($Value -isnot [string] -or $Value -cnotmatch '^[A-Za-z]:\\' -or $Value.Contains('..') -or $Value -match '[\x00-\x1f]') { throw "$Where must be a local absolute path." }
    return [IO.Path]::TrimEndingDirectorySeparator([IO.Path]::GetFullPath($Value))
}
function Require-RelativePath([object]$Value, [string]$Where) {
    if ($Value -isnot [string] -or $Value.Length -gt 512 -or $Value -cnotmatch '^[^\\/:<>"|?*\x00-\x1f]+(/[^\\/:<>"|?*\x00-\x1f]+)*$') { throw "$Where must be a Control relative path." }
    foreach ($part in $Value.Split('/')) { if ($part -in @('.','..') -or $part.EndsWith('.') -or $part.EndsWith(' ')) { throw "$Where is invalid." } }
    return $Value
}
function Same-Path([string]$Left, [string]$Right) {
    return [string]::Equals((Require-LocalPath $Left 'path'), (Require-LocalPath $Right 'path'), [StringComparison]::OrdinalIgnoreCase)
}
function Normalize-Origin([object]$Value, [string]$Where) {
    $uri = $null
    if ($Value -isnot [string] -or -not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or $uri.AbsolutePath -cne '/' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw "$Where must be a fixed HTTPS origin." }
    return $uri.GetLeftPart([UriPartial]::Authority)
}
function Read-Plan([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath
    $plan = Get-Content -Raw -Encoding UTF8 -LiteralPath $resolved | ConvertFrom-Json -AsHashtable -Depth 30
    Require-Fields $plan @('schemaVersion','publicOrigin','hosts','servers') @() 'plan'
    if (($plan.schemaVersion -isnot [int] -and $plan.schemaVersion -isnot [long]) -or $plan.schemaVersion -ne 1) { throw 'Unsupported plan schemaVersion.' }
    $normalizedOrigin = Normalize-Origin $plan.publicOrigin 'publicOrigin'
    if ($plan.publicOrigin -cne $normalizedOrigin) { throw 'publicOrigin must be a normalized HTTPS origin.' }
    if ($plan.hosts -isnot [array] -or $plan.hosts.Count -ne 2 -or $plan.servers -isnot [array] -or $plan.servers.Count -ne 5) { throw 'The plan requires exactly two hosts and five servers.' }
    $hostKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $serverKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $serverIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($hostSpec in $plan.hosts) {
        Require-Fields $hostSpec @('hostKey','displayName','managedRoots','executables') @() 'host'
        $key = Require-Key $hostSpec.hostKey 'hostKey'
        if (-not $hostKeys.Add($key)) { throw "Duplicate hostKey $key." }
        $null = Require-Text $hostSpec.displayName 'host.displayName'
        if ($hostSpec.managedRoots -isnot [array] -or $hostSpec.managedRoots.Count -lt 1 -or $hostSpec.executables -isnot [array] -or $hostSpec.executables.Count -lt 1) { throw "Host $key requires roots and executables." }
        $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($root in $hostSpec.managedRoots) {
            Require-Fields $root @('id','path') @() 'managedRoot'
            if (-not $ids.Add((Require-Guid $root.id 'managedRoot.id'))) { throw "Duplicate resource ID on $key." }
            $null = Require-LocalPath $root.path 'managedRoot.path'
        }
        foreach ($exe in $hostSpec.executables) {
            Require-Fields $exe @('id','path','kind') @() 'executable'
            if (-not $ids.Add((Require-Guid $exe.id 'executable.id'))) { throw "Duplicate resource ID on $key." }
            $null = Require-LocalPath $exe.path 'executable.path'
            if ($exe.kind -cnotin @('Java','Velocity')) { throw 'Only Java and Velocity executable kinds are supported by this plan.' }
        }
    }
    $ports = @{}
    foreach ($server in $plan.servers) {
        Require-Fields $server @('serverId','hostKey','serverKey','displayName','kind','managedRootId','executableId','serverDirectory','argumentList','listenPort','stopCommand','startDelaySeconds','stopTimeoutSeconds') @('environmentId','runtimeServerId') 'server'
        if (-not $server.Contains('runtimeServerId')) { throw 'server.runtimeServerId is required (null for Velocity).' }
        if (-not $server.Contains('environmentId')) { $server.environmentId = $null }
        $id = Require-Guid $server.serverId 'server.serverId'
        if (-not $serverIds.Add($id)) { throw "Duplicate serverId $id." }
        $key = Require-Key $server.serverKey 'server.serverKey'
        if (-not $serverKeys.Add($key)) { throw "Duplicate serverKey $key." }
        $hostKey = Require-Key $server.hostKey 'server.hostKey'
        $hostSpec = @($plan.hosts | Where-Object hostKey -CEQ $hostKey)
        if ($hostSpec.Count -ne 1) { throw "Unknown hostKey $hostKey." }
        $null = Require-Text $server.displayName 'server.displayName'
        if ($null -eq $server.runtimeServerId) {
            if ($server.kind -cne 'Velocity') { throw "Java server $key requires runtimeServerId." }
        } else { $null = Require-Text $server.runtimeServerId 'server.runtimeServerId' }
        $null = Require-Text $server.stopCommand 'server.stopCommand' 4096
        $null = Require-RelativePath $server.serverDirectory 'server.serverDirectory'
        if ($server.kind -cnotin @('Java','Velocity')) { throw 'Only Java and Velocity server kinds are supported.' }
        if ($server.environmentId) { $null = Require-Guid $server.environmentId 'server.environmentId' }
        $rootId = Require-Guid $server.managedRootId 'server.managedRootId'
        $exeId = Require-Guid $server.executableId 'server.executableId'
        if (@($hostSpec[0].managedRoots | Where-Object id -EQ $rootId).Count -ne 1 -or @($hostSpec[0].executables | Where-Object { $_.id -eq $exeId -and $_.kind -ceq $server.kind }).Count -ne 1) { throw "Server $key references an unplanned resource." }
        if ($server.argumentList -isnot [array] -or $server.argumentList.Count -gt 128 -or @($server.argumentList | Where-Object { $_ -isnot [string] -or $_.Length -gt 4096 -or $_.Contains([char]0) }).Count) { throw "Server $key has invalid argumentList." }
        if (($server.listenPort -isnot [int] -and $server.listenPort -isnot [long]) -or $server.listenPort -lt 1 -or $server.listenPort -gt 65535) { throw "Server $key has invalid listenPort." }
        if (($server.startDelaySeconds -isnot [int] -and $server.startDelaySeconds -isnot [long]) -or $server.startDelaySeconds -lt 0 -or $server.startDelaySeconds -gt 900 -or ($server.stopTimeoutSeconds -isnot [int] -and $server.stopTimeoutSeconds -isnot [long]) -or $server.stopTimeoutSeconds -lt 10 -or $server.stopTimeoutSeconds -gt 600) { throw "Server $key has invalid timing." }
        if (-not $ports.ContainsKey($hostKey)) { $ports[$hostKey] = [Collections.Generic.HashSet[int]]::new() }
        if (-not $ports[$hostKey].Add($server.listenPort)) { throw "Duplicate listenPort on $hostKey." }
    }
    if (@($plan.servers | Where-Object kind -CEQ 'Velocity').Count -ne 1 -or @($plan.servers | Where-Object kind -CEQ 'Java').Count -ne 4) { throw 'The plan requires four Java servers and one Velocity proxy.' }
    return $plan
}

function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
}
function Request([Net.Http.HttpClient]$Client, [string]$Method, [string]$Path, [object]$Body = $null, [string]$Csrf = '', [guid]$Key = [guid]::Empty) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Path)
    try {
        if ($Method -eq 'POST') { $request.Headers.Add('Origin', $Client.BaseAddress.GetLeftPart([UriPartial]::Authority)) }
        if ($Csrf) { $request.Headers.Add('X-CSRF-TOKEN', $Csrf) }
        if ($Key -ne [guid]::Empty) { $request.Headers.Add('Idempotency-Key', $Key.ToString('D')); $request.Headers.TryAddWithoutValidation('If-None-Match', '*') | Out-Null }
        if ($Path -in @('/Login','/Mfa/Verify')) {
            if ($Body -isnot [System.Collections.IDictionary]) { throw 'Login form body is invalid.' }
            $fields = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
            foreach ($name in $Body.Keys) { $fields.Add([string]$name, [string]$Body[$name]) }
            $request.Content = [Net.Http.FormUrlEncodedContent]::new($fields)
        } elseif ($null -ne $Body) {
            $json = ConvertTo-Json -InputObject $Body -Depth 30 -Compress
            $request.Content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        try { return [pscustomobject]@{ Status = [int]$response.StatusCode; Location = $response.Headers.Location; Text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } }
        finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Require-Status($Response, [int]$Expected, [string]$Step) {
    if ($Response.Status -ne $Expected) {
        $code = ''
        try { $code = (ConvertFrom-Json -InputObject $Response.Text -AsHashtable).errorCode } catch { }
        throw "$Step failed: HTTP $($Response.Status) $code"
    }
}
function Form-Token([string]$Html) {
    $match = [regex]::Match($Html, 'name="__RequestVerificationToken"[^>]*value="([^"]+)"')
    if (-not $match.Success) { throw 'Antiforgery token is unavailable.' }
    return [Net.WebUtility]::HtmlDecode($match.Groups[1].Value)
}
function Read-Json([object]$Response) { return ConvertFrom-Json -InputObject $Response.Text -AsHashtable -Depth 30 }
function New-ExpectedServerConfig([System.Collections.IDictionary]$Server, [string]$HostId) {
    return [ordered]@{
        schemaVersion = 1; serverId = $Server.serverId; hostId = $HostId; revision = 1; kind = $Server.kind
        managedRootId = $Server.managedRootId; serverDirectory = $Server.serverDirectory; executableId = $Server.executableId
        argumentList = @($Server.argumentList); stopCommand = $Server.stopCommand; listenPort = $Server.listenPort
        autoStart = $false; startDelaySeconds = $Server.startDelaySeconds; startupTimeoutSeconds = 900; stopTimeoutSeconds = $Server.stopTimeoutSeconds
        restartOnCrash = $false; restartDelaySeconds = 60; restartMaxCount = 3; restartWindowSeconds = 600
        ignoredExitCodes = @(0); dependsOnServerIds = @(); dependencyReadiness = 'Running'; logRetentionDays = 30
    }
}
function Assert-ExistingServer([System.Collections.IDictionary]$Existing, [System.Collections.IDictionary]$Server, [string]$HostId) {
    $config = ConvertFrom-Json -InputObject $Existing.desiredConfigJson -AsHashtable -Depth 30
    $expected = New-ExpectedServerConfig $Server $HostId
    if ($config.Count -ne $expected.Count) { throw "Existing server $($Server.serverKey) config fields differ." }
    foreach ($name in $expected.Keys) {
        if ((ConvertTo-Json -InputObject $config[$name] -Compress -Depth 10) -cne (ConvertTo-Json -InputObject $expected[$name] -Compress -Depth 10)) { throw "Existing server $($Server.serverKey) config differs at $name." }
    }
    $plannedEnvironmentId = if ($Server.Contains('environmentId')) { $Server.environmentId } else { $null }
    if ($Existing.hostId -ne $HostId -or $Existing.displayName -cne $Server.displayName -or $Existing.runtimeServerId -cne $Server.runtimeServerId -or $Existing.environmentId -ne $plannedEnvironmentId) { throw "Existing server $($Server.serverKey) metadata conflicts." }
}

$plan = Read-Plan $PlanPath
if ($Phase -eq 'Validate') { Write-Host 'Plan validated: two hosts, four Java servers, one Velocity inventory entry.'; return }
if ([string]::IsNullOrWhiteSpace($ExpectedPublicOrigin)) { throw 'Hosts and Servers phases require -ExpectedPublicOrigin.' }
$expectedOrigin = Normalize-Origin $ExpectedPublicOrigin 'ExpectedPublicOrigin'
if (-not [string]::Equals($expectedOrigin, (Normalize-Origin $plan.publicOrigin 'publicOrigin'), [StringComparison]::Ordinal)) { throw 'ExpectedPublicOrigin does not match the plan publicOrigin.' }
if ($Phase -eq 'Hosts' -and [string]::IsNullOrWhiteSpace($ReceiptPath)) { throw 'Hosts phase requires -ReceiptPath.' }
if ($ReceiptPath -and (-not [IO.Path]::IsPathFullyQualified($ReceiptPath) -or [IO.Path]::GetFullPath($ReceiptPath) -eq [IO.Path]::GetFullPath($PlanPath))) { throw 'ReceiptPath must be a separate absolute path.' }
Write-Host "Control destination: $expectedOrigin"

$cookies = [Net.CookieContainer]::new()
$handler = [Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = $cookies
$handler.UseCookies = $true
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [uri]$plan.publicOrigin
$client.Timeout = [TimeSpan]::FromSeconds(30)
try {
    $loginPage = Request $client GET '/Login'
    Require-Status $loginPage 200 'Login page'
    $loginToken = Form-Token $loginPage.Text
    $userName = Read-Host 'Control owner name'
    $password = Read-Secret 'Control owner password'
    try { $login = Request $client POST '/Login' ([ordered]@{ UserName = $userName; Password = $password; __RequestVerificationToken = $loginToken }) }
    finally { $password = $null }
    Require-Status $login 302 'Password authentication'
    if ($login.Location.OriginalString -notmatch '^/Mfa/Verify(?:\?|$)') { throw 'MFA enrollment or an unexpected login route requires the normal browser UI.' }
    $mfaPage = Request $client GET '/Mfa/Verify'
    Require-Status $mfaPage 200 'MFA page'
    $mfaToken = Form-Token $mfaPage.Text
    $code = Read-Secret 'Current TOTP code'
    try { $mfa = Request $client POST '/Mfa/Verify' ([ordered]@{ Code = $code; __RequestVerificationToken = $mfaToken }) }
    finally { $code = $null }
    Require-Status $mfa 302 'MFA authentication'
    if ($mfa.Location.OriginalString -notmatch '^/(?:\?|$)') { throw 'MFA did not complete the owner session.' }
    $hostPage = Request $client GET '/Hosts'
    Require-Status $hostPage 200 'Owner session'
    $csrf = Form-Token $hostPage.Text
    $hostsResponse = Request $client GET '/api/control/v1/hosts'
    Require-Status $hostsResponse 200 'List hosts'
    $existingHosts = @(Read-Json $hostsResponse)

    if ($Phase -eq 'Hosts') {
        foreach ($hostSpec in $plan.hosts) {
            $sameKey = @($existingHosts | Where-Object hostKey -CEQ $hostSpec.hostKey)
            if ($sameKey.Count -gt 1 -or ($sameKey.Count -eq 1 -and ($sameKey[0].displayName -cne $hostSpec.displayName -or -not $sameKey[0].enabled))) { throw "Existing host $($hostSpec.hostKey) conflicts with the plan." }
        }
        foreach ($hostSpec in $plan.hosts) {
            $sameKey = @($existingHosts | Where-Object hostKey -CEQ $hostSpec.hostKey)
            if ($sameKey.Count -gt 1) { throw "Duplicate remote hostKey $($hostSpec.hostKey)." }
            if ($sameKey.Count -eq 1) {
                if ($sameKey[0].displayName -cne $hostSpec.displayName -or -not $sameKey[0].enabled) { throw "Existing host $($hostSpec.hostKey) conflicts with the plan." }
                Write-Host "Host exists: $($hostSpec.hostKey) $($sameKey[0].hostId)"
                continue
            }
            $body = [ordered]@{ hostKey = $hostSpec.hostKey; displayName = $hostSpec.displayName }
            $created = Request $client POST '/api/control/v1/hosts' $body $csrf ([guid]::NewGuid())
            Require-Status $created 201 "Create host $($hostSpec.hostKey)"
            $row = Read-Json $created
            Write-Host "Host created: $($hostSpec.hostKey) $($row.hostId)"
        }
        $current = Request $client GET '/api/control/v1/hosts'
        Require-Status $current 200 'Refresh hosts'
        $currentHosts = @(Read-Json $current)
        $receiptHosts = @(
            foreach ($hostSpec in $plan.hosts) {
                $row = @($currentHosts | Where-Object hostKey -CEQ $hostSpec.hostKey)
                if ($row.Count -ne 1 -or -not $row[0].enabled) { throw "Host receipt cannot resolve $($hostSpec.hostKey)." }
                [ordered]@{ hostKey = $hostSpec.hostKey; hostId = $row[0].hostId }
            }
        )
        $receipt = [ordered]@{ schemaVersion = 1; publicOrigin = $plan.publicOrigin; hosts = $receiptHosts }
        $receiptFull = [IO.Path]::GetFullPath($ReceiptPath)
        $receiptDir = [IO.Path]::GetDirectoryName($receiptFull)
        if (-not [IO.Directory]::Exists($receiptDir)) { throw "Receipt directory does not exist: $receiptDir" }
        $receiptJson = ConvertTo-Json -InputObject $receipt -Depth 5
        if ([IO.File]::Exists($receiptFull)) {
            $existingReceipt = Get-Content -Raw -Encoding UTF8 -LiteralPath $receiptFull | ConvertFrom-Json -AsHashtable
            if ((ConvertTo-Json -InputObject $existingReceipt -Compress -Depth 5) -cne (ConvertTo-Json -InputObject $receipt -Compress -Depth 5)) { throw 'Existing host receipt differs; inspect before replacing.' }
        } else {
            $temporary = Join-Path $receiptDir ('.' + [IO.Path]::GetFileName($receiptFull) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
            try { [IO.File]::WriteAllText($temporary, $receiptJson, [Text.UTF8Encoding]::new($false)); [IO.File]::Move($temporary, $receiptFull) }
            finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
        }
        Write-Host "Nonsecret host receipt: $receiptFull"
        Write-Host 'Issue each Agent token in /Hosts and enter it locally with Agent --configure. Then configure and start Agent/Runner before -Phase Servers.'
        return
    }

    $serversResponse = Request $client GET '/api/control/v1/servers'
    Require-Status $serversResponse 200 'List servers'
    $existingServers = @(Read-Json $serversResponse)
    $hostMap = @{}
    foreach ($hostSpec in $plan.hosts) {
        $sameKey = @($existingHosts | Where-Object hostKey -CEQ $hostSpec.hostKey)
        if ($sameKey.Count -ne 1 -or -not $sameKey[0].enabled -or $sameKey[0].connectionState -cne 'Online') { throw "Host $($hostSpec.hostKey) must be registered, enabled and Online." }
        $hostMap[$hostSpec.hostKey] = $sameKey[0].hostId
        $resourcesResponse = Request $client GET "/api/control/v1/hosts/$($sameKey[0].hostId)/resources"
        Require-Status $resourcesResponse 200 "Resources for $($hostSpec.hostKey)"
        $report = Read-Json $resourcesResponse
        if (-not $report.lastSeenAtUtc) { throw "Host $($hostSpec.hostKey) has no Agent report." }
        foreach ($root in $hostSpec.managedRoots) {
            $found = @($report.resources.roots | Where-Object id -EQ $root.id)
            if ($found.Count -ne 1 -or $found[0].backupOnly -or -not (Same-Path $found[0].path $root.path)) { throw "Managed root mismatch on $($hostSpec.hostKey): $($root.id)." }
        }
        foreach ($exe in $hostSpec.executables) {
            $found = @($report.resources.executables | Where-Object id -EQ $exe.id)
            if ($found.Count -ne 1 -or $found[0].kind -cne $exe.kind -or -not (Same-Path $found[0].path $exe.path)) { throw "Executable mismatch on $($hostSpec.hostKey): $($exe.id)." }
        }
    }
    foreach ($server in $plan.servers) {
        $sameKey = @($existingServers | Where-Object serverKey -CEQ $server.serverKey)
        $sameId = @($existingServers | Where-Object serverId -EQ $server.serverId)
        if ($sameKey.Count -gt 1 -or $sameId.Count -gt 1 -or ($sameKey.Count -ne $sameId.Count) -or ($sameKey.Count -eq 1 -and $sameKey[0].serverId -ne $sameId[0].serverId)) { throw "Existing server identity conflicts for $($server.serverKey)." }
        if ($sameKey.Count -eq 1) { Assert-ExistingServer $sameKey[0] $server ([string]$hostMap[$server.hostKey]) }
    }
    foreach ($server in $plan.servers) {
        $sameKey = @($existingServers | Where-Object serverKey -CEQ $server.serverKey)
        $sameId = @($existingServers | Where-Object serverId -EQ $server.serverId)
        if ($sameKey.Count -gt 1 -or $sameId.Count -gt 1 -or ($sameKey.Count -ne $sameId.Count) -or ($sameKey.Count -eq 1 -and $sameKey[0].serverId -ne $sameId[0].serverId)) { throw "Existing server identity conflicts for $($server.serverKey)." }
        if ($sameKey.Count -eq 1) {
            $existing = $sameKey[0]
            Assert-ExistingServer $existing $server ([string]$hostMap[$server.hostKey])
            Write-Host "Server exists: $($server.serverKey) $($server.serverId)"
            continue
        }
        $config = New-ExpectedServerConfig $server ([string]$hostMap[$server.hostKey])
        $body = [ordered]@{ hostId = $hostMap[$server.hostKey]; serverKey = $server.serverKey; displayName = $server.displayName; environmentId = $(if ($server.environmentId) { $server.environmentId } else { $null }); runtimeServerId = $server.runtimeServerId; config = $config }
        $created = Request $client POST '/api/control/v1/servers' $body $csrf ([guid]::NewGuid())
        Require-Status $created 201 "Create server $($server.serverKey)"
        Write-Host "Server created: $($server.serverKey) $($server.serverId)"
    }
    Write-Host 'Velocity proxy is inventory-only in P3. Control process operations and reliable external-process state are unsupported.'
} finally { $client.Dispose(); $handler.Dispose() }
