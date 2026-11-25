#!/usr/bin/env bash
# Run the AgriChain ledger and dashboard on this laptop.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8000}"
PYTHON="${PYTHON:-python3}"
VENV="$ROOT/.venv"

cd "$ROOT/ledger"

if "$PYTHON" -c 'import fastapi, uvicorn' >/dev/null 2>&1; then
  RUN=("$PYTHON" -m uvicorn)
else
  if [[ ! -x "$VENV/bin/python" ]]; then
    echo "Creating virtualenv at $VENV"
    "$PYTHON" -m venv "$VENV"
  fi
  "$VENV/bin/pip" install -q -r requirements.txt
  RUN=("$VENV/bin/python" -m uvicorn)
fi

lan="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit }}' || true)"
if [[ -z "$lan" ]]; then
  lan="$( (hostname -I 2>/dev/null || true) | awk '{print $1}' )"
fi
echo "AgriChain ledger"
echo "  local:     http://localhost:$PORT"
if [[ -n "$lan" ]]; then
  echo "  LAN:       http://$lan:$PORT  (phone on Wi-Fi)"
fi
echo "  dashboard: http://localhost:$PORT/"
echo "  USB phone: adb reverse tcp:$PORT tcp:$PORT, host http://127.0.0.1:$PORT"
echo

exec "${RUN[@]}" app:app --host 0.0.0.0 --port "$PORT" "$@"
