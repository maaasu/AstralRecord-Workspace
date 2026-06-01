@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
dotnet run --project "%SCRIPT_DIR%DbRebuildTool.csproj" -- %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo DB rebuild completed successfully.
    echo ========================================
) else (
    echo ========================================
    echo DB rebuild failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

pause

exit /b %EXIT_CODE%
