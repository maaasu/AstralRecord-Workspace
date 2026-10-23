#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$JdkArchive,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedArchiveSha256,
    [Parameter(Mandatory)][guid]$ExecutableId,
    [Parameter(Mandatory)][string]$CliPath,
    [Parameter(Mandatory)][string]$TrustedCertificatePath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedCertificateSha256,
    [string]$JdkRootName = 'jdk-25.0.4.1+1',
    [string]$InstallRoot = (Join-Path $env:ProgramFiles 'AstralRecordControl\Java'),
    [string]$AgentDataRoot = (Join-Path $env:ProgramData 'AstralRecordControl\Agent'),
    [string]$RunnerDataRoot = (Join-Path $env:ProgramData 'AstralRecordControl\Runner')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Control.Deployment.psm1') -Force

function Read-JavaRuntimeConfig([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "設定がありません: $Path" }
    $value = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json -AsHashtable
    if ($value -isnot [Collections.IDictionary] -or $value['localResources'] -isnot [Collections.IDictionary] -or
        $value['localResources']['executables'] -isnot [array] -or $value['localResources']['roots'] -isnot [array]) {
        throw 'Agent/RunnerのlocalResources設定を確認できません。'
    }
    return $value
}

function Assert-JavaResourcePair($Agent, $Runner, [guid]$Id) {
    if ($Id -eq [guid]::Empty -or [string]$Agent['hostId'] -ine [string]$Runner['hostId']) { throw 'HostIdまたはExecutableIdが一致しません。' }
    $wanted = $Id.ToString('D')
    $agentEntries = @($Agent['localResources']['executables'] | Where-Object { [string]$_['id'] -ieq $wanted })
    $runnerEntries = @($Runner['localResources']['executables'] | Where-Object { [string]$_['id'] -ieq $wanted })
    if ($agentEntries.Count -ne 1 -or $runnerEntries.Count -ne 1 -or
        [string]$agentEntries[0]['kind'] -cne 'Java' -or [string]$runnerEntries[0]['kind'] -cne 'Java' -or
        [string]$agentEntries[0]['path'] -ine [string]$runnerEntries[0]['path']) {
        throw '指定IDは両設定で同じJava実行資源である必要があります。'
    }
    foreach ($key in @('roots', 'executables')) {
        $left = @($Agent['localResources'][$key]); $right = @($Runner['localResources'][$key])
        if ($left.Count -ne $right.Count -or @($left | ForEach-Object { [string]$_['id'] } | Select-Object -Unique).Count -ne $left.Count) { throw 'Agent/Runnerの資源一覧が一致しません。' }
        foreach ($entry in $left) {
            $match = @($right | Where-Object { [string]$_['id'] -ieq [string]$entry['id'] })
            if ($match.Count -ne 1 -or [string]$entry['path'] -ine [string]$match[0]['path'] -or
                ($key -eq 'executables' -and [string]$entry['kind'] -cne [string]$match[0]['kind'])) {
                throw 'Agent/Runnerの資源一覧が一致しません。'
            }
        }
    }
    $path = [string]$agentEntries[0]['path']
    if ([IO.Path]::GetFileName($path) -ine 'java.exe' -or !(Test-Path -LiteralPath (Get-ControlAbsolutePath $path) -PathType Leaf)) {
        throw '登録済みJava実行ファイルが見つかりません。'
    }
    return $path
}

function Assert-JdkArchive([string]$Archive, [string]$Hash, [string]$RootName) {
    if ($RootName -cnotmatch '^[A-Za-z0-9._+-]+$' -or $RootName -in @('.', '..')) { throw 'JDK root名を確認してください。' }
    $archivePath = Get-ControlAbsolutePath $Archive
    if (!(Test-Path -LiteralPath $archivePath -PathType Leaf)) { throw 'JDK ZIPがありません。' }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ine $Hash) { throw 'JDK ZIPのSHA-256が一致しません。' }
    Add-Type -AssemblyName System.IO.Compression
    $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $total = [long]0; $javaFound = $false
        if ($zip.Entries.Count -gt 50000) { throw 'JDK ZIPのentry数が多すぎます。' }
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            $parts = $name.Split('/', [StringSplitOptions]::RemoveEmptyEntries)
            if (($parts.Length -lt 2 -and $name -cne "$RootName/") -or $parts[0] -cne $RootName -or
                @($parts | Where-Object { $_ -in @('.', '..') -or $_.EndsWith('.') -or $_ -match '[\\:< >"|?*]' }).Count -gt 0) { throw 'JDK ZIPに許可外のpathがあります。' }
            if ($name.StartsWith('/') -or $name.Contains('//') -or $name.Contains('\') -or !$seen.Add($name)) { throw 'JDK ZIPに不正または重複するentryがあります。' }
            $mode = ($entry.ExternalAttributes -shr 16) -band 0xF000
            if ($mode -eq 0xA000 -or ($entry.ExternalAttributes -band 0x400) -ne 0) { throw 'JDK ZIPのリンクを展開できません。' }
            $total += $entry.Length
            if ($total -gt 4GB) { throw 'JDK ZIPの展開サイズが大きすぎます。' }
            if ($name -ceq "$RootName/bin/java.exe") { $javaFound = $true }
        }
        if (!$javaFound) { throw 'JDK ZIPにbin/java.exeがありません。' }
    } finally { $zip.Dispose() }
    return $archivePath
}

function Get-JavaRuntimeService([string]$Component) {
    $name = "AstralRecordControl.$Component"
    $service = Get-Service -Name $name -ErrorAction Stop
    if ($service.Status -notin @('Running', 'Stopped')) { throw "$name の状態を確認できません。" }
    return $service
}

function Stop-JavaRuntimeService([string]$Component) {
    $service = Get-JavaRuntimeService $Component
    if ($service.Status -eq 'Stopped') { return }
    Stop-Service -Name $service.Name -ErrorAction Stop
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
}

function Start-JavaRuntimeService([string]$Component) {
    $service = Get-JavaRuntimeService $Component
    if ($service.Status -eq 'Running') { return }
    Start-Service -Name $service.Name -ErrorAction Stop
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(60))
}

function Assert-JavaRuntimeStopped([string]$Cli, [string]$Pipe) {
    $lines = & $Cli runner check-update --pipe-name $Pipe
    if ($LASTEXITCODE -ne 0) { throw 'Runnerの停止・予約状態を確認できません。' }
    $snapshot = ($lines -join "`n") | ConvertFrom-Json
    Assert-ControlRunnerStoppedSnapshot $snapshot
}

function Test-Java25Executable([string]$JavaPath) {
    $info = [Diagnostics.ProcessStartInfo]::new($JavaPath)
    $info.ArgumentList.Add('-version')
    $info.UseShellExecute = $false
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        if (!$process.WaitForExit(15000)) { $process.Kill($true); throw 'Java -versionが時間内に終了しません。' }
        $output = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
        if ($process.ExitCode -ne 0 -or $output -notmatch '(?i)(?:openjdk|java) version "25(?:\.|\")') { throw 'Java 25の実行を確認できません。' }
    } finally { $process.Dispose() }
}

function Assert-TrustedCertificate([string]$Path, [string]$Hash) {
    $certificatePath = Get-ControlAbsolutePath $Path
    if (!(Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'API公開CA証明書がありません。' }
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($certificatePath)
    try {
        $rawHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificate.RawData))
        $basic = @($certificate.Extensions | Where-Object { $_ -is [Security.Cryptography.X509Certificates.X509BasicConstraintsExtension] })
        if ($rawHash -ine $Hash -or $certificate.HasPrivateKey -or $basic.Count -ne 1 -or !$basic[0].CertificateAuthority -or
            $certificate.Subject -cne $certificate.Issuer -or $certificate.Subject -notmatch 'CN=AstralRecord API Internal CA') {
            throw 'API公開CA証明書の識別・SHA-256を確認できません。'
        }
    } finally { $certificate.Dispose() }
    return $certificatePath
}

function Assert-DefaultJavaTrustStore {
    foreach ($scope in @('Process', 'User', 'Machine')) {
        foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
            if ([Environment]::GetEnvironmentVariable($name, $scope) -match '(?i)-Djavax\.net\.ssl\.trustStore(?:=|\s)') {
                throw 'Java環境変数が別のtruststoreを指定しています。'
            }
        }
    }
}

function Assert-JavaTrust([string]$JavaHome, [string]$CertificatePath, [string]$Hash, [bool]$Import) {
    $keytool = Join-Path $JavaHome 'bin\keytool.exe'
    $cacerts = Join-Path $JavaHome 'lib\security\cacerts'
    if (!(Test-Path -LiteralPath $keytool -PathType Leaf) -or !(Test-Path -LiteralPath $cacerts -PathType Leaf)) { throw 'JDKのkeytool/cacertsがありません。' }
    $storeArguments = @('-cacerts', '-storepass', 'changeit')
    $null = & $keytool -list @storeArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'JDKの標準cacertsを読み取れません。' }
    $alias = 'astralrecord-api-root'
    $export = Join-Path $JavaHome ('control-java-trust-' + [guid]::NewGuid().ToString('N') + '.cer')
    try {
        $null = & $keytool -exportcert @storeArguments -alias $alias -file $export 2>&1
        if ($LASTEXITCODE -ne 0) {
            if (!$Import) { throw '新JDKにAPI公開CAが登録されていません。' }
            $null = & $keytool -importcert @storeArguments -alias $alias -file $CertificatePath -trustcacerts -noprompt 2>&1
            if ($LASTEXITCODE -ne 0) { throw '新JDKへのAPI公開CAの登録に失敗しました。' }
            $null = & $keytool -exportcert @storeArguments -alias $alias -file $export 2>&1
        }
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $export -PathType Leaf) -or
            (Get-FileHash -LiteralPath $export -Algorithm SHA256).Hash -ine $Hash) {
            throw '新JDKのAPI公開CAが指定SHA-256と一致しません。'
        }
    } finally { if (Test-Path -LiteralPath $export) { Remove-Item -LiteralPath $export -Force } }
}

function Set-JavaRuntimeAcl([string]$Path) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($pair in @(@('S-1-5-18','FullControl'), @('S-1-5-32-544','FullControl'),
        @(([Security.Principal.NTAccount]::new('NT SERVICE','AstralRecordControl.Agent').Translate([Security.Principal.SecurityIdentifier]).Value),'ReadAndExecute'),
        @(([Security.Principal.NTAccount]::new('NT SERVICE','AstralRecordControl.Runner').Translate([Security.Principal.SecurityIdentifier]).Value),'ReadAndExecute'))) {
        $sid = [Security.Principal.SecurityIdentifier]::new($pair[0])
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, $pair[1], 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Assert-JavaRuntimeRemovalTarget([string]$Parent, [string]$Target, [string]$ExpectedLeaf) {
    $parentPath = Get-ControlAbsolutePath $Parent
    $targetPath = Get-ControlAbsolutePath $Target
    if ([IO.Path]::GetDirectoryName($targetPath) -ine $parentPath -or
        [IO.Path]::GetFileName($targetPath) -cne $ExpectedLeaf) {
        throw '再帰削除の対象が想定した親ディレクトリ直下ではありません。'
    }
    return $targetPath
}

function Install-JavaRuntimeArchive([string]$Archive, [string]$Hash, [string]$RootName, [string]$Root,
    [string]$CertificatePath, [string]$CertificateHash) {
    $release = Join-Path $Root ($RootName + '-' + $Hash.Substring(0, 12).ToLowerInvariant())
    $java = Join-Path $release 'bin\java.exe'
    $marker = Join-Path $release 'control-java-archive.sha256'
    if (Test-Path -LiteralPath $release) {
        $null = Get-ControlAbsolutePath $release
        if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $marker -PathType Leaf) -or
            (Get-Content -Raw -Encoding UTF8 -LiteralPath $marker).Trim() -ine $Hash) { throw '既存JDK releaseの検証に失敗しました。' }
        Test-Java25Executable $java
        Assert-JavaTrust $release $CertificatePath $CertificateHash $false
        return $java
    }
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    $stagingLeaf = '.stage-' + [guid]::NewGuid().ToString('N')
    $staging = Join-Path $Root $stagingLeaf
    try {
        [IO.Compression.ZipFile]::ExtractToDirectory($Archive, $staging)
        $unpacked = Join-Path $staging $RootName
        if (!(Test-Path -LiteralPath (Join-Path $unpacked 'bin\java.exe') -PathType Leaf)) { throw '展開したJDKを確認できません。' }
        [IO.File]::WriteAllText((Join-Path $unpacked 'control-java-archive.sha256'), $Hash.ToLowerInvariant() + "`n", [Text.UTF8Encoding]::new($false))
        Set-JavaRuntimeAcl $unpacked
        Test-Java25Executable (Join-Path $unpacked 'bin\java.exe')
        Assert-JavaTrust $unpacked $CertificatePath $CertificateHash $true
        [IO.Directory]::Move($unpacked, $release)
        return $java
    } finally {
        if (Test-Path -LiteralPath $staging) {
            $safeStaging = Assert-JavaRuntimeRemovalTarget $Root $staging $stagingLeaf
            Remove-Item -LiteralPath $safeStaging -Recurse -Force
        }
    }
}

function Backup-JavaRuntimeConfig([string]$Path) {
    $directory = Join-Path (Split-Path -Parent $Path) 'java-runtime-backups'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Set-Acl -LiteralPath $directory -AclObject (Get-Acl -LiteralPath (Split-Path -Parent $Path))
    $backup = Join-Path $directory ("config.$([guid]::NewGuid().ToString('N')).backup")
    Copy-Item -LiteralPath $Path -Destination $backup
    Set-Acl -LiteralPath $backup -AclObject (Get-Acl -LiteralPath $Path)
    return $backup
}

function Write-JavaRuntimeConfig([string]$Path, $Value) {
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 64), [Text.UTF8Encoding]::new($false))
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Path)
        [IO.File]::Move($temporary, $Path, $true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Restore-JavaRuntimeConfig([string]$Path, [string]$Backup) {
    if (!(Test-Path -LiteralPath $Backup -PathType Leaf)) { throw '設定のバックアップがありません。' }
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.restore'
    try {
        Copy-Item -LiteralPath $Backup -Destination $temporary
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Backup)
        [IO.File]::Move($temporary, $Path, $true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Invoke-ControlJavaRuntimeUpdate {
    [CmdletBinding(SupportsShouldProcess)]
    param([string]$Archive, [string]$Hash, [guid]$Id, [string]$Cli, [string]$CertificatePath, [string]$CertificateHash,
        [string]$RootName, [string]$Root, [string]$AgentRoot, [string]$RunnerRoot,
        [string]$ComputerName = [Environment]::MachineName)
    if ($ComputerName -ine 'MC-01') { throw 'この更新はMC-01専用です。' }
    $archivePath = Assert-JdkArchive $Archive $Hash $RootName
    $trustedCertificate = Assert-TrustedCertificate $CertificatePath $CertificateHash
    Assert-DefaultJavaTrustStore
    $installPath = Get-ControlAbsolutePath $Root
    $agentPath = Join-Path (Get-ControlAbsolutePath $AgentRoot) 'config.json'
    $runnerPath = Join-Path (Get-ControlAbsolutePath $RunnerRoot) 'config.json'
    $cliPath = Get-ControlAbsolutePath $Cli
    if (!(Test-Path -LiteralPath $cliPath -PathType Leaf)) { throw 'Control CLIがありません。' }
    $agent = Read-JavaRuntimeConfig $agentPath
    $runner = Read-JavaRuntimeConfig $runnerPath
    $oldPath = Assert-JavaResourcePair $agent $runner $Id
    $releasePath = Join-Path $installPath ($RootName + '-' + $Hash.Substring(0, 12).ToLowerInvariant())
    $newPath = Join-Path $releasePath 'bin\java.exe'
    $runnerService = Get-JavaRuntimeService 'Runner'
    $agentService = Get-JavaRuntimeService 'Agent'
    if ($runnerService.Status -ne 'Running') { throw 'Runnerが稼働していません。' }
    $agentWasRunning = $agentService.Status -eq 'Running'
    $pipe = [string]$runner['pipeName']
    if (!$pipe) { $pipe = 'AstralRecordControl.Runner.v1' }
    if ($pipe -cnotmatch '^[A-Za-z0-9._-]+$' -or [string]$agent['runnerPipeName'] -cne $pipe) { throw 'Runner pipe設定が一致しません。' }
    Assert-JavaRuntimeStopped $cliPath $pipe
    if ($oldPath -ieq $newPath) {
        $null = Install-JavaRuntimeArchive $archivePath $Hash $RootName $installPath $trustedCertificate $CertificateHash
        Write-Output "Java 25 runtimeは登録済みです: $newPath"
        return
    }
    if (!$PSCmdlet.ShouldProcess('MC-01 Java executable resource', "Install $newPath and update Agent/Runner settings")) { return }
    $newJava = Install-JavaRuntimeArchive $archivePath $Hash $RootName $installPath $trustedCertificate $CertificateHash
    $agentBackup = $null; $runnerBackup = $null; $agentStopped = $false; $runnerStopped = $false
    $configChanged = $false
    try {
        if ($agentWasRunning) { Stop-JavaRuntimeService 'Agent'; $agentStopped = $true }
        Assert-JavaRuntimeStopped $cliPath $pipe
        $agentBackup = Backup-JavaRuntimeConfig $agentPath
        $runnerBackup = Backup-JavaRuntimeConfig $runnerPath
        Stop-JavaRuntimeService 'Runner'; $runnerStopped = $true
        foreach ($config in @($agent, $runner)) {
            $entry = @($config['localResources']['executables'] | Where-Object { [string]$_['id'] -ieq $Id.ToString('D') })[0]
            $entry['path'] = $newJava
        }
        $configChanged = $true
        Write-JavaRuntimeConfig $agentPath $agent
        Write-JavaRuntimeConfig $runnerPath $runner
        $verifyAgent = Read-JavaRuntimeConfig $agentPath
        $verifyRunner = Read-JavaRuntimeConfig $runnerPath
        if ((Assert-JavaResourcePair $verifyAgent $verifyRunner $Id) -ine $newJava) { throw '新しいJava設定の検証に失敗しました。' }
        Test-ControlConfiguration $AgentRoot 'Agent'
        Test-ControlConfiguration $RunnerRoot 'Runner'
        Start-JavaRuntimeService 'Runner'; $runnerStopped = $false
        Assert-JavaRuntimeStopped $cliPath $pipe
        if ($agentWasRunning) { Start-JavaRuntimeService 'Agent'; $agentStopped = $false }
        Write-Output "Java 25 runtimeを登録しました: $newJava"
        Write-Output "設定backup: $agentBackup ; $runnerBackup"
    } catch {
        $failure = $_
        $recoveryErrors = [Collections.Generic.List[string]]::new()
        try {
            if ((Get-JavaRuntimeService 'Agent').Status -eq 'Running') { Stop-JavaRuntimeService 'Agent' }
            if ($configChanged -and (Get-JavaRuntimeService 'Runner').Status -eq 'Running') {
                Assert-JavaRuntimeStopped $cliPath $pipe
                Stop-JavaRuntimeService 'Runner'
            }
            if ($configChanged) {
                Restore-JavaRuntimeConfig $agentPath $agentBackup
                Restore-JavaRuntimeConfig $runnerPath $runnerBackup
            }
            if ((Get-JavaRuntimeService 'Runner').Status -eq 'Stopped') { Start-JavaRuntimeService 'Runner' }
            if ($agentWasRunning) { Start-JavaRuntimeService 'Agent' }
        } catch { $recoveryErrors.Add($_.Exception.Message) }
        if ($recoveryErrors.Count) { throw "Java更新失敗、復旧未完了: $($failure.Exception.Message); $($recoveryErrors -join '; '); backups: $agentBackup ; $runnerBackup" }
        throw "Java更新失敗、元の設定とサービス状態へ復旧: $($failure.Exception.Message)"
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    if (!$IsWindows) { throw 'Windows上で実行してください。' }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw '管理者PowerShellで実行してください。' }
    Invoke-ControlJavaRuntimeUpdate -Archive $JdkArchive -Hash $ExpectedArchiveSha256 -Id $ExecutableId -Cli $CliPath `
        -CertificatePath $TrustedCertificatePath -CertificateHash $ExpectedCertificateSha256 `
        -RootName $JdkRootName -Root $InstallRoot -AgentRoot $AgentDataRoot -RunnerRoot $RunnerDataRoot
}
