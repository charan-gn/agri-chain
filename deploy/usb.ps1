# Connect a USB-debugging phone to the laptop ledger (Windows PowerShell).
# Usage: .\deploy\usb.cmd [-Install] [-BuildOnly]   (or run usb.ps1 with -ExecutionPolicy Bypass)
param([switch]$Install, [switch]$BuildOnly)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$port = if ($env:PORT) { $env:PORT } else { "8000" }
$webPort = if ($env:WEB_PORT) { $env:WEB_PORT } else { "8080" }

if (-not $env:ANDROID_HOME -and (Test-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
}

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem @(
        (Join-Path $env:ProgramFiles "Eclipse Adoptium\jdk-17*"),
        (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium\jdk-17*"),
        (Join-Path $env:ProgramFiles "Android\Android Studio\jbr")
    ) -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}

if ($BuildOnly) {
    Push-Location (Join-Path $root "android")
    try { & .\gradlew.bat assembleDebug } finally { Pop-Location }
    Write-Host "APK: $(Join-Path $root 'android\app\build\outputs\apk\debug\app-debug.apk')"
    exit
}

$adb = $null
$command = Get-Command adb -ErrorAction SilentlyContinue
if ($command) { $adb = $command.Source }
if (-not $adb) {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { $adb = $candidate; break }
    }
}
if (-not $adb) { throw "adb not found. Install Android platform-tools and retry." }

& $adb start-server | Out-Null
Write-Host "Waiting for a USB device..."
& $adb wait-for-device
& $adb reverse "tcp:$port" "tcp:$port"
& $adb reverse "tcp:$webPort" "tcp:$webPort"

if ($Install) {
    Write-Host "Building and installing the debug APK..."
    Push-Location (Join-Path $root "android")
    try { & .\gradlew.bat installDebug } finally { Pop-Location }
}

$permissions = @(
    "SEND_SMS", "RECEIVE_SMS", "CAMERA",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
    "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS"
)
foreach ($permission in $permissions) {
    & $adb shell pm grant com.agrichain "android.permission.$permission" 2>$null | Out-Null
}

& $adb shell am start -n com.agrichain/.MainActivity --es ledgerUrl "http://127.0.0.1:$port" | Out-Null
Write-Host ""
Write-Host "USB link ready: phone 127.0.0.1:$port -> laptop ledger, 127.0.0.1:$webPort -> web app."
Write-Host "In the app tap Auto-detect ledger (or Ledger sync -> Use USB), then open Farmer / Buyer / Box / Sync."
Write-Host "Dashboard: http://127.0.0.1:$webPort   Ledger: http://127.0.0.1:$port/health"
& $adb reverse --list
