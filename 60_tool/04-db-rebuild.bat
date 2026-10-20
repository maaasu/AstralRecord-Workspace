@echo off
setlocal

set "TOOL_DIR=%~dp0db-rebuild"
set "CONFIG_PATH=%TOOL_DIR%\db-rebuild.config.json"
if exist "%TOOL_DIR%\db-rebuild.local.json" set "CONFIG_PATH=%TOOL_DIR%\db-rebuild.local.json"
dotnet run --project "%TOOL_DIR%\DbRebuildTool.csproj" -- --config "%CONFIG_PATH%" %*
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
