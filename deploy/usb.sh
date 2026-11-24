#!/usr/bin/env bash
# Connect a USB-debugging phone to the laptop ledger.
# Usage: deploy/usb.sh [--install]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"

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
"$ADB" reverse tcp:8000 tcp:8000
"$ADB" reverse tcp:8080 tcp:8080

if [[ "${1:-}" == "--install" ]]; then
  echo "Building and installing the debug APK…"
  (cd "$ROOT/android" && ./gradlew installDebug)
fi

"$ADB" shell am start -n com.agrichain/.MainActivity >/dev/null
echo
echo "USB link ready: phone 127.0.0.1:8000 → laptop ledger, 127.0.0.1:8080 → web app."
echo "In the app tap 'Use USB link', then open Farmer / Buyer / Box / Sync."
echo "Dashboard: http://127.0.0.1:8080   Ledger: http://127.0.0.1:8000/health"
"$ADB" reverse --list
