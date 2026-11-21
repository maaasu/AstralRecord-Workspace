[CmdletBinding()]
param(
    [string]$Port,
    [switch]$Open,
    [switch]$Stop,
    [switch]$Foreground
)

$ErrorActionPreference = 'Stop'

function Get-WorkLiveUrl([int]$ServerPort) {
    return "http://127.0.0.1:$ServerPort"
}

function Get-WorkLiveHealth([int]$ServerPort) {
    $response = $null
    $reader = $null
    try {
        $request = [Net.WebRequest]::CreateHttp((Get-WorkLiveUrl $ServerPort) + '/api/health')
        $request.Timeout = 500
        $request.ReadWriteTimeout = 500
        $request.Proxy = $null
        $request.AllowAutoRedirect = $false
        $request.KeepAlive = $false
        $response = $request.GetResponse()
        $reader = [IO.StreamReader]::new($response.GetResponseStream(), [Text.Encoding]::UTF8)
        $buffer = New-Object 'char[]' 4097
        $count = $reader.ReadBlock($buffer, 0, $buffer.Length)
        if ($count -eq $buffer.Length) { return }
        $health = [string]::new($buffer, 0, $count) | ConvertFrom-Json
        if ($health.app -ceq 'work-live') {
            return [pscustomobject]@{ ServerId = $response.Headers['X-Work-Live-Pid'] }
        }
    }
    catch { return }
    finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Test-WorkLivePortInUse([int]$ServerPort) {
    $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $ServerPort)
    $probe.ExclusiveAddressUse = $true
    try {
        $probe.Start()
        return $false
    }
    catch [Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -in @('AddressAlreadyInUse', 'AccessDenied')) { return $true }
        throw
    }
    finally { $probe.Stop() }
}

function Read-WorkLiveRegistration {
    try {
        if (Test-Path -LiteralPath $registration) {
            return (Get-Content -Raw -Encoding UTF8 -LiteralPath $registration | ConvertFrom-Json)
        }
    }
    catch { return }
}

function Get-RegisteredWorkLive {
    try {
        $entry = Read-WorkLiveRegistration
        $registeredPort = [int]$entry.port
        if (!$entry -or [int]$entry.pid -lt 1 -or $registeredPort -lt 1 -or $registeredPort -gt 65535) { return }
        $running = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
        if (!$running -or $running.ProcessName -ne 'node' -or
            [string]$running.StartTime.ToUniversalTime().Ticks -cne $entry.startTicks) { return }
        $health = Get-WorkLiveHealth $registeredPort
        if (!$health -or $health.ServerId -cne [string]$running.Id) { return }
        return [pscustomobject]@{ Process = $running; Port = $registeredPort }
    }
    catch { return }
}

if (!$PSBoundParameters.ContainsKey('Port')) {
    $Port = if ($env:WORK_LIVE_PORT) { $env:WORK_LIVE_PORT } else { '5490' }
}
$number = 0
if (!$Stop -and ($Port -notmatch '^\d+$' -or ![int]::TryParse($Port, [ref]$number) -or $number -lt 1 -or $number -gt 65535)) {
    [Console]::Error.WriteLine('ポートは 1〜65535 の整数で指定してください。')
    exit 1
}

$storage = Join-Path $env:LOCALAPPDATA 'work-live'
$registration = Join-Path $storage 'server.pid'
$mutex = [Threading.Mutex]::new($false, 'Local\AstralRecord-WorkLive-Launcher')
$held = $false
$process = $null
$registeredProcess = $false

try {
    try { $held = $mutex.WaitOne(12000) }
    catch [Threading.AbandonedMutexException] { $held = $true }
    if (!$held) { throw '別の起動操作が完了するまでの待機時間を超えました。' }

    if ($Stop) {
        $registered = Get-RegisteredWorkLive
        if (!$registered) {
            Write-Output '起動スクリプトで起動した作業ライブは見つかりませんでした。'
            return
        }
        Stop-Process -InputObject $registered.Process
        if (!$registered.Process.WaitForExit(5000)) { throw '作業ライブを停止できませんでした。' }
        Remove-Item -LiteralPath $registration
        Write-Output '作業ライブを停止しました。'
        return
    }

    $url = Get-WorkLiveUrl $number
    if (Get-WorkLiveHealth $number) {
        Write-Output $url
        return
    }
    if (Test-WorkLivePortInUse $number) {
        [Console]::Error.WriteLine("ポート $number は使用中です。-Port または WORK_LIVE_PORT で使用するポートを指定してください。")
        exit 1
    }
    $registered = Get-RegisteredWorkLive
    if ($registered) {
        Write-Output (Get-WorkLiveUrl $registered.Port)
        return
    }

    $node = Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$node) { throw 'Node.js が見つかりません。Node.js 24 以上を PATH に追加してください。' }
    $version = & $node.Source --version
    if ($version -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 24) {
        throw '作業ライブには Node.js 24 以上が必要です。'
    }
    if (!(Test-Path -LiteralPath $storage)) {
        New-Item -ItemType Directory -Path $storage | Out-Null
    }

    $server = Join-Path $PSScriptRoot 'server.ts'
    $arguments = @('"' + $server + '"', '--port', [string]$number)
    if ($Foreground) {
        $process = Start-Process -FilePath $node.Source -ArgumentList $arguments -NoNewWindow -PassThru
    }
    else {
        $process = Start-Process -FilePath $node.Source -ArgumentList $arguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $storage 'server.log') -RedirectStandardError (Join-Path $storage 'server-error.log')
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    $healthy = $false
    do {
        $health = Get-WorkLiveHealth $number
        if ($health -and $health.ServerId -ceq [string]$process.Id) {
            $healthy = $true
            break
        }
        if ($process.HasExited) { break }
        Start-Sleep -Milliseconds 120
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$healthy) {
        if ($health) {
            Write-Output $url
            return
        }
        if ($process.HasExited -and (Test-WorkLivePortInUse $number)) { throw "ポート $number は使用中です。" }
        throw "ポート $number で作業ライブを起動できませんでした。ログの保存先は $storage です。"
    }

    $entry = @{ pid = $process.Id; port = $number; startTicks = [string]$process.StartTime.ToUniversalTime().Ticks } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($registration, $entry)
    $registeredProcess = $true
    Write-Output $url
    if ($Open) { Start-Process $url }
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
finally {
    if ($process -and !$registeredProcess -and !$process.HasExited) {
        Stop-Process -InputObject $process -ErrorAction SilentlyContinue
    }
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}

if ($Foreground -and $process) {
    Wait-Process -InputObject $process -ErrorAction SilentlyContinue
    exit $process.ExitCode
}
