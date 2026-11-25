#!/usr/bin/env bash
# Connect a USB-debugging phone to the laptop ledger.
# Usage: deploy/usb.sh [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PORT="${PORT:-8000}"
WEB_PORT="${WEB_PORT:-8080}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb"; do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      ADB="$candidate"
      break
    fi
  done
fi
if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools and retry." >&2
  exit 1
fi

"$ADB" start-server >/dev/null
echo "Waiting for a USB device…"
"$ADB" wait-for-device
"$ADB" reverse "tcp:$PORT" "tcp:$PORT"
"$ADB" reverse "tcp:$WEB_PORT" "tcp:$WEB_PORT"

if [[ "${1:-}" == "--install" ]]; then
  echo "Building and installing the debug APK…"
  (cd "$ROOT/android" && ./gradlew installDebug)
fi

for permission in \
  SEND_SMS RECEIVE_SMS CAMERA \
  ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
  BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
  NEARBY_WIFI_DEVICES POST_NOTIFICATIONS; do
  "$ADB" shell pm grant com.agrichain "android.permission.$permission" >/dev/null 2>&1 || true
done

"$ADB" shell am start -n com.agrichain/.MainActivity --es ledgerUrl "http://127.0.0.1:$PORT" >/dev/null
echo
echo "USB link ready: phone 127.0.0.1:$PORT → laptop ledger, 127.0.0.1:$WEB_PORT → web app."
echo "In the app tap Auto-detect ledger (or Ledger sync -> Use USB), then open Farmer / Buyer / Box / Sync."
echo "Dashboard: http://127.0.0.1:$WEB_PORT   Ledger: http://127.0.0.1:$PORT/health"
"$ADB" reverse --list
