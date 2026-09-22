@echo off
setlocal

rem Compatibility shortcut: stopped Dev, master data only, then startup/migration.
call "%~dp001-deploy-debug.bat" -MasterDataOnly %*
exit /b %ERRORLEVEL%
