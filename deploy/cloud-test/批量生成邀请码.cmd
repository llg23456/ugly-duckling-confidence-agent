@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0generate-invites.ps1" %*
pause
