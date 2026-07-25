@echo off
setlocal

set "TOOL_DIR=%~dp0skilltree-editor"
set "BUILD_SCRIPT=%~dp006-skilltree-editor-build.bat"

call "%BUILD_SCRIPT%"
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if not "%BUILD_EXIT_CODE%"=="0" (
    echo Skill Tree Editor was not started because the frontend build failed.
    echo.
    pause
    exit /b %BUILD_EXIT_CODE%
)

dotnet run --project "%TOOL_DIR%\src\SkillTreeEditor.Server\SkillTreeEditor.Server.csproj" -- %*
set "SERVER_EXIT_CODE=%ERRORLEVEL%"
if not "%SERVER_EXIT_CODE%"=="0" (
    echo.
    echo Skill Tree Editor server stopped with exit code %SERVER_EXIT_CODE%.
    echo Check the message above for the cause. If port 5274 is already in use, close the existing editor first.
    echo.
    pause
)

exit /b %SERVER_EXIT_CODE%
