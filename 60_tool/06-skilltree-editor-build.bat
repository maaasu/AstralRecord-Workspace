@echo off
setlocal

set "CLIENT_DIR=%~dp0skilltree-editor\src\SkillTreeEditor.Client"

where npm >nul 2>&1
if errorlevel 1 (
    echo ERROR: npm was not found. Install Node.js 24 LTS and open a new terminal.
    exit /b 1
)

if not exist "%CLIENT_DIR%\package.json" (
    echo ERROR: Skill Tree Editor client was not found:
    echo %CLIENT_DIR%
    exit /b 1
)

pushd "%CLIENT_DIR%"
if errorlevel 1 exit /b 1

if not exist "node_modules\" (
    echo Installing Skill Tree Editor frontend dependencies...
    call npm ci
    if errorlevel 1 (
        popd
        echo ERROR: npm ci failed.
        exit /b 1
    )
)

echo Building Skill Tree Editor frontend...
call npm run build
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Skill Tree Editor frontend build failed.
    exit /b %EXIT_CODE%
)

echo Skill Tree Editor frontend build succeeded.
exit /b 0
