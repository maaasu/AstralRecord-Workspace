#requires -Version 7.0
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$root=Join-Path ([IO.Path]::GetTempPath()) ('ar-plugin-test-selection-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
Start-Transcript -LiteralPath (Join-Path $root 'verification.log') | Out-Null
function Assert($Condition,$Message) { if (!$Condition) { throw $Message } }
$previousPath=$env:Path
$savedEnvironment=@{}
foreach ($name in @('AR_TEST_MAVEN_LOG','AR_TEST_MAVEN_EXIT','AR_TEST_JAR')) { $savedEnvironment[$name]=[Environment]::GetEnvironmentVariable($name) }
try {
    $scriptRoot=Split-Path -Parent $PSScriptRoot
    . (Join-Path $scriptRoot 'PluginTestSelection.ps1')
    $configPath=Join-Path $root 'deploy.json'
    $project=Join-Path $root 'project'
    $output=Join-Path $project 'dist'
    $destination=Join-Path $root 'server/plugins'
    $bin=Join-Path $root 'bin'
    New-Item -ItemType Directory -Path "$project/src/main/java",$output,$destination,$bin -Force | Out-Null
    Set-Content -LiteralPath "$project/src/main/java/Alpha.java" -Value 'class Alpha {}' -Encoding ASCII
    Set-Content -LiteralPath "$project/src/main/java/Beta.java" -Value 'class Beta {}' -Encoding ASCII
    $config=@{
        iis=@{enabled=$false};api=@{enabled=$false};web=@{enabled=$false};fileDatabase=@{enabled=$false}
        plugin=@{enabled=$true;projectPath=$project;buildOutputPath=$output;deployPath=$destination;
            artifactPattern='AstralRecord*.jar';deployFileName='AstralRecord.jar';cleanupPatterns=@('AstralRecord*.jar')}
    }
    $config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configPath -Encoding UTF8
    @'
@echo off
echo %*>>"%AR_TEST_MAVEN_LOG%"
if not "%AR_TEST_MAVEN_EXIT%"=="0" exit /b %AR_TEST_MAVEN_EXIT%
echo new jar>"%AR_TEST_JAR%"
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $bin 'mvn.cmd') -Encoding ASCII
    $env:Path="$bin;$previousPath"
    $env:AR_TEST_MAVEN_LOG=Join-Path $root 'maven-arguments.log'
    $env:AR_TEST_JAR=Join-Path $output 'AstralRecord-test.jar'
    $env:AR_TEST_MAVEN_EXIT='0'

    # 既存runとの互換性と、テスト条件変更時の再開拒否に使う識別値を検査する。
    $default=Resolve-PluginTestSelection
    $explicit=Resolve-PluginTestSelection -TestMode All
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try {
        $legacy=[BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes((Get-FileHash -LiteralPath $configPath).Hash+'|Full'))).Replace('-','').ToLowerInvariant()
    } finally { $algorithm.Dispose() }
    $allFingerprint=Get-DevDeploymentFingerprint $configPath 'Full' $default
    Assert ($allFingerprint -ceq $legacy) 'Default fingerprint must preserve existing runs.'
    Assert ((Get-DevDeploymentFingerprint $configPath 'Full' $explicit) -ceq $legacy) 'Explicit default must equal implicit default.'
    $skip=Resolve-PluginTestSelection -TestMode Skip
    Assert ((Get-DevDeploymentFingerprint $configPath 'Full' $skip) -cne $legacy) 'Skip must not reuse an All run.'
    $selected=Resolve-PluginTestSelection -TestMode selected -Tests 'AlphaTest, BetaTest'
    $normalized=Resolve-PluginTestSelection -TestMode Selected -Tests 'AlphaTest,BetaTest'
    $different=Resolve-PluginTestSelection -TestMode Selected -Tests 'GammaTest'
    Assert ((Get-DevDeploymentFingerprint $configPath 'Full' $selected) -ceq (Get-DevDeploymentFingerprint $configPath 'Full' $normalized)) 'Whitespace/case normalization must agree.'
    Assert ((Get-DevDeploymentFingerprint $configPath 'Full' $selected) -cne (Get-DevDeploymentFingerprint $configPath 'Full' $different)) 'Different test lists must not share a run.'
    Assert ((Resolve-PluginTestSelection -PluginOnly).FingerprintSuffix -ceq '') 'PluginOnly default must preserve existing runs.'
    Assert ((Resolve-PluginTestSelection -PluginOnly -TestMode All).FingerprintSuffix -cne '') 'PluginOnly All must not reuse a Skip run.'
    foreach ($mode in @('All','Selected','Skip')) {
        $rejected=$false
        try { $null=Resolve-PluginTestSelection -TestMode $mode -Tests '' -TestsSpecified } catch { $rejected=$true }
        Assert $rejected 'An explicitly empty Tests argument must be rejected.'
    }

    $backend=Join-Path $scriptRoot 'deploy-debug.ps1'
    foreach ($case in @(
        @{Name='default';Flags=@();Expected=@('-Dmaven.test.skip=false','-DskipTests=false')},
        @{Name='plugin-default';Flags=@('-PluginOnly');Expected=@('-Dmaven.test.skip=true','-DskipTests=true')},
        @{Name='all-override';Flags=@('-PluginOnly','-TestMode','All');Expected=@('-Dmaven.test.skip=false','-DskipTests=false')},
        @{Name='selected';Flags=@('-TestMode','Selected','-Tests','AlphaTest, Beta*');Expected=@('-Dtest=AlphaTest,Beta*','-Dsurefire.failIfNoSpecifiedTests=true','-DskipTests=false')},
        @{Name='skip';Flags=@('-TestMode','Skip');Expected=@('-Dmaven.test.skip=true','-DskipTests=true')}
    )) {
        New-Item -ItemType Directory -Path $output -Force | Out-Null
        Set-Content -LiteralPath "$destination/AstralRecord.jar" -Value 'old jar'
        Remove-Item -LiteralPath $env:AR_TEST_MAVEN_LOG -ErrorAction SilentlyContinue
        $run=Join-Path $root $case.Name
        New-Item -ItemType Directory -Path $run | Out-Null
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backend -ConfigPath $configPath @($case.Flags) *> "$run/backend.log"
        Assert ($LASTEXITCODE -eq 0) "Backend failed: $($case.Name). See $run/backend.log"
        $arguments=Get-Content -Raw -Encoding UTF8 -LiteralPath $env:AR_TEST_MAVEN_LOG
        Assert ($arguments -match '^clean package ') 'Every test mode must retain clean package.'
        foreach ($argument in $case.Expected) { Assert ($arguments.Contains($argument)) "Missing Maven option: $argument" }
        Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath "$destination/AstralRecord.jar").Trim() -eq 'new jar') 'Fresh artifact was not deployed.'
        Assert (!(Test-Path -LiteralPath $output)) 'Existing artifact cleanup changed.'
    }

    # Maven失敗時は、以前のJARが残っていても配置しない。
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    Set-Content -LiteralPath $env:AR_TEST_JAR -Value 'stale output'
    Set-Content -LiteralPath "$destination/AstralRecord.jar" -Value 'keep deployed jar'
    $env:AR_TEST_MAVEN_EXIT='17'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backend -ConfigPath $configPath -TestMode Selected -Tests MissingTest *> "$root/maven-failed.log"
    Assert ($LASTEXITCODE -ne 0) 'Maven/test failure must stop deployment.'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath "$destination/AstralRecord.jar").Trim() -eq 'keep deployed jar') 'Failed build deployed a stale artifact.'

    # 同じrunでもテスト条件を変えたbackend呼び出しは、Maven開始前に拒否する。
    $run=Join-Path $root 'mismatched-run'
    New-Item -ItemType Directory -Path $run | Out-Null
    @{status='Deploying';label='Dev';configurationFingerprint=$allFingerprint} | ConvertTo-Json | Set-Content -LiteralPath "$run/workflow-state.json" -Encoding UTF8
    Remove-Item -LiteralPath $env:AR_TEST_MAVEN_LOG
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backend -ConfigPath $configPath -TestMode Skip -WorkflowRunDirectory $run -WorkflowFingerprint $allFingerprint *> "$run/backend.log"
    Assert ($LASTEXITCODE -ne 0 -and !(Test-Path -LiteralPath $env:AR_TEST_MAVEN_LOG)) 'Mismatched test conditions reached Maven.'
    Assert ((Get-Content -Raw -Encoding UTF8 -LiteralPath "$run/backend.log") -match 'configuration changed') 'Backend failed for a different reason than fingerprint mismatch.'

    foreach ($flags in @(
        @('-TestMode','Selected'), @('-TestMode','Selected','-Tests','AlphaTest,'),
        @('-TestMode','Selected','-Tests','AlphaTest;whoami'), @('-Tests','AlphaTest'),
        @('-TestMode','All','-Tests','AlphaTest'), @('-MasterDataOnly','-TestMode','Skip'),
        @('-ReleaseManagementOnly','-TestMode','All'), @('-PreflightOnly','-TestMode','All')
    )) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backend -ConfigPath $configPath @flags *> "$root/invalid-selection.log"
        Assert ($LASTEXITCODE -ne 0 -and !(Test-Path -LiteralPath $env:AR_TEST_MAVEN_LOG)) 'Invalid direct backend selection reached Maven.'
    }
    $config.plugin.enabled=$false
    $config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $configPath -Encoding UTF8
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $backend -ConfigPath $configPath -TestMode All *> "$root/disabled-plugin.log"
    Assert ($LASTEXITCODE -ne 0 -and !(Test-Path -LiteralPath $env:AR_TEST_MAVEN_LOG)) 'Disabled Plugin accepted explicit tests.'
    Write-Host "PASS: real backend routing, clean builds, defaults/overrides, selected tests, failure-before-deployment, validation, resume fingerprints. Evidence: $root"
}
finally {
    $env:Path=$previousPath
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name]) }
    Stop-Transcript | Out-Null
}
