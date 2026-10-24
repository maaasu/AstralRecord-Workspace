#requires -Version 7.0
param(
    [string]$ToolRoot = (Join-Path $PSScriptRoot '..'),
    [Parameter(Mandatory = $true)][string]$CompatibleSdkHome,
    [Parameter(Mandatory = $true)][string]$IncompatibleSdkHome,
    [string]$PortableSdkUserProfile = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Assert($Condition, [string]$Message) { if (!$Condition) { throw $Message } }

$ToolRoot = (Resolve-Path -LiteralPath $ToolRoot).Path
$CompatibleSdkHome = (Resolve-Path -LiteralPath $CompatibleSdkHome).Path
$IncompatibleSdkHome = (Resolve-Path -LiteralPath $IncompatibleSdkHome).Path
$resolver = Join-Path $ToolRoot '06-master-data-editor-build.bat'
$editor = Join-Path $ToolRoot 'skilltree-editor'
foreach ($path in @($resolver, (Join-Path $editor 'global.json'),
        (Join-Path $CompatibleSdkHome 'dotnet.exe'), (Join-Path $IncompatibleSdkHome 'dotnet.exe'))) {
    Assert (Test-Path -LiteralPath $path -PathType Leaf) "Required real launcher/SDK file is missing: $path"
}
$evidence = Join-Path ([IO.Path]::GetTempPath()) ('ar editor launcher ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $evidence | Out-Null
$before = [Environment]::GetEnvironmentVariables('Process')
$passed = 0

function Invoke-CapturedProcess([Diagnostics.ProcessStartInfo]$Start, [string]$Name) {
    $Start.UseShellExecute = $false
    $Start.CreateNoWindow = $true
    $Start.RedirectStandardOutput = $true
    $Start.RedirectStandardError = $true
    $Start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $Start.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    # Set only the new process environment, never the PowerShell/user/system environment.
    $Start.Environment['DOTNET_CLI_TELEMETRY_OPTOUT'] = '1'
    $Start.Environment['DOTNET_SKIP_FIRST_TIME_EXPERIENCE'] = '1'
    $Start.Environment['DOTNET_NOLOGO'] = '1'
    $process = [Diagnostics.Process]::Start($Start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(30000)) {
            $process.Kill($true)
            $process.WaitForExit()
            throw "Child timed out: $Name"
        }
        $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.Result + $stderr.Result }
        [IO.File]::WriteAllText((Join-Path $evidence ($Name + '.log')), $result.Output, [Text.UTF8Encoding]::new($false))
        return $result
    } finally { $process.Dispose() }
}

function Invoke-Resolver([string]$Name, [string]$ShadowErrorLevel,
        [string]$PreferredHome, [string]$FallbackHome = '', [int]$CodePage = 65001, [switch]$InvalidTemp,
        [string]$ProfileHome = '') {
    $caseRoot = Join-Path $evidence $Name
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $child = Join-Path $caseRoot 'probe.cmd'
    # All paths are passed in environment variables; the CMD source remains ASCII even for Unicode homes.
    $body = @'
@echo off
chcp %AR_TEST_CODE_PAGE% >nul
set "ProgramFiles=%AR_TEST_ISOLATED_HOME%"
set "ProgramFiles(x86)=%AR_TEST_ISOLATED_HOME%"
set "USERPROFILE=%AR_TEST_ISOLATED_HOME%"
if defined AR_TEST_PROFILE_HOME set "USERPROFILE=%AR_TEST_PROFILE_HOME%"
set "LOCALAPPDATA=%AR_TEST_ISOLATED_HOME%"
call "%AR_TEST_RESOLVER%" --resolve-dotnet
if errorlevel 1 goto failed
set "ERRORLEVEL="
echo TEST_HOST=%ASTRALRECORD_DOTNET_EXE%
echo TEST_ROOT=%DOTNET_ROOT%
echo TEST_LOG=%ASTRALRECORD_EDITOR_SDK_LOG%
exit /b 0
:failed
set "ERRORLEVEL="
echo TEST_RESOLVER_FAILED
exit /b 1
'@
    [IO.File]::WriteAllText($child, $body.Replace("`r`n", "`n").Replace("`n", "`r`n"), [Text.ASCIIEncoding]::new())
    $start = [Diagnostics.ProcessStartInfo]::new($env:ComSpec)
    # Public acceptance uses Explorer -> 05, which enables extensions before this internal 06 resolver.
    $start.Arguments = '/d /e:on /v:off /c ""' + $child + '""'
    $start.WorkingDirectory = $caseRoot
    $start.Environment['ERRORLEVEL'] = $ShadowErrorLevel
    $start.Environment['ASTRALRECORD_DOTNET_HOME'] = $PreferredHome
    $start.Environment['DOTNET_ROOT'] = $FallbackHome
    $start.Environment['DOTNET_ROOT_X64'] = ''
    $start.Environment['ASTRALRECORD_DOTNET_EXE'] = ''
    $start.Environment['ASTRALRECORD_EDITOR_SDK_LOG'] = ''
    $start.Environment['PATH'] = (Join-Path $env:SystemRoot 'System32')
    $start.Environment['TEMP'] = if ($InvalidTemp) { Join-Path $editor 'global.json' } else { $caseRoot }
    $start.Environment['TMP'] = $caseRoot
    $start.Environment['AR_TEST_RESOLVER'] = $resolver
    $start.Environment['AR_TEST_CODE_PAGE'] = [string]$CodePage
    $start.Environment['AR_TEST_ISOLATED_HOME'] = (Join-Path $caseRoot 'isolated user')
    $start.Environment['AR_TEST_PROFILE_HOME'] = $ProfileHome
    return Invoke-CapturedProcess $start $Name
}

function Assert-SelectedSdk($Result, [string]$Name, [switch]$MissingLog) {
    Assert ($Result.ExitCode -eq 0) ("$Name did not accept the compatible real SDK: " + $Result.Output)
    Assert ($Result.Output.Contains('TEST_HOST=' + (Join-Path $CompatibleSdkHome 'dotnet.exe'))) ("$Name selected the wrong host: " + $Result.Output)
    Assert ($Result.Output.Contains('TEST_ROOT=' + $CompatibleSdkHome)) ("$Name did not export DOTNET_ROOT: " + $Result.Output)
    $match = [regex]::Match($Result.Output, '(?m)^TEST_LOG=(.+)\r?$')
    Assert $match.Success ("$Name did not export the SDK diagnostic path: " + $Result.Output)
    $path = $match.Groups[1].Value.TrimEnd("`r")
    if ($MissingLog) {
        Assert (!(Test-Path -LiteralPath $path)) "$Name unexpectedly wrote a log below the global.json file."
        return ''
    }
    Assert (Test-Path -LiteralPath $path -PathType Leaf) "$Name did not retain its SDK diagnostic log."
    $log = Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    Assert ($log.Contains('Exit code: 0')) "$Name did not record a successful real --version exit code."
    return $log
}

try {
    # The real SDK's exit code under the actual editor global.json is the oracle, not a fake EXE/version string.
    foreach ($entry in @(@('compatible-sdk', $CompatibleSdkHome, $true), @('incompatible-sdk', $IncompatibleSdkHome, $false))) {
        $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $entry[1] 'dotnet.exe'))
        $start.ArgumentList.Add('--version')
        $start.WorkingDirectory = $editor
        $result = Invoke-CapturedProcess $start $entry[0]
        Assert (($result.ExitCode -eq 0) -eq $entry[2]) ("Real SDK precondition failed for $($entry[0]): " + $result.Output)
    }

    foreach ($shadow in @('1', '-1', '0')) {
        $name = 'inherited-errorlevel-' + $shadow
        $result = Invoke-Resolver $name $shadow $CompatibleSdkHome
        $null = Assert-SelectedSdk $result $name
        $passed++
    }

    $result = Invoke-Resolver 'old-sdk-fallback' '1' $IncompatibleSdkHome $CompatibleSdkHome
    $log = Assert-SelectedSdk $result 'old-sdk-fallback'
    Assert ($log.Contains('Checking: "' + (Join-Path $IncompatibleSdkHome 'dotnet.exe') + '"')) 'The incompatible SDK was not actually tried.'
    Assert ($log -match 'Exit code: (?!0\b)-?\d+') 'The incompatible SDK rejection was not logged before fallback.'
    $passed++

    # Version text is not asserted: the real exit code must work with both UTF-8 and legacy console output.
    $result = Invoke-Resolver 'legacy-console-output' '-1' $CompatibleSdkHome '' 932
    $null = Assert-SelectedSdk $result 'legacy-console-output'
    $passed++

    $result = Invoke-Resolver 'no-compatible-sdk' '0' $IncompatibleSdkHome
    Assert ($result.ExitCode -eq 1 -and $result.Output.Contains('TEST_RESOLVER_FAILED')) ('Missing SDK incorrectly succeeded: ' + $result.Output)
    Assert ($result.Output.Contains('No .NET SDK compatible with skilltree-editor\global.json was found.')) 'Missing actionable SDK error.'
    Assert ($result.Output -notmatch '(?m)^\d+\.\d+\.\d+[\w.\-]*\r?$') 'Failure diagnostics escaped global.json and printed a successful SDK version.'
    $match = [regex]::Match($result.Output, 'SDK diagnostics: "([^"]+)"')
    Assert $match.Success ('Failure did not identify its diagnostic log: ' + $result.Output)
    $failureLog = $match.Groups[1].Value
    Assert (Test-Path -LiteralPath $failureLog -PathType Leaf) 'Failure diagnostic log was not retained.'
    $log = Get-Content -Raw -Encoding UTF8 -LiteralPath $failureLog
    Assert ($log.Contains('SDK resolution directory:') -and $log.Contains($IncompatibleSdkHome) -and $log.Contains('Exit code:')) 'Failure log omitted resolution directory, attempted real SDK, or exit code.'
    $passed++

    # A log redirection failure must not replace the real SDK exit code with a stale ERRORLEVEL.
    $globalJson = Join-Path $editor 'global.json'
    $globalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $globalJson).Hash
    $result = Invoke-Resolver 'invalid-temp-fallback' '0' $IncompatibleSdkHome $CompatibleSdkHome -InvalidTemp
    $null = Assert-SelectedSdk $result 'invalid-temp-fallback' -MissingLog
    $passed++
    $result = Invoke-Resolver 'invalid-temp-no-compatible' '-1' $IncompatibleSdkHome -InvalidTemp
    Assert ($result.ExitCode -eq 1 -and $result.Output.Contains('TEST_RESOLVER_FAILED')) ('Unwritable TEMP made the incompatible SDK succeed: ' + $result.Output)
    Assert ($result.Output.Contains('No .NET SDK compatible with skilltree-editor\global.json was found.') -and $result.Output.Contains('SDK diagnostics could not be written to TEMP.')) 'Unwritable TEMP did not retain actionable failure diagnostics.'
    Assert ($result.Output -notmatch '(?m)^\d+\.\d+\.\d+[\w.\-]*\r?$') 'Invalid TEMP failure diagnostics lost the global.json SDK constraint.'
    Assert ((Get-FileHash -Algorithm SHA256 -LiteralPath $globalJson).Hash -ceq $globalHash) 'The TEMP fixture changed the real global.json file.'
    $passed++

    if ($PortableSdkUserProfile) {
        $profileSdk = (Resolve-Path -LiteralPath (Join-Path $PortableSdkUserProfile '.astralrecord/tools/dotnet')).Path
        Assert ($profileSdk -ieq $CompatibleSdkHome) 'Portable profile case requires CompatibleSdkHome below that profile.'
        $result = Invoke-Resolver 'portable-profile-discovery' '1' '' -ProfileHome $PortableSdkUserProfile
        $null = Assert-SelectedSdk $result 'portable-profile-discovery'
        $passed++
    }

    $after = [Environment]::GetEnvironmentVariables('Process')
    Assert ($before.Count -eq $after.Count) 'The parent process environment gained/lost variables.'
    foreach ($key in $before.Keys) { Assert ($after[$key] -ceq $before[$key]) "Parent environment changed: $key" }
    Write-Host "PASS: $passed launcher cases; inherited ERRORLEVEL, real SDK compatibility/fallback, console output, invalid TEMP and failure diagnostics."
} finally {
    Write-Host "Evidence: $evidence"
}
