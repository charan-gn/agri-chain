#!/usr/bin/env bash
# One command: start the ledger + dashboard, then install and wire a USB phone.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${PORT:-8000}"

"$ROOT/tools/ledger.sh" &
ledger_pid=$!

cleanup() {
  kill "$ledger_pid" 2>/dev/null || true
  wait "$ledger_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
done

if ! curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  echo "Ledger did not answer on port $PORT." >&2
  exit 1
fi

"$ROOT/tools/phone.sh" "$@"

echo
echo "Ledger running at http://localhost:$PORT/ (Ctrl-C stops it)"
wait "$ledger_pid"
