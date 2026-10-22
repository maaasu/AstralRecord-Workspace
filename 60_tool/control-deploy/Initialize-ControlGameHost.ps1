#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PlanPath,
    [Parameter(Mandatory)][string]$HostReceiptPath,
    [Parameter(Mandatory)][string]$PackageDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedManifestSha256,
    [switch]$PreflightOnly
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-LocalPath([string]$Path, [string]$Kind) {
    if (-not [IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'A local absolute path is required.' }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($full.Length -le 3) { throw 'A drive root is not allowed.' }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse path refused: $full" }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor = $parent
    }
    if ($Kind -and -not (Test-Path -LiteralPath $full -PathType $Kind)) { throw "Required local $Kind is missing: $full" }
    return $full
}

function Read-JsonObject([string]$Path) {
    $file = Assert-LocalPath $Path 'Leaf'
    $value = Get-Content -Raw -Encoding UTF8 -LiteralPath $file | ConvertFrom-Json -AsHashtable
    if ($value -isnot [System.Collections.IDictionary]) { throw 'JSON root must be an object.' }
    return $value
}

function Assert-Guid([object]$Value, [string]$Label) {
    $id = [Guid]::Empty
    if (-not [Guid]::TryParse([string]$Value, [ref]$id) -or $id -eq [Guid]::Empty) { throw "Invalid $Label." }
    return $id.ToString('D')
}

function Assert-Origin([object]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate([string]$Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -ne 'https' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
        throw 'A fixed HTTPS publicOrigin is required.'
    }
    return $uri.AbsoluteUri.TrimEnd('/')
}

function Assert-Package([string]$Root, [string]$ExpectedHash) {
    $manifestPath = Join-Path $Root 'release-manifest.json'
    $manifestPath = Assert-LocalPath $manifestPath 'Leaf'
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).Hash
    if ($actualHash -ine $ExpectedHash) { throw 'Release manifest SHA-256 does not match the expected seal.' }
    $manifest = Read-JsonObject $manifestPath
    if ($manifest.containsRuntimeConfiguration -ne $false -or $manifest.sha256 -isnot [System.Collections.IDictionary]) {
        throw 'Release manifest is missing its binary-only hash catalog.'
    }
    $sections = @('Control/Agent', 'Control/Runner', 'Control/Cli', 'Tools/60_tool/control-deploy')
    foreach ($section in $sections) {
        $directory = Assert-LocalPath (Join-Path $Root ($section.Replace('/', '\'))) 'Container'
        $files = @(Get-ChildItem -LiteralPath $directory -Recurse -File -Force)
        if ($files.Count -eq 0) { throw "Release section is empty: $section" }
        foreach ($item in Get-ChildItem -LiteralPath $directory -Recurse -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Release section contains a reparse point: $section" }
        }
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($Root.Length + 1).Replace('\', '/')
            if (-not $manifest.sha256.Contains($relative)) { throw "Release file is not sealed: $relative" }
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash -ine [string]$manifest.sha256[$relative]) {
                throw "Release file hash mismatch: $relative"
            }
        }
        foreach ($entry in $manifest.sha256.Keys) {
            if ($entry.StartsWith("$section/", [StringComparison]::Ordinal) -and
                -not (Test-Path -LiteralPath (Join-Path $Root $entry.Replace('/', '\')) -PathType Leaf)) {
                throw "Sealed release file is missing: $entry"
            }
        }
    }
    $phase = ''
    foreach ($component in @('Agent', 'Runner', 'Cli')) {
        $componentRoot = Join-Path $Root "Control\$component"
        foreach ($file in @("AstralRecordControl.$component.exe", "AstralRecordControl.$component.dll", "AstralRecordControl.$component.runtimeconfig.json", 'control-package.json')) {
            if (-not (Test-Path -LiteralPath (Join-Path $componentRoot $file) -PathType Leaf)) { throw "Incomplete $component package." }
        }
        $package = Read-JsonObject (Join-Path $componentRoot 'control-package.json')
        if ($package.component -ne $component -or $package.protocolVersion -ne 1 -or $package.phase -notin @('P1','P2','P3')) {
            throw "Unsupported $component package."
        }
        if ($phase -and $package.phase -ne $phase) { throw 'Control package phases differ.' }
        $phase = $package.phase
    }
    foreach ($file in @('Install-Control.ps1', 'Install-ControlCli.ps1', 'Control.Deployment.psm1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root "Tools\60_tool\control-deploy\$file") -PathType Leaf)) {
            throw "Required deployment tool is missing: $file"
        }
    }
    return $manifest
}

function New-Resources($HostDefinition) {
    if ($HostDefinition.managedRoots -isnot [array] -or $HostDefinition.executables -isnot [array]) {
        throw 'Host resources must be arrays.'
    }
    $roots = @()
    $executables = @()
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $HostDefinition.managedRoots) {
        $id = Assert-Guid $root.id 'managed root id'
        if (-not $ids.Add($id)) { throw 'Duplicate resource id.' }
        $path = Assert-LocalPath ([string]$root.path) 'Container'
        $roots += [ordered]@{ id = $id; displayName = [IO.Path]::GetFileName($path); path = $path; backupOnly = $false }
    }
    foreach ($executable in $HostDefinition.executables) {
        $id = Assert-Guid $executable.id 'executable id'
        if (-not $ids.Add($id)) { throw 'Duplicate resource id.' }
        if ($executable.kind -notin @('Java', 'Velocity')) { throw 'Only Java and Velocity executable resources are supported for passive game hosts.' }
        $kind = if ([string]$executable.kind -ieq 'Velocity') { 'Velocity' } else { 'Java' }
        $path = Assert-LocalPath ([string]$executable.path) 'Leaf'
        if ([IO.Path]::GetFileName($path) -ine 'java.exe') { throw 'Executable resource must be java.exe.' }
        $executables += [ordered]@{ id = $id; displayName = $kind; path = $path; kind = $kind }
    }
    if ($roots.Count -eq 0 -or @($executables | Where-Object { $_.kind -eq 'Java' }).Count -eq 0) {
        throw 'Game host requires managed roots and Java.'
    }
    return [ordered]@{ roots = $roots; executables = $executables }
}

function Assert-ResourceMatch($Actual, $Expected) {
    if ($null -eq $Actual -or @($Actual.roots).Count -ne $Expected.roots.Count -or
        @($Actual.executables).Count -ne $Expected.executables.Count) { throw 'Existing local resources differ from the import plan.' }
    foreach ($kind in @('roots', 'executables')) {
        foreach ($wanted in $Expected[$kind]) {
            $matches = @($Actual[$kind] | Where-Object { [string]$_.id -ieq $wanted.id })
            if ($matches.Count -ne 1 -or [string]$matches[0].path -ine $wanted.path) { throw 'Existing local resources differ from the import plan.' }
            if ($kind -eq 'executables' -and [string]$matches[0].kind -ine $wanted.kind) { throw 'Existing executable kind differs from the import plan.' }
            if ($kind -eq 'roots' -and $matches[0].backupOnly -eq $true) { throw 'A game root cannot be backupOnly.' }
        }
    }
}

function Assert-ConfigMatch($Config, [string]$Component, [string]$HostId, [string]$Origin,
    [string]$AgentSid, [string]$PipeName, $Resources) {
    if ([string]$Config.hostId -ine $HostId) { throw "Existing $Component Host ID differs from the receipt." }
    Assert-ResourceMatch $Config.localResources $Resources
    foreach ($flag in @('enableProcessControl', 'enableFileControl', 'enableSchedules')) {
        if ($Config.Contains($flag) -and $Config[$flag] -ne $false) { throw "Existing $Component $flag must be false." }
    }
    if ($Component -eq 'Agent') {
        if ([string]$Config.serverUrl -ine $Origin -or [string]$Config.runnerPipeName -ne $PipeName -or
            [string]$Config.tokenFile -ne 'credentials/token.bin') { throw 'Existing Agent binding differs from the receipt.' }
    } else {
        if ([string]$Config.agentSid -ne $AgentSid -or [string]$Config.pipeName -ne $PipeName) {
            throw 'Existing Runner identity or pipe differs from the expected Agent.'
        }
    }
}

function Set-AdminOnlyAcl([string]$Path) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18', 'S-1-5-32-544')) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid), 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Add-ServiceAcl([string]$Path, [string[]]$Sids, [string]$Rights, [bool]$IsDirectory) {
    $acl = Get-Acl -LiteralPath $Path
    foreach ($sid in $Sids) {
        $inheritance = if ($IsDirectory) { 'ContainerInherit,ObjectInherit' } else { 'None' }
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid), $Rights, $inheritance, 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Get-ServiceDefinition([string]$Component) {
    $name = "AstralRecordControl.$Component"
    return Get-CimInstance Win32_Service -Filter "Name='$name'" -ErrorAction Stop
}

function Assert-StoppedService($Service, [string]$Component, [string]$InstallRoot, [string]$DataRoot, [switch]$AllowRunning) {
    if ($null -eq $Service) { return }
    $name = "AstralRecordControl.$Component"
    if (($Service.State -ne 'Stopped' -and -not ($AllowRunning -and $Service.State -eq 'Running')) -or
        $Service.StartName -ine "NT SERVICE\$name") { throw "Existing $name has an unexpected state or identity." }
    $match = [regex]::Match([string]$Service.PathName, '^\s*"([^"]+\.exe)"\s+--data-root\s+"([^"]+)"\s*$')
    if (-not $match.Success) { throw "Existing $name has an unrecognized command line." }
    $binary = Assert-LocalPath $match.Groups[1].Value 'Leaf'
    $configuredData = Assert-LocalPath $match.Groups[2].Value 'Container'
    $componentRoot = [IO.Path]::GetFullPath((Join-Path $InstallRoot $Component)).TrimEnd('\') + '\'
    if (-not $binary.StartsWith($componentRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($binary) -ine "$name.exe" -or $configuredData -ine $DataRoot) {
        throw "Existing $name points outside the expected installation."
    }
}

function Set-ManualStart([string]$Component) {
    $name = "AstralRecordControl.$Component"
    & sc.exe config $name 'start=' 'demand' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not set $name to Manual." }
}

function Assert-CredentialFile([string]$Path, [string]$AgentSid) {
    $file = Assert-LocalPath $Path 'Leaf'
    if ((Get-Item -LiteralPath $file).Length -eq 0) { throw 'Agent credential file is empty.' }
    $allowed = @('S-1-5-18', 'S-1-5-32-544', $AgentSid)
    $directory = [IO.Path]::GetDirectoryName($file)
    $dirAcl = Get-Acl -LiteralPath $directory
    if (-not $dirAcl.AreAccessRulesProtected) { throw 'Agent credential directory ACL must be protected.' }
    foreach ($acl in @($dirAcl, (Get-Acl -LiteralPath $file))) {
        foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowed) {
                throw 'Agent credential ACL grants an unexpected principal.'
            }
        }
    }
}

function Write-NewConfig([string]$Path, $Config) {
    if (Test-Path -LiteralPath $Path) { throw 'Existing configuration will not be replaced.' }
    $temporary = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $Config | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -LiteralPath $temporary
        Move-Item -LiteralPath $temporary -Destination $Path
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Stop-ServiceAndConfirm([string]$Name) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $service = Get-Service -Name $Name -ErrorAction Stop
        if ($service.Status -eq 'Stopped') { return }
        if ($service.Status -notin @('StartPending', 'Running', 'StopPending')) {
            throw "Cannot confirm safe rollback for $Name in state $($service.Status)."
        }
        try { Stop-Service -Name $Name -ErrorAction Stop } catch { }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Name to stop after failed initialization."
}

function Start-PassiveServices {
    $requested = [Collections.Generic.List[string]]::new()
    try {
        foreach ($name in @('AstralRecordControl.Runner', 'AstralRecordControl.Agent')) {
            $requested.Add($name)
            Start-Service -Name $name
            if ((Get-Service -Name $name -ErrorAction Stop).Status -ne 'Running') {
                throw "$name did not reach Running."
            }
        }
        Set-ControlServiceStartup Runner
        Set-ControlServiceStartup Agent
    } catch {
        $startupError = $_
        $rollbackErrors = @()
        for ($index = $requested.Count - 1; $index -ge 0; $index--) {
            try { Stop-ServiceAndConfirm $requested[$index] }
            catch { $rollbackErrors += $_.Exception.Message }
        }
        if ($rollbackErrors.Count -gt 0) {
            throw "Service startup failed and rollback was incomplete: $($rollbackErrors -join '; '). Original error: $($startupError.Exception.Message)"
        }
        throw $startupError
    }
}

if (-not $IsWindows) { throw 'Windows is required.' }
$plan = Read-JsonObject $PlanPath
$receipt = Read-JsonObject $HostReceiptPath
if ($plan.schemaVersion -ne 1 -or $receipt.schemaVersion -ne 1) { throw 'Unsupported import schema version.' }
$origin = Assert-Origin $plan.publicOrigin
if ((Assert-Origin $receipt.publicOrigin) -ine $origin) { throw 'Plan and receipt public origins differ.' }
$hostKey = $env:COMPUTERNAME.ToLowerInvariant()
$planHosts = @($plan.hosts | Where-Object { [string]$_.hostKey -ieq $hostKey })
$receiptHosts = @($receipt.hosts | Where-Object { [string]$_.hostKey -ieq $hostKey })
if ($planHosts.Count -ne 1 -or $receiptHosts.Count -ne 1) { throw "Exactly one plan and receipt host must match $hostKey." }
$hostId = Assert-Guid $receiptHosts[0].hostId 'Host ID'
$resources = New-Resources $planHosts[0]
$packageRoot = Assert-LocalPath $PackageDirectory 'Container'
$null = Assert-Package $packageRoot $ExpectedManifestSha256
$marker = Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
if (Test-Path -LiteralPath $marker) { throw 'Managed operations marker already exists; this passive initializer refuses to proceed.' }
Write-Output "Validated passive host $hostKey. Expected Host ID: $hostId; Control origin: $origin."
if ($PreflightOnly) { Write-Output 'Fixture-safe preflight passed; no service or ACL changes made.'; return }

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not [Security.Principal.WindowsPrincipal]::new($identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this initializer in an elevated local administrator PowerShell session.'
}
$installRoot = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'AstralRecordControl')).TrimEnd('\')
$agentData = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'AstralRecordControl\Agent')).TrimEnd('\')
$runnerData = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'AstralRecordControl\Runner')).TrimEnd('\')
$services = @{}
foreach ($component in @('Agent', 'Runner')) {
    $data = if ($component -eq 'Agent') { $agentData } else { $runnerData }
    $services[$component] = Get-ServiceDefinition $component
    Assert-StoppedService $services[$component] $component $installRoot $data
}

$bootstrapRoot = Assert-LocalPath (Join-Path $env:ProgramData 'AstralRecordControl\Bootstrap') ''
New-Item -ItemType Directory -Path $bootstrapRoot -Force | Out-Null
Set-AdminOnlyAcl $bootstrapRoot
$stage = Join-Path $bootstrapRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Set-AdminOnlyAcl $stage
foreach ($relative in @('Control\Agent', 'Control\Runner', 'Control\Cli', 'Tools\60_tool\control-deploy')) {
    $destination = Join-Path $stage $relative
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $packageRoot $relative) -Force |
        Copy-Item -Destination $destination -Recurse -Force
}
Copy-Item -LiteralPath (Join-Path $packageRoot 'release-manifest.json') -Destination $stage
$null = Assert-Package $stage $ExpectedManifestSha256
Import-Module (Join-Path $stage 'Tools\60_tool\control-deploy\Control.Deployment.psm1') -Force
if ($services['Agent']) {
    & (Join-Path $stage 'Tools\60_tool\control-deploy\Install-ControlCli.ps1') -PackageDirectory (Join-Path $stage 'Control\Cli')
}
$cliPath=Join-Path $env:LOCALAPPDATA 'AstralRecordControl\Cli\bin\AstralRecordControl.Cli.exe'
$install = Join-Path $stage 'Tools\60_tool\control-deploy\Install-Control.ps1'
$validated = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$attemptedInstall = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$initialized = $false
try {
    foreach ($component in @('Runner', 'Agent')) {
        if ($null -eq $services[$component]) {
            $null = $attemptedInstall.Add($component)
            & $install -Component $component -PackageDirectory (Join-Path $stage "Control\$component") -InstallRoot $installRoot -KeepManual
            if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Installer failed for $component." }
        }
        $data = if ($component -eq 'Agent') { $agentData } else { $runnerData }
        $service = Get-ServiceDefinition $component
        Assert-StoppedService $service $component $installRoot $data
        $null = $validated.Add($component)
        Set-ManualStart $component
    }
    if (!(Test-Path -LiteralPath $cliPath -PathType Leaf)) { throw 'The Control CLI was not installed.' }
    $agentSid = [Security.Principal.NTAccount]::new('NT SERVICE\AstralRecordControl.Agent').Translate([Security.Principal.SecurityIdentifier]).Value
    $runnerSid = [Security.Principal.NTAccount]::new('NT SERVICE\AstralRecordControl.Runner').Translate([Security.Principal.SecurityIdentifier]).Value
    $pipeName = 'AstralRecordControl.Runner.v1'
    $agentConfigPath = Join-Path $agentData 'config.json'
    $runnerConfigPath = Join-Path $runnerData 'config.json'
    if (Test-Path -LiteralPath $agentConfigPath) {
        Assert-ConfigMatch (Read-JsonObject $agentConfigPath) 'Agent' $hostId $origin $agentSid $pipeName $resources
    } else {
        Write-NewConfig $agentConfigPath ([ordered]@{
            hostId = $hostId; serverUrl = $origin; runnerPipeName = $pipeName
            tokenFile = 'credentials/token.bin'; localResources = $resources
            enableFileControl = $false; enableSchedules = $false
        })
    }
    if (Test-Path -LiteralPath $runnerConfigPath) {
        Assert-ConfigMatch (Read-JsonObject $runnerConfigPath) 'Runner' $hostId $origin $agentSid $pipeName $resources
    } else {
        Write-NewConfig $runnerConfigPath ([ordered]@{
            hostId = $hostId; agentSid = $agentSid; pipeName = $pipeName
            localResources = $resources; enableProcessControl = $false; enableFileControl = $false
        })
    }
    $credential = Join-Path $agentData 'credentials\token.bin'
    if (-not (Test-Path -LiteralPath $credential -PathType Leaf)) {
        & $cliPath login --server-url $origin
        if ($LASTEXITCODE -ne 0) { throw 'The initial Control login did not complete.' }
        & $cliPath agent enroll --host-id $hostId --expected-server-url $origin --start-agent false
        if ($LASTEXITCODE -ne 0) { throw 'Agent enrollment did not complete.' }
    }
    if (-not (Test-Path -LiteralPath $credential -PathType Leaf)) { throw 'Agent credential was not created.' }
    Assert-CredentialFile $credential $agentSid
    Assert-ConfigMatch (Read-JsonObject $agentConfigPath) 'Agent' $hostId $origin $agentSid $pipeName $resources
    Assert-ConfigMatch (Read-JsonObject $runnerConfigPath) 'Runner' $hostId $origin $agentSid $pipeName $resources
    foreach ($root in $resources.roots) {
        $path = Assert-LocalPath $root.path 'Container'
        Add-ServiceAcl $path @($agentSid, $runnerSid) 'Modify' $true
    }
    foreach ($executable in $resources.executables | Sort-Object path -Unique) {
        $path = Assert-LocalPath $executable.path 'Leaf'
        $bin = [IO.Path]::GetDirectoryName($path)
        if ([IO.Path]::GetFileName($bin) -ine 'bin') { throw 'Java executable must be under a bin directory.' }
        $javaHome = Assert-LocalPath ([IO.Path]::GetDirectoryName($bin)) 'Container'
        Add-ServiceAcl $javaHome @($agentSid, $runnerSid) 'ReadAndExecute' $true
        Add-ServiceAcl $path @($agentSid, $runnerSid) 'ReadAndExecute' $false
    }
    & $install -Component Runner -PackageDirectory (Join-Path $stage 'Control\Runner') -InstallRoot $installRoot -PreflightOnly
    & $install -Component Agent -PackageDirectory (Join-Path $stage 'Control\Agent') -InstallRoot $installRoot -PreflightOnly
    if (Test-Path -LiteralPath $marker) { throw 'Managed operations marker appeared during initialization.' }
    Start-PassiveServices
    $initialized = $true
    Write-Output "Passive Runner and Agent started for $hostKey. Host ID: $hostId; origin: $origin."
} finally {
    foreach ($component in @($validated) + @($attemptedInstall) | Select-Object -Unique) {
        $service = Get-ServiceDefinition $component
        if ($null -ne $service) {
            $data = if ($component -eq 'Agent') { $agentData } else { $runnerData }
            Assert-StoppedService $service $component $installRoot $data -AllowRunning
            if (-not $initialized) { Set-ManualStart $component }
        }
    }
}
