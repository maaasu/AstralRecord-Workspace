#requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceTools = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('ar local layout ' + [Guid]::NewGuid().ToString('N'))
$toolRoot = Join-Path $fixture '60_tool'

function Assert($Condition, $Message) {
    if (!$Condition) { throw $Message }
}

function Write-FixtureFile([string]$RelativePath, [string]$Contents) {
    $path = Join-Path $toolRoot $RelativePath
    [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
    [IO.File]::WriteAllText($path, $Contents)
}

function Assert-MigrationRefused([string]$Message) {
    $declined = $false
    try { & $migration } catch { $declined = $_.Exception.Message.Contains($Message) }
    Assert $declined "Expected migration failure: $Message"
}

foreach ($part in @('Move-LocalToolLayout.ps1', '16-maintenance/Distribution.ps1', '16-maintenance/Diagnostics.ps1', '16-maintenance/WorkflowRunRoot.ps1')) {
    Write-FixtureFile $part (Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $sourceTools $part))
}
$migration = Join-Path $toolRoot 'Move-LocalToolLayout.ps1'
$relativeRun = 'run-20261115T000000Z-' + [Guid]::NewGuid().ToString('N')
$config = @{
    devWorkflow = @{ runRoot = '' }
    historyDatabaseMigrations = @{
        projectPath = Join-Path $toolRoot 'db-migrate/DbMigrateTool.csproj'
        configPath = Join-Path $toolRoot 'db-migrate/history-db-migrate.local.json'
    }
    externalPath = Join-Path $fixture 'external/60_tool/db-migrate/history-db-migrate.local.json'
}
$originalConfig = $config | ConvertTo-Json -Depth 8 -Compress
Write-FixtureFile 'deploy-debug/deploy-debug.local.json' $originalConfig
Write-FixtureFile 'db-migrate/history-db-migrate.local.json' '{}'
Write-FixtureFile 'db-migrate/obj/legacy-assets.json' 'generated files'
Write-FixtureFile 'network-plugin-build/output/AstralRecordLobby.jar' 'lobby artifact'
Write-FixtureFile "deploy-debug/runs/$relativeRun/dev-deploy.log" 'historical log'
Write-FixtureFile "deploy-debug/runs/$relativeRun/control-gate-run.json" '{"adapterId":"01"}'
Write-FixtureFile 'deploy-debug/runs/active-run.json' (@{relativeRunDirectory=$relativeRun;status='Completed'} | ConvertTo-Json)

Assert-MigrationRefused 'Complete or release the existing workflow'
Write-FixtureFile "deploy-debug/runs/$relativeRun/control-gate-released.json" '{}'
$maintenance = @{
    workflow = @{runRoot=''}
    controlGate = @{bindings=@{paths=@(@{
        path=Join-Path $toolRoot 'network-plugin-build/output'
        accessRoot=$fixture
        relativePath='60_tool/network-plugin-build/output'
    })}}
}
Write-FixtureFile 'maintenance/maintenance.local.json' ($maintenance | ConvertTo-Json -Depth 8)

$binding = $maintenance.controlGate.bindings.paths[0]
$registeredOutput = Join-Path $toolRoot 'network-plugin-build/output'
$binding.accessRoot = $registeredOutput
$binding.path = Join-Path $registeredOutput 'artifacts'
$binding.relativePath = 'artifacts'
Write-FixtureFile 'maintenance/maintenance.local.json' ($maintenance | ConvertTo-Json -Depth 8)
Assert-MigrationRefused 'escape its Control accessRoot'
$binding.accessRoot = $fixture
$binding.path = $registeredOutput
$binding.relativePath = '60_tool/network-plugin-build/output'
Write-FixtureFile 'maintenance/maintenance.local.json' ($maintenance | ConvertTo-Json -Depth 8)

& $migration -WhatIf
Assert (Test-Path -LiteralPath (Join-Path $toolRoot 'deploy-debug/deploy-debug.local.json')) 'WhatIf moved the settings.'
Assert (!(Test-Path -LiteralPath (Join-Path $toolRoot '01-deploy-debug'))) 'WhatIf created a tool directory.'

Write-FixtureFile '12-build-network-plugins/output/existing.jar' 'unrelated output'
Assert-MigrationRefused 'Migration destination already exists'
Assert (Test-Path -LiteralPath (Join-Path $toolRoot "deploy-debug/runs/$relativeRun")) 'Collision must stop before moving older histories.'
$newOutput = Join-Path $toolRoot '12-build-network-plugins/output'
Remove-Item -LiteralPath (Join-Path $newOutput 'existing.jar')
[IO.Directory]::Delete($newOutput, $false)

& $migration
$updated = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $toolRoot '01-deploy-debug/deploy-debug.local.json') | ConvertFrom-Json
Assert ($updated.historyDatabaseMigrations.projectPath -ceq (Join-Path $toolRoot '13-db-migrate/DbMigrateTool.csproj')) 'Game/history shared runner was not updated.'
Assert ($updated.historyDatabaseMigrations.configPath -ceq (Join-Path $toolRoot '15-history-db-migrate/history-db-migrate.local.json')) 'History local config path did not follow 15.'
Assert ($updated.externalPath -ceq $config.externalPath) 'Migration changed an external checkout path.'
Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $toolRoot '01-deploy-debug/legacy-settings/deploy-debug.local.json')) -ceq $originalConfig) 'Original settings were not preserved.'
Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $toolRoot "01-deploy-debug/legacy-runs/$relativeRun/dev-deploy.log")) -ceq 'historical log') 'Run log was not preserved.'
$updatedMaintenance = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $toolRoot '16-maintenance/maintenance.local.json') | ConvertFrom-Json
Assert ($updatedMaintenance.controlGate.bindings.paths[0].relativePath -ceq '60_tool/12-build-network-plugins/output') 'Control source binding retained the legacy directory.'
Assert (Test-Path -LiteralPath (Join-Path $newOutput 'AstralRecordLobby.jar')) 'Network artifact was not moved.'
Assert (Test-Path -LiteralPath (Join-Path $toolRoot '13-db-migrate/legacy-build/obj/legacy-assets.json')) 'Legacy build was not archived.'
Assert (!(Test-Path -LiteralPath (Join-Path $toolRoot 'deploy-debug'))) 'Empty legacy directory was not removed.'
& $migration
Write-Host "PASS: local settings, Control bindings, legacy journal and build retention, dry run, collisions, and rerun. Evidence: $fixture"
