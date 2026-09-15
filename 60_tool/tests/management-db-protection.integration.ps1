param([string]$RepoRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('ar-management-protection-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporaryRoot)
try {
    foreach ($database in @('ManagementDB', 'WebSiteDB')) {
        foreach ($slot in @('SqlServer', 'MasterData', 'History')) {
            $connections = @{ SqlServer='Server=127.0.0.1,1;Database=AstralRecord;Integrated Security=true;Connect Timeout=1'; MasterData='Server=127.0.0.1,1;Database=MasterDataDB;Integrated Security=true;Connect Timeout=1'; History='Server=127.0.0.1,1;Database=HistoryDB;Integrated Security=true;Connect Timeout=1' }
            $connections[$slot] = "Server=127.0.0.1,1;Database=$database;Integrated Security=true;Connect Timeout=1"
            $path = Join-Path $temporaryRoot 'rebuild.json'
            [IO.File]::WriteAllText($path, (@{ connectionStrings=$connections; seedMasterData=$false } | ConvertTo-Json -Depth 5))
            $output = & dotnet (Join-Path $RepoRoot '60_tool/db-rebuild/bin/Debug/net10.0/DbRebuildTool.dll') --config $path --yes 2>&1 | Out-String
            if ($LASTEXITCODE -eq 0 -or $output -notmatch 'Persistent management databases must not be rebuilt') { throw "Rebuild guard failed for $slot / $database" }
        }
        $config = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $RepoRoot '60_tool/db-migrate/db-migrate.config.json') | ConvertFrom-Json
        $config.connectionStrings.sqlServer = "Server=127.0.0.1,1;Database=$database;Integrated Security=true;Connect Timeout=1"
        $config.migrationsRootPath = Join-Path $RepoRoot '00_docs/40_Database設計書/table-definitions/AstralRecord/migrations'
        $path = Join-Path $temporaryRoot 'migrate.json'
        [IO.File]::WriteAllText($path, ($config | ConvertTo-Json -Depth 20))
        $output = & dotnet (Join-Path $RepoRoot '60_tool/db-migrate/bin/Debug/net10.0/DbMigrateTool.dll') --config $path 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0 -or $output -notmatch 'Game migrations must not target persistent management databases') { throw "Migration guard failed for $database" }
    }
    Write-Output 'PASS: rebuild rejects all three connection slots; game migration rejects persistent DBs before connecting (8 cases).'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporaryRoot)
    $tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd('\') -ne $tempParent.TrimEnd('\') -or [IO.Path]::GetFileName($resolved) -notmatch '^ar-management-protection-[0-9a-f]{32}$') { throw 'Unsafe temporary cleanup target.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
