"""Mandi price cache and simulated feed.

The demo has no Agmarknet key, so refresh walks a seeded price table with a
small deterministic variation (at most +/-2.5%) and stores the result in
SQLite. Swap :func:`_next_price` for a real feed without touching the API.
"""

from __future__ import annotations

import hashlib
import sqlite3
import time
from typing import Any

from fastapi import APIRouter, Body, Query, Request
from fastapi.responses import JSONResponse

SOURCE = "demo-mandi"
MINUTE_MS = 60_000

# Paise per kg. Telangana markets keep the demo close to home.
SEED_PRICES: dict[tuple[str, str], int] = {
    ("wheat", "warangal"): 2450,
    ("wheat", "khammam"): 2380,
    ("wheat", "nizamabad"): 2510,
    ("rice", "warangal"): 2200,
    ("rice", "karimnagar"): 2280,
    ("maize", "khammam"): 1950,
    ("maize", "nizamabad"): 2010,
    ("chilli", "warangal"): 8500,
    ("chilli", "khammam"): 9100,
    ("cotton", "adilabad"): 7050,
    ("cotton", "warangal"): 7200,
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS price_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    crop TEXT NOT NULL,
    market TEXT NOT NULL,
    price_per_kg_paise INTEGER NOT NULL,
    source TEXT NOT NULL,
    ts INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_price_crop_market ON price_snapshots(crop, market, id);
"""

router = APIRouter()


def _normalize(value: str | None) -> str | None:
    if value is None:
        return None
    cleaned = value.strip().lower()
    return cleaned or None


def _connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=30, isolation_level=None)
    conn.row_factory = sqlite3.Row
    return conn


def _ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(SCHEMA)


def _seed_if_empty(conn: sqlite3.Connection, now_ms: int) -> None:
    count = conn.execute("SELECT COUNT(*) AS n FROM price_snapshots").fetchone()["n"]
    if count:
        return
    conn.execute("BEGIN IMMEDIATE")
    try:
        for (crop, market), price in SEED_PRICES.items():
            conn.execute(
                """
                INSERT INTO price_snapshots
                    (crop, market, price_per_kg_paise, source, ts)
                VALUES (?, ?, ?, ?, ?)
                """,
                (crop, market, price, SOURCE, now_ms),
            )
        conn.execute("COMMIT")
    except Exception:
        conn.execute("ROLLBACK")
        raise


def _next_price(crop: str, market: str, base: int, now_ms: int) -> int:
    bucket = now_ms // MINUTE_MS
    digest = hashlib.sha256(f"{crop}:{market}:{bucket}".encode("utf-8")).digest()
    span = max(10, base // 40)  # at most 2.5% either way
    offset = int.from_bytes(digest[:4], "big") % (span * 2 + 1) - span
    return max(100, base + offset)


def _query(conn: sqlite3.Connection, crop: str | None, market: str | None) -> dict:
    sql = """
        SELECT p.crop, p.market, p.price_per_kg_paise, p.source, p.ts
        FROM price_snapshots p
        JOIN (
            SELECT crop, market, MAX(id) AS id
            FROM price_snapshots
            GROUP BY crop, market
        ) latest ON latest.id = p.id
    """
    clauses: list[str] = []
    params: list[str] = []
    if crop:
        clauses.append("p.crop = ?")
        params.append(crop)
    if market:
        clauses.append("p.market = ?")
        params.append(market)
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY p.crop, p.market"

    rows = conn.execute(sql, params).fetchall()
    prices = [
        {
            "crop": row["crop"],
            "market": row["market"],
            "pricePerKgPaise": int(row["price_per_kg_paise"]),
            "source": row["source"],
            "ts": int(row["ts"]),
        }
        for row in rows
    ]
    updated_at = max((item["ts"] for item in prices), default=0)
    return {"updatedAt": updated_at, "prices": prices}


def list_prices(
    db_path: str,
    crop: str | None = None,
    market: str | None = None,
    now_ms: int | None = None,
) -> dict:
    crop = _normalize(crop)
    market = _normalize(market)
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)

    conn = _connect(db_path)
    try:
        _ensure_schema(conn)
        _seed_if_empty(conn, now_ms)
        return _query(conn, crop, market)
    finally:
        conn.close()


def refresh_prices(
    db_path: str,
    crop: str | None = None,
    market: str | None = None,
    now_ms: int | None = None,
) -> dict:
    crop = _normalize(crop)
    market = _normalize(market)
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)

    pairs = [
        (seed_crop, seed_market)
        for seed_crop, seed_market in SEED_PRICES
        if (crop is None or seed_crop == crop) and (market is None or seed_market == market)
    ]

    conn = _connect(db_path)
    try:
        _ensure_schema(conn)
        _seed_if_empty(conn, now_ms)
        if pairs:
            conn.execute("BEGIN IMMEDIATE")
            try:
                for pair_crop, pair_market in pairs:
                    latest = conn.execute(
                        """
                        SELECT price_per_kg_paise FROM price_snapshots
                        WHERE crop = ? AND market = ?
                        ORDER BY id DESC LIMIT 1
                        """,
                        (pair_crop, pair_market),
                    ).fetchone()
                    base = int(latest["price_per_kg_paise"]) if latest else SEED_PRICES[(pair_crop, pair_market)]
                    price = _next_price(pair_crop, pair_market, base, now_ms)
                    conn.execute(
                        """
                        INSERT INTO price_snapshots
                            (crop, market, price_per_kg_paise, source, ts)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        (pair_crop, pair_market, price, SOURCE, now_ms),
                    )
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
        return _query(conn, crop, market)
    finally:
        conn.close()


def _bad_request(detail: str) -> JSONResponse:
    return JSONResponse(status_code=400, content={"error": "bad_request", "detail": detail})


@router.get("/prices")
def get_prices(
    request: Request,
    crop: str | None = Query(default=None),
    market: str | None = Query(default=None),
) -> dict:
    return list_prices(request.app.state.db.path, crop=crop, market=market)


@router.post("/prices/refresh")
def refresh_market_prices(request: Request, payload: Any = Body(default=None)) -> Any:
    if payload is None:
        payload = {}
    if not isinstance(payload, dict):
        return _bad_request("body must be an object")

    crop = payload.get("crop")
    market = payload.get("market")
    for key, value in (("crop", crop), ("market", market)):
        if value is not None and not isinstance(value, str):
            return _bad_request(f"{key} must be a string")
    return refresh_prices(request.app.state.db.path, crop=crop, market=market)
