@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0export-feedback.ps1" %*
pause
