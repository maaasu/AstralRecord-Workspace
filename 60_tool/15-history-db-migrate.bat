@echo off
setlocal

set "TOOL_DIR=%~dp0db-migrate"
set "CONFIG_PATH=%TOOL_DIR%\history-db-migrate.config.json"
if exist "%TOOL_DIR%\history-db-migrate.local.json" set "CONFIG_PATH=%TOOL_DIR%\history-db-migrate.local.json"
dotnet run --project "%TOOL_DIR%\DbMigrateTool.csproj" -- --config "%CONFIG_PATH%" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo HistoryDB migration completed successfully.
    echo ========================================
) else (
    echo ========================================
    echo HistoryDB migration failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

pause

exit /b %EXIT_CODE%
