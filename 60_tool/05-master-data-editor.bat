@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "ERRORLEVEL="

set "TOOL_DIR=%~dp0skilltree-editor"
set "BUILD_SCRIPT=%~dp006-master-data-editor-build.bat"

pushd "%TOOL_DIR%"
if errorlevel 1 (
    echo ERROR: Master Data Editor directory could not be opened.
    pause
    exit /b 1
)

call "%BUILD_SCRIPT%" --resolve-dotnet
if errorlevel 1 (
    echo Master Data Editor was not started because the .NET 10 SDK is unavailable.
    echo.
    popd
    pause
    exit /b 1
)
set "PATH=%DOTNET_ROOT%;%PATH%"

call "%BUILD_SCRIPT%"
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if not "%BUILD_EXIT_CODE%"=="0" (
    echo Master Data Editor was not started because the frontend build failed.
    echo.
    popd
    pause
    exit /b %BUILD_EXIT_CODE%
)

"%ASTRALRECORD_DOTNET_EXE%" run --project "%TOOL_DIR%\src\SkillTreeEditor.Server\SkillTreeEditor.Server.csproj" -- %*
set "SERVER_EXIT_CODE=%ERRORLEVEL%"
popd
if not "%SERVER_EXIT_CODE%"=="0" (
    echo.
    echo Master Data Editor server stopped with exit code %SERVER_EXIT_CODE%.
    echo Check the message above for the cause. If port 5274 is already in use, close the existing editor first.
    echo.
    pause
)

exit /b %SERVER_EXIT_CODE%
