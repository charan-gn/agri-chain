#!/usr/bin/env bash
# Host the AgriChain ledger + public web app on Linux (or macOS/WSL).
# Usage: deploy/serve-linux.sh [--lan]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${VENV:-$ROOT/.venv}"
PYTHON="${PYTHON:-python3}"
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"
WEB_PORT="${WEB_PORT:-8080}"
LAN=0
[[ "${1:-}" == "--lan" ]] && LAN=1

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "python3 not found. Install it first, e.g.: sudo apt install python3 python3-venv" >&2
  exit 1
fi

if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Creating venv in $VENV"
  "$PYTHON" -m venv "$VENV" || {
    echo "venv failed. On Debian/Ubuntu: sudo apt install python3-venv" >&2
    exit 1
  }
fi

echo "Installing Python dependencies (first run only)…"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet -r "$ROOT/ledger/requirements.txt" -r "$ROOT/webapp/requirements.txt"

export LEDGER_DB="${LEDGER_DB:-$ROOT/ledger/ledger.db}"

cleanup() {
  trap - EXIT INT TERM
  [[ -n "${WEB_PID:-}" ]] && kill "$WEB_PID" 2>/dev/null || true
  [[ -n "${LEDGER_PID:-}" ]] && kill "$LEDGER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Starting ledger on :$PORT (db: $LEDGER_DB)"
(cd "$ROOT/ledger" && exec "$VENV/bin/uvicorn" app:app --host "$HOST" --port "$PORT") &
LEDGER_PID=$!

for _ in $(seq 1 60); do
  if "$VENV/bin/python" -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:$PORT/health', timeout=1)" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$LEDGER_PID" 2>/dev/null; then
    echo "Ledger exited during startup." >&2
    exit 1
  fi
  sleep 0.5
done

echo "Starting web app on :$WEB_PORT (ledger: http://127.0.0.1:$PORT)"
(cd "$ROOT/webapp" && LEDGER_URL="http://127.0.0.1:$PORT" exec "$VENV/bin/uvicorn" app:app --host "$HOST" --port "$WEB_PORT") &
WEB_PID=$!

echo
echo "Dashboard : http://127.0.0.1:$WEB_PORT"
echo "Buyer trace: http://127.0.0.1:$WEB_PORT/trace"
echo "Ledger API : http://127.0.0.1:$PORT/health"
if [[ "$LAN" == "1" ]]; then
  ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  [[ -n "$ip" ]] && echo "LAN        : http://$ip:$WEB_PORT  (phone browser on same Wi-Fi)"
fi
echo "USB phone  : deploy/usb.sh --install   (then app → 'Use USB link')"
echo "Press Ctrl+C to stop."

wait
