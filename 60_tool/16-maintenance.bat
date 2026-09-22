@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0invoke-powershell7.ps1" "%~dp0maintenance\maintenance.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %EXIT_CODE%
