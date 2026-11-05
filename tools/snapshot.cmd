@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0snapshot.ps1" %*
