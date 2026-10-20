Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function Assert-CutoverLocalPath([string]$Path) {
    if (![IO.Path]::IsPathFullyQualified($Path) -or $Path.StartsWith('\\')) { throw 'A local absolute path is required.' }
    $cursor=[IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Reparse points are not allowed.' }
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
}

function Get-CutoverFileManifest([string]$Root) {
    Assert-CutoverLocalPath $Root
    if (!(Test-Path -LiteralPath $Root -PathType Container)) { throw 'Filebase root is missing.' }
    $files=[ordered]@{}
    # Descend explicitly so a junction is rejected before it can be followed.
    $pending=[Collections.Generic.Stack[string]]::new()
    $pending.Push([IO.Path]::GetFullPath($Root))
    while ($pending.Count) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Filebase contains a reparse point.' }
            $relative=[IO.Path]::GetRelativePath($Root,$item.FullName).Replace('\','/')
            if ($item.PSIsContainer) {
                if ($relative -ne '99.work') { $pending.Push($item.FullName) }
            } elseif ($item.Extension -in @('.yml','.yaml','.json')) {
                $files[$relative]=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash
            }
        }
    }
    if (!$files.Contains('config.yml') -or !$files.Count) { throw 'Filebase config.yml is required.' }
    return [ordered]@{schemaVersion=1;fileCount=$files.Count;files=$files}
}

function Assert-CutoverFileManifest($Expected,$Actual) {
    if ($Expected.schemaVersion -ne 1 -or $Expected.fileCount -ne $Expected.files.Count -or $Expected.fileCount -ne $Actual.fileCount) { throw 'Filebase manifest count differs.' }
    foreach ($entry in $Expected.files.GetEnumerator()) {
        if (!$Actual.files.Contains($entry.Key) -or $entry.Value -cnotmatch '^[A-Fa-f0-9]{64}$' -or $Actual.files[$entry.Key] -ine $entry.Value) { throw 'Filebase manifest content differs.' }
    }
}

function ConvertTo-CutoverApiSettings([string]$Json) {
    $document=[Text.Json.Nodes.JsonNode]::Parse($Json)
    $root=$document['FileDatabase']['RootPath'].GetValue[string]()
    if ($root -notin @('C:\server\FileDatabase\file','C:\app-01\workspace\40_filebase')) { throw 'Unexpected current FileDatabase root.' }
    if (!$document['MasterData']['AutoSeedOnStartup'].GetValue[bool]()) { throw 'Expected startup seed is not enabled; review the effective configuration.' }
    $document['FileDatabase']['RootPath']=[Text.Json.Nodes.JsonValue]::Create('C:\app-01\workspace\40_filebase')
    return $document.ToJsonString([Text.Json.JsonSerializerOptions]@{WriteIndented=$true})
}

function Assert-CutoverAccessRules($Rules,[string[]]$IdentitySids) {
    $allowed=[Security.AccessControl.FileSystemRights]0
    foreach ($rule in $Rules) {
        # Reject explicit restrictions rather than guessing service group membership.
        if ($rule.AccessControlType -eq 'Deny') { throw 'A Filebase deny ACL requires review.' }
        if ($rule.IdentityReference.Value -in $IdentitySids -and !($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly)) { $allowed=$allowed -bor $rule.FileSystemRights }
    }
    $required=[Security.AccessControl.FileSystemRights]::Modify
    if (($allowed -band $required) -ne $required) { throw 'The API pool does not have verified Filebase read/write access.' }
}

function ConvertFrom-CutoverUtcTimestamp($Value) {
    # SQL datetime2 stores the Seeder's DateTime.UtcNow without a zone. ASP.NET
    # consequently returns no suffix; never interpret that value as APP local time.
    if ($Value -is [DateTimeOffset]) { return $Value.ToUniversalTime() }
    if ($Value -is [DateTime]) {
        if ($Value.Kind -eq [DateTimeKind]::Unspecified) { $Value=[DateTime]::SpecifyKind($Value,[DateTimeKind]::Utc) }
        return [DateTimeOffset]$Value.ToUniversalTime()
    }
    return [DateTimeOffset]::Parse([string]$Value,[Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::AssumeUniversal -bor [Globalization.DateTimeStyles]::AdjustToUniversal)
}

function New-CutoverPrivateDirectory([string]$Path) {
    Assert-CutoverLocalPath $Path
    New-Item -ItemType Directory -Path $Path -Force | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

Export-ModuleMember -Function Assert-CutoverLocalPath,Get-CutoverFileManifest,Assert-CutoverFileManifest,ConvertTo-CutoverApiSettings,Assert-CutoverAccessRules,ConvertFrom-CutoverUtcTimestamp,New-CutoverPrivateDirectory
