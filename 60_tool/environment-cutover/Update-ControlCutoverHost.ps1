#requires -Version 7.0
#requires -RunAsAdministrator
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('RestoreAgent','ConfigureResources')][string]$Phase,
    [Parameter(Mandatory)][string]$ToolRoot,
    [Parameter(Mandatory)][string]$CliPath,
    [Parameter(Mandatory)][string]$ReportDirectory,
    [Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ExpectedDeploymentModuleSha256,
    [string]$ExpectedToolManifestSha256 = '',
    [switch]$GrantAppDirectoryMetadataAccess
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'ControlCutover.psm1') -Force

function Assert-LocalAbsolute([string]$Path) {
    if (![IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'LocalAbsolutePathRequired' }
    $full=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    if ($full.Length -le 3) { throw 'DriveRootRefused' }
    $cursor=$full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'ReparsePathRefused' }
        }
        $parent=[IO.Path]::GetDirectoryName($cursor)
        if ($parent -eq $cursor) { break }
        $cursor=$parent
    }
    return $full
}

function Assert-ServiceAcl([string]$Path,[string]$ServiceSid) {
    $acl=Get-Acl -LiteralPath $Path
    if (!$acl.AreAccessRulesProtected) { throw 'UnprotectedServiceDataAcl' }
    $allowed=@('S-1-5-18','S-1-5-32-544',$ServiceSid)
    $hasServiceAccess=$false
    foreach ($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if ($rule.IsInherited -or $rule.AccessControlType -eq 'Deny') { throw 'UnsafeServiceDataAcl' }
        if ($rule.IdentityReference.Value -notin $allowed) { throw 'UnexpectedServiceDataAcl' }
        if ($rule.IdentityReference.Value -eq $ServiceSid -and
            ($rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) -eq [Security.AccessControl.FileSystemRights]::Modify -and
            $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::InheritOnly) { $hasServiceAccess=$true }
    }
    if (!$hasServiceAccess) { throw 'ServiceDataAccessMissing' }
}

function Assert-DirectoryReadAcl([string]$Path,[string]$ServiceSid) {
    $acl=Get-Acl -LiteralPath $Path
    $read=[Security.AccessControl.FileSystemRights]::ReadAndExecute
    $hasRead=$false
    foreach ($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Deny') { throw 'AppDirectoryDenyRefused' }
        if ($rule.IdentityReference.Value -ne $ServiceSid) { continue }
        if ($rule.AccessControlType -eq 'Allow' -and ($rule.FileSystemRights -band $read) -eq $read -and
            $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::InheritOnly) { $hasRead=$true }
    }
    if (!$hasRead) { throw 'AppRootReadMissing' }
}

function New-AppAclBackup([string]$AgentData,$Entries) {
    $directory=Join-Path $AgentData ('cutover-acl-backup-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory -ErrorAction Stop | Out-Null
    $private=[Security.AccessControl.DirectorySecurity]::new()
    $private.SetAccessRuleProtection($true,$false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $private.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),[Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow))
    }
    Set-Acl -LiteralPath $directory -AclObject $private
    $backupPath=Join-Path $directory 'directory-dacls.json'
    $content=@{schemaVersion=1;capturedAtUtc=[DateTimeOffset]::UtcNow.ToString('O');directories=@(
        $Entries | ForEach-Object { @{path=$_.path;dacl=$_.acl.GetSecurityDescriptorSddlForm([Security.AccessControl.AccessControlSections]::Access)} })}
    [IO.File]::WriteAllText($backupPath,($content | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    foreach ($path in @($directory,$backupPath)) {
        $acl=Get-Acl -LiteralPath $path
        if ($path -eq $directory -and !$acl.AreAccessRulesProtected) { throw 'AppAclBackupNotPrivate' }
        foreach ($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin @('S-1-5-18','S-1-5-32-544')) { throw 'AppAclBackupNotPrivate' }
        }
    }
    return $backupPath
}

function Grant-AppMetadataAccess([string]$AgentData,[string[]]$Directories,[string[]]$ServiceSids) {
    $entries=@()
    $read=[Security.AccessControl.FileSystemRights]::ReadAndExecute
    $allowedRights=[int]$read -bor [int][Security.AccessControl.FileSystemRights]::Synchronize
    foreach ($path in $Directories) {
        $resolved=Assert-LocalAbsolute $path
        if ($resolved -ine $path -or !(Test-Path -LiteralPath $path -PathType Container)) { throw 'AppDirectoryMissing' }
        $acl=Get-Acl -LiteralPath $path
        foreach ($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Deny') { throw 'AppDirectoryDenyRefused' }
            if ($rule.IdentityReference.Value -in $ServiceSids -and !$rule.IsInherited -and
                ($rule.AccessControlType -ne 'Allow' -or
                $rule.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
                $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or
                ($rule.FileSystemRights -band $read) -ne $read -or
                (([int]$rule.FileSystemRights -band (-bnot $allowedRights)) -ne 0))) { throw 'AppDirectoryExistingServiceAceRefused' }
        }
        $entries+=@{path=$path;acl=$acl}
    }
    $null=New-AppAclBackup $AgentData $entries
    try {
        foreach ($entry in $entries) {
            $acl=Get-Acl -LiteralPath $entry.path
            foreach ($sid in $ServiceSids) {
                $matching=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]) | Where-Object {
                    $_.IdentityReference.Value -eq $sid -and $_.AccessControlType -eq 'Allow' -and !$_.IsInherited -and
                    $_.InheritanceFlags -eq [Security.AccessControl.InheritanceFlags]::None -and
                    $_.PropagationFlags -eq [Security.AccessControl.PropagationFlags]::None -and
                    ($_.FileSystemRights -band $read) -eq $read })
                if ($matching.Count -eq 0) { $acl.AddAccessRule((New-ControlCutoverMetadataReadRule $sid)) }
            }
            Set-Acl -LiteralPath $entry.path -AclObject $acl
            foreach ($sid in $ServiceSids) { Assert-DirectoryReadAcl $entry.path $sid }
        }
    } catch {
        $recovered=$true
        foreach ($entry in $entries) { try { Set-Acl -LiteralPath $entry.path -AclObject $entry.acl } catch { $recovered=$false } }
        if (!$recovered) { throw 'AppAclRecoveryIncomplete' }
        throw
    }
    return $entries
}

function Get-InstalledService([string]$Component) {
    $name="AstralRecordControl.$Component"
    $service=Get-CimInstance Win32_Service -Filter "Name='$name'"
    if (!$service -or $service.StartName -ine "NT SERVICE\$name" -or $service.State -notin @('Running','Stopped')) { throw 'UnexpectedService' }
    $paths=Get-ControlCutoverServicePaths ([string]$service.PathName) $Component
    $binary=Get-ControlAbsolutePath $paths.binary
    $data=Get-ControlAbsolutePath $paths.data
    $expectedBinary=(Get-ControlAbsolutePath (Join-Path $env:ProgramFiles "AstralRecordControl\$Component"))+'\'
    $expectedData=Get-ControlAbsolutePath (Join-Path $env:ProgramData "AstralRecordControl\$Component")
    if (!$binary.StartsWith($expectedBinary,[StringComparison]::OrdinalIgnoreCase) -or $data -ine $expectedData -or
        !(Test-Path -LiteralPath $binary -PathType Leaf) -or !(Test-Path -LiteralPath $data -PathType Container)) { throw 'UnexpectedServiceInstallation' }
    Assert-ControlPackage ([IO.Path]::GetDirectoryName($binary)) $Component | Out-Null
    $sid=[Security.Principal.NTAccount]::new("NT SERVICE\$name").Translate([Security.Principal.SecurityIdentifier]).Value
    Assert-ServiceAcl $data $sid
    return [pscustomobject]@{Name=$name;State=[string]$service.State;Binary=$binary;Data=$data;Sid=$sid}
}

function Assert-CredentialAcl([string]$AgentData,[string]$AgentSid) {
    $path=Join-Path $AgentData 'credentials\token.bin'
    if (!(Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) { throw 'AgentCredentialMissing' }
    $allowed=@('S-1-5-18','S-1-5-32-544',$AgentSid)
    $directory=Join-Path $AgentData 'credentials'
    if (!(Get-Acl -LiteralPath $directory).AreAccessRulesProtected) { throw 'UnprotectedCredentialAcl' }
    foreach ($item in @($directory,$path)) {
        $acl=Get-Acl -LiteralPath $item
        foreach ($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowed) { throw 'UnexpectedCredentialAcl' }
        }
    }
}

function Read-Config([string]$Path) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'MissingControlConfig' }
    $value=Get-Content -Raw -Encoding UTF8 -LiteralPath $Path | ConvertFrom-Json -AsHashtable
    if ($value -isnot [Collections.IDictionary]) { throw 'InvalidControlConfig' }
    return $value
}

function Write-NewConfig([string]$Path,$Value) {
    if (Test-Path -LiteralPath $Path) { throw 'ExistingConfigProtected' }
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    try {
        [IO.File]::WriteAllText($temporary,($Value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary,$Path)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Backup-Config([string]$Path,[string]$ServiceSid) {
    $directory=Join-Path ([IO.Path]::GetDirectoryName($Path)) 'cutover-backups'
    if (!(Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null; Set-ControlAcl $directory $ServiceSid }
    Assert-ServiceAcl $directory $ServiceSid
    $destination=Join-Path $directory (([IO.Path]::GetFileName($Path))+'.'+[DateTimeOffset]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')+'.'+[Guid]::NewGuid().ToString('N')+'.backup')
    Copy-Item -LiteralPath $Path -Destination $destination
    Set-Acl -LiteralPath $destination -AclObject (Get-Acl -LiteralPath $Path)
    return $destination
}

function Replace-Config([string]$Path,$Value) {
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    try {
        [IO.File]::WriteAllText($temporary,($Value|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Path)
        [IO.File]::Move($temporary,$Path,$true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Restore-Config([string]$Path,[string]$Backup) {
    if (!$Backup -or !(Test-Path -LiteralPath $Backup -PathType Leaf)) { throw 'ConfigBackupMissing' }
    $temporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.restore'
    try {
        Copy-Item -LiteralPath $Backup -Destination $temporary
        Set-Acl -LiteralPath $temporary -AclObject (Get-Acl -LiteralPath $Backup)
        [IO.File]::Move($temporary,$Path,$true)
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}

function Get-StoppedSnapshot([string]$ConfigPath) {
    if (![IO.Path]::IsPathFullyQualified($CliPath) -or !(Test-Path -LiteralPath $CliPath -PathType Leaf)) { throw 'InvalidCliPath' }
    $runner=Read-Config $ConfigPath
    $pipe=[string]$runner['pipeName']
    if ($pipe -cnotmatch '^[A-Za-z0-9._-]+$') { throw 'InvalidPipeName' }
    $lines=& $CliPath runner check-update --pipe-name $pipe
    if ($LASTEXITCODE -ne 0) { throw 'RunnerSafetyCheckFailed' }
    $snapshot=($lines -join "`n")|ConvertFrom-Json -AsHashtable
    Assert-ControlCutoverSnapshot $snapshot
    return $snapshot
}

function Assert-RunnerPersistedSafe($RunnerService,$Snapshot) {
    $assembly=Join-Path ([IO.Path]::GetDirectoryName($RunnerService.Binary)) 'Microsoft.Data.Sqlite.dll'
    $database=Join-Path $RunnerService.Data 'runner.db'
    if (!(Test-Path -LiteralPath $assembly -PathType Leaf) -or !(Test-Path -LiteralPath $database -PathType Leaf)) { throw 'RunnerLedgerUnavailable' }
    try {
        Add-Type -Path $assembly -ErrorAction Stop
        $builder=[Microsoft.Data.Sqlite.SqliteConnectionStringBuilder]::new()
        $builder.DataSource=$database
        $builder.Mode=[Microsoft.Data.Sqlite.SqliteOpenMode]::ReadOnly
        $builder.Cache=[Microsoft.Data.Sqlite.SqliteCacheMode]::Private
        $builder.Pooling=$false
        $connection=[Microsoft.Data.Sqlite.SqliteConnection]::new($builder.ToString())
        try {
            $connection.Open()
            $command=$connection.CreateCommand()
            $command.CommandText='SELECT id, config, desiredState, nextAutoStartUtc FROM Servers'
            $reader=$command.ExecuteReader()
            $rows=@()
            try {
                while ($reader.Read()) {
                    $rows+=@{id=$reader.GetString(0);config=$reader.GetString(1);desiredState=$reader.GetString(2);
                        nextAutoStartUtc=if($reader.IsDBNull(3)){$null}else{$reader.GetString(3)}}
                }
            } finally { $reader.Dispose();$command.Dispose() }
            $ids=@($Snapshot['servers'] | ForEach-Object {[string]$_['serverId']})
            if ($rows.Count -ne $ids.Count) { throw 'RunnerLedgerMismatch' }
            foreach ($row in $rows) {
                if ($row.id -notin $ids -or $row.desiredState -cne 'Stopped' -or $row.nextAutoStartUtc) { throw 'AutomaticStartPending' }
                $config=$row.config | ConvertFrom-Json -AsHashtable
                if ($config['autoStart'] -ne $false -or $config['restartOnCrash'] -ne $false) { throw 'AutomaticStartConfigured' }
            }
        } finally { $connection.Dispose() }
    } catch {
        if ($_.Exception.Message -in @('RunnerLedgerMismatch','AutomaticStartPending','AutomaticStartConfigured')) { throw }
        throw 'RunnerLedgerUnavailable'
    }
}

function Assert-ToolRoot([string]$Root,[string]$ComputerName) {
    $root=Assert-LocalAbsolute $Root
    $files=@('control-gate\Control.Gate.psm1','deploy-debug\dev-update.ps1','deploy-debug\deploy-debug.ps1',
        'maintenance\maintenance.ps1','maintenance\UpdateWorkflow.ps1','player-patch-migration\player-patch-migration.ps1',
        'master-data-reload\master-data-reload.ps1')
    $markerPath=Join-Path $env:ProgramData 'AstralRecordControl\managed-operations.enabled'
    if ($ComputerName -eq 'APP-01') {
        if (Test-Path -LiteralPath $markerPath) { throw 'UnexpectedAppMarker' }
        if ($ExpectedToolManifestSha256 -cnotmatch '^[A-Fa-f0-9]{64}$') { throw 'ExpectedToolManifestHashRequired' }
        $manifestPath=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($root))) 'operations-manifest.json'
        if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
            (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -ine $ExpectedToolManifestSha256) { throw 'ToolManifestMismatch' }
        $manifest=Read-Config $manifestPath
        if ($manifest['schemaVersion'] -ne 1) { throw 'InvalidToolManifest' }
        foreach ($file in $files) {
            $entry='Tools/60_tool/'+($file -replace '\\','/')
            $path=Join-Path $root $file
            if (!(Test-Path -LiteralPath $path -PathType Leaf) -or !$manifest['sha256'].Contains($entry) -or
                (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ine [string]$manifest['sha256'][$entry]) { throw 'ToolHashMismatch' }
        }
    } else {
        $marker=Read-Config $markerPath
        if ($marker['schemaVersion'] -ne 1 -or (Assert-LocalAbsolute ([string]$marker['toolRoot'])) -ine $root) { throw 'MarkerRootMismatch' }
        foreach ($file in $files) {
            $path=Join-Path $root $file
            if (!(Test-Path -LiteralPath $path -PathType Leaf) -or
                ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'ManagedEntryMissing' }
            $parseErrors=$null
            [Management.Automation.Language.Parser]::ParseFile($path,[ref]$null,[ref]$parseErrors) | Out-Null
            if ($parseErrors.Count) { throw 'ManagedEntrySyntaxError' }
        }
    }
    $deployModule=Join-Path $root 'control-deploy\Control.Deployment.psm1'
    if (!(Test-Path -LiteralPath $deployModule -PathType Leaf) -or
        (Get-FileHash -LiteralPath $deployModule -Algorithm SHA256).Hash -ine $ExpectedDeploymentModuleSha256) { throw 'DeploymentModuleHashMismatch' }
    if ($ComputerName -eq 'APP-01') {
        $entry='Tools/60_tool/control-deploy/Control.Deployment.psm1'
        if (!$manifest['sha256'].Contains($entry) -or (Get-FileHash -LiteralPath $deployModule -Algorithm SHA256).Hash -ine [string]$manifest['sha256'][$entry]) { throw 'DeploymentModuleHashMismatch' }
    }
    Import-Module $deployModule -Force
    return $root
}

function Wait-ServiceState([string]$Name,[string]$Expected) {
    $service=Get-Service -Name $Name
    $service.WaitForStatus($Expected,[TimeSpan]::FromSeconds(45))
}

if (!$IsWindows -or $env:COMPUTERNAME -notin @('APP-01','DEV-01','MC-01')) { throw 'UnsupportedHost' }
if (![IO.Path]::IsPathFullyQualified($ReportDirectory) -or $ReportDirectory.StartsWith('\\')) { throw 'LocalReportDirectoryRequired' }
$spec=Get-ControlCutoverHost $env:COMPUTERNAME
$report=[ordered]@{schemaVersion=1;computer=$env:COMPUTERNAME;phase=$Phase;hostId=$spec.hostId;
    rootId=if($spec.ContainsKey('rootId')){$spec.rootId}else{$null};rootPath=if($spec.ContainsKey('rootPath')){$spec.rootPath}else{$null};
    metadataOnly=if($env:COMPUTERNAME -eq 'APP-01' -and $Phase -eq 'ConfigureResources'){$true}else{$null};
    targetDirectories=if($env:COMPUTERNAME -eq 'APP-01' -and $Phase -eq 'ConfigureResources'){@(Get-ControlCutoverAppMetadataDirectories)}else{$null};
    success=$false;stage='Preflight';errorType=$null;completedAtUtc=$null}
$agentWasRunning=$false; $devRunnerStopped=$false; $agentBackup=$null; $runnerBackup=$null
$appAgentStarted=$false; $appRunnerStarted=$false; $appAclOriginal=@(); $lock=$null
try {
    if ($GrantAppDirectoryMetadataAccess -and ($env:COMPUTERNAME -ne 'APP-01' -or $Phase -ne 'ConfigureResources')) { throw 'MetadataGrantOnlyForAppConfigure' }
    $null=Assert-LocalAbsolute $ReportDirectory
    if (!(Test-Path -LiteralPath $ReportDirectory -PathType Container)) { throw 'ReportDirectoryMissing' }
    if (![IO.Path]::IsPathFullyQualified($CliPath) -or !(Test-Path -LiteralPath $CliPath -PathType Leaf)) { throw 'InvalidCliPath' }
    $null=Assert-ToolRoot $ToolRoot $env:COMPUTERNAME
    $report.stage='ValidateServices'
    $agentService=Get-InstalledService Agent
    $runnerService=Get-InstalledService Runner
    try {
        $lock=[IO.FileStream]::new((Join-Path $agentService.Data 'control-cutover.lock'),[IO.FileMode]::CreateNew,
            [IO.FileAccess]::ReadWrite,[IO.FileShare]::None,4096,[IO.FileOptions]::DeleteOnClose)
    } catch { throw 'CutoverLockUnavailable' }
    $agentConfigPath=Join-Path $agentService.Data 'config.json'
    $runnerConfigPath=Join-Path $runnerService.Data 'config.json'
    if ($Phase -eq 'RestoreAgent') {
        $report.stage='ValidateRestore'
        if ($env:COMPUTERNAME -eq 'APP-01' -or $agentService.State -ne 'Stopped' -or $runnerService.State -ne 'Running') { throw 'UnexpectedRestoreState' }
        $agent=Read-Config $agentConfigPath; $runner=Read-Config $runnerConfigPath
        Assert-ControlCutoverBinding $agent $runner $env:COMPUTERNAME $agentService.Sid
        Assert-CredentialAcl $agentService.Data $agentService.Sid
        Test-ControlConfiguration $agentService.Data Agent
        Test-ControlConfiguration $runnerService.Data Runner
        $report.stage='StartAgent'
        Start-Service -Name $agentService.Name
        Wait-ServiceState $agentService.Name 'Running'
    } elseif ($env:COMPUTERNAME -eq 'APP-01') {
        $report.stage='ValidateApp'
        if ($agentService.State -ne 'Stopped' -or $runnerService.State -ne 'Stopped') { throw 'AppServicesMustBeStopped' }
        $root=Get-ControlCutoverRoot 'APP-01'
        if (!(Test-Path -LiteralPath $root.path -PathType Container)) { throw 'AppRootMissing' }
        $appDirectories=@(Get-ControlCutoverAppMetadataDirectories)
        if ($GrantAppDirectoryMetadataAccess) {
            $report.stage='GrantAppMetadataAccess'
            $appAclOriginal=@(Grant-AppMetadataAccess $agentService.Data $appDirectories @($agentService.Sid,$runnerService.Sid))
        }
        foreach ($path in $appDirectories) {
            $null=Assert-LocalAbsolute $path
            if (!(Test-Path -LiteralPath $path -PathType Container)) { throw 'AppDirectoryMissing' }
            Assert-DirectoryReadAcl $path $agentService.Sid
            Assert-DirectoryReadAcl $path $runnerService.Sid
        }
        $resources=@{roots=@($root);executables=@()}
        $report.stage='ConfigureApp'
        if (!(Test-Path -LiteralPath $runnerConfigPath)) {
            Write-NewConfig $runnerConfigPath @{hostId=$spec.hostId;agentSid=$agentService.Sid;pipeName='AstralRecordControl.Runner.v1';localResources=$resources;enableProcessControl=$false;enableFileControl=$false}
        }
        if (!(Test-Path -LiteralPath $agentConfigPath)) {
            Write-NewConfig $agentConfigPath @{hostId=$spec.hostId;serverUrl='https://control.astralrecord.com';runnerPipeName='AstralRecordControl.Runner.v1';tokenFile='credentials/token.bin';localResources=$resources;enableFileControl=$false;enableSchedules=$false}
        }
        $agent=Read-Config $agentConfigPath; $runner=Read-Config $runnerConfigPath
        Assert-ControlCutoverBinding $agent $runner 'APP-01' $agentService.Sid
        if (@($agent['localResources']['roots']).Count -ne 1 -or [string]$agent['localResources']['roots'][0]['id'] -ine $root.id) { throw 'UnexpectedAppResources' }
        $credential=Join-Path $agentService.Data 'credentials\token.bin'
        if (!(Test-Path -LiteralPath $credential -PathType Leaf)) {
            Invoke-ControlCutoverAgentEnrollment $CliPath ([Guid]$spec.hostId)
        }
        Assert-CredentialAcl $agentService.Data $agentService.Sid
        $agent=Read-Config $agentConfigPath
        Assert-ControlCutoverBinding $agent $runner 'APP-01' $agentService.Sid
        Test-ControlConfiguration $agentService.Data Agent
        Test-ControlConfiguration $runnerService.Data Runner
        $report.stage='StartRunner'
        $appRunnerStarted=$true
        Start-Service -Name $runnerService.Name
        Wait-ServiceState $runnerService.Name 'Running'
        $appSnapshot=Get-StoppedSnapshot $runnerConfigPath
        if (@($appSnapshot['servers']).Count) { throw 'UnexpectedAppServers' }
        $report.stage='StartAgent'
        $appAgentStarted=$true
        Start-Service -Name $agentService.Name
        Wait-ServiceState $agentService.Name 'Running'
        # The APP marker is installed separately with the repository's official Enable-ManagedOperations.ps1.
    } else {
        $report.stage='ValidateGameHost'
        if ($runnerService.State -ne 'Running') { throw 'RunnerMustBeRunning' }
        $agent=Read-Config $agentConfigPath; $runner=Read-Config $runnerConfigPath
        Assert-ControlCutoverBinding $agent $runner $env:COMPUTERNAME $agentService.Sid
        Assert-CredentialAcl $agentService.Data $agentService.Sid
        $null=Get-StoppedSnapshot $runnerConfigPath
        if ($env:COMPUTERNAME -eq 'DEV-01') {
            $root=Get-ControlCutoverRoot 'DEV-01'
            if (!(Test-Path -LiteralPath $root.path -PathType Container) -or !(Test-Path -LiteralPath (Join-Path $root.path '40_filebase') -PathType Container)) { throw 'DevFilebaseRootMissing' }
            $agentWasRunning=$agentService.State -eq 'Running'
            $report.stage='StopDevAgent'
            if ($agentWasRunning) { Stop-Service -Name $agentService.Name; Wait-ServiceState $agentService.Name 'Stopped' }
            $report.stage='ValidateDevStopped'
            $safeSnapshot=Get-StoppedSnapshot $runnerConfigPath
            Assert-RunnerPersistedSafe $runnerService $safeSnapshot
            $agentBackup=Backup-Config $agentConfigPath $agentService.Sid
            $runnerBackup=Backup-Config $runnerConfigPath $runnerService.Sid
            $report.stage='UpdateDevResources'
            $devRunnerStopped=$true
            Stop-Service -Name $runnerService.Name
            Wait-ServiceState $runnerService.Name 'Stopped'
            $agent=Add-ControlCutoverRoot $agent 'DEV-01'
            $runner=Add-ControlCutoverRoot $runner 'DEV-01'
            Assert-ControlCutoverBinding $agent $runner 'DEV-01' $agentService.Sid
            Replace-Config $agentConfigPath $agent
            Replace-Config $runnerConfigPath $runner
            Test-ControlConfiguration $agentService.Data Agent
            Test-ControlConfiguration $runnerService.Data Runner
            $report.stage='StartDevServices'
            Start-Service -Name $runnerService.Name
            Wait-ServiceState $runnerService.Name 'Running'
            $devRunnerStopped=$false
            Start-Service -Name $agentService.Name
            Wait-ServiceState $agentService.Name 'Running'
        }
    }
    $report.success=$true
    $report.stage='Completed'
} catch {
    $report.errorType=$_.Exception.GetType().FullName
    # Never stop a running game process or erase an existing credential during recovery.
    if ($Phase -eq 'ConfigureResources' -and $env:COMPUTERNAME -eq 'APP-01') {
        $servicesRecovered=$true
        foreach ($item in @(@{name='AstralRecordControl.Agent';started=$appAgentStarted},@{name='AstralRecordControl.Runner';started=$appRunnerStarted})) {
            try { if ($item.started -and (Get-Service -Name $item.name).Status -eq 'Running') { Stop-Service -Name $item.name; Wait-ServiceState $item.name 'Stopped' } }
            catch { $servicesRecovered=$false }
        }
        if (!$servicesRecovered) { $report.errorType='RecoveryIncomplete' }
        if ($appAclOriginal.Count) {
            $recovered=$true
            foreach ($entry in $appAclOriginal) { try { Set-Acl -LiteralPath $entry.path -AclObject $entry.acl } catch { $recovered=$false } }
            if (!$recovered) { $report.errorType='RecoveryIncomplete' }
        }
    }
    if ($Phase -eq 'ConfigureResources' -and $env:COMPUTERNAME -eq 'DEV-01' -and $devRunnerStopped) {
        try {
            if ((Get-Service -Name 'AstralRecordControl.Runner').Status -eq 'Stopped') {
                Restore-Config $agentConfigPath $agentBackup
                Restore-Config $runnerConfigPath $runnerBackup
                Start-Service -Name 'AstralRecordControl.Runner'
                Wait-ServiceState 'AstralRecordControl.Runner' 'Running'
                if ($agentWasRunning) { Start-Service -Name 'AstralRecordControl.Agent'; Wait-ServiceState 'AstralRecordControl.Agent' 'Running' }
            }
        } catch { $report.errorType='RecoveryIncomplete' }
    }
    if ($Phase -eq 'ConfigureResources' -and $env:COMPUTERNAME -eq 'DEV-01' -and $agentWasRunning -and !$devRunnerStopped) {
        try {
            if ((Get-Service -Name 'AstralRecordControl.Runner').Status -eq 'Running' -and
                (Get-Service -Name 'AstralRecordControl.Agent').Status -eq 'Stopped') {
                Start-Service -Name 'AstralRecordControl.Agent'
                Wait-ServiceState 'AstralRecordControl.Agent' 'Running'
            }
        } catch { $report.errorType='RecoveryIncomplete' }
    }
    Write-Error 'Control cutover host phase failed. Inspect the nonsecret report and actual service states.' -ErrorAction Continue
} finally {
    if ($lock) { $lock.Dispose() }
    $report.completedAtUtc=[DateTimeOffset]::UtcNow.ToString('O')
    if (Test-Path -LiteralPath $ReportDirectory -PathType Container) {
        $null=Assert-LocalAbsolute $ReportDirectory
        $destination=Join-Path $ReportDirectory ($env:COMPUTERNAME+'-control-cutover-'+$Phase+'.json')
        $report|ConvertTo-Json -Depth 5|Set-Content -Encoding UTF8 -LiteralPath $destination
    }
}
if (!$report.success) { exit 1 }
