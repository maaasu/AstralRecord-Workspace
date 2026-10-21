Set-StrictMode -Version Latest

$script:ApiCaSubject = 'CN=AstralRecord API Internal CA'
$script:ApiLeafSubject = 'CN=app-01'
$script:ApiDnsName = 'app-01'
$script:ApiIpAddress = '192.168.0.88'
$script:ApiBindingKey = '0.0.0.0:444'
$script:ApiTrustAlias = 'astralrecord-api-root'

function Assert-ApiTlsMachine([string[]]$AllowedComputers) {
    if (!$IsWindows -or $env:COMPUTERNAME -notin $AllowedComputers) { throw 'Run locally on the specified Windows computer.' }
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent()
    $principal=[Security.Principal.WindowsPrincipal]::new($identity)
    if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run in an elevated administrator session.' }
}

function New-ApiTlsKeyAcl {
    $acl=[Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $administrators=[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $acl.SetOwner($administrators)
    $acl.SetGroup($administrators)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $acl
}

function New-ApiTlsPrivateDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw 'Private backup directory already exists.' }
    New-Item -ItemType Directory -Path $Path -ErrorAction Stop | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $administrators=[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $acl.SetOwner($administrators)
    $acl.SetGroup($administrators)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Get-ApiTlsCertificates([string]$Subject,[string]$Store='Cert:\LocalMachine\My') {
    return @(Get-ChildItem -LiteralPath $Store | Where-Object { $_.Subject -ceq $Subject })
}

function Get-ApiTlsSha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Copy-ApiTlsPinnedCertificate([string]$Source,[string]$Destination,[string]$ExpectedSha256) {
    if ($ExpectedSha256 -cnotmatch '^[A-Fa-f0-9]{64}$' -or (Test-Path -LiteralPath $Destination)) { throw 'Pinned certificate destination or SHA-256 is invalid.' }
    Copy-Item -LiteralPath $Source -Destination $Destination -ErrorAction Stop
    if ((Get-ApiTlsSha256 $Destination) -cne $ExpectedSha256.ToUpperInvariant()) { throw 'Pinned local public API CA snapshot SHA-256 differs.' }
    return $Destination
}

function Get-ApiTlsExtension($Certificate,[string]$Oid) {
    $items=@($Certificate.Extensions | Where-Object { $_.Oid.Value -eq $Oid })
    if ($items.Count -ne 1) { throw "Expected one X.509 extension $Oid." }
    return $items[0]
}

function Assert-ApiTlsPrivateKeyAcl($Certificate) {
    if (!$Certificate.HasPrivateKey) { throw 'Certificate private key is unavailable.' }
    $rsa=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPrivateKey($Certificate)
    try {
        if ($rsa -isnot [Security.Cryptography.RSACng] -or !$rsa.Key.IsMachineKey) { throw 'Expected a machine-scoped CNG private key.' }
        if ($rsa.Key.ExportPolicy -ne [Security.Cryptography.CngExportPolicies]::None) { throw 'API TLS private key is exportable.' }
        $keyName=$rsa.Key.UniqueName
        if ($keyName -notmatch '^[A-Za-z0-9_-]+$') { throw 'Unexpected CNG key name.' }
        $keyPath=Join-Path $env:ProgramData "Microsoft\Crypto\Keys\$keyName"
        $acl=Get-Acl -LiteralPath $keyPath -ErrorAction Stop
        if (!$acl.AreAccessRulesProtected) { throw 'API TLS private key inherits file permissions.' }
        $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
        $allowed=@('S-1-5-18','S-1-5-32-544')
        if (@($rules | Where-Object { $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -notin $allowed }).Count -gt 0) { throw 'API TLS private key permits another account.' }
        foreach ($sid in $allowed) {
            if (@($rules | Where-Object { $_.IdentityReference.Value -eq $sid -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) }).Count -eq 0) { throw 'SYSTEM/Administrators lack API TLS private-key access.' }
        }
    } finally { if ($rsa) { $rsa.Dispose() } }
}

function Assert-ApiTlsRoot($Certificate,[switch]$RequirePrivateKey) {
    if ($Certificate.Subject -cne $script:ApiCaSubject -or $Certificate.Issuer -cne $script:ApiCaSubject) { throw 'Unexpected API CA subject/issuer.' }
    if ($RequirePrivateKey -and !$Certificate.HasPrivateKey) { throw 'API CA private key is unavailable.' }
    $bc=Get-ApiTlsExtension $Certificate '2.5.29.19'
    $bc=[Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($bc,$bc.Critical)
    if (!$bc.Critical -or !$bc.CertificateAuthority -or !$bc.HasPathLengthConstraint -or $bc.PathLengthConstraint -ne 0) { throw 'API CA basic constraints must be critical CA=true, pathLen=0.' }
    $ku=Get-ApiTlsExtension $Certificate '2.5.29.15'
    $ku=[Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($ku,$ku.Critical)
    $required=[Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign -bor [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::CrlSign
    if (($ku.KeyUsages -band $required) -ne $required) { throw 'API CA key usages must allow certificate and CRL signing.' }
    if ($Certificate.SignatureAlgorithm.Value -ne '1.2.840.113549.1.1.11') { throw 'API CA must use RSA/SHA-256.' }
    $rsa=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($Certificate)
    try { if ($null -eq $rsa -or $rsa.KeySize -lt 3072) { throw 'API CA RSA key must be at least 3072 bits.' } } finally { if ($rsa) { $rsa.Dispose() } }
    if ($Certificate.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow) { throw 'API CA has expired.' }
    return $true
}

function Assert-ApiTlsLeaf($Certificate,$Root,[switch]$RequirePrivateKey) {
    Assert-ApiTlsRoot $Root | Out-Null
    if ($Certificate.Subject -cne $script:ApiLeafSubject -or $Certificate.Issuer -cne $Root.Subject) { throw 'Unexpected API leaf subject/issuer.' }
    if ($RequirePrivateKey -and !$Certificate.HasPrivateKey) { throw 'API leaf private key is unavailable.' }
    $bc=Get-ApiTlsExtension $Certificate '2.5.29.19'
    $bc=[Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($bc,$bc.Critical)
    if ($bc.CertificateAuthority) { throw 'API leaf must not be a CA.' }
    $ku=Get-ApiTlsExtension $Certificate '2.5.29.15'
    $ku=[Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($ku,$ku.Critical)
    $required=[Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
    if (($ku.KeyUsages -band $required) -ne $required -or ($ku.KeyUsages -band [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign)) { throw 'Unexpected API leaf key usage.' }
    $eku=Get-ApiTlsExtension $Certificate '2.5.29.37'
    $eku=[Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($eku,$eku.Critical)
    if (@($eku.EnhancedKeyUsages | Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.1' }).Count -ne 1 -or $eku.EnhancedKeyUsages.Count -ne 1) { throw 'API leaf must have only Server Authentication EKU.' }
    $san=Get-ApiTlsExtension $Certificate '2.5.29.17'
    $san=[Security.Cryptography.X509Certificates.X509SubjectAlternativeNameExtension]::new($san.RawData,$san.Critical)
    $dns=@($san.EnumerateDnsNames()); $ips=@($san.EnumerateIPAddresses() | ForEach-Object ToString)
    if ($dns.Count -ne 1 -or $dns[0] -cne $script:ApiDnsName -or $ips.Count -ne 1 -or $ips[0] -ne $script:ApiIpAddress) { throw 'API leaf SAN must contain only app-01 and 192.168.0.88.' }
    if ($Certificate.SignatureAlgorithm.Value -ne '1.2.840.113549.1.1.11') { throw 'API leaf must use RSA/SHA-256.' }
    $rsa=[Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($Certificate)
    try { if ($null -eq $rsa -or $rsa.KeySize -lt 3072) { throw 'API leaf RSA key must be at least 3072 bits.' } } finally { if ($rsa) { $rsa.Dispose() } }
    if ($Certificate.NotBefore.ToUniversalTime() -gt [DateTime]::UtcNow -or $Certificate.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow -or $Certificate.NotAfter -gt $Root.NotAfter -or ($Certificate.NotAfter - $Certificate.NotBefore).TotalDays -gt 367) { throw 'API leaf validity is invalid.' }
    $chain=[Security.Cryptography.X509Certificates.X509Chain]::new()
    try {
        $chain.ChainPolicy.TrustMode=[Security.Cryptography.X509Certificates.X509ChainTrustMode]::CustomRootTrust
        $chain.ChainPolicy.CustomTrustStore.Add($Root)
        $chain.ChainPolicy.RevocationMode=[Security.Cryptography.X509Certificates.X509RevocationMode]::NoCheck
        if (!$chain.Build($Certificate)) { throw 'API leaf chain does not validate against the selected CA.' }
    } finally { $chain.Dispose() }
    return $true
}

function ConvertFrom-ApiTlsHttpBindingJson([string]$Json) {
    $parsed=$Json | ConvertFrom-Json -AsHashtable
    $bindings=@($parsed['SslCertificateBindings'] | Where-Object { $null -ne $_ })
    if ($bindings.Count -ne 1 -or $bindings[0]['IpPort'] -ne $script:ApiBindingKey) { throw 'Unexpected HTTP.sys SSL binding count or endpoint.' }
    $binding=$bindings[0]
    $parsedId=[Guid]::Empty
    if ($binding['SslCertStoreName'] -ine 'MY' -or !([Guid]::TryParse([string]$binding['GuidString'],[ref]$parsedId))) { throw 'Unexpected HTTP.sys certificate store or AppId.' }
    $hashBytes=[Convert]::FromBase64String([string]$binding['SslHash'])
    if ($hashBytes.Length -ne 20) { throw 'Unexpected HTTP.sys certificate hash.' }
    return [ordered]@{raw=$binding;thumbprint=[Convert]::ToHexString($hashBytes);appId=[string]$binding['GuidString'];store=[string]$binding['SslCertStoreName']}
}

function ConvertTo-ApiTlsHttpBindingJson($Binding,[string]$Thumbprint) {
    if ($Thumbprint -cnotmatch '^[A-Fa-f0-9]{40}$') { throw 'Expected a SHA-1 certificate thumbprint.' }
    if ($Binding['IpPort'] -ne $script:ApiBindingKey) { throw 'Only the API HTTP.sys endpoint may be updated.' }
    $copy=$Binding | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $copy['SslHash']=[Convert]::ToBase64String([Convert]::FromHexString($Thumbprint))
    $document=[ordered]@{SslCertificateBindings=@($copy)}
    $json=$document | ConvertTo-Json -Depth 30
    $parsed=ConvertFrom-ApiTlsHttpBindingJson $json
    if ($parsed.thumbprint -cne $Thumbprint.ToUpperInvariant()) { throw 'HTTP.sys update document differs from the selected certificate.' }
    return $json
}

function Assert-ApiTlsHttpBindingRestored($Expected,$Actual,[string]$NewThumbprint) {
    $before=$Expected.raw | ConvertTo-Json -Depth 30 -Compress
    $after=$Actual.raw | ConvertTo-Json -Depth 30 -Compress
    if ($NewThumbprint) {
        $copy=$Expected.raw | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
        $copy['SslHash']=[Convert]::ToBase64String([Convert]::FromHexString($NewThumbprint))
        $before=$copy | ConvertTo-Json -Depth 30 -Compress
    }
    if ($before -cne $after) { throw 'HTTP.sys binding policy differs from the expected complete snapshot.' }
}

function Invoke-ApiTlsNativeUtf8([string]$Executable,[string[]]$Arguments,[string]$DiagnosticPath='') {
    # netsh JSON depends on its console code page as well as the capture decoder.
    # ProcessStartInfo redirection alone can already produce broken JSON in netsh.
    $previousEncoding=[Console]::OutputEncoding
    try {
        [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false,$true)
        $response=@(& $Executable @Arguments 2>&1)
        $exitCode=$LASTEXITCODE
        $text=$response -join "`n"
        if ($DiagnosticPath) { [IO.File]::WriteAllText($DiagnosticPath,$text,[Text.UTF8Encoding]::new($false)) }
        if ($exitCode -ne 0 -or @($response | Where-Object { $_ -is [Management.Automation.ErrorRecord] }).Count) { throw 'Unable to inspect existing HTTP.sys SSL binding.' }
        return $text
    } finally { [Console]::OutputEncoding=$previousEncoding }
}

function Get-ApiTlsHttpBinding {
    $response=Invoke-ApiTlsNativeUtf8 (Join-Path $env:windir 'System32/netsh.exe') @('http','show','sslcert',"ipport=$script:ApiBindingKey",'json=enable')
    return ConvertFrom-ApiTlsHttpBindingJson $response
}

function New-ApiTlsHttpUpdateArguments($Binding,[string]$Thumbprint) {
    # The installed Windows build supports JSON for show, but not for update.
    # This cutover only accepts the observed default policy; refuse custom policy
    # before issuing a command rather than resetting an unspecified option.
    $known=@('IpPort','ConfigType','GuidString','SslHash','SslCertStoreName',
        'DefaultCertCheckMode','DefaultRevocationFreshnessTime','DefaultRevocationUrlRetrievalTimeout',
        'DefaultSslCtlIdentifier','DefaultSslCtlStoreName','DefaultFlags')
    if (@($Binding.Keys | Where-Object { $_ -cnotin $known }).Count -or
        @($known | Where-Object { !$Binding.Contains($_) }).Count -or
        $Binding.ConfigType -ne 1 -or $Binding.DefaultCertCheckMode -ne 0 -or
        $Binding.DefaultRevocationFreshnessTime -ne 0 -or $Binding.DefaultRevocationUrlRetrievalTimeout -ne 0 -or
        $null -ne $Binding.DefaultSslCtlIdentifier -or $null -ne $Binding.DefaultSslCtlStoreName -or
        $Binding.DefaultFlags -ne 0) { throw 'The API binding has nondefault policy; inspect it before updating the certificate.' }
    $null=ConvertTo-ApiTlsHttpBindingJson $Binding $Thumbprint
    return @('http','update','sslcert',"ipport=$script:ApiBindingKey",("certhash="+$Thumbprint.ToUpperInvariant()),
        ("appid="+$Binding.GuidString),("certstorename="+$Binding.SslCertStoreName),
        'verifyclientcertrevocation=enable','verifyrevocationwithcachedclientcertonly=disable','usagecheck=enable',
        'revocationfreshnesstime=0','urlretrievaltimeout=0','dsmapperusage=disable','clientcertnegotiation=disable')
}

function Set-ApiTlsHttpBinding($Binding,[string]$Thumbprint,[string]$DiagnosticPath) {
    $arguments=New-ApiTlsHttpUpdateArguments $Binding $Thumbprint
    $text=Invoke-ApiTlsNativeUtf8 (Join-Path $env:windir 'System32/netsh.exe') $arguments $DiagnosticPath
    if ($text -match '(?im)^\s*(?:Usage|使用法)\s*:') { throw 'HTTP.sys rejected the certificate-update syntax; inspect the saved native output.' }
    # The caller must still re-read and compare the complete binding. netsh can
    # return exit code zero for a rejected command, so its exit code is not proof.
}

function Restore-ApiTlsHttpBinding($Before,[string]$DiagnosticPath) {
    $alreadyOriginal=$false
    try {
        $current=Get-ApiTlsHttpBinding
        Assert-ApiTlsHttpBindingRestored $Before $current ''
        $alreadyOriginal=$true
    } catch { }
    if (!$alreadyOriginal) {
        # An update was issued by this operation. A failed re-read must not skip
        # restoring its saved certificate. This does not delete or recreate a binding.
        Set-ApiTlsHttpBinding $Before.raw $Before.thumbprint $DiagnosticPath
        $current=Get-ApiTlsHttpBinding
        Assert-ApiTlsHttpBindingRestored $Before $current ''
    }
}

function Assert-ApiTlsIisBinding {
    [xml]$iis=Get-Content -Raw -Encoding UTF8 -LiteralPath "$env:windir\System32\inetsrv\config\applicationHost.config"
    $site=$iis.SelectSingleNode('/configuration/system.applicationHost/sites/site[@name="AstralRecordApi"]')
    if ($null -eq $site) { throw 'AstralRecordApi IIS site is missing.' }
    $https=@($site.SelectNodes('bindings/binding[@protocol="https"]'))
    if ($https.Count -ne 1 -or $https[0].GetAttribute('bindingInformation') -ne '*:444:' -or $https[0].GetAttribute('sslFlags') -ne '0') { throw 'Expected the existing non-SNI *:444 API IIS binding.' }
}

function Test-ApiTlsHealth {
    foreach ($url in @('https://app-01:444/api/health','https://192.168.0.88:444/api/health')) {
        $response=Invoke-WebRequest -Uri $url -Method Get -SkipHttpErrorCheck -TimeoutSec 10 -NoProxy -ErrorAction Stop
        if ($response.StatusCode -notin @(200,401)) { throw "TLS health endpoint returned unexpected status for $url." }
    }
}

Export-ModuleMember -Function Assert-ApiTlsMachine,New-ApiTlsKeyAcl,New-ApiTlsPrivateDirectory,Get-ApiTlsCertificates,Get-ApiTlsSha256,Copy-ApiTlsPinnedCertificate,Assert-ApiTlsRoot,Assert-ApiTlsLeaf,Assert-ApiTlsPrivateKeyAcl,ConvertFrom-ApiTlsHttpBindingJson,ConvertTo-ApiTlsHttpBindingJson,Assert-ApiTlsHttpBindingRestored,Get-ApiTlsHttpBinding,New-ApiTlsHttpUpdateArguments,Set-ApiTlsHttpBinding,Restore-ApiTlsHttpBinding,Assert-ApiTlsIisBinding,Test-ApiTlsHealth
