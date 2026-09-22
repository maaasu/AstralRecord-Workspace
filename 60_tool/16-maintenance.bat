@echo off
setlocal
where pwsh >nul 2>nul
if errorlevel 1 (
    echo PowerShell 7 is required. Install it and add pwsh to PATH.
    exit /b 1
)
pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0maintenance\maintenance.ps1" %*
exit /b %ERRORLEVEL%
