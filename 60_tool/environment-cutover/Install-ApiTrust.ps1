#requires -Version 7.0
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Install','Verify')][string]$Phase,
    [Parameter(Mandatory)][string]$RootCertificatePath,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedSha256,
    [string]$ReportDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ApiTls.psm1') -Force
$runId=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[Guid]::NewGuid().ToString('N')
$report=[ordered]@{schemaVersion=1;computer=$env:COMPUTERNAME;phase=$Phase;runId=$runId;success=$false;stage='Preflight';errorType=$null;publicCaSha256=$null;javaPath=$null}
$stageDirectory=$null
try {
Assert-ApiTlsMachine @('DEV-01','MC-01')
if (!(Test-Path -LiteralPath $RootCertificatePath -PathType Leaf)) { throw 'Public API CA certificate is missing.' }
$stageDirectory=Join-Path $env:ProgramData ('AstralRecordControl\api-tls-trust-'+[Guid]::NewGuid().ToString('N'))
New-ApiTlsPrivateDirectory $stageDirectory
$pinnedCertificatePath=Join-Path $stageDirectory 'pinned-root.cer'
Copy-ApiTlsPinnedCertificate $RootCertificatePath $pinnedCertificatePath $ExpectedSha256 | Out-Null
Set-Acl -LiteralPath $pinnedCertificatePath -AclObject (New-ApiTlsKeyAcl)
if ((Get-ApiTlsSha256 $pinnedCertificatePath) -cne $ExpectedSha256.ToUpperInvariant()) { throw 'Pinned API CA snapshot changed after restricting access.' }
$report.publicCaSha256=$ExpectedSha256.ToUpperInvariant()
$ca=[Security.Cryptography.X509Certificates.X509Certificate2]::new($pinnedCertificatePath)
if ($ca.HasPrivateKey) { throw 'The public CA file unexpectedly contains a private key.' }
Assert-ApiTlsRoot $ca | Out-Null

$javaPath=switch ($env:COMPUTERNAME) {
    'DEV-01' {'C:\Program Files\AstralRecordControl\Java\jdk-25.0.4.1+1\bin\java.exe'}
    'MC-01' {'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'}
}
$report.javaPath=$javaPath
$javaHome=Split-Path -Parent (Split-Path -Parent $javaPath)
$keytool=Join-Path $javaHome 'bin\keytool.exe'
$cacerts=Join-Path $javaHome 'lib\security\cacerts'
foreach ($path in @($javaPath,$keytool,$cacerts)) { if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw 'The registered Java runtime or its cacerts is missing.' } }

# A custom truststore would make importing into this JDK's cacerts ineffective.
foreach ($scope in @('Process','User','Machine')) {
    foreach ($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')) {
        $value=[Environment]::GetEnvironmentVariable($name,$scope)
        if ($value -match '(?i)-Djavax\.net\.ssl\.trustStore(?:=|\s)') { throw 'A Java environment option selects a custom truststore.' }
    }
}
foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
    if ($process.CommandLine -match '(?i)-Djavax\.net\.ssl\.trustStore(?:=|\s)') { throw 'A running Java process selects a custom truststore.' }
}

$trusted=@(Get-ApiTlsCertificates 'CN=AstralRecord API Internal CA' 'Cert:\LocalMachine\Root')
if ($trusted.Count -gt 1 -or ($trusted.Count -eq 1 -and $trusted[0].Thumbprint -cne $ca.Thumbprint)) { throw 'A different API CA is present in Windows Trusted Root.' }

$alias='astralrecord-api-root'
# cacerts contains public trust anchors. Use the JDK's documented initial store password;
# do not collect or persist any administrator-selected password in this local setup tool.
$storeArguments=@('-cacerts','-storepass','changeit')
if ($Phase -eq 'Install') {
    $report.stage='JavaTrustStorePreflight'
    $null=& $keytool -list @storeArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'The Java trust store is customized or unreadable; the automatic standard-store setup made no trust changes.' }
    $backupPath=Join-Path $stageDirectory 'cacerts.before'
    $report.stage='BackingUpJavaTrust'
    Copy-Item -LiteralPath $cacerts -Destination $backupPath -ErrorAction Stop
    Set-Acl -LiteralPath $backupPath -AclObject (New-ApiTlsKeyAcl)
    Write-Host "Protected cacerts backup: $backupPath"
    Write-Host 'Updating the standard JDK public trust store automatically. A separately customized store must be prepared before rerunning.'
    $existingPath=Join-Path $stageDirectory 'existing-alias.cer'
    & $keytool -exportcert @storeArguments -alias $alias -file $existingPath
    if ($LASTEXITCODE -eq 0) {
        if ((Get-ApiTlsSha256 $existingPath) -cne $ExpectedSha256.ToUpperInvariant()) { throw 'The Java trust alias belongs to another CA.' }
        Write-Host 'The pinned public API CA is already in this Java cacerts.'
    } else {
        $report.stage='InstallingJavaTrust'
        # The protected DER snapshot was validated and SHA-256 pinned above.
        & $keytool -importcert @storeArguments -alias $alias -file $pinnedCertificatePath -trustcacerts -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'keytool could not import the pinned public API CA.' }
    }
    $verifiedPath=Join-Path $stageDirectory 'verified-alias.cer'
    & $keytool -exportcert @storeArguments -alias $alias -file $verifiedPath
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $verifiedPath -PathType Leaf) -or (Get-ApiTlsSha256 $verifiedPath) -cne $ExpectedSha256.ToUpperInvariant()) { throw 'Java trust alias did not match the pinned public API CA after import.' }
    $report.stage='InstallingWindowsTrust'
    if ($trusted.Count -eq 0) { Import-Certificate -CertStoreLocation 'Cert:\LocalMachine\Root' -FilePath $pinnedCertificatePath | Out-Null }
    $trusted=@(Get-ApiTlsCertificates 'CN=AstralRecord API Internal CA' 'Cert:\LocalMachine\Root')
    if ($trusted.Count -ne 1 -or $trusted[0].Thumbprint -cne $ca.Thumbprint) { throw 'Windows trusted API CA did not match the pinned certificate.' }
    $report.stage='TrustInstalled'; $report.success=$true
    Write-Host "API CA trusted on $env:COMPUTERNAME. Public SHA256: $($ExpectedSha256.ToUpperInvariant())"
    Write-Host 'After APP-01 Bind, run this script again with -Phase Verify.'
    return
}

if ($trusted.Count -ne 1 -or $trusted[0].Thumbprint -cne $ca.Thumbprint) { throw 'Pinned API CA is not in Windows Trusted Root.' }
$export=Join-Path $stageDirectory 'trusted-java-ca.cer'
$report.stage='VerifyingTrust'
& $keytool -exportcert @storeArguments -alias $alias -file $export
if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $export -PathType Leaf) -or (Get-ApiTlsSha256 $export) -cne $ExpectedSha256.ToUpperInvariant()) { throw 'Pinned API CA is not in this Java cacerts.' }
Test-ApiTlsHealth
& $javaPath (Join-Path $PSScriptRoot 'Test-ApiTls.java')
if ($LASTEXITCODE -ne 0) { throw 'Java rejected normal API TLS verification.' }
$report.stage='TrustVerified'; $report.success=$true
Write-Host "Windows and Java API TLS verification passed on $env:COMPUTERNAME."
} catch {
    $report.errorType=$_.Exception.GetType().FullName
    Write-Host 'Trust setup stopped. Correct the cause and rerun the same phase; if Java trust was partly changed, inspect the protected cacerts.before backup for manual recovery.'
    throw
} finally {
    if ($Phase -eq 'Verify' -and $stageDirectory -and (Test-Path -LiteralPath $stageDirectory -PathType Container)) {
        Get-ChildItem -LiteralPath $stageDirectory -File | Remove-Item -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stageDirectory -Force -ErrorAction SilentlyContinue
    }
    if (!$ReportDirectory) {
        $ReportDirectory=if ($env:COMPUTERNAME -eq 'DEV-01') { 'C:\AstralRecord-Worktrees\logs\EnvironmentCutover' } else { 'E:\mc-01\ControlDeployment\EnvironmentCutover' }
    }
    if (![IO.Path]::IsPathFullyQualified($ReportDirectory) -or $ReportDirectory.StartsWith('\\')) { throw 'A local report directory is required.' }
    New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
    $report | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $ReportDirectory ($env:COMPUTERNAME+'-api-trust-'+$runId+'.json'))
    Write-Host "API trust report directory: $ReportDirectory"
}
