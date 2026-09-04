@echo off
setlocal

set "TOOL_DIR=%~dp0db-reset-except-release-notes"
dotnet run --project "%TOOL_DIR%\DbResetExceptReleaseNotesTool.csproj" -- %*
set "EXIT_CODE=%ERRORLEVEL%"

set "SKIP_PAUSE=0"
for %%A in (%*) do if /I "%%~A"=="--yes" set "SKIP_PAUSE=1"

echo.
if "%EXIT_CODE%"=="0" (
    echo ========================================
    echo DB reset completed successfully.
    echo Release note publication and notification data were preserved.
    echo ========================================
) else (
    echo ========================================
    echo DB reset failed.
    echo Exit code: %EXIT_CODE%
    echo Check the message above for the cause.
    echo ========================================
)

if "%SKIP_PAUSE%"=="0" pause

exit /b %EXIT_CODE%
