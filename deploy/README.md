# Deploy the ledger + public web viewer

The web viewer is an optional read-only gateway in front of the ledger. It
serves the dashboard and a buyer trace page at `http://<host>:8080/trace`, and
proxies `/api/trades` to the v2 `median20` token price. It never writes events
and does not need the admin token.

## Linux / macOS / WSL

```bash
./deploy/serve-linux.sh          # localhost only
./deploy/serve-linux.sh --lan    # also print the LAN URL for phones
```

The script creates `.venv`, installs `ledger/requirements.txt` and
`webapp/requirements.txt`, starts the ledger on `:8000` and the viewer on
`:8080`, and stops both on Ctrl-C. Override with `PORT`, `WEB_PORT` or
`LEDGER_DB`.

## Docker Compose

```bash
docker compose -f deploy/docker-compose.yml up --build
```

The ledger persists in the `ledger-data` volume; set `AGRICHAIN_DEV=0` and a
real `AGRICHAIN_ADMIN_TOKEN` before exposing it outside a trusted LAN. The
viewer is read-only, so it can stay unauthenticated like the ledger dashboard.

## Tests

```bash
python3 -m pytest webapp/tests -q
```
