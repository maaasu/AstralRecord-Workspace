@echo off
setlocal

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0generate-status-types.ps1" %*
set "GENERATOR_EXIT_CODE=%ERRORLEVEL%"

if not "%GENERATOR_EXIT_CODE%"=="0" (
    echo.
    echo Status type generation failed with exit code %GENERATOR_EXIT_CODE%.
    echo Check the message above and fix the shared catalog or generator.
)

exit /b %GENERATOR_EXIT_CODE%
