@echo off
setlocal

set "TOOL_DIR=%~dp0management-db-migrate"
dotnet run --project "%TOOL_DIR%\ManagementDbMigrateTool.csproj" -- --config "%TOOL_DIR%\management-db-migrate.config.json" %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo ManagementDB migration completed successfully.
    echo ========================================
) else (
    echo ========================================
    echo ManagementDB migration failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

pause
exit /b %EXIT_CODE%
