"""Public read-only gateway for the AgriChain v3 ledger.

The browser talks only to this app; it proxies the ledger's ``/v3`` feeds so
the ledger can stay bound to localhost. The v2 batch/trace API was removed with
T38.

    LEDGER_URL=http://127.0.0.1:8000 uvicorn app:app --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlencode
from urllib.parse import urlparse

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

BASE_DIR = Path(__file__).resolve().parent
STATIC_DIR = BASE_DIR / "static"
LEDGER_URL = os.environ.get("LEDGER_URL", "http://127.0.0.1:8000").rstrip("/")
TIMEOUT = float(os.environ.get("LEDGER_TIMEOUT", "8"))


class LedgerError(Exception):
    def __init__(self, status: int, detail: str) -> None:
        self.status = status
        self.detail = detail
        super().__init__(detail)


def ledger_call(path: str, method: str = "GET", body: Any = None) -> Any:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        f"{LEDGER_URL}{path}",
        data=data,
        method=method,
        headers={"Accept": "application/json", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
            detail = payload.get("detail") or payload.get("error") or raw
        except json.JSONDecodeError:
            detail = raw or f"ledger returned {exc.code}"
        raise LedgerError(exc.code, str(detail)) from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise LedgerError(502, f"ledger unreachable at {LEDGER_URL}: {reason}") from exc


app = FastAPI(title="AgriChain Web", version="3.0")


@app.exception_handler(LedgerError)
async def ledger_error_handler(request: Request, exc: LedgerError) -> JSONResponse:
    code = exc.status if 400 <= exc.status < 600 else 502
    error = "ledger_unreachable" if code == 502 else "ledger_error"
    return JSONResponse(status_code=code, content={"error": error, "detail": exc.detail})


@app.get("/api/config")
def config() -> dict:
    parsed = urlparse(LEDGER_URL)
    return {"ledgerUrl": LEDGER_URL, "ledgerHost": parsed.netloc, "version": 3}


@app.get("/api/health")
def health() -> dict:
    return {"ok": True, "ledger": ledger_call("/v3/health"), "ledgerUrl": LEDGER_URL}


def _feed(name: str, **params: Any) -> Any:
    query = urlencode({key: value for key, value in params.items() if value is not None})
    suffix = f"?{query}" if query else ""
    return ledger_call(f"/v3/{name}{suffix}")


@app.get("/api/ledger")
def ledger() -> Any:
    return ledger_call("/v3/ledger")


@app.get("/api/stats")
def stats() -> Any:
    return ledger_call("/v3/stats")


@app.get("/api/checkpoint")
def checkpoint() -> Any:
    return ledger_call("/v3/checkpoint")


@app.get("/api/messages")
def messages(
    type: str | None = None,
    status: str | None = None,
    actor: str | None = None,
    afterSeq: int | None = None,
    limit: int | None = None,
) -> Any:
    return _feed("messages", type=type, status=status, actor=actor, afterSeq=afterSeq, limit=limit)


@app.get("/api/messages/{msg_id}")
def message(msg_id: str) -> Any:
    return ledger_call("/v3/messages/" + quote(msg_id, safe=""))


@app.get("/api/listings")
def listings(region: int | None = None, crop: int | None = None, afterSeq: int | None = None) -> Any:
    return _feed("listings", region=region, crop=crop, afterSeq=afterSeq)


@app.get("/api/orders")
def orders(
    region: int | None = None,
    crop: int | None = None,
    status: str | None = None,
    actor: str | None = None,
) -> Any:
    return _feed("orders", region=region, crop=crop, status=status, actor=actor)


@app.get("/api/trades")
def trades(region: int | None = None, crop: str | None = None, actor: str | None = None) -> Any:
    return _feed("trades", region=region, crop=crop, actor=actor)


@app.get("/api/prices")
def prices(region: int | None = None, sinceVersion: int | None = None) -> Any:
    return _feed("prices", region=region, sinceVersion=sinceVersion)


@app.get("/api/actors/{actor_id}")
def actor(actor_id: str) -> Any:
    return ledger_call("/v3/actors/" + quote(actor_id, safe=""))


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/explorer")
def explorer() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/assets", StaticFiles(directory=STATIC_DIR), name="assets")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
