param([Parameter(Mandatory)][string]$ApiDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ApiDirectory).Path
$settingsPath = Join-Path $root 'appsettings.json'
$settings = Get-Content -Raw -Encoding UTF8 -LiteralPath $settingsPath | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($settings.ApiKey.Key)) { throw 'ApiKey.Key is required.' }
$headers = @{ 'X-Api-Key'=[string]$settings.ApiKey.Key }
if ($settings.WorldPlacements.Standalone -ne $true) { throw 'This launcher requires WorldPlacements.Standalone=true.' }
if ($settings.ConnectionStrings.WorldPlacement) { throw 'Dev-only launcher must not configure a channel placement database.' }
$url = [uri]$settings.Kestrel.Endpoints.Http.Url
if (-not $url.IsLoopback -or $url.Scheme -ne 'http') { throw 'Dev-only launcher requires an HTTP loopback endpoint.' }
$executable = Join-Path $root 'AstralRecordApi.exe'
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Publish the API to this directory first.' }
if (@([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() | Where-Object Port -EQ $url.Port).Count -gt 0) {
    throw 'The selected port already has a listener. Reuse or stop that process explicitly.'
}
$process = Start-Process -FilePath $executable -WorkingDirectory $root -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $root 'placement-api.stdout.log') `
    -RedirectStandardError (Join-Path $root 'placement-api.stderr.log')
$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    if ($process.HasExited) { throw "Placement API exited ($($process.ExitCode)). Inspect the API logs." }
    try {
        $health = Invoke-RestMethod -Uri ($url.AbsoluteUri.TrimEnd('/') + '/api/health') -Headers $headers -TimeoutSec 2
        $ready = $true
        break
    } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $ready) { throw "Placement API did not become ready; process $($process.Id) remains for diagnosis." }
[pscustomobject]@{ ProcessId=$process.Id; Url=$url.AbsoluteUri; Directory=$root; Ready=$true }
