@echo off
setlocal
chcp 65001 >nul
if not exist "%~dp0Runtime\PowerShell\pwsh.exe" (
    echo PowerShell runtime is missing. Keep the complete update folder together.
    pause
    exit /b 1
)
"%~dp0Runtime\PowerShell\pwsh.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Deployment\Update-ControlHost.ps1" -PackageDirectory "%~dp0Package"
set "CONTROL_UPDATE_EXIT=%ERRORLEVEL%"
if "%CONTROL_UPDATE_EXIT%"=="0" (
    echo Control update completed.
) else (
    echo Control update failed. See the message above and the Logs folder.
)
pause
exit /b %CONTROL_UPDATE_EXIT%
