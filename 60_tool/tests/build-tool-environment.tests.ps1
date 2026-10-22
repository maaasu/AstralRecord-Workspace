#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
function Assert($Condition,[string]$Message) { if (!$Condition) { throw $Message } }
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar build environment '+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../invoke-powershell7.ps1') -Destination $root
$launcher=Join-Path $root 'invoke-powershell7.ps1'
@{executablePath=(Join-Path $PSHOME 'pwsh.exe')} | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root 'powershell7.local.json')
$probe=Join-Path $root 'probe.ps1'
@'
param([string]$OutputPath,[string]$Forwarded)
@{javaHome=$env:JAVA_HOME;dotnetRoot=$env:DOTNET_ROOT;path=$env:PATH;forwarded=$Forwarded;
  maven=(Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue).Source;
  java=(Get-Command java -CommandType Application -ErrorAction SilentlyContinue).Source;
  dotnet=(Get-Command dotnet -CommandType Application -ErrorAction SilentlyContinue).Source} |
  ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $OutputPath
exit 19
'@ | Set-Content -Encoding UTF8 -LiteralPath $probe
$capture=Join-Path $root 'probe.json'
$local=Join-Path $root 'build-tools.local.json'
$settings=@{javaHome=(Join-Path $root 'fake jdk');mavenHome=(Join-Path $root 'fake maven');dotnetRoot=(Join-Path $root 'fake sdk')}
foreach ($entry in @(@('javaHome','bin/java.exe'),@('javaHome','bin/javac.exe'),@('mavenHome','bin/mvn.cmd'),@('dotnetRoot','dotnet.exe'))) {
    $file=Join-Path $settings[$entry[0]] $entry[1]
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($file)) | Out-Null
    [IO.File]::WriteAllText($file,'fixture')
}
$before=@{path=$env:PATH;javaHome=$env:JAVA_HOME;dotnetRoot=$env:DOTNET_ROOT}
try {
    $settings | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $local
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -ScriptPath $probe -OutputPath $capture -Forwarded 'spaces & literal $value'
    Assert ($LASTEXITCODE -eq 19) 'Child exit code was lost.'
    $result=Get-Content -Raw -Encoding UTF8 -LiteralPath $capture | ConvertFrom-Json
    Assert ($result.javaHome -ceq $settings.javaHome -and $result.dotnetRoot -ceq $settings.dotnetRoot) 'Build homes were not inherited.'
    Assert ($result.java -ceq (Join-Path $settings.javaHome 'bin/java.exe')) 'Configured JDK did not precede PATH.'
    Assert ($result.maven -ceq (Join-Path $settings.mavenHome 'bin/mvn.cmd')) 'Configured Maven did not precede PATH.'
    Assert ($result.dotnet -ceq (Join-Path $settings.dotnetRoot 'dotnet.exe')) 'Configured SDK did not precede PATH.'
    Assert ($result.forwarded -ceq 'spaces & literal $value') 'Arguments changed across the launcher.'
    Assert ($env:PATH -ceq $before.path -and $env:JAVA_HOME -ceq $before.javaHome -and $env:DOTNET_ROOT -ceq $before.dotnetRoot) 'Parent environment changed.'
    # Exercise the other Maven BAT through a harmless replacement backend.
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../12-build-network-plugins.bat') -Destination $root
    $networkDirectory=New-Item -ItemType Directory -Path (Join-Path $root 'network-plugin-build')
    @'
param([string]$Target,[switch]$SkipTests)
if($PSVersionTable.PSVersion.Major -lt 7 -or $Target -cne 'Proxy' -or !$SkipTests -or !$env:JAVA_HOME -or !(Get-Command mvn -CommandType Application)){throw 'Network BAT bootstrap failed.'}
exit 0
'@ | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $networkDirectory.FullName 'build-network-plugins.ps1')
    $networkBat=Join-Path $root '12-build-network-plugins.bat'
    $start=[Diagnostics.ProcessStartInfo]::new($env:ComSpec)
    $start.Arguments='/d /c ""'+$networkBat+'" -Target Proxy -SkipTests"'
    $start.UseShellExecute=$false;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $process=[Diagnostics.Process]::Start($start)
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    Assert ($process.ExitCode -eq 0) ('Network BAT failed: '+$stdout.Result+$stderr.Result)
    $process.Dispose()
    Remove-Item -LiteralPath $capture,$local
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -ScriptPath $probe -OutputPath $capture
    Assert ($LASTEXITCODE -eq 19) 'Unconfigured launcher failed.'
    $result=Get-Content -Raw -Encoding UTF8 -LiteralPath $capture | ConvertFrom-Json
    Assert ($result.path -ceq $before.path -and $result.javaHome -ceq $before.javaHome -and $result.dotnetRoot -ceq $before.dotnetRoot) 'Absent config changed inherited environment.'
    Remove-Item -LiteralPath $capture
    $driveRelative=$settings.javaHome.Substring(0,2)+[IO.Path]::GetRelativePath((Get-Location).ProviderPath,$settings.javaHome)
    $currentDriveRooted=$settings.javaHome.Substring(2)
    foreach ($invalid in @(@{javaHome='relative/jdk'},@{javaHome=$driveRelative},@{javaHome=$currentDriveRooted},@{javaHome=$settings.mavenHome},@{mavenHome=42},@{dotnetRoot=(Join-Path $root 'missing')},@{unknown=$settings.javaHome},@{javaHome=''})) {
        $invalid | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath $local
        $output=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -ScriptPath $probe -OutputPath $capture 2>&1
        Assert ($LASTEXITCODE -eq 1 -and !(Test-Path -LiteralPath $capture)) 'Invalid build config reached the child.'
        Assert ((($output -join "`n") -replace '\s','') -match 'build-tools\.local\.json') ('Missing actionable configuration error: '+($output -join "`n"))
        if($invalid.ContainsKey('javaHome') -and $invalid.javaHome -in @($driveRelative,$currentDriveRooted)) {
            Assert ((($output -join "`n") -replace '\s','') -match 'absolutedirectorypath') 'Relative form was not rejected by the absolute-path guard.'
        }
    }
    '[]' | Set-Content -Encoding UTF8 -LiteralPath $local
    $output=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $launcher -ScriptPath $probe -OutputPath $capture 2>&1
    Assert ($LASTEXITCODE -eq 1 -and !(Test-Path -LiteralPath $capture)) 'Non-object config reached the child.'
    Write-Host 'PASS: Windows PowerShell 5 launcher selects build tools, preserves parent/default environment and arguments/exit code, and rejects invalid configuration.'
} finally {
    # Keep this isolated fixture and its output for diagnostics; no deployment or real build is invoked.
    Write-Host "Evidence: $root"
}
