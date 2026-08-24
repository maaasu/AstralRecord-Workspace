@echo off
setlocal

set "SCRIPT_DIR=%~dp0deploy-debug"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\deploy-debug.ps1" -ReleaseManagementOnly %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo Release management deployment succeeded.
    echo ========================================
) else (
    echo ========================================
    echo Release management deployment failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

pause

exit /b %EXIT_CODE%
