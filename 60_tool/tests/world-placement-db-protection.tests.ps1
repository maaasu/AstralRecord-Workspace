param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('ar-placement-protection-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporaryRoot)
$cases = 0
try {
    foreach ($database in @('WorldPlacementDevDB', 'WorldPlacementDB', 'UnexpectedDatabase')) {
        foreach ($slot in @('SqlServer', 'MasterData', 'History')) {
            $connections = @{ SqlServer='Server=127.0.0.1,1;Database=AstralRecord;Integrated Security=true;Connect Timeout=1'; MasterData='Server=127.0.0.1,1;Database=MasterDataDB;Integrated Security=true;Connect Timeout=1'; History='Server=127.0.0.1,1;Database=HistoryDB;Integrated Security=true;Connect Timeout=1' }
            $connections[$slot] = "Server=127.0.0.1,1;Database=$database;Integrated Security=true;Connect Timeout=1"
            $path = Join-Path $temporaryRoot 'config.json'
            [IO.File]::WriteAllText($path, (@{ connectionStrings=$connections; seedMasterData=$false } | ConvertTo-Json -Depth 5))
            foreach ($tool in @(@{Directory='db-rebuild';Assembly='DbRebuildTool'}, @{Directory='db-reset-except-release-notes';Assembly='DbResetExceptReleaseNotesTool'})) {
                $output = & dotnet (Join-Path $RepoRoot "60_tool/$($tool.Directory)/bin/Debug/net10.0/$($tool.Assembly).dll") --config $path --yes 2>&1 | Out-String
                if ($LASTEXITCODE -eq 0 -or $output -notmatch 'connection must target database') { throw "Database guard failed for $($tool.Directory) / $slot / $database" }
                $cases++
            }
        }
    }
    Write-Output "PASS: $cases misconfigured rebuild/reset connections rejected before database access."
} finally {
    $resolved = [IO.Path]::GetFullPath($temporaryRoot)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\') -ne $tempParent.TrimEnd('\') -or [IO.Path]::GetFileName($resolved) -notmatch '^ar-placement-protection-[0-9a-f]{32}$') { throw 'Unsafe temporary cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
exit 0
