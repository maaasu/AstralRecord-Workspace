@echo off
setlocal

set "REPOSITORY_ROOT=%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%REPOSITORY_ROOT%\generate-tag-types.ps1" %*
set "GENERATOR_EXIT_CODE=%ERRORLEVEL%"

if not "%GENERATOR_EXIT_CODE%"=="0" (
    echo.
    echo Master tag generation failed with exit code %GENERATOR_EXIT_CODE%.
    echo Check the message above and fix the shared catalog, tag usage, or generator.
)

exit /b %GENERATOR_EXIT_CODE%
