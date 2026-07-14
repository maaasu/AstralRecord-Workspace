@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%master-data-reload.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo Master data reload completed.
    echo Next: run /masterdata reload in Minecraft.
    echo ========================================
) else (
    echo ========================================
    echo Master data reload failed. Exit code: %EXIT_CODE%
    echo ========================================
)

pause
exit /b %EXIT_CODE%
