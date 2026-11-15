#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$migrationScript = Join-Path $PSScriptRoot '../Move-LocalEditorData.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('astral-editor-layout-' + [Guid]::NewGuid().ToString('N'))
function Assert-Test([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function New-Case([string]$Name) {
    $root = Join-Path $testRoot "$Name/60_tool"
    $old = Join-Path $root 'skilltree-editor'
    $new = Join-Path $root '05-master-data-editor'
    [IO.Directory]::CreateDirectory($old) | Out-Null
    [IO.Directory]::CreateDirectory($new) | Out-Null
    Copy-Item -LiteralPath $migrationScript -Destination (Join-Path $new 'Move-LocalEditorData.ps1')
    return @{ Old=$old; New=$new; Script=(Join-Path $new 'Move-LocalEditorData.ps1') }
}
function Write-Fixture([string]$Path, [string]$Text) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
try {
    $case = New-Case 'success'
    Write-Fixture (Join-Path $case.Old '.backups/日本語.yml') "name: '&b原稿'`r`n"
    Write-Fixture (Join-Path $case.Old 'src/SkillTreeEditor.Server/appsettings.local.json') '{"private":"test-only"}'
    Write-Fixture (Join-Path $case.New 'src/SkillTreeEditor.Server/Program.cs') '// tracked source'
    $originalHash = (Get-FileHash -LiteralPath (Join-Path $case.Old '.backups/日本語.yml')).Hash
    & $case.Script -WhatIf
    Assert-Test (Test-Path -LiteralPath (Join-Path $case.Old '.backups/日本語.yml')) 'WhatIf changed the source.'
    & $case.Script
    Assert-Test (!(Test-Path -LiteralPath $case.Old)) 'The empty old directory remains.'
    Assert-Test ((Get-FileHash -LiteralPath (Join-Path $case.New '.backups/日本語.yml')).Hash -eq $originalHash) 'Backup bytes changed.'
    Assert-Test ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $case.New 'src/SkillTreeEditor.Server/Program.cs')) -eq '// tracked source') 'Tracked source changed.'
    & $case.Script

    $case = New-Case 'collision'
    Write-Fixture (Join-Path $case.Old '.backups/keep.yml') 'keep'
    Write-Fixture (Join-Path $case.Old 'src/local.json') 'old'
    Write-Fixture (Join-Path $case.New 'src/local.json') 'new'
    $blocked = $false
    try { & $case.Script } catch { $blocked = $_.Exception.Message -like '*destination already exists*' }
    Assert-Test $blocked 'A colliding destination was not rejected.'
    Assert-Test (Test-Path -LiteralPath (Join-Path $case.Old '.backups/keep.yml')) 'Collision partially moved files.'

    $case = New-Case 'active'
    Write-Fixture (Join-Path $case.Old '.cache/tool-jobs/run-test/state.json') '{"status":"running"}'
    $blocked = $false
    try { & $case.Script } catch { $blocked = $_.Exception.Message -like '*Finish the editor tool job*' }
    Assert-Test $blocked 'An active worker was not rejected.'
    Assert-Test (Test-Path -LiteralPath $case.Old) 'An active job was moved.'
    Write-Host 'Editor data migration: WhatIf, preservation, collisions, idempotence and active-job checks passed.'
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^astral-editor-layout-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -Recurse -Force -LiteralPath $resolved }
}
