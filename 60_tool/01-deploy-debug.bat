@echo off
setlocal

set "SCRIPT_DIR=%~dp0deploy-debug"
where pwsh >nul 2>nul
if errorlevel 1 (
    echo PowerShell 7 is required. Install it and add pwsh to PATH.
    pause
    exit /b 1
)
pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\dev-update.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo Deployment succeeded.
    echo ========================================
) else (
    echo ========================================
    echo Deployment failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

pause

exit /b %EXIT_CODE%
