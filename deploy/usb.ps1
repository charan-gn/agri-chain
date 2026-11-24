# Connect a USB-debugging phone to the laptop ledger (Windows PowerShell).
# Usage: .\deploy\usb.ps1 [-Install]
param([switch]$Install)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

$adb = $null
$command = Get-Command adb -ErrorAction SilentlyContinue
if ($command) { $adb = $command.Source }
if (-not $adb) {
    $candidates = @(
        (Join-Path ${env:ANDROID_HOME} "platform-tools\adb.exe"),
        (Join-Path ${env:ANDROID_SDK_ROOT} "platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { $adb = $candidate; break }
    }
}
if (-not $adb) { throw "adb not found. Install Android platform-tools and retry." }

& $adb start-server | Out-Null
Write-Host "Waiting for a USB device..."
& $adb wait-for-device
& $adb reverse tcp:8000 tcp:8000
& $adb reverse tcp:8080 tcp:8080

if ($Install) {
    Write-Host "Building and installing the debug APK..."
    Push-Location (Join-Path $root "android")
    try { & .\gradlew.bat installDebug } finally { Pop-Location }
}

& $adb shell am start -n com.agrichain/.MainActivity | Out-Null
Write-Host ""
Write-Host "USB link ready: phone 127.0.0.1:8000 -> laptop ledger, 127.0.0.1:8080 -> web app."
Write-Host "In the app tap 'Use USB link', then open Farmer / Buyer / Box / Sync."
Write-Host "Dashboard: http://127.0.0.1:8080   Ledger: http://127.0.0.1:8000/health"
& $adb reverse --list
