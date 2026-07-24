@echo off
setlocal
set "TOOL_DIR=%~dp0skilltree-editor"
dotnet run --project "%TOOL_DIR%\src\SkillTreeEditor.Server\SkillTreeEditor.Server.csproj" -- %*
exit /b %ERRORLEVEL%
