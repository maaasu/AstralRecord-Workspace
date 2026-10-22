#requires -Version 7.0
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../ApiFilebaseCutover.psm1') -Force
$root=Join-Path ([IO.Path]::GetTempPath()) ('astral-filebase-cutover-'+[Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $root 'source/99.work'),(Join-Path $root 'target/99.work') -Force | Out-Null
    foreach ($name in @('source','target')) {
        [IO.File]::WriteAllText((Join-Path $root "$name/config.yml"),'database: []')
        [IO.File]::WriteAllText((Join-Path $root "$name/item.yml"),'id: new')
        [IO.File]::WriteAllText((Join-Path $root "$name/99.work/draft.yml"),$name)
    }
    $expected=Get-CutoverFileManifest (Join-Path $root 'source')
    Assert-CutoverFileManifest $expected (Get-CutoverFileManifest (Join-Path $root 'target'))
    [IO.File]::WriteAllText((Join-Path $root 'target/item.yml'),'id: changed')
    $rejected=$false
    try { Assert-CutoverFileManifest $expected (Get-CutoverFileManifest (Join-Path $root 'target')) } catch { $rejected=$true }
    if (!$rejected) { throw 'A changed master file was accepted.' }
    [IO.File]::WriteAllText((Join-Path $root 'target/item.yml'),'id: new')
    [IO.File]::WriteAllText((Join-Path $root 'target/extra.yml'),'id: extra')
    $rejected=$false
    try { Assert-CutoverFileManifest $expected (Get-CutoverFileManifest (Join-Path $root 'target')) } catch { $rejected=$true }
    if (!$rejected) { throw 'An extra master file was accepted.' }
    if ($IsWindows) {
        $site=Join-Path $root 'acl-site'; $private=Join-Path $root 'acl-private'
        New-Item -ItemType Directory -Path $site,$private | Out-Null
        $currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User
        foreach ($directory in @($site,$private)) {
            $acl=[Security.AccessControl.DirectorySecurity]::new()
            $acl.SetAccessRuleProtection($true,$false)
            $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($currentSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
            if ($directory -ceq $site) {
                $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-32-545'),'ReadAndExecute','ContainerInherit,ObjectInherit','None','Allow'))
            }
            Set-Acl -LiteralPath $directory -AclObject $acl
        }
        $destination=Join-Path $site 'settings.json'; $staged=Join-Path $private 'staged.json'; $backup=Join-Path $private 'original.json'
        [IO.File]::WriteAllText($destination,'original')
        [IO.File]::WriteAllText($staged,'updated')
        $originalAcl=Get-Acl -LiteralPath $destination
        $access=[Security.AccessControl.AccessControlSections]::Access
        $originalSddl=$originalAcl.GetSecurityDescriptorSddlForm($access)
        [IO.File]::Replace($staged,$destination,$backup)
        if ((Get-Acl -LiteralPath $destination).GetSecurityDescriptorSddlForm($access) -ceq $originalSddl) { throw 'ACL fixture did not reproduce private staging inheritance.' }
        Restore-CutoverFileAcl $destination $originalAcl
        Restore-CutoverFileAcl $destination (Get-Acl -LiteralPath $backup)
        if ((Get-Acl -LiteralPath $destination).GetSecurityDescriptorSddlForm($access) -cne $originalSddl -or
            [IO.File]::ReadAllText($destination) -cne 'updated' -or [IO.File]::ReadAllText($backup) -cne 'original') { throw 'Original ACL or atomic replacement contents were not retained.' }
        $link=Join-Path $root 'target/link'
        New-Item -ItemType Junction -Path $link -Target (Join-Path $root 'source') | Out-Null
        try {
            $rejected=$false
            try { $null=Get-CutoverFileManifest (Join-Path $root 'target') } catch { $rejected=$true }
            if (!$rejected) { throw 'A Filebase junction was accepted.' }
        } finally { Remove-Item -LiteralPath $link -Force }
    }
    $original='{"FileDatabase":{"RootPath":"C:\\server\\FileDatabase\\file","other":17},"MasterData":{"AutoSeedOnStartup":true},"ApiKey":{"Key":"PRIVATE_SENTINEL"},"array":[true,null,{"value":"日本語"}]}'
    $updated=ConvertTo-CutoverApiSettings $original
    $before=[Text.Json.Nodes.JsonNode]::Parse($original)
    $after=[Text.Json.Nodes.JsonNode]::Parse($updated)
    if ($after['FileDatabase']['RootPath'].GetValue[string]() -cne 'C:\app-01\workspace\40_filebase') { throw 'New root was not applied.' }
    $before['FileDatabase']['RootPath']=[Text.Json.Nodes.JsonValue]::Create('C:\app-01\workspace\40_filebase')
    if (![Text.Json.Nodes.JsonNode]::DeepEquals($before,$after)) { throw 'A setting other than FileDatabase.RootPath changed.' }
    $rejected=$false
    try { $null=ConvertTo-CutoverApiSettings ($original.Replace('C:\\server\\FileDatabase\\file','D:\\unknown')) } catch { $rejected=$true }
    if (!$rejected) { throw 'An unexpected active root was accepted.' }
    $processModel=[pscustomobject]@{IdentityType='ApplicationPoolIdentity';ManualMembership=$false}
    $processModel | Add-Member ScriptMethod GetAttributeValue {
        param($name)
        if ($name -cne 'manualGroupMembership') { throw 'Unexpected IIS attribute name.' }
        return $this.ManualMembership
    }
    Assert-CutoverPoolIdentity $processModel
    foreach ($case in @(@{identity='ApplicationPoolIdentity';manual=$true},
        @{identity='LocalSystem';manual=$false},@{identity='ApplicationPoolIdentity';manual=$null})) {
        $processModel.IdentityType=$case.identity; $processModel.ManualMembership=$case.manual
        $rejected=$false
        try { Assert-CutoverPoolIdentity $processModel } catch { $rejected=$true }
        if (!$rejected) { throw 'An unverified application pool identity was accepted.' }
    }
    $readRule=[pscustomobject]@{IdentityReference=@{Value='pool'};FileSystemRights=[Security.AccessControl.FileSystemRights]::ReadAndExecute;AccessControlType='Allow';PropagationFlags=[Security.AccessControl.PropagationFlags]::None}
    $rejected=$false
    try { Assert-CutoverAccessRules @($readRule) @('pool') } catch { $rejected=$true }
    if (!$rejected) { throw 'Read-only Filebase access was accepted.' }
    $modifyRule=[pscustomobject]@{IdentityReference=@{Value='pool'};FileSystemRights=[Security.AccessControl.FileSystemRights]::Modify;AccessControlType='Allow';PropagationFlags=[Security.AccessControl.PropagationFlags]::None}
    Assert-CutoverAccessRules @($modifyRule) @('pool')
    $modifyRule.AccessControlType='Deny'
    $rejected=$false
    try { Assert-CutoverAccessRules @($readRule,$modifyRule) @('pool') } catch { $rejected=$true }
    if (!$rejected) { throw 'A deny ACL was accepted.' }
    $expectedUtc=[DateTimeOffset]::Parse('2026-10-20T12:00:02Z')
    foreach ($value in @('2026-10-20T12:00:02',[DateTime]::new(2026,10,20,12,0,2,[DateTimeKind]::Unspecified),'2026-10-20T21:00:02+09:00')) {
        if ((ConvertFrom-CutoverUtcTimestamp $value) -ne $expectedUtc) { throw 'UTC seed timestamp was interpreted as local time.' }
    }
    # Invoke-RestMethod emits a JSON array as one pipeline item. Execute the actual
    # cutover assignment/verification against that response shape, without HTTP/IIS.
    $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../Set-ApiFilebaseRoot.ps1'),[ref]$null,[ref]$null)
    $apiFunction=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-LocalApi'},$true)[0]
    . ([scriptblock]::Create($apiFunction.Extent.Text))
    function Invoke-RestMethod { param($Uri,$Headers,$TimeoutSec,$MaximumRedirection,[switch]$NoProxy) Write-Output -NoEnumerate $fixtureRuns }
    $headers=@{}
    $runsAssignment=$ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$runs'},$true)[0]
    $seedAssignment=$ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$seed'},$true)[0]
    $guard=$ast.FindAll({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Clauses[0].Item1.Extent.Text.StartsWith('$master.status')},$true)[0]
    $newRoot='C:\app-01\workspace\40_filebase';$startedAt=[DateTimeOffset]::UtcNow
    $master=[pscustomobject]@{status='ok';lastSeedRunStatus='SUCCEEDED';lastSeedRunId='current'}
    $currentSeed=[pscustomobject]@{seedRunId='current';sourceRootPath=$newRoot;triggerType='STARTUP';startedAt=$startedAt.UtcDateTime.ToString('s')}
    $fixtureRuns=@([pscustomobject]@{seedRunId='old';sourceRootPath='C:\server\FileDatabase\file';triggerType='STARTUP';startedAt='2026-10-20T00:00:00'},$currentSeed)
    . ([scriptblock]::Create($runsAssignment.Extent.Text))
    . ([scriptblock]::Create($seedAssignment.Extent.Text))
    . ([scriptblock]::Create($guard.Extent.Text))
    if ($seed.Count -ne 1 -or $seed[0].seedRunId -cne 'current') { throw 'The current startup seed was not selected from the JSON array.' }
    $currentSeed.sourceRootPath='C:\wrong'
    $rejected=$false
    try { . ([scriptblock]::Create($guard.Extent.Text)) } catch { $rejected=$true }
    if (!$rejected) { throw 'A startup seed from the wrong root was accepted.' }
    Write-Host 'PASS: file manifest detects drift, work drafts are excluded, only the intended setting changes, and unexpected roots are rejected.'
} finally {
    if ([IO.Path]::GetFullPath($root).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()),[StringComparison]::OrdinalIgnoreCase) -and [IO.Path]::GetFileName($root).StartsWith('astral-filebase-cutover-')) { Remove-Item -LiteralPath $root -Recurse -Force }
}
