@echo off
setlocal

set "SCRIPT_DIR=%~dp0astralarchitect-deploy"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\astralarchitect-deploy.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo AstralArchitect build/deploy succeeded.
    echo ========================================
) else (
    echo ========================================
    echo AstralArchitect build/deploy failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

exit /b %EXIT_CODE%
