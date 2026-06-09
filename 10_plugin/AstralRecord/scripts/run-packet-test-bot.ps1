[CmdletBinding()]
param(
    [string]$HostName = "localhost",
    [int]$Port = 25578,
    [string]$Username = "CodexPacketBot",
    [string]$Version = "1.21.11",
    [int]$StaySeconds = 20,
    [int]$SwapHandCount = 0,
    [int]$SwapHandDelayMs = 50,
    [int]$SwapHandStartDelayMs = 1000,
    [string]$BotRoot = "",
    [string]$LogPath = "",
    [string]$ExpectLogPattern = "",
    [string]$RejectLogPattern = "",
    [int]$ExpectTimeoutSeconds = 30,
    [switch]$SkipNpmInstall
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pluginRoot = Split-Path -Parent $scriptRoot
if ([string]::IsNullOrWhiteSpace($BotRoot)) {
    $BotRoot = Join-Path $pluginRoot ".dev-server\packet-test-bot"
}

if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path $pluginRoot ".dev-server\integration-live-clone-actionring\logs\latest.log"
}

New-Item -ItemType Directory -Force -Path $BotRoot | Out-Null

$packageJson = Join-Path $BotRoot "package.json"
if (-not (Test-Path -LiteralPath $packageJson)) {
    @"
{
  "private": true,
  "type": "module",
  "dependencies": {
    "minecraft-protocol": "1.66.2"
  }
}
"@ | Set-Content -LiteralPath $packageJson -Encoding UTF8
}

if (-not $SkipNpmInstall) {
    Push-Location $BotRoot
    try {
        npm install --silent
    } finally {
        Pop-Location
    }
}

$botSourcePath = Join-Path $BotRoot "packet-test-bot.mjs"
@'
const originalConsoleLog = console.log.bind(console);
console.log = (...args) => {
  if (String(args[0] ?? '').startsWith('Chunk size is ')) return;
  originalConsoleLog(...args);
};

const { default: mc } = await import('minecraft-protocol');

const host = process.env.PACKET_BOT_HOST || 'localhost';
const port = Number(process.env.PACKET_BOT_PORT || '25578');
const username = process.env.PACKET_BOT_USERNAME || 'CodexPacketBot';
const version = process.env.PACKET_BOT_VERSION || '1.21.11';
const staySeconds = Number(process.env.PACKET_BOT_STAY_SECONDS || '20');
const swapHandCount = Number(process.env.PACKET_BOT_SWAP_HAND_COUNT || '0');
const swapHandDelayMs = Number(process.env.PACKET_BOT_SWAP_HAND_DELAY_MS || '50');
const swapHandStartDelayMs = Number(process.env.PACKET_BOT_SWAP_HAND_START_DELAY_MS || '1000');

let finished = false;
let joined = false;
let sequence = 1;

function finish(code, message) {
  if (finished) return;
  finished = true;
  if (message) {
    const stream = code === 0 ? process.stdout : process.stderr;
    stream.write(message + '\n');
  }
  try {
    client?.end('packet test finished');
  } catch {
    // The connection may already be closed by the server.
  }
  setTimeout(() => process.exit(code), 250);
}

const client = mc.createClient({
  host,
  port,
  username,
  version,
  auth: 'offline'
});

client.once('login', () => {
  joined = true;
  console.log(`[PacketTestBot] login username=${username} host=${host} port=${port} version=${version}`);
  if (swapHandCount > 0) {
    setTimeout(() => sendSwapHandPackets(), swapHandStartDelayMs);
  }
  setTimeout(() => finish(0, `[PacketTestBot] stayed ${staySeconds}s and disconnecting`), staySeconds * 1000);
});

function sendSwapHandPackets() {
  for (let index = 0; index < swapHandCount; index += 1) {
    setTimeout(() => {
      client.write('block_dig', {
        status: 6,
        location: { x: 0, y: 0, z: 0 },
        face: 0,
        sequence: sequence++
      });
      console.log(`[PacketTestBot] sent swap-hand packet ${index + 1}/${swapHandCount}`);
    }, index * swapHandDelayMs);
  }
}

client.on('end', (reason) => {
  if (!finished) {
    finish(joined ? 0 : 1, `[PacketTestBot] ended before requested stay reason=${reason ?? 'unknown'}`);
  }
});

client.on('error', (error) => {
  if (!finished) {
    finish(1, `[PacketTestBot] error ${error?.stack || error}`);
  }
});

setTimeout(() => {
  if (!joined) {
    finish(1, `[PacketTestBot] login timeout username=${username} host=${host} port=${port}`);
  }
}, 30000);
'@ | Set-Content -LiteralPath $botSourcePath -Encoding UTF8

$env:PACKET_BOT_HOST = $HostName
$env:PACKET_BOT_PORT = [string]$Port
$env:PACKET_BOT_USERNAME = $Username
$env:PACKET_BOT_VERSION = $Version
$env:PACKET_BOT_STAY_SECONDS = [string]$StaySeconds
$env:PACKET_BOT_SWAP_HAND_COUNT = [string]$SwapHandCount
$env:PACKET_BOT_SWAP_HAND_DELAY_MS = [string]$SwapHandDelayMs
$env:PACKET_BOT_SWAP_HAND_START_DELAY_MS = [string]$SwapHandStartDelayMs

$logStartOffset = 0L
if ((-not [string]::IsNullOrWhiteSpace($ExpectLogPattern) -or -not [string]::IsNullOrWhiteSpace($RejectLogPattern)) -and
    (Test-Path -LiteralPath $LogPath)) {
    $logStartOffset = (Get-Item -LiteralPath $LogPath).Length
}

Push-Location $BotRoot
try {
    $stderrPath = Join-Path $BotRoot "packet-test-bot.stderr.log"
    if (Test-Path -LiteralPath $stderrPath) {
        Remove-Item -LiteralPath $stderrPath -Force
    }
    & node $botSourcePath 2> $stderrPath
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $stderrPath) {
            Get-Content -LiteralPath $stderrPath
        }
        throw "Packet test bot exited with code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

function Read-NewLogText {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][long]$StartOffset
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return ""
    }
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        if ($StartOffset -gt 0 -and $StartOffset -lt $stream.Length) {
            [void]$stream.Seek($StartOffset, [System.IO.SeekOrigin]::Begin)
        }
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $true)
        try {
            return $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

$newLogText = Read-NewLogText -Path $LogPath -StartOffset $logStartOffset
if (-not [string]::IsNullOrWhiteSpace($RejectLogPattern) -and $newLogText -match $RejectLogPattern) {
    throw "Rejected log pattern was found: $RejectLogPattern"
}

if (-not [string]::IsNullOrWhiteSpace($ExpectLogPattern)) {
    if ($newLogText -match $ExpectLogPattern) {
        Write-Host "[PacketTestBot] matched log pattern: $ExpectLogPattern"
        return
    }

    $deadline = (Get-Date).AddSeconds($ExpectTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $newLogText = Read-NewLogText -Path $LogPath -StartOffset $logStartOffset
        if (-not [string]::IsNullOrWhiteSpace($RejectLogPattern) -and $newLogText -match $RejectLogPattern) {
            throw "Rejected log pattern was found: $RejectLogPattern"
        }
        if ($newLogText -match $ExpectLogPattern) {
            Write-Host "[PacketTestBot] matched log pattern: $ExpectLogPattern"
            return
        }
    } while ((Get-Date) -lt $deadline)

    throw "Expected log pattern was not found within ${ExpectTimeoutSeconds}s: $ExpectLogPattern"
}

if (-not [string]::IsNullOrWhiteSpace($RejectLogPattern)) {
    Write-Host "[PacketTestBot] rejected log pattern was not found: $RejectLogPattern"
}
