@echo off
rem Start the ledger + public web app without changing the machine execution policy.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve.ps1" %*
