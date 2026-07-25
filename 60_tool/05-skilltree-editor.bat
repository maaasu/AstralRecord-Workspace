@echo off
setlocal

set "TOOL_DIR=%~dp0skilltree-editor"
set "BUILD_SCRIPT=%~dp006-skilltree-editor-build.bat"

call "%BUILD_SCRIPT%"
set "BUILD_EXIT_CODE=%ERRORLEVEL%"
if not "%BUILD_EXIT_CODE%"=="0" (
    echo Skill Tree Editor was not started because the frontend build failed.
    exit /b %BUILD_EXIT_CODE%
)

dotnet run --project "%TOOL_DIR%\src\SkillTreeEditor.Server\SkillTreeEditor.Server.csproj" -- %*
exit /b %ERRORLEVEL%
