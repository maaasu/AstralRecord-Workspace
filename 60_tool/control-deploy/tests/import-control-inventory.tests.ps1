#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot '..\Import-ControlInventory.ps1'
$readmePath = Join-Path $PSScriptRoot '..\IMPORT-CONTROL-INVENTORY.md'
$readme = Get-Content -Raw -Encoding UTF8 -LiteralPath $readmePath
$match = [regex]::Match($readme, '(?s)```json\s*(.*?)\s*```')
if (-not $match.Success) { throw 'The documented plan example is missing.' }
$sourcePlan = ConvertFrom-Json -InputObject $match.Groups[1].Value -AsHashtable -Depth 30
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('control-inventory-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $planPath = Join-Path $testRoot 'plan.json'
    function Write-Plan($value) {
        [IO.File]::WriteAllText($planPath, (ConvertTo-Json -InputObject $value -Depth 30), [Text.UTF8Encoding]::new($false))
    }
    function Assert-Rejected([string]$Case, [scriptblock]$Change) {
        $plan = ConvertFrom-Json -InputObject ($match.Groups[1].Value) -AsHashtable -Depth 30
        & $Change $plan
        Write-Plan $plan
        $rejected = $false
        try { & $scriptPath -PlanPath $planPath -Phase Validate | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw "$Case was accepted unexpectedly." }
    }
    Write-Plan $sourcePlan
    & $scriptPath -PlanPath $planPath -Phase Validate | Out-Null
    Assert-Rejected 'duplicate server ID' { param($p) $p.servers[1].serverId = $p.servers[0].serverId }
    Assert-Rejected 'non-HTTPS origin' { param($p) $p.publicOrigin = 'http://control.example.test' }
    Assert-Rejected 'unknown resource ID' { param($p) $p.servers[0].managedRootId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb' }
    Assert-Rejected 'path traversal' { param($p) $p.servers[0].serverDirectory = '../10_Dev' }
    Assert-Rejected 'duplicate port' { param($p) $p.servers[2].listenPort = $p.servers[1].listenPort }
    Assert-Rejected 'unsupported extra capability' { param($p) $p.servers[1].autoStart = $true }
    Write-Plan $sourcePlan
    . $scriptPath -PlanPath $planPath -Phase Validate | Out-Null
    if ((Form-Token '<input name="__RequestVerificationToken" type="hidden" value="fixture-csrf" />') -cne 'fixture-csrf') { throw 'Antiforgery form token fixture failed.' }
    $server = $sourcePlan.servers[0]
    $hostId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
    $savedConfig = New-ExpectedServerConfig $server $hostId
    $existing = [ordered]@{ desiredConfigJson = (ConvertTo-Json -InputObject $savedConfig -Compress -Depth 10); hostId = $hostId; displayName = $server.displayName; runtimeServerId = $server.runtimeServerId; environmentId = $null }
    Assert-ExistingServer $existing $server $hostId
    $savedConfig.argumentList = @('-jar','different.jar')
    $existing.desiredConfigJson = ConvertTo-Json -InputObject $savedConfig -Compress -Depth 10
    $rejected = $false
    try { Assert-ExistingServer $existing $server $hostId } catch { $rejected = $true }
    if (-not $rejected) { throw 'Conflicting existing server was accepted.' }
    $savedConfig = New-ExpectedServerConfig $server $hostId
    $null = $savedConfig.Remove('logRetentionDays')
    $existing.desiredConfigJson = ConvertTo-Json -InputObject $savedConfig -Compress -Depth 10
    $rejected = $false
    try { Assert-ExistingServer $existing $server $hostId } catch { $rejected = $true }
    if (-not $rejected) { throw 'Existing server missing a config field was accepted.' }
    $savedConfig = New-ExpectedServerConfig $server $hostId
    $savedConfig.startupTimeoutSeconds = 1200
    $existing.desiredConfigJson = ConvertTo-Json -InputObject $savedConfig -Compress -Depth 10
    $rejected = $false
    try { Assert-ExistingServer $existing $server $hostId } catch { $rejected = $true }
    if (-not $rejected) { throw 'Existing server changed startup timeout was accepted.' }
    Write-Plan $sourcePlan
    $rejected = $false
    try { & $scriptPath -PlanPath $planPath -Phase Hosts -ReceiptPath (Join-Path $testRoot 'hosts.json') -ExpectedPublicOrigin 'https://wrong.example.test' | Out-Null }
    catch { $rejected = $_.Exception.Message -like '*does not match*' }
    if (-not $rejected) { throw 'Mismatched public origin was not rejected before network/authentication.' }
    $rejected = $false
    try { & $scriptPath -PlanPath $planPath -Phase Hosts -ReceiptPath (Join-Path $testRoot 'hosts.json') | Out-Null }
    catch { $rejected = $_.Exception.Message -like '*ExpectedPublicOrigin*' }
    if (-not $rejected) { throw 'Missing expected public origin was not rejected before network/authentication.' }
    Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
public sealed class InventoryFakeHandler : HttpMessageHandler
{
    public string Method = "";
    public string Path = "";
    public string Origin = "";
    public string Csrf = "";
    public string Key = "";
    public string IfNoneMatch = "";
    public string Body = "";
    public string ContentType = "";
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        Method = request.Method.Method;
        Path = request.RequestUri!.AbsolutePath;
        System.Collections.Generic.IEnumerable<string> values;
        if (request.Headers.TryGetValues("Origin", out values)) Origin = string.Join("", values);
        if (request.Headers.TryGetValues("X-CSRF-TOKEN", out values)) Csrf = string.Join("", values);
        if (request.Headers.TryGetValues("Idempotency-Key", out values)) Key = string.Join("", values);
        if (request.Headers.TryGetValues("If-None-Match", out values)) IfNoneMatch = string.Join("", values);
        Body = request.Content is null ? "" : request.Content.ReadAsStringAsync(cancellationToken).GetAwaiter().GetResult();
        ContentType = request.Content?.Headers.ContentType?.MediaType ?? "";
        var isGet = request.Method == HttpMethod.Get;
        return Task.FromResult(new HttpResponseMessage(isGet ? HttpStatusCode.OK : HttpStatusCode.Created) {
            Content = new StringContent(isGet ? "<input name=\"__RequestVerificationToken\" type=\"hidden\" value=\"fixture-csrf\" />" : "{}")
        });
    }
}
'@
    $fake = [InventoryFakeHandler]::new()
    $client = [Net.Http.HttpClient]::new($fake)
    try {
        $client.BaseAddress = [uri]'https://control.example.test'
        foreach ($path in @('/Login', '/Mfa/Verify')) {
            $page = Request $client GET $path
            if ($page.Status -ne 200 -or $fake.Method -cne 'GET' -or $fake.Path -cne $path -or $fake.Body -cne '' -or $fake.ContentType -cne '' -or (Form-Token $page.Text) -cne 'fixture-csrf') { throw 'HTTP authentication page GET contract fixture failed.' }
        }
        $key = [guid]::NewGuid()
        $response = Request $client POST '/api/control/v1/hosts' ([ordered]@{ hostKey = 'dev-01'; displayName = 'DEV-01' }) 'csrf-fixture' $key
        if ($response.Status -ne 201 -or $fake.Method -cne 'POST' -or $fake.Path -cne '/api/control/v1/hosts' -or $fake.Origin -cne 'https://control.example.test' -or $fake.Csrf -cne 'csrf-fixture' -or $fake.Key -cne $key.ToString('D') -or $fake.IfNoneMatch -cne '*' -or $fake.Body -notmatch '"hostKey":"dev-01"' -or $fake.ContentType -cne 'application/json') { throw 'HTTP owner JSON create contract fixture failed.' }
        $null = Request $client POST '/Login' ([ordered]@{ UserName = 'fixture'; Password = 'fixture'; __RequestVerificationToken = 'fixture' })
        if ($fake.Path -cne '/Login' -or $fake.Body -notmatch 'UserName=fixture&Password=fixture' -or $fake.Origin -cne 'https://control.example.test' -or $fake.ContentType -cne 'application/x-www-form-urlencoded') { throw 'HTTP owner login form contract fixture failed.' }
        $null = Request $client POST '/Mfa/Verify' ([ordered]@{ Code = '123456'; __RequestVerificationToken = 'fixture' })
        if ($fake.Path -cne '/Mfa/Verify' -or $fake.Body -cne 'Code=123456&__RequestVerificationToken=fixture' -or $fake.Origin -cne 'https://control.example.test' -or $fake.ContentType -cne 'application/x-www-form-urlencoded') { throw 'HTTP owner MFA form contract fixture failed.' }
    } finally { $client.Dispose(); $fake.Dispose() }
    Write-Host 'Control inventory plan fixture checks passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolved.StartsWith($temporary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test cleanup escaped temporary directory.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
