@echo off
setlocal

set "TOOL_DIR=%~dp0db-migrate"
dotnet run --project "%TOOL_DIR%\DbMigrateTool.csproj" -- --config "%TOOL_DIR%\history-db-migrate.config.json" %*
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
