Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ControlAbsolutePath([string]$Path) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'A local absolute path is required.' }
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($resolved.StartsWith('\\')) { throw 'A local absolute path is required.' }
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
    if ($manifest.component -ne $Component -or $manifest.phase -notin @('P1','P2') -or $manifest.protocolVersion -ne 1) { throw 'Package capability/version mismatch.' }
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
        if ($config.Control.ContainsKey('EnableProcessControl') -and $config.Control.EnableProcessControl -isnot [bool]) { throw 'Control.EnableProcessControl must be boolean.' }
        if ($config.Control.ContainsKey('EnableFileControl') -and $config.Control.EnableFileControl -isnot [bool]) { throw 'Control.EnableFileControl must be boolean.' }
        $uri = $null
        if (-not [Uri]::TryCreate($config.Control.PublicOrigin, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) { throw 'Control.PublicOrigin must be a fixed HTTPS origin.' }
        if (-not $config.Kestrel.Endpoints.Https.Url.StartsWith('https://', [StringComparison]::OrdinalIgnoreCase)) { throw 'An HTTPS Kestrel endpoint is required.' }
        if (-not $config.Kestrel.Endpoints.Https.Certificate.Subject -or $config.Kestrel.Endpoints.Https.Certificate.Store -ne 'My' -or $config.Kestrel.Endpoints.Https.Certificate.Location -ne 'LocalMachine') { throw 'Use a LocalMachine/My server certificate and grant its private key to the service identity.' }
        if (-not (Test-Path -LiteralPath (Join-Path $root 'control.db'))) { throw 'Create the owner with Control.Cli before starting Web.' }
    } else {
        $id = [Guid]::Empty
        if (-not [Guid]::TryParse($config.hostId, [ref]$id) -or $id -eq [Guid]::Empty) { throw 'A registered hostId is required.' }
        if ($Component -eq 'Agent') {
            if ($config.ContainsKey('enableFileControl') -and $config.enableFileControl -isnot [bool]) { throw 'Agent enableFileControl must be boolean.' }
            $uri = $null
            if (-not [Uri]::TryCreate($config.serverUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) { throw 'Agent serverUrl must be a fixed HTTPS origin.' }
            if (-not (Test-Path -LiteralPath (Join-Path $root 'credentials\token.bin'))) { throw 'Run Agent --configure before starting Agent.' }
        } else {
            if ($config.ContainsKey('enableProcessControl') -and $config.enableProcessControl -isnot [bool]) { throw 'Runner enableProcessControl must be boolean.' }
            if ($config.ContainsKey('enableFileControl') -and $config.enableFileControl -isnot [bool]) { throw 'Runner enableFileControl must be boolean.' }
            try { $agentSid = [Security.Principal.SecurityIdentifier]::new([string]$config.agentSid) } catch { throw 'Runner agentSid is invalid.' }
            if (-not $agentSid.Value.StartsWith('S-1-5-', [StringComparison]::Ordinal)) { throw 'Runner agentSid must identify the configured Windows principal.' }
        }
    }
}

function Assert-ControlRunnerUpdateReady([string]$CliPath, [string]$PipeName) {
    if (![IO.Path]::IsPathFullyQualified($CliPath) -or !(Test-Path -LiteralPath $CliPath -PathType Leaf)) { throw 'An installed Control CLI path is required.' }
    if (!$PipeName -or $PipeName -notmatch '^[A-Za-z0-9._-]+$') { throw 'Runner pipe name is invalid.' }
    $output=& $CliPath runner check-update --pipe-name $PipeName
    if ($LASTEXITCODE -ne 0) { throw 'Runner update safety check failed.' }
    try { $snapshot=($output -join "`n") | ConvertFrom-Json } catch { throw 'Runner snapshot JSON is invalid.' }
    Assert-ControlRunnerStoppedSnapshot $snapshot
}

function Set-ControlManagedMarkerAcl([string]$Path) {
    $acl=[Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($pair in @(@('S-1-5-18','FullControl'),@('S-1-5-32-544','FullControl'),@('S-1-1-0','Read'))) {
        $sid=[Security.Principal.SecurityIdentifier]::new($pair[0])
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,$pair[1],'Allow'))
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Enable-ControlManagedOperations([string]$ToolRoot, [switch]$Renew, [string]$CliPath = '', [string]$PipeName = '') {
    if (!$IsWindows) { throw 'Managed operations attestation requires Windows.' }
    $root=Get-ControlAbsolutePath $ToolRoot
    $files=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    $hashes=[ordered]@{}
    foreach ($file in $files) {
        $path=Join-Path $root $file
        if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Managed tool entry is missing: $file" }
        if ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Managed tool entry is a reparse point: $file" }
        $errors=$null
        [Management.Automation.Language.Parser]::ParseFile($path,[ref]$null,[ref]$errors) | Out-Null
        if ($errors.Count) { throw "Managed tool entry has a syntax error: $file" }
        $hashes[$file]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
    }
    $dataRoot=Join-Path $env:ProgramData 'AstralRecordControl'
    if ($Renew) {
        if (!(Test-Path -LiteralPath $dataRoot -PathType Container)) { throw 'Managed operations marker directory is missing.' }
    } else {
        New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
    }
    $rootAcl=[Security.AccessControl.DirectorySecurity]::new()
    $rootAcl.SetAccessRuleProtection($true,$false)
    foreach ($pair in @(@('S-1-5-18','FullControl'),@('S-1-5-32-544','FullControl'),@('S-1-1-0','ReadAndExecute'))) {
        $sid=[Security.Principal.SecurityIdentifier]::new($pair[0])
        $rootAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,$pair[1],'ContainerInherit,ObjectInherit','None','Allow'))
    }
    $marker=Join-Path $dataRoot 'managed-operations.enabled'
    $exists=Test-Path -LiteralPath $marker
    if ($Renew -and !$exists) { throw 'Managed operations marker is missing; renewal requires an existing installation.' }
    if (!$Renew -and $exists) { throw 'Managed operations marker already exists; use the renewal command.' }
    if ($exists) {
        if (!(Test-Path -LiteralPath $marker -PathType Leaf) -or ((Get-Item -LiteralPath $marker -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Managed operations marker is not a regular file.' }
        try { $previous=Get-Content -Raw -Encoding UTF8 -LiteralPath $marker | ConvertFrom-Json -AsHashtable } catch { throw 'Existing managed operations marker is invalid.' }
        if ($previous.schemaVersion -ne 1 -or !$previous.toolRoot -or
            [IO.Path]::GetFullPath([string]$previous.toolRoot).TrimEnd('\','/') -ine $root) { throw 'Existing managed operations marker belongs to a different installation.' }
        Assert-ControlRunnerUpdateReady $CliPath $PipeName
    }
    Set-Acl -LiteralPath $dataRoot -AclObject $rootAcl
    $content=@{schemaVersion=1;toolRoot=$root;hashes=$hashes;createdAtUtc=[DateTimeOffset]::UtcNow.ToString('O')} | ConvertTo-Json -Depth 6
    $temporary=Join-Path $dataRoot ('.managed-operations.'+[Guid]::NewGuid().ToString('N')+'.tmp')
    $backup=if ($Renew) { Join-Path $dataRoot ('managed-operations.'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'.'+[Guid]::NewGuid().ToString('N')+'.backup') } else { $null }
    try {
        [IO.File]::WriteAllText($temporary,$content,[Text.UTF8Encoding]::new($false))
        Set-ControlManagedMarkerAcl $temporary
        if ($Renew) {
            Assert-ControlRunnerUpdateReady $CliPath $PipeName
            [IO.File]::Replace($temporary,$marker,$backup,$false)
            Set-ControlManagedMarkerAcl $backup
        } else {
            [IO.File]::Move($temporary,$marker)
        }
        Set-ControlManagedMarkerAcl $marker
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    return $marker
}

function Assert-ControlRunnerStoppedSnapshot($Snapshot) {
    if (!$Snapshot -or !$Snapshot.runnerEpoch -or $null -eq $Snapshot.holds -or $null -eq $Snapshot.servers) { throw 'Runner snapshot is incomplete.' }
    if (@($Snapshot.holds).Count -ne 0) { throw 'Runner update requires no active holds.' }
    foreach ($server in @($Snapshot.servers)) {
        if (!$server.serverId -or $server.processState -cne 'Stopped' -or $server.currentSessionId) {
            throw 'Runner update requires every managed server to be fully stopped; Unmanaged is refused.'
        }
    }
}

Export-ModuleMember -Function Get-ControlAbsolutePath, Assert-ControlSeparatePaths, Assert-ControlPackage, Set-ControlAcl, Test-ControlConfiguration, Assert-ControlRunnerStoppedSnapshot, Assert-ControlRunnerUpdateReady, Enable-ControlManagedOperations
