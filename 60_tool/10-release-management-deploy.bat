@echo off
setlocal

set "SCRIPT_DIR=%~dp0deploy-debug"
set "CONFIG_ARGS=-ConfigPath "%SCRIPT_DIR%\deploy-debug.config.json""
if exist "%SCRIPT_DIR%\deploy-debug.local.json" set "CONFIG_ARGS=-ConfigPath "%SCRIPT_DIR%\deploy-debug.local.json""
for %%A in (%*) do for /F "tokens=1 delims=:" %%B in ("%%~A") do if /I "%%~B"=="-ConfigPath" set "CONFIG_ARGS="
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0invoke-powershell7.ps1" "%SCRIPT_DIR%\deploy-debug.ps1" -ReleaseManagementOnly %CONFIG_ARGS% %*
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
