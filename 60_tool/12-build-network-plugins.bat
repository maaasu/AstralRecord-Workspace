@echo off
setlocal

set "SCRIPT_DIR=%~dp0network-plugin-build"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%\build-network-plugins.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo Network plugin build succeeded.
    echo ========================================
) else (
    echo ========================================
    echo Network plugin build failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

exit /b %EXIT_CODE%
