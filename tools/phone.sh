#!/usr/bin/env bash
# Build, install and wire an Android phone connected over USB.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="com.agrichain"
PORT="${PORT:-8000}"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
SKIP_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --no-build) SKIP_BUILD=1 ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

command -v "$ADB" >/dev/null 2>&1 || {
  echo "adb not found. Install Android platform-tools first." >&2
  exit 1
}

device="$("$ADB" devices | awk '$2 == "device" { print $1; exit }')"
if [[ -z "$device" ]]; then
  echo "No USB device in 'device' state." >&2
  echo "Enable Developer options -> USB debugging, reconnect, and allow this laptop." >&2
  "$ADB" devices
  exit 1
fi
adb=("$ADB" -s "$device")

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  if [[ -z "${JAVA_HOME:-}" && -d "$HOME/.local/opt/jdk-17.0.21+9" ]]; then
    export JAVA_HOME="$HOME/.local/opt/jdk-17.0.21+9"
  fi
  ( cd "$ROOT/android" && ./gradlew :app:assembleDebug --console=plain -q )
fi
[[ -f "$APK" ]] || {
  echo "APK missing at $APK; run without --no-build." >&2
  exit 1
}

"${adb[@]}" install -r "$APK" >/dev/null
"${adb[@]}" reverse "tcp:$PORT" "tcp:$PORT"

for permission in \
  SEND_SMS RECEIVE_SMS CAMERA \
  ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
  BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
  NEARBY_WIFI_DEVICES POST_NOTIFICATIONS; do
  "${adb[@]}" shell pm grant "$PKG" "android.permission.$permission" >/dev/null 2>&1 || true
done

"${adb[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null

echo "Installed $PKG on $device"
echo "USB ledger: http://127.0.0.1:$PORT (adb reverse active)"
echo "Ledger discovery is automatic: the app probes the saved host, USB loopback,"
echo "the emulator host and UDP/LAN replies, then saves the reachable server."
echo "In the app: Register actors if this is a new phone."
echo "Dashboard:  http://localhost:$PORT/"
