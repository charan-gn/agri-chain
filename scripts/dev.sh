#!/usr/bin/env bash
# AgriChain device cheat sheet (T16). Requires adb + one phone in "device" state.
#
#   Install:            cd android && ./gradlew installDebug
#   Ledger over USB:    adb reverse tcp:8000 tcp:8000, then use http://127.0.0.1:8000
#   Logs:               adb logcat -c; adb logcat -s AgriChain:V
#   Permissions:        adb shell pm grant com.agrichain android.permission.SEND_SMS
#                       If SMS is blocked for a sideloaded APK: App info > menu >
#                       "Allow restricted settings", then grant again.
#   Drive the UI:       adb shell am start, input tap, input text,
#                       uiautomator dump + adb pull
#   Radio-only test:    human turns Wi-Fi and mobile data off (or:
#                       adb shell svc wifi disable; adb shell svc data disable)
#                       then adb reverse --remove-all; keep the SIM on.
#   Two-phone tests:    adb -s <serial> ... for each phone. SMS/Nearby need the
#                       human to send, move the box and approve connections.
#
# Usage: scripts/dev.sh [install|reverse|logs|grant|ui|devices|help]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-adb}"
PKG="com.agrichain"
PORT="${PORT:-8000}"
SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | awk '$2 == "device" { print $1; exit }')}"
ADB_CMD=("$ADB")
if [[ -n "$SERIAL" ]]; then
  ADB_CMD+=(-s "$SERIAL")
fi

usage() {
  sed -n '2,19p' "${BASH_SOURCE[0]}" | grep -v '^set ' | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Commands:
  install   Build and install the debug APK
  reverse   Map phone 127.0.0.1:8000 to this laptop (adb reverse)
  logs      Clear logcat and follow the single AgriChain tag
  grant     Grant every runtime permission the app declares
  ui        Dump the current UI hierarchy to ./window.xml
  devices   List attached adb devices
EOF
}

need_device() {
  if [[ -z "$SERIAL" ]]; then
    echo "No device in 'device' state. Run: adb devices" >&2
    exit 1
  fi
}

case "${1:-help}" in
  help|-h|--help)
    usage
    ;;
  devices)
    "$ADB" devices
    ;;
  install)
    need_device
    if [[ -z "${JAVA_HOME:-}" && -d "$HOME/.local/opt/jdk-17.0.21+9" ]]; then
      export JAVA_HOME="$HOME/.local/opt/jdk-17.0.21+9"
    fi
    ( cd "$ROOT/android" && ./gradlew installDebug )
    ;;
  reverse)
    need_device
    "${ADB_CMD[@]}" reverse "tcp:$PORT" "tcp:$PORT"
    echo "USB ledger: http://127.0.0.1:$PORT"
    ;;
  logs)
    need_device
    "${ADB_CMD[@]}" logcat -c
    exec "${ADB_CMD[@]}" logcat -s AgriChain:V
    ;;
  grant)
    need_device
    for permission in \
      SEND_SMS RECEIVE_SMS CAMERA \
      ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
      BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
      NEARBY_WIFI_DEVICES POST_NOTIFICATIONS; do
      "${ADB_CMD[@]}" shell pm grant "$PKG" "android.permission.$permission" \
        >/dev/null 2>&1 || true
    done
    "${ADB_CMD[@]}" shell dumpsys package "$PKG" \
      | grep -E 'android.permission.(SEND_SMS|RECEIVE_SMS|CAMERA|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|BLUETOOTH_SCAN|BLUETOOTH_ADVERTISE|BLUETOOTH_CONNECT|NEARBY_WIFI_DEVICES|POST_NOTIFICATIONS): granted=true' \
      || true
    ;;
  ui)
    need_device
    "${ADB_CMD[@]}" shell uiautomator dump /sdcard/window.xml >/dev/null
    "${ADB_CMD[@]}" pull /sdcard/window.xml ./window.xml >/dev/null
    echo "Wrote $(pwd)/window.xml"
    ;;
  *)
    usage
    exit 2
    ;;
esac
