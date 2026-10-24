@echo off
setlocal DisableDelayedExpansion

if /i "%~1"=="--resolve-dotnet" goto resolve_dotnet

set "CLIENT_DIR=%~dp0skilltree-editor\src\SkillTreeEditor.Client"
set "NPM_COMMAND="
set "NODEJS_DIR="

if defined ASTRALRECORD_NODE_HOME call :try_nodejs "%ASTRALRECORD_NODE_HOME%"
if defined NODE_HOME call :try_nodejs "%NODE_HOME%"
for /f "delims=" %%I in ('where npm.cmd 2^>nul') do call :try_nodejs "%%~dpI"
if defined ProgramFiles call :try_nodejs "%ProgramFiles%\nodejs"
if defined LOCALAPPDATA call :try_nodejs "%LOCALAPPDATA%\Programs\nodejs"
if defined LOCALAPPDATA call :try_nodejs "%LOCALAPPDATA%\AstralRecord\tools\node"

if not defined NPM_COMMAND (
    echo ERROR: A complete Node.js and npm installation was not found.
    echo Set ASTRALRECORD_NODE_HOME or NODE_HOME to a Node.js 24 LTS directory,
    echo or extract Node.js into "%LOCALAPPDATA%\AstralRecord\tools\node".
    exit /b 1
)

set "PATH=%NODEJS_DIR%;%PATH%"
echo Using Node.js: "%NODEJS_DIR%\node.exe"
echo Using npm: "%NPM_COMMAND%"

if not exist "%CLIENT_DIR%\package.json" (
    echo ERROR: Master Data Editor client was not found:
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

echo Building Master Data Editor frontend...
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
echo Master Data Editor frontend build succeeded.
exit /b 0

:dependencies_failed
popd
echo ERROR: npm ci failed.
exit /b 1

:build_failed
popd
echo ERROR: Master Data Editor frontend build failed after reinstalling dependencies.
exit /b 1

:install_dependencies
if exist "node_modules\" (
    echo Removing incomplete Master Data Editor frontend dependencies...
    rmdir /s /q "node_modules"
    if exist "node_modules\" exit /b 1
)
echo Installing Master Data Editor frontend dependencies...
call "%NPM_COMMAND%" ci
exit /b %ERRORLEVEL%

:try_nodejs
if defined NPM_COMMAND exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1\node.exe" exit /b 0
if not exist "%~1\npm.cmd" exit /b 0
if not exist "%~1\node_modules\npm\bin\npm-cli.js" exit /b 0
for %%I in ("%~1\.") do set "NODEJS_DIR=%%~fI"
set "NPM_COMMAND=%NODEJS_DIR%\npm.cmd"
exit /b 0

:resolve_dotnet
set "EDITOR_DOTNET_EXE="
set "EDITOR_DOTNET_DIR="
pushd "%~dp0skilltree-editor"
if errorlevel 1 (
    echo ERROR: Master Data Editor directory could not be opened for SDK resolution.
    exit /b 1
)
if defined ASTRALRECORD_DOTNET_HOME call :try_dotnet "%ASTRALRECORD_DOTNET_HOME%"
if defined DOTNET_ROOT call :try_dotnet "%DOTNET_ROOT%"
for /f "delims=" %%I in ('where dotnet.exe 2^>nul') do call :try_dotnet "%%~dpI"
if defined ProgramFiles call :try_dotnet "%ProgramFiles%\dotnet"
if defined ProgramFiles(x86) call :try_dotnet "%ProgramFiles(x86)%\dotnet"
if defined USERPROFILE call :try_dotnet "%USERPROFILE%\.dotnet"
if defined LOCALAPPDATA call :try_dotnet "%LOCALAPPDATA%\Programs\dotnet"
if defined LOCALAPPDATA call :try_dotnet "%LOCALAPPDATA%\AstralRecord\tools\dotnet"
popd
if not defined EDITOR_DOTNET_EXE (
    echo ERROR: No .NET SDK compatible with skilltree-editor\global.json was found.
    echo A runtime-only installation or an older .NET 10 SDK cannot build the editor.
    echo Set ASTRALRECORD_DOTNET_HOME or DOTNET_ROOT to a directory containing dotnet.exe and a compatible SDK,
    echo or extract the SDK into "%LOCALAPPDATA%\AstralRecord\tools\dotnet".
    echo Verify dotnet --version from the skilltree-editor directory.
    exit /b 1
)
echo Using .NET SDK host: "%EDITOR_DOTNET_EXE%"
endlocal & set "ASTRALRECORD_DOTNET_EXE=%EDITOR_DOTNET_EXE%" & set "DOTNET_ROOT=%EDITOR_DOTNET_DIR%" & exit /b 0

:try_dotnet
if defined EDITOR_DOTNET_EXE exit /b 0
if "%~1"=="" exit /b 0
if not exist "%~1\dotnet.exe" exit /b 0
"%~1\dotnet.exe" --version >nul 2>&1
if not "%ERRORLEVEL%"=="0" exit /b 0
"%~1\dotnet.exe" --version 2>nul | "%SystemRoot%\System32\findstr.exe" /r /b /c:"10\." >nul
if errorlevel 1 exit /b 0
for %%I in ("%~1\.") do set "EDITOR_DOTNET_DIR=%%~fI"
set "EDITOR_DOTNET_EXE=%EDITOR_DOTNET_DIR%\dotnet.exe"
exit /b 0
