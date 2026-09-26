@echo off
setlocal

rem Compatibility shortcut: Dev master data only, then reload/startup and migration.
call "%~dp001-deploy-debug.bat" -MasterDataOnly %*
exit /b %ERRORLEVEL%
