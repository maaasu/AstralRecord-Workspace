#requires -Version 7.0
param(
    [string]$OfficialArchive = '',
    [string]$OfficialArchiveSha256 = '',
    [string]$OfficialCertificatePath = '',
    [string]$OfficialCertificateSha256 = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Expect-Failure([scriptblock]$Action, [string]$Message) {
    try { & $Action; throw "Expected failure: $Message" }
    catch { if ($_.Exception.Message -like 'Expected failure:*') { throw } }
}

$scriptPath = Join-Path $PSScriptRoot '..\Update-ControlJavaRuntime.ps1'
. $scriptPath -JdkArchive 'unused.zip' -ExpectedArchiveSha256 ('0' * 64) -ExecutableId '233fe4fc-6564-4f56-a616-b123e8db9f7d' -CliPath 'unused.exe' -TrustedCertificatePath 'unused.cer' -ExpectedCertificateSha256 ('0' * 64)
$fixtureParent = [IO.Path]::GetTempPath()
$fixtureLeaf = 'control-java-runtime-test-' + [guid]::NewGuid().ToString('N')
$fixture = Join-Path $fixtureParent $fixtureLeaf
$javaId = [guid]'233fe4fc-6564-4f56-a616-b123e8db9f7d'
$velocityId = [guid]'e42d0000-0000-0000-0000-000000000001'
$rootName = 'jdk-25.0.4.1+1'
$archive = Join-Path $fixture 'jdk.zip'
$certificatePath = Join-Path $fixture 'api-root.cer'
$installRoot = Join-Path $fixture 'Java'
$agentRoot = Join-Path $fixture 'Agent'
$runnerRoot = Join-Path $fixture 'Runner'
$cli = Join-Path $fixture 'control-cli.exe'
$oldJava = Join-Path $fixture 'jdk-21\bin\java.exe'
$oldVelocity = Join-Path $fixture 'proxy-jdk-21\bin\java.exe'
$script:states = @{ Agent = 'Running'; Runner = 'Running' }
$script:safetyChecks = 0
$script:rejectSafetyAt = 0
$script:failAgentStartOnce = $false
$script:installCalls = 0

function New-TestConfig([string]$path, [string]$kind) {
    $resources = [ordered]@{
        roots = @([ordered]@{ id = '11111111-1111-1111-1111-111111111111'; path = $fixture; displayName = 'Minecraft' })
        executables = @(
            [ordered]@{ id = $javaId.ToString('D'); path = $oldJava; kind = 'Java'; displayName = 'Java' },
            [ordered]@{ id = $velocityId.ToString('D'); path = $oldVelocity; kind = 'Velocity'; displayName = 'Velocity' }
        )
    }
    $value = if ($kind -eq 'Agent') {
        [ordered]@{ hostId = '22222222-2222-2222-2222-222222222222'; runnerPipeName = 'AstralRecordControl.Runner.v1'; serverUrl = 'https://control.example.test'; tokenFile = 'credentials/token.bin'; localResources = $resources; customPreserved = 'agent-value' }
    } else {
        [ordered]@{ hostId = '22222222-2222-2222-2222-222222222222'; pipeName = 'AstralRecordControl.Runner.v1'; agentSid = 'S-1-5-80-1'; enableProcessControl = $true; localResources = $resources; customPreserved = 'runner-value' }
    }
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
}

# The real archive validator runs on a small temporary ZIP. Service/process calls below are mocked.
try {
    New-Item -ItemType Directory -Path $fixture, $agentRoot, $runnerRoot -Force | Out-Null
    [IO.File]::WriteAllText($cli, 'fixture')
    New-Item -ItemType Directory -Path (Split-Path -Parent $oldJava), (Split-Path -Parent $oldVelocity) -Force | Out-Null
    [IO.File]::WriteAllText($oldJava, 'fixture-java21')
    [IO.File]::WriteAllText($oldVelocity, 'fixture-velocity-java21')
    [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Create).Dispose()
    $zip = [IO.Compression.ZipFile]::Open($archive, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry("$rootName/bin/java.exe")
        $writer = [IO.StreamWriter]::new($entry.Open())
        try { $writer.Write('fixture') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    Assert ((Assert-JdkArchive $archive $hash $rootName) -eq $archive) 'Valid ZIP was rejected.'
    Expect-Failure { Assert-JdkArchive $archive ('0' * 64) $rootName } 'Wrong archive hash was accepted.'
    $unsafeArchive = Join-Path $fixture 'unsafe.zip'
    $zip = [IO.Compression.ZipFile]::Open($unsafeArchive, [IO.Compression.ZipArchiveMode]::Create)
    try { $null = $zip.CreateEntry("$rootName/../outside.txt"); $null = $zip.CreateEntry("$rootName/bin/java.exe") }
    finally { $zip.Dispose() }
    Expect-Failure { Assert-JdkArchive $unsafeArchive (Get-FileHash -LiteralPath $unsafeArchive -Algorithm SHA256).Hash $rootName } 'Path traversal ZIP was accepted.'
    $rsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new('CN=AstralRecord API Internal CA', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true, $false, 0, $true))
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddDays(30))
        try { [IO.File]::WriteAllBytes($certificatePath, $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert)) }
        finally { $certificate.Dispose() }
    } finally { $rsa.Dispose() }
    $certificateHash = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash
    Assert ((Assert-TrustedCertificate $certificatePath $certificateHash) -eq $certificatePath) 'Pinned public CA was rejected.'
    Expect-Failure { Assert-TrustedCertificate $certificatePath ('0' * 64) } 'Wrong CA hash was accepted.'
    if ($OfficialArchive) {
        if (!$OfficialArchiveSha256 -or !$OfficialCertificatePath -or !$OfficialCertificateSha256) { throw 'Official archive test requires both pinned SHA-256 values and the public CA.' }
        $null = Assert-JdkArchive $OfficialArchive $OfficialArchiveSha256 $rootName
        $null = Assert-TrustedCertificate $OfficialCertificatePath $OfficialCertificateSha256
        # Service SIDs are unavailable in the local test fixture; archive, Java and cacerts remain real.
        function Set-JavaRuntimeAcl([string]$Path) { }
        $realJava = Install-JavaRuntimeArchive $OfficialArchive $OfficialArchiveSha256 $rootName (Join-Path $fixture 'official-runtime') $OfficialCertificatePath $OfficialCertificateSha256
        Assert (Test-Path -LiteralPath $realJava -PathType Leaf) 'Official JDK was not installed in the fixture.'
        Write-Output 'Official Java 25 archive, executable and pinned API CA import passed.'
    }
    New-TestConfig (Join-Path $agentRoot 'config.json') 'Agent'
    New-TestConfig (Join-Path $runnerRoot 'config.json') 'Runner'

    function Get-JavaRuntimeService([string]$Component) { [pscustomobject]@{ Status = $script:states[$Component] } }
    function Stop-JavaRuntimeService([string]$Component) { $script:states[$Component] = 'Stopped' }
    function Start-JavaRuntimeService([string]$Component) {
        if ($Component -eq 'Agent' -and $script:failAgentStartOnce) {
            $script:failAgentStartOnce = $false
            throw 'InjectedAgentStartFailure'
        }
        $script:states[$Component] = 'Running'
    }
    function Assert-JavaRuntimeStopped([string]$Cli, [string]$Pipe) {
        $script:safetyChecks++
        if ($script:rejectSafetyAt -eq $script:safetyChecks) { throw 'InjectedUnsafeRunner' }
    }
    function Install-JavaRuntimeArchive([string]$Archive, [string]$Hash, [string]$RootName, [string]$Root, [string]$CertificatePath, [string]$CertificateHash) {
        $script:installCalls++
        $path = Join-Path $Root ($RootName + '-' + $Hash.Substring(0, 12).ToLowerInvariant() + '\bin\java.exe')
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        [IO.File]::WriteAllText($path, 'fixture-java25')
        return $path
    }
    function Test-ControlConfiguration([string]$DataRoot, [string]$Component) { }
    $arguments = @{ Archive = $archive; Hash = $hash; Id = $javaId; Cli = $cli; CertificatePath = $certificatePath; CertificateHash = $certificateHash; RootName = $rootName; Root = $installRoot; AgentRoot = $agentRoot; RunnerRoot = $runnerRoot; ComputerName = 'MC-01'; Confirm = $false }

    $script:safetyChecks = 0
    Invoke-ControlJavaRuntimeUpdate @arguments -WhatIf | Out-Null
    Assert ($script:installCalls -eq 0 -and $script:states.Agent -eq 'Running' -and $script:states.Runner -eq 'Running') 'WhatIf changed runtime or services.'

    $script:safetyChecks = 0; $script:rejectSafetyAt = 1
    Expect-Failure { Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null } 'Unsafe initial snapshot was accepted.'
    Assert ($script:installCalls -eq 0 -and $script:states.Agent -eq 'Running') 'Unsafe initial snapshot caused a change.'
    $script:rejectSafetyAt = 0

    $wrong = Read-JavaRuntimeConfig (Join-Path $agentRoot 'config.json')
    $wrong.localResources.executables[0].id = [guid]::NewGuid().ToString('D')
    Write-JavaRuntimeConfig (Join-Path $agentRoot 'config.json') $wrong
    Expect-Failure { Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null } 'Mismatched resource ID was accepted.'
    New-TestConfig (Join-Path $agentRoot 'config.json') 'Agent'

    $agentBefore = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $agentRoot 'config.json')
    $runnerBefore = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $runnerRoot 'config.json')
    $script:safetyChecks = 0; $script:rejectSafetyAt = 2
    Expect-Failure { Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null } 'Unsafe second snapshot was accepted.'
    Assert ($script:states.Agent -eq 'Running' -and $script:states.Runner -eq 'Running') 'Unsafe second snapshot did not restore services.'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $agentRoot 'config.json')) -ceq $agentBefore) 'Unsafe second snapshot changed Agent config.'
    $script:rejectSafetyAt = 0

    $script:safetyChecks = 0; $script:failAgentStartOnce = $true
    Expect-Failure { Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null } 'Injected Agent startup failure was not reported.'
    Assert ($script:states.Agent -eq 'Running' -and $script:states.Runner -eq 'Running') 'Rollback did not restore service states.'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $agentRoot 'config.json')) -ceq $agentBefore) 'Agent config was not rolled back.'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $runnerRoot 'config.json')) -ceq $runnerBefore) 'Runner config was not rolled back.'

    $script:safetyChecks = 0
    Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null
    $agentAfter = Read-JavaRuntimeConfig (Join-Path $agentRoot 'config.json')
    $runnerAfter = Read-JavaRuntimeConfig (Join-Path $runnerRoot 'config.json')
    $newPath = Assert-JavaResourcePair $agentAfter $runnerAfter $javaId
    Assert ($newPath -ne $oldJava -and $agentAfter.localResources.executables[1].path -eq $oldVelocity -and
        $runnerAfter.localResources.executables[1].path -eq $oldVelocity) 'Java changed incorrectly or Velocity was modified.'
    Assert ($agentAfter.customPreserved -eq 'agent-value' -and $runnerAfter.customPreserved -eq 'runner-value') 'Unrelated settings were modified.'
    Assert ($script:states.Agent -eq 'Running' -and $script:states.Runner -eq 'Running') 'Services were not restored after success.'
    $installedCalls = $script:installCalls
    Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null
    Assert ($script:installCalls -eq $installedCalls + 1 -and $script:states.Agent -eq 'Running') 'Idempotent replay failed.'
    New-TestConfig (Join-Path $agentRoot 'config.json') 'Agent'
    New-TestConfig (Join-Path $runnerRoot 'config.json') 'Runner'
    $script:states.Agent = 'Stopped'; $script:safetyChecks = 0
    Invoke-ControlJavaRuntimeUpdate @arguments | Out-Null
    Assert ($script:states.Agent -eq 'Stopped' -and $script:states.Runner -eq 'Running') 'Initially stopped Agent was started.'
    Write-Output 'Java runtime update fixture passed: hash, WhatIf, safety checks, resource identity, rollback, Velocity preservation.'
} finally {
    if (Test-Path -LiteralPath $fixture) {
        $safeFixture = Assert-JavaRuntimeRemovalTarget $fixtureParent $fixture $fixtureLeaf
        Remove-Item -LiteralPath $safeFixture -Recurse -Force
    }
}
