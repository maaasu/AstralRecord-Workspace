#requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../ApiTls.psm1') -Force

function Assert-Throws([scriptblock]$Action,[string]$Message) {
    $threw=$false
    try { & $Action | Out-Null } catch { $threw=$true }
    if (!$threw) { throw $Message }
}

function New-TestRoot([bool]$Ca=$true,[int]$PathLength=0,[bool]$CanSign=$true) {
    $rsa=[Security.Cryptography.RSA]::Create(3072)
    $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=AstralRecord API Internal CA',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($Ca,$true,$PathLength,$true))
    $uses=if ($CanSign) {
        [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyCertSign -bor [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::CrlSign
    } else { [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature }
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($uses,$true))
    $certificate=$request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-2),[DateTimeOffset]::UtcNow.AddYears(10))
    return @{certificate=$certificate;key=$rsa}
}

function New-TestLeaf($Root,[string]$Dns='app-01',[string]$Ip='192.168.0.88',[string]$Eku='1.3.6.1.5.5.7.3.1',[bool]$Ca=$false) {
    $rsa=[Security.Cryptography.RSA]::Create(3072)
    $request=[Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=app-01',$rsa,[Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($Ca,$false,0,$true))
    $uses=[Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature -bor [Security.Cryptography.X509Certificates.X509KeyUsageFlags]::KeyEncipherment
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new($uses,$true))
    $oids=[Security.Cryptography.OidCollection]::new(); [void]$oids.Add([Security.Cryptography.Oid]::new($Eku))
    $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension]::new($oids,$false))
    $san=[Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
    $san.AddDnsName($Dns); $san.AddIpAddress([Net.IPAddress]::Parse($Ip))
    $request.CertificateExtensions.Add($san.Build())
    $issued=$request.Create($Root.certificate,[DateTimeOffset]::UtcNow.AddMinutes(-1),[DateTimeOffset]::UtcNow.AddDays(365),[byte[]](1..16))
    return @{certificate=$issued;key=$rsa}
}

$root=New-TestRoot
$leaf=New-TestLeaf $root
try {
    if (!(Assert-ApiTlsRoot $root.certificate -RequirePrivateKey) -or !(Assert-ApiTlsLeaf $leaf.certificate $root.certificate)) { throw 'Valid API certificates were rejected.' }
    $badDns=New-TestLeaf $root -Dns 'device_server'
    try { Assert-Throws { Assert-ApiTlsLeaf $badDns.certificate $root.certificate } 'A wrong DNS SAN was accepted.' } finally { $badDns.certificate.Dispose(); $badDns.key.Dispose() }
    $badIp=New-TestLeaf $root -Ip '192.168.0.89'
    try { Assert-Throws { Assert-ApiTlsLeaf $badIp.certificate $root.certificate } 'A wrong IP SAN was accepted.' } finally { $badIp.certificate.Dispose(); $badIp.key.Dispose() }
    $badEku=New-TestLeaf $root -Eku '1.3.6.1.5.5.7.3.2'
    try { Assert-Throws { Assert-ApiTlsLeaf $badEku.certificate $root.certificate } 'A client-auth-only leaf was accepted.' } finally { $badEku.certificate.Dispose(); $badEku.key.Dispose() }
    $badCaLeaf=New-TestLeaf $root -Ca $true
    try { Assert-Throws { Assert-ApiTlsLeaf $badCaLeaf.certificate $root.certificate } 'A CA leaf was accepted.' } finally { $badCaLeaf.certificate.Dispose(); $badCaLeaf.key.Dispose() }
    $badPath=New-TestRoot -PathLength 1
    try { Assert-Throws { Assert-ApiTlsRoot $badPath.certificate } 'A CA with pathLen=1 was accepted.' } finally { $badPath.certificate.Dispose(); $badPath.key.Dispose() }
    $badUsage=New-TestRoot -CanSign $false
    try { Assert-Throws { Assert-ApiTlsRoot $badUsage.certificate } 'A CA without signing key usage was accepted.' } finally { $badUsage.certificate.Dispose(); $badUsage.key.Dispose() }
    $httpJson=@{SslCertificateBindings=@(@{IpPort='0.0.0.0:444';SslHash=[Convert]::ToBase64String([byte[]](1..20));SslCertStoreName='MY';GuidString='{4dc3e181-e14b-4a21-b022-59fc669b0914}';DefaultFlags=7;ExtraPolicy=@{Mode='preserve'}})} | ConvertTo-Json -Depth 8
    $binding=ConvertFrom-ApiTlsHttpBindingJson $httpJson
    if ($binding.thumbprint -cne '0102030405060708090A0B0C0D0E0F1011121314' -or $binding.raw.DefaultFlags -ne 7) { throw 'HTTP.sys JSON binding was parsed incorrectly.' }
    $nextThumbprint='ABCDEF0123456789ABCDEF0123456789ABCDEF01'
    $updateJson=ConvertTo-ApiTlsHttpBindingJson $binding.raw $nextThumbprint
    $updated=ConvertFrom-ApiTlsHttpBindingJson $updateJson
    Assert-ApiTlsHttpBindingRestored $binding $updated $nextThumbprint
    if ((($updateJson | ConvertFrom-Json -AsHashtable).SslCertificateBindings).Count -ne 1 -or $updated.raw.ExtraPolicy.Mode -cne 'preserve') { throw 'HTTP.sys update document changed non-hash policy.' }
    $restoreJson=ConvertTo-ApiTlsHttpBindingJson $binding.raw $binding.thumbprint
    Assert-ApiTlsHttpBindingRestored $binding (ConvertFrom-ApiTlsHttpBindingJson $restoreJson) ''
    $altered=$updateJson | ConvertFrom-Json -AsHashtable
    $altered.SslCertificateBindings[0].DefaultFlags=0
    Assert-Throws { Assert-ApiTlsHttpBindingRestored $binding (ConvertFrom-ApiTlsHttpBindingJson ($altered | ConvertTo-Json -Depth 30)) $nextThumbprint } 'A changed HTTP.sys policy flag was accepted.'
    $wrongEndpoint=$binding.raw | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
    $wrongEndpoint.IpPort='0.0.0.0:445'
    Assert-Throws { ConvertTo-ApiTlsHttpBindingJson $wrongEndpoint $nextThumbprint } 'A different HTTP.sys endpoint was accepted.'
    Assert-Throws { ConvertFrom-ApiTlsHttpBindingJson '{"SslCertificateBindings":[]}' } 'Missing HTTP.sys binding was accepted.'
    # Reproduce netsh UTF-8 JSON under a Japanese Windows console (CP932).
    # Implicit native capture can consume the message's closing quote as well.
    $module=Get-Module ApiTls
    $oldEncoding=[Console]::OutputEncoding
    try {
        [Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
        [Console]::OutputEncoding=[Text.Encoding]::GetEncoding(932)
        $nativeJson=$httpJson | ConvertFrom-Json -AsHashtable
        $nativeJson['message']='正常に終了しました。'
        $nativeJson['status']=0
        $bytes=[Text.Encoding]::UTF8.GetBytes(($nativeJson | ConvertTo-Json -Depth 8))
        $encodedBytes=[Convert]::ToBase64String($bytes)
        $nativeCommand="[Console]::OpenStandardOutput().Write([Convert]::FromBase64String('$encodedBytes'))"
        $encodedCommand=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($nativeCommand))
        $nativeArgs=@('-NoProfile','-EncodedCommand',$encodedCommand)
        $response=& $module { param($exe,$nativeArgs) Invoke-ApiTlsNativeUtf8 $exe $nativeArgs } (Join-Path $PSHOME 'pwsh.exe') $nativeArgs
        $nativeParsed=$response | ConvertFrom-Json -AsHashtable
        if ($nativeParsed.message -cne '正常に終了しました。' -or $nativeParsed.status -ne 0) { throw 'UTF-8 native JSON was corrupted by the console encoding.' }
        Assert-ApiTlsHttpBindingRestored $binding (ConvertFrom-ApiTlsHttpBindingJson $response) ''
        if ([Console]::OutputEncoding.CodePage -ne 932) { throw 'The native helper did not restore the caller console encoding.' }
        # Use the real Windows command as well: a redirected fake process does not
        # reproduce netsh's own console-dependent JSON generation.
        $realJson=& $module { Invoke-ApiTlsNativeUtf8 (Join-Path $env:windir 'System32/netsh.exe') @('http','show','sslcert','ipport=0.0.0.0:444','json=enable') }
        $realParsed=$realJson | ConvertFrom-Json -AsHashtable
        if (!$realParsed.ContainsKey('SslCertificateBindings') -or $realParsed.status -ne 0) { throw 'Real netsh did not return valid JSON under CP932.' }
        $errorCommand=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('[Console]::Error.Write("diagnostic"); exit 0'))
        Assert-Throws { & $module { param($exe,$argsForChild) Invoke-ApiTlsNativeUtf8 $exe $argsForChild } (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile','-EncodedCommand',$errorCommand) } 'Native stderr was mixed into a successful JSON response.'
        $failureCommand=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('exit 3'))
        Assert-Throws { & $module { param($exe,$argsForChild) Invoke-ApiTlsNativeUtf8 $exe $argsForChild } (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile','-EncodedCommand',$failureCommand) } 'A failing native command was accepted.'
        if ([Console]::OutputEncoding.CodePage -ne 932) { throw 'Native failure did not restore the caller console encoding.' }
    } finally { [Console]::OutputEncoding=$oldEncoding }
    $sample=Join-Path ([IO.Path]::GetTempPath()) ('api-ca-'+[Guid]::NewGuid().ToString('N')+'.cer')
    $snapshot=Join-Path ([IO.Path]::GetTempPath()) ('api-ca-snapshot-'+[Guid]::NewGuid().ToString('N')+'.cer')
    try {
        [IO.File]::WriteAllBytes($sample,$root.certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert))
        $pin=Get-ApiTlsSha256 $sample
        Copy-ApiTlsPinnedCertificate $sample $snapshot $pin | Out-Null
        [IO.File]::WriteAllText($sample,'changed after snapshot')
        if ((Get-ApiTlsSha256 $snapshot) -cne $pin) { throw 'Source change altered the pinned local certificate.' }
        Assert-Throws { Copy-ApiTlsPinnedCertificate $sample ($sample+'.bad') $pin } 'A changed public CA source was accepted.'
    } finally {
        Remove-Item -LiteralPath $sample,$snapshot,($sample+'.bad') -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'PASS: X.509 validation, pinned certificate snapshot, HTTP.sys policy roundtrip, and UTF-8 native JSON under CP932.'
} finally {
    $leaf.certificate.Dispose(); $leaf.key.Dispose(); $root.certificate.Dispose(); $root.key.Dispose()
}
