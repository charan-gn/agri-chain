@echo off
rem Build/install the APK and add adb reverse ports for USB debugging.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0usb.ps1" %*
