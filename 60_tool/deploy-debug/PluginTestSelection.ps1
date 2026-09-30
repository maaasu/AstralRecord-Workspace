Set-StrictMode -Version Latest

function Resolve-PluginTestSelection {
    <#
    .SYNOPSIS
    Plugin のテスト指定を検証し、入口とビルドで共用する正規化済み設定を返す。
    .DESCRIPTION
    未指定時は通常更新を All、PluginOnly を Skip とする。Selected はクラス名・
    ワイルドカード・任意の #メソッド指定をカンマで連結して受け取る。不正な指定は例外にする。
    #>
    param(
        [string]$TestMode,
        [string]$Tests,
        [switch]$TestsSpecified,
        [switch]$PluginOnly,
        [switch]$NoPlugin
    )

    if ($NoPlugin -and ($TestMode -or $Tests -or $TestsSpecified)) {
        throw 'TestMode/Tests require a Plugin build.'
    }
    $defaultMode = if ($PluginOnly) { 'Skip' } else { 'All' }
    $mode = if (!$TestMode) { $defaultMode } else {
        switch ($TestMode) {
            'All' { 'All' }
            'Selected' { 'Selected' }
            'Skip' { 'Skip' }
            default { throw 'TestMode must be All, Selected, or Skip.' }
        }
    }
    $selector = ''
    if ($mode -eq 'Selected') {
        if ([string]::IsNullOrWhiteSpace($Tests)) {
            throw 'Selected requires -Tests with at least one test class or pattern.'
        }
        $patterns = @($Tests.Split(',') | ForEach-Object { $_.Trim() })
        foreach ($pattern in $patterns) {
            if ($pattern -cnotmatch '^[A-Za-z0-9_.$*/?]+(?:#[A-Za-z0-9_$*?]+(?:\+[A-Za-z0-9_$*?]+)*)?$') {
                throw 'Tests must contain comma-separated test class patterns, optionally followed by #method. Empty entries and other syntax are not supported.'
            }
        }
        $selector = $patterns -join ','
    }
    elseif ($Tests -or $TestsSpecified) {
        throw '-Tests can only be used with -TestMode Selected.'
    }

    $mavenArguments = @('clean', 'package')
    if ($mode -eq 'Skip') {
        $mavenArguments += @('-Dmaven.test.skip=true', '-DskipTests=true')
    }
    else {
        $mavenArguments += @('-Dmaven.test.skip=false', '-DskipTests=false')
        if ($mode -eq 'Selected') {
            $mavenArguments += @("-Dtest=$selector", '-Dsurefire.failIfNoSpecifiedTests=true')
        }
    }

    # 従来の既定条件だけは旧runのfingerprintを維持し、それ以外の条件を区別する。
    $fingerprintSuffix = if ($mode -eq $defaultMode) { '' } else { "|PluginTests=$mode|Tests=$selector" }
    return [pscustomobject]@{
        Mode = $mode
        Tests = $selector
        MavenArguments = $mavenArguments
        FingerprintSuffix = $fingerprintSuffix
    }
}

function Get-DevDeploymentFingerprint {
    <#
    .SYNOPSIS
    設定ファイル・更新対象・テスト条件から、再開とbackend検証に共通の識別値を返す。
    .DESCRIPTION
    ConfigPath の読み取りに失敗した場合は例外にする。設定や外部状態は変更しない。
    #>
    param([string]$ConfigPath, [string]$Mode, $TestSelection)

    $text = (Get-FileHash -LiteralPath $ConfigPath -Algorithm SHA256).Hash + '|' + $Mode + $TestSelection.FingerprintSuffix
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($text))).Replace('-', '').ToLowerInvariant()
    }
    finally { $algorithm.Dispose() }
}
