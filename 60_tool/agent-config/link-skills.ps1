# Claude Code 用の .claude/skills を、正本 .codex/skills への junction として用意する。
# 正本は .codex/skills のみ。junction は Git 管理しない（.gitignore 済み）。
# 管理者権限・開発者モードは不要。何度実行しても安全（冪等）。
$ErrorActionPreference = 'Stop'
$root = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
$source = Join-Path $root '.codex\skills'
$claudeDir = Join-Path $root '.claude'
$link = Join-Path $claudeDir 'skills'

if (-not (Test-Path -LiteralPath $source)) { Write-Error "正本が見つかりません: $source"; exit 1 }
New-Item -ItemType Directory -Force -Path $claudeDir | Out-Null

if (Test-Path -LiteralPath $link) {
    $item = Get-Item -LiteralPath $link -Force
    if ($item.LinkType -eq 'Junction' -and ($item.Target -replace '/', '\') -ieq $source) { Write-Output "OK: $link -> $source"; exit 0 }
    if ($item.LinkType -eq 'Junction') { (Get-Item -LiteralPath $link -Force).Delete() }   # 張り替え。中身は消えない
    else { Write-Warning "$link は junction ではない実ディレクトリです。二重管理を避けるため手動で確認してください。"; exit 1 }
}
New-Item -ItemType Junction -Path $link -Target $source | Out-Null
Write-Output "linked: $link -> $source"
