@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%scripts\build-and-deploy.ps1" %*
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
    echo ========================================
)

exit /b %EXIT_CODE%
