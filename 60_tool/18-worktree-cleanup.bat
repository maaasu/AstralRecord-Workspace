@echo off
setlocal EnableExtensions DisableDelayedExpansion
chcp 65001 >nul
set "PYTHONIOENCODING=utf-8"
set "ERRORLEVEL="

for %%I in ("%~dp0..") do set "ASTRAL_CLEANUP_REPO=%%~fI"
set "ASTRAL_CLEANUP_MAIN_REPO="
for /f "tokens=1,*" %%A in ('git -C "%ASTRAL_CLEANUP_REPO%" -c "core.quotePath=false" worktree list --porcelain') do (
    if not defined ASTRAL_CLEANUP_MAIN_REPO if "%%A"=="worktree" set "ASTRAL_CLEANUP_MAIN_REPO=%%B"
)
if not defined ASTRAL_CLEANUP_MAIN_REPO set "ASTRAL_CLEANUP_MAIN_REPO=%ASTRAL_CLEANUP_REPO%"

for %%I in ("%ASTRAL_CLEANUP_MAIN_REPO%\..\AstralRecord-Worktrees\_logs") do set "ASTRAL_CLEANUP_LOG_DIR=%%~fI"
if not exist "%ASTRAL_CLEANUP_LOG_DIR%\." mkdir "%ASTRAL_CLEANUP_LOG_DIR%"
if not exist "%ASTRAL_CLEANUP_LOG_DIR%\." (
    echo ログ用ディレクトリを作成できませんでした。 1>&2
    exit /b 1
)

rem 呼び出し元の作業ツリーを保護できるようにカレントディレクトリを維持する。
python "%ASTRAL_CLEANUP_REPO%\.codex\skills\astralrecord-prune-codex-worktrees\scripts\prune_codex_worktrees.py" --execute --write-management %* >"%ASTRAL_CLEANUP_LOG_DIR%\worktree-cleanup-latest.log" 2>&1
exit /b %ERRORLEVEL%
