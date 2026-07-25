@echo off
setlocal

set "CLIENT_DIR=%~dp0skilltree-editor\src\SkillTreeEditor.Client"
set "NPM_COMMAND="

for /f "delims=" %%I in ('where npm 2^>nul') do if not defined NPM_COMMAND set "NPM_COMMAND=%%I"
if not defined NPM_COMMAND if exist "%ProgramFiles%\nodejs\npm.cmd" set "NPM_COMMAND=%ProgramFiles%\nodejs\npm.cmd"
if not defined NPM_COMMAND if exist "%LOCALAPPDATA%\Programs\nodejs\npm.cmd" set "NPM_COMMAND=%LOCALAPPDATA%\Programs\nodejs\npm.cmd"

if not defined NPM_COMMAND (
    echo ERROR: npm was not found. Install Node.js 24 LTS and open a new terminal.
    exit /b 1
)

for %%I in ("%NPM_COMMAND%") do set "NODEJS_DIR=%%~dpI"
set "PATH=%NODEJS_DIR%;%PATH%"

if not exist "%CLIENT_DIR%\package.json" (
    echo ERROR: Skill Tree Editor client was not found:
    echo %CLIENT_DIR%
    exit /b 1
)

pushd "%CLIENT_DIR%"
if errorlevel 1 exit /b 1

set "INSTALL_DEPENDENCIES="
if not exist "node_modules\.bin\tsc.cmd" set "INSTALL_DEPENDENCIES=1"
if not exist "node_modules\.bin\vite.cmd" set "INSTALL_DEPENDENCIES=1"

if defined INSTALL_DEPENDENCIES (
    call :install_dependencies
    if errorlevel 1 goto dependencies_failed
)

echo Building Skill Tree Editor frontend...
call "%NPM_COMMAND%" run build
if errorlevel 1 goto retry_build
goto build_succeeded

:retry_build
echo Initial frontend build failed. Reinstalling dependencies and retrying once...
call :install_dependencies
if errorlevel 1 goto dependencies_failed
call "%NPM_COMMAND%" run build
if errorlevel 1 goto build_failed

:build_succeeded
popd
echo Skill Tree Editor frontend build succeeded.
exit /b 0

:dependencies_failed
popd
echo ERROR: npm ci failed.
exit /b 1

:build_failed
popd
echo ERROR: Skill Tree Editor frontend build failed after reinstalling dependencies.
exit /b 1

:install_dependencies
if exist "node_modules\" (
    echo Removing incomplete Skill Tree Editor frontend dependencies...
    rmdir /s /q "node_modules"
    if exist "node_modules\" exit /b 1
)
echo Installing Skill Tree Editor frontend dependencies...
call "%NPM_COMMAND%" ci
exit /b %ERRORLEVEL%
