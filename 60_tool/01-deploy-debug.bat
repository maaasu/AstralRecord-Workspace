@echo off
setlocal

set "SCRIPT_DIR=%~dp0deploy-debug"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0invoke-powershell7.ps1" "%SCRIPT_DIR%\dev-update.ps1" %*
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
