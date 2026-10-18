Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ControlAbsolutePath([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'A local absolute path is required.' }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($resolved.Length -le 3) { throw 'A drive root is not allowed.' }
    $cursor = $resolved
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse points are not allowed.' }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
    return $resolved
}

function Assert-ControlSeparatePaths([string]$First, [string]$Second) {
    $a = (Get-ControlAbsolutePath $First) + '\'
    $b = (Get-ControlAbsolutePath $Second) + '\'
    if ($a.StartsWith($b, [StringComparison]::OrdinalIgnoreCase) -or $b.StartsWith($a, [StringComparison]::OrdinalIgnoreCase)) { throw 'Binary and persistent data paths must be separate.' }
}

function Assert-ControlPackage([string]$Path, [string]$Component) {
    $root = Get-ControlAbsolutePath $Path
    foreach ($name in @("AstralRecordControl.$Component.exe", "AstralRecordControl.$Component.dll", "AstralRecordControl.$Component.runtimeconfig.json")) {
        if (-not (Test-Path -LiteralPath (Join-Path $root $name) -PathType Leaf)) { throw "Package is incomplete: $name" }
    }
    foreach ($item in Get-ChildItem -LiteralPath $root -Recurse -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Package contains a reparse point.' }
        if ($item.Name -match '(?i)(\.db(?:-wal|-shm)?$|\.local\.json$|^token\.bin$|\.pfx$|\.pem$|^config\.json$|^credentials$|^keys$)') { throw 'Package contains persistent data or local configuration.' }
    }
    $manifestPath = Join-Path $root 'control-package.json'
    if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Missing package manifest.' }
    $manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json
    if ($manifest.component -ne $Component -or $manifest.phase -ne 'P0' -or $manifest.protocolVersion -ne 1) { throw 'Package capability/version mismatch.' }
    return $root
}

function Set-ControlAcl([string]$Path, [string]$ServiceSid, [switch]$ReadOnly) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18', 'S-1-5-32-544', $ServiceSid)) {
        $rights = if ($sid -eq $ServiceSid) { if ($ReadOnly) { 'ReadAndExecute' } else { 'Modify' } } else { 'FullControl' }
        $rule = [Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid), $rights, 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Test-ControlConfiguration([string]$DataRoot, [string]$Component) {
    $root = Get-ControlAbsolutePath $DataRoot
    $configPath = Join-Path $root 'config.json'
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) { throw 'Complete config.json before starting the service.' }
    $config = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json -AsHashtable
    if ($Component -eq 'Web') {
        $uri = $null
        if (-not [Uri]::TryCreate($config.Control.PublicOrigin, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) { throw 'Control.PublicOrigin must be a fixed HTTPS origin.' }
        if (-not $config.Kestrel.Endpoints.Https.Url.StartsWith('https://', [StringComparison]::OrdinalIgnoreCase)) { throw 'An HTTPS Kestrel endpoint is required.' }
        if (-not $config.Kestrel.Endpoints.Https.Certificate.Subject -or $config.Kestrel.Endpoints.Https.Certificate.Store -ne 'My' -or $config.Kestrel.Endpoints.Https.Certificate.Location -ne 'LocalMachine') { throw 'Use a LocalMachine/My server certificate and grant its private key to the service identity.' }
        if (-not (Test-Path -LiteralPath (Join-Path $root 'control.db'))) { throw 'Create the owner with Control.Cli before starting Web.' }
    } else {
        $id = [Guid]::Empty
        if (-not [Guid]::TryParse($config.hostId, [ref]$id) -or $id -eq [Guid]::Empty) { throw 'A registered hostId is required.' }
        if ($Component -eq 'Agent') {
            $uri = $null
            if (-not [Uri]::TryCreate($config.serverUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https') { throw 'Agent serverUrl must use HTTPS.' }
            if (-not (Test-Path -LiteralPath (Join-Path $root 'credentials\token.bin'))) { throw 'Run Agent --configure before starting Agent.' }
        } elseif (-not $config.agentSid.StartsWith('S-1-5-', [StringComparison]::Ordinal)) { throw 'Runner agentSid is required.' }
    }
}

Export-ModuleMember -Function Get-ControlAbsolutePath, Assert-ControlSeparatePaths, Assert-ControlPackage, Set-ControlAcl, Test-ControlConfiguration
