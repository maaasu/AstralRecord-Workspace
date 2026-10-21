#requires -Version 7.0
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Prepare','Bind')][string]$Phase,
    [string]$PublicRootPath='C:\app-01\ControlDeployment\EnvironmentCutover\AstralRecord-Api-Root.cer',
    [string]$ReportDirectory='C:\app-01\ControlDeployment\EnvironmentCutover',
    [ValidatePattern('^[A-Fa-f0-9]{40}$')][string]$ExpectedCurrentThumbprint
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ApiTls.psm1') -Force
$runId=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'-'+[Guid]::NewGuid().ToString('N')
$report=[ordered]@{schemaVersion=1;computer='APP-01';phase=$Phase;runId=$runId;success=$false;stage='Preflight';errorType=$null;publicCaSha256=$null;leafThumbprint=$null;leafExpiresUtc=$null}
try {
Assert-ApiTlsMachine @('APP-01')
Assert-ApiTlsIisBinding
if (![IO.Path]::IsPathFullyQualified($ReportDirectory) -or $ReportDirectory.StartsWith('\\')) { throw 'A local report directory is required.' }
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null

$caSubject='CN=AstralRecord API Internal CA'
$leafSubject='CN=app-01'
$cas=@(Get-ApiTlsCertificates $caSubject)
$leaves=@(Get-ApiTlsCertificates $leafSubject)
if ($cas.Count -gt 1 -or $leaves.Count -gt 1) { throw 'Multiple same-name API certificates; resolve manually before continuing.' }
if (($cas.Count -eq 0) -ne ($leaves.Count -eq 0)) { throw 'Partial API certificate installation; inspect before continuing.' }
$preexistingTrust=@(Get-ApiTlsCertificates $caSubject 'Cert:\LocalMachine\Root')
if ($preexistingTrust.Count -gt 1 -or ($preexistingTrust.Count -eq 1 -and ($cas.Count -ne 1 -or $preexistingTrust[0].Thumbprint -cne $cas[0].Thumbprint))) { throw 'A different or orphaned API CA is already trusted.' }

if ($Phase -eq 'Prepare') {
    $report.stage='PreparingCertificates'
    if (![IO.Path]::IsPathFullyQualified($PublicRootPath) -or $PublicRootPath.StartsWith('\\')) { throw 'Use an absolute local public-certificate path.' }
    if ($cas.Count -eq 0 -and (Test-Path -LiteralPath $PublicRootPath)) { throw 'Public CA export path already exists before initial certificate creation.' }
    if ($cas.Count -eq 0) {
        $keyAcl=New-ApiTlsKeyAcl
        $ca=New-SelfSignedCertificate -Type Custom -Subject $caSubject -CertStoreLocation 'Cert:\LocalMachine\My' -Provider 'Microsoft Software Key Storage Provider' `
            -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable -SecurityDescriptor $keyAcl `
            -KeyUsage CertSign,CRLSign -TextExtension '2.5.29.19={critical}{text}CA=1&pathlength=0' `
            -NotAfter (Get-Date).AddYears(10)
        Assert-ApiTlsRoot $ca -RequirePrivateKey | Out-Null
        $leaf=New-SelfSignedCertificate -Type Custom -Subject $leafSubject -Signer $ca -CertStoreLocation 'Cert:\LocalMachine\My' -Provider 'Microsoft Software Key Storage Provider' `
            -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable -SecurityDescriptor $keyAcl `
            -KeyUsage DigitalSignature,KeyEncipherment -TextExtension @(
                '2.5.29.17={text}DNS=app-01&IPAddress=192.168.0.88',
                '2.5.29.37={text}1.3.6.1.5.5.7.3.1',
                '2.5.29.19={critical}{text}CA=0') -NotAfter (Get-Date).AddYears(1)
    } else { $ca=$cas[0]; $leaf=$leaves[0] }
    Assert-ApiTlsRoot $ca -RequirePrivateKey | Out-Null
    Assert-ApiTlsLeaf $leaf $ca -RequirePrivateKey | Out-Null
    Assert-ApiTlsPrivateKeyAcl $ca
    Assert-ApiTlsPrivateKeyAcl $leaf
    $publicDir=Split-Path -Parent $PublicRootPath
    New-Item -ItemType Directory -Path $publicDir -Force | Out-Null
    if (Test-Path -LiteralPath $PublicRootPath) {
        $existing=[Security.Cryptography.X509Certificates.X509Certificate2]::new($PublicRootPath)
        if ($existing.Thumbprint -cne $ca.Thumbprint) { throw 'Public CA export path contains a different certificate.' }
    } else { Export-Certificate -Cert $ca -FilePath $PublicRootPath -Type CERT -NoClobber | Out-Null }
    $preparedCaHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ca.RawData))
    if ((Get-ApiTlsSha256 $PublicRootPath) -cne $preparedCaHash) { throw 'Public CA export differs from the prepared certificate.' }
    $protectedExportDirectory=Join-Path $env:ProgramData ('AstralRecordControl\api-tls-prepare-'+[Guid]::NewGuid().ToString('N'))
    New-ApiTlsPrivateDirectory $protectedExportDirectory
    $protectedExportPath=Join-Path $protectedExportDirectory 'prepared-root.cer'
    Export-Certificate -Cert $ca -FilePath $protectedExportPath -Type CERT -NoClobber | Out-Null
    Set-Acl -LiteralPath $protectedExportPath -AclObject (New-ApiTlsKeyAcl)
    if ((Get-ApiTlsSha256 $protectedExportPath) -cne $preparedCaHash) { throw 'Protected API CA export differs from the prepared certificate.' }
    $trusted=@(Get-ApiTlsCertificates $caSubject 'Cert:\LocalMachine\Root')
    if ($trusted.Count -gt 1 -or ($trusted.Count -eq 1 -and $trusted[0].Thumbprint -cne $ca.Thumbprint)) { throw 'Unexpected trusted API CA certificate.' }
    if ($trusted.Count -eq 0) { Import-Certificate -CertStoreLocation 'Cert:\LocalMachine\Root' -FilePath $protectedExportPath | Out-Null }
    $trusted=@(Get-ApiTlsCertificates $caSubject 'Cert:\LocalMachine\Root')
    if ($trusted.Count -ne 1 -or $trusted[0].Thumbprint -cne $ca.Thumbprint) { throw 'API CA trust installation did not match the prepared certificate.' }
    $report.publicCaSha256=$preparedCaHash
    $report.leafThumbprint=$leaf.Thumbprint
    $report.leafExpiresUtc=$leaf.NotAfter.ToUniversalTime().ToString('O')
    $report.stage='Prepared'; $report.success=$true
    Write-Host "API TLS prepared. Public CA SHA256: $preparedCaHash"
    Write-Host "CA thumbprint: $($ca.Thumbprint); leaf thumbprint: $($leaf.Thumbprint); leaf expires UTC: $($leaf.NotAfter.ToUniversalTime().ToString('O'))"
    Write-Host 'Distribute the public CA and verify trust on both clients before Bind.'
    return
}

if (!$ExpectedCurrentThumbprint) { throw 'Bind requires the expected current HTTP.sys certificate thumbprint.' }
if ($cas.Count -ne 1 -or $leaves.Count -ne 1) { throw 'Prepare the API CA and leaf certificate first.' }
$ca=$cas[0]; $leaf=$leaves[0]
Assert-ApiTlsRoot $ca -RequirePrivateKey | Out-Null
Assert-ApiTlsLeaf $leaf $ca -RequirePrivateKey | Out-Null
Assert-ApiTlsPrivateKeyAcl $ca
Assert-ApiTlsPrivateKeyAcl $leaf
$trusted=@(Get-ApiTlsCertificates $caSubject 'Cert:\LocalMachine\Root')
if ($trusted.Count -ne 1 -or $trusted[0].Thumbprint -cne $ca.Thumbprint) { throw 'API CA is not trusted on APP-01.' }
$before=Get-ApiTlsHttpBinding
if ($before.thumbprint -cne $ExpectedCurrentThumbprint.ToUpperInvariant()) { throw 'HTTP.sys binding changed since its expected snapshot.' }
if ($before.thumbprint -ceq $leaf.Thumbprint) {
    Test-ApiTlsHealth
    $report.publicCaSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ca.RawData))
    $report.leafThumbprint=$leaf.Thumbprint
    $report.leafExpiresUtc=$leaf.NotAfter.ToUniversalTime().ToString('O')
    $report.stage='AlreadyBound'; $report.success=$true
    Write-Host 'API TLS binding is already current.'
    return
}
$backup=Join-Path $env:ProgramData ('AstralRecordControl\api-tls-'+[Guid]::NewGuid().ToString('N'))
$null=New-ApiTlsHttpUpdateArguments $before.raw $leaf.Thumbprint
New-ApiTlsPrivateDirectory $backup
$backupPath=Join-Path $backup 'http-sys-before.json'
$afterPath=Join-Path $backup 'http-sys-after.json'
ConvertTo-ApiTlsHttpBindingJson $before.raw $before.thumbprint | Set-Content -Encoding utf8NoBOM -LiteralPath $backupPath
ConvertTo-ApiTlsHttpBindingJson $before.raw $leaf.Thumbprint | Set-Content -Encoding utf8NoBOM -LiteralPath $afterPath
$changed=$false
try {
    $report.stage='UpdatingBinding'
    $report['nativeUpdateOutput']='APP-01-api-tls-'+$runId+'-update.txt'
    $changed=$true
    Set-ApiTlsHttpBinding $before.raw $leaf.Thumbprint (Join-Path $ReportDirectory $report.nativeUpdateOutput)
    $after=Get-ApiTlsHttpBinding
    if ($after.thumbprint -cne $leaf.Thumbprint) { throw 'HTTP.sys did not bind the selected API leaf.' }
    Assert-ApiTlsHttpBindingRestored $before $after $leaf.Thumbprint
    Test-ApiTlsHealth
    $report.publicCaSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($ca.RawData))
    $report.leafThumbprint=$leaf.Thumbprint
    $report.leafExpiresUtc=$leaf.NotAfter.ToUniversalTime().ToString('O')
    $report.stage='BoundAndVerified'; $report.success=$true
    Write-Host "API TLS binding updated. Leaf thumbprint: $($leaf.Thumbprint); rollback snapshot: $backupPath"
} catch {
    $original=$_.Exception.Message
    if ($changed) {
        $report.stage='RollingBackBinding'
        $report['nativeRestoreOutput']='APP-01-api-tls-'+$runId+'-restore.txt'
        $report['rollbackVerified']=$false
        try { Restore-ApiTlsHttpBinding $before (Join-Path $ReportDirectory $report.nativeRestoreOutput) }
        catch {
            $report.stage='RollbackUnverified'
            throw "API TLS failed and automatic rollback could not be verified; inspect $backupPath and the current binding before retrying. Cause: $original"
        }
        $report['rollbackVerified']=$true
    }
    throw "API TLS binding was not accepted; previous certificate restored if changed. Cause: $original"
}
} catch {
    $report.errorType=$_.Exception.GetType().FullName
    throw
} finally {
    if (![IO.Path]::IsPathFullyQualified($ReportDirectory) -or $ReportDirectory.StartsWith('\\')) { throw 'A local report directory is required.' }
    New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
    $report | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $ReportDirectory ('APP-01-api-tls-'+$runId+'.json'))
    Write-Host "API TLS report directory: $ReportDirectory"
}
