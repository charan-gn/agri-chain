"""AgriChain v3 ledger service.

The ledger is the only orderer. Clients sign independent v3 messages; the
``/v3`` routes in :mod:`v3_api` ingest them and publish ledger-signed receipts,
notices, prices and checkpoints. The v2 ``/events`` API and the per-actor chain
model were removed with T38.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse

from v3_api import install_v3

DEFAULT_DB = Path(__file__).resolve().parent / "ledger.db"


def http_port() -> int:
    configured = os.environ.get("AGRICHAIN_HTTP_PORT") or os.environ.get("PORT") or "8000"
    try:
        return int(configured)
    except ValueError:
        return 8000


def admin_token() -> str | None:
    configured = os.environ.get("AGRICHAIN_ADMIN_TOKEN")
    if configured:
        return configured
    if os.environ.get("AGRICHAIN_DEV") == "1":
        return "dev-admin-token"
    return None


def require_admin(request: Request) -> None:
    expected = admin_token()
    if expected is None:
        raise HTTPException(
            status_code=401,
            detail={
                "error": "admin_disabled",
                "detail": "actor registration is closed; set AGRICHAIN_ADMIN_TOKEN",
                "retry": False,
            },
        )
    if request.headers.get("x-admin-token") != expected:
        raise HTTPException(
            status_code=401,
            detail={"error": "unauthorized", "detail": "missing or invalid admin token", "retry": False},
        )


def create_app(
    db_path: str | None = None,
    node_key_path: str | None = None,
    v3_key_path: str | None = None,
) -> FastAPI:
    """Build the v3-only FastAPI application.

    ``node_key_path`` is accepted for compatibility with existing launchers but
    is ignored: v3 uses the ledger key managed by :func:`install_v3`.
    """
    del node_key_path
    app = FastAPI(title="AgriChain Ledger", version="3.0")

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    rate_hits: dict[str, list[float]] = {}

    @app.middleware("http")
    async def rate_limit(request: Request, call_next):
        try:
            limit = int(os.environ.get("AGRICHAIN_V3_RATE_LIMIT", "60"))
        except ValueError:
            limit = 60
        if limit > 0 and request.url.path.startswith("/v3/"):
            ip = request.client.host if request.client else "unknown"
            now = time.monotonic()
            hits = [hit for hit in rate_hits.get(ip, []) if now - hit < 60]
            if len(hits) >= limit:
                return JSONResponse(
                    status_code=429,
                    content={
                        "error": "rate_limited",
                        "detail": "too many requests",
                        "retry": True,
                    },
                )
            hits.append(now)
            rate_hits[ip] = hits
        return await call_next(request)

    @app.get("/", include_in_schema=False)
    def root() -> RedirectResponse:
        return RedirectResponse(url="/explorer")

    @app.get("/health", include_in_schema=False)
    def health() -> dict:
        return {"ok": True, "version": 3}

    database = db_path or os.environ.get("LEDGER_DB", str(DEFAULT_DB))
    install_v3(app, db_path=database, key_path=v3_key_path, require_admin=require_admin)
    return app


app = create_app()
