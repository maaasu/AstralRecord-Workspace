@echo off
setlocal
set "NETHERNET_PWSH=E:\mc-01\ControlDeployment\Automation-20261030\Runtime\PowerShell\pwsh.exe"
if not exist "%NETHERNET_PWSH%" (
    echo Bundled PowerShell was not found: %NETHERNET_PWSH%
    pause
    exit /b 1
)
"%NETHERNET_PWSH%" -NoProfile -File "%~dp0Repair-ControlNetherNetFirewall.ps1" -Confirm:$false 1>"%~dp0result.json" 2>"%~dp0error.txt"
set "NETHERNET_EXIT=%ERRORLEVEL%"
type "%~dp0result.json"
type "%~dp0error.txt"
echo Exit code: %NETHERNET_EXIT%
pause
exit /b %NETHERNET_EXIT%
