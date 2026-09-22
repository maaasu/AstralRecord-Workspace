@echo off
setlocal

rem Compatibility shortcut: same startup/migration workflow as 01.
call "%~dp001-deploy-debug.bat" -PluginOnly %*
exit /b %ERRORLEVEL%
