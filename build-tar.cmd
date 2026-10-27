@echo off
rem Double-click: builds csorgchart_offline.tar next to this file (see build-tar.ps1).
rem Arguments are passed on, e.g.  build-tar.cmd -RunTests
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-tar.ps1" %*
set EXITCODE=%ERRORLEVEL%
echo.
pause
exit /b %EXITCODE%
