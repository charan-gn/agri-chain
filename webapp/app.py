"""Public AgriChain web app.

A small read-only gateway in front of the ledger. It keeps the browser away
from the ledger's CORS/host settings and serves the dashboard and the buyer
trace page.

    LEDGER_URL=http://127.0.0.1:8000 uvicorn app:app --host 0.0.0.0 --port 8080
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlparse

from fastapi import Body, FastAPI, Request
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


def batch_path(batch_id: str) -> str:
    return "/batches/" + quote(batch_id, safe="")


app = FastAPI(title="AgriChain Web", version="0.1")


@app.exception_handler(LedgerError)
async def ledger_error_handler(request: Request, exc: LedgerError) -> JSONResponse:
    code = exc.status if 400 <= exc.status < 600 else 502
    error = "ledger_unreachable" if code == 502 else "ledger_error"
    return JSONResponse(status_code=code, content={"error": error, "detail": exc.detail})


@app.get("/api/config")
def config() -> dict:
    parsed = urlparse(LEDGER_URL)
    return {"ledgerUrl": LEDGER_URL, "ledgerHost": parsed.netloc}


@app.get("/api/health")
def health() -> dict:
    return {"ok": True, "ledger": ledger_call("/health"), "ledgerUrl": LEDGER_URL}


@app.get("/api/batches")
def batches() -> Any:
    return ledger_call("/batches")


@app.get("/api/batches/{batch_id}")
def batch(batch_id: str) -> Any:
    return ledger_call(batch_path(batch_id))


@app.get("/api/prices")
def prices() -> Any:
    return ledger_call("/prices")


@app.post("/api/prices/refresh")
def refresh_prices(payload: Any = Body(default=None)) -> Any:
    return ledger_call("/prices/refresh", "POST", payload if isinstance(payload, dict) else {})


@app.get("/api/offers")
def offers(actorId: str | None = None, status: str | None = None) -> Any:
    query = []
    if actorId:
        query.append("actorId=" + quote(actorId, safe=""))
    if status:
        query.append("status=" + quote(status, safe=""))
    suffix = "?" + "&".join(query) if query else ""
    return ledger_call("/barter/offers" + suffix)


@app.get("/api/actors/{actor_id}")
def actor(actor_id: str) -> Any:
    return ledger_call("/actors/" + quote(actor_id, safe=""))


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/trace")
def trace() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/assets", StaticFiles(directory=STATIC_DIR), name="assets")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8080)
