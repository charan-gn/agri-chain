from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from crypto import (
    MARKET_BATCH,
    canonical_event,
    sha256_hex,
    validate_public_key,
    verify_signature,
)
from db import BatchNotFound, EventConflict, LedgerDb, PrevMismatch
from prices import router as prices_router
from tokens import (
    InsufficientBalance,
    assert_transfer_allowed,
    compute_balances,
    trades_for_crop,
    weighted_price,
)
from validation import ValidationError, validate_actor_registration, validate_event

DEFAULT_DB = Path(__file__).resolve().parent / "ledger.db"


class ApiError(Exception):
    def __init__(self, status: int, error: str, detail: str = "", **extra: Any) -> None:
        self.status = status
        self.payload = {"error": error, "detail": detail, **extra}
        super().__init__(detail)


def event_out(row: dict) -> dict:
    return {
        "eventId": row["event_id"],
        "batchId": row["batch_id"],
        "type": row["type"],
        "actorId": row["actor_id"],
        "ts": row["ts"],
        "lat": row["lat"],
        "lon": row["lon"],
        "data": json.loads(row["data_json"]),
        "prevHash": row["prev_hash"],
        "hash": row["hash"],
        "sig": row["sig"],
    }


def append_one(db: LedgerDb, raw: Any) -> dict:
    try:
        event = validate_event(raw)
    except ValidationError as exc:
        raise ApiError(400, "bad_event", str(exc))

    actor = db.get_actor(event["actorId"])
    if not actor:
        raise ApiError(422, "unknown_actor", "actor is not registered")

    canonical = canonical_event(event)
    if event["hash"] != sha256_hex(canonical):
        raise ApiError(400, "bad_hash", "hash does not match the canonical event")

    if not verify_signature(actor["pub_key"], event["sig"], canonical.encode("utf-8")):
        raise ApiError(400, "bad_signature", "signature does not verify for the actor")

    history = [event_out(row) for row in db.events_for_batch(event["batchId"])]
    already_stored = any(
        item["eventId"] == event["eventId"] and item["hash"] == event["hash"]
        for item in history
    )
    if not already_stored:
        try:
            assert_transfer_allowed(history, event)
        except InsufficientBalance as exc:
            raise ApiError(
                409,
                "insufficient_balance",
                "transfer exceeds the sender's balance",
                balance=exc.available,
            )

    data_json = json.dumps(
        event["data"],
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    try:
        seq, head = db.append_event(event, data_json, int(time.time() * 1000))
    except PrevMismatch as exc:
        raise ApiError(
            409,
            "prev_mismatch",
            "event does not extend the current batch head",
            head=exc.head,
        )
    except BatchNotFound:
        raise ApiError(404, "batch_not_found", "batch has no REGISTER event")
    except EventConflict as exc:
        raise ApiError(409, "event_conflict", str(exc))

    return {"ok": True, "batchId": event["batchId"], "head": head, "seq": seq}


def verify_events(db: LedgerDb, rows: list[dict]) -> dict:
    head = ""
    last_hash = rows[-1]["hash"] if rows else ""
    for row in rows:
        event = event_out(row)
        if event["prevHash"] != head:
            return {
                "valid": False,
                "events": len(rows),
                "head": last_hash,
                "brokenAt": event["eventId"],
                "reason": "prevHash does not match the preceding event",
            }
        canonical = canonical_event(event)
        if event["hash"] != sha256_hex(canonical):
            return {
                "valid": False,
                "events": len(rows),
                "head": last_hash,
                "brokenAt": event["eventId"],
                "reason": "hash does not match the canonical event",
            }
        actor = db.get_actor(event["actorId"])
        if not actor or not verify_signature(
            actor["pub_key"], event["sig"], canonical.encode("utf-8")
        ):
            return {
                "valid": False,
                "events": len(rows),
                "head": last_hash,
                "brokenAt": event["eventId"],
                "reason": "signature does not verify",
            }
        head = event["hash"]
    return {"valid": True, "events": len(rows), "head": head}


def build_summary(db: LedgerDb, rows: list[dict], include_events: bool) -> dict:
    events = [event_out(row) for row in rows]
    register = events[0]
    if register["type"] != "REGISTER":
        raise ApiError(404, "not_found", "batch not found")
    register_data = register["data"]

    balances = compute_balances(events)
    last_seq = {register["actorId"]: rows[0]["seq"]}
    for row, event in zip(rows[1:], events[1:]):
        if event["type"] == "TRANSFER":
            last_seq[event["actorId"]] = row["seq"]
            last_seq[event["data"]["toActorId"]] = row["seq"]

    owner = max(balances, key=lambda actor: (balances[actor], last_seq.get(actor, 0)))
    summary = {
        "batchId": register["batchId"],
        "crop": register_data["crop"],
        "grade": register_data["grade"],
        "initialKg": int(register_data["weightKg"]),
        "balances": balances,
        "ownerId": owner,
        "tamperCount": sum(1 for row in rows if row["type"] == "TAMPER"),
        "lastHash": rows[-1]["hash"],
        "verified": verify_events(db, rows)["valid"],
    }
    if include_events:
        summary["events"] = events
    return summary


def create_app(db_path: str | None = None) -> FastAPI:
    app = FastAPI(title="AgriChain Ledger", version="0.1")
    db = LedgerDb(db_path or os.environ.get("LEDGER_DB", str(DEFAULT_DB)))
    app.state.db = db

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status, content=exc.payload)

    @app.exception_handler(RequestValidationError)
    async def request_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content={"error": "bad_request", "detail": "Invalid request"},
        )

    @app.get("/health")
    def health() -> dict:
        return {"ok": True}

    @app.post("/actors", status_code=201)
    def register_actor(payload: Any = Body(...)) -> dict:
        try:
            actor = validate_actor_registration(payload)
        except ValidationError as exc:
            raise ApiError(400, "bad_actor", str(exc))
        try:
            validate_public_key(actor["pubKey"])
        except (ValueError, TypeError):
            raise ApiError(400, "bad_key", "pubKey must be a P-256 SPKI DER key")

        created, existing = db.add_actor(
            actor["actorId"],
            actor["name"],
            actor["role"],
            actor["pubKey"],
            int(time.time() * 1000),
        )
        if not created and existing["pub_key"] != actor["pubKey"]:
            raise ApiError(
                409,
                "actor_key_mismatch",
                "actorId is already registered with a different key",
            )
        return {"actorId": actor["actorId"]}

    @app.get("/actors/{actor_id}")
    def get_actor(actor_id: str) -> dict:
        actor = db.get_actor(actor_id)
        if not actor:
            raise ApiError(404, "not_found", "actor not found")
        return {
            "actorId": actor["actor_id"],
            "name": actor["name"],
            "role": actor["role"],
            "pubKey": actor["pub_key"],
        }

    @app.post("/events", status_code=201)
    def post_event(payload: Any = Body(...)) -> dict:
        return append_one(db, payload)

    @app.post("/events/bulk")
    def post_events(payload: Any = Body(...)) -> dict:
        if not isinstance(payload, dict) or not isinstance(payload.get("events"), list):
            raise ApiError(400, "bad_request", 'body must be {"events":[...]}')
        results = []
        for raw in payload["events"]:
            event_id = raw.get("eventId") if isinstance(raw, dict) else None
            try:
                result = append_one(db, raw)
                results.append(
                    {
                        "eventId": event_id,
                        "ok": True,
                        "status": 201,
                        "seq": result["seq"],
                        "head": result["head"],
                    }
                )
            except ApiError as exc:
                results.append(
                    {
                        "eventId": event_id,
                        "ok": False,
                        "status": exc.status,
                        "error": exc.payload["error"],
                        "detail": exc.payload.get("detail", ""),
                    }
                )
        return {"results": results}

    @app.get("/events")
    def list_events(batchId: str = Query(...)) -> dict:
        rows = db.events_for_batch(batchId)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        return {
            "batchId": batchId,
            "head": rows[-1]["hash"],
            "events": [event_out(row) for row in rows],
        }

    @app.get("/trades")
    def list_trades(crop: str = Query(...)) -> dict:
        batches = [
            [event_out(row) for row in db.events_for_batch(batch_id)]
            for batch_id in db.register_batches()
        ]
        trades = trades_for_crop(batches, crop)
        return {
            "crop": crop,
            "updatedAt": int(time.time() * 1000),
            "pricePerKgPaise": weighted_price(trades),
            "trades": trades,
        }

    @app.get("/batches/{batch_id}/verify")
    def verify_batch(batch_id: str) -> dict:
        if batch_id == MARKET_BATCH:
            raise ApiError(404, "not_found", "batch not found")
        rows = db.events_for_batch(batch_id)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        return verify_events(db, rows)

    @app.get("/batches/{batch_id}")
    def get_batch(batch_id: str) -> dict:
        if batch_id == MARKET_BATCH:
            raise ApiError(404, "not_found", "batch not found")
        rows = db.events_for_batch(batch_id)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        return build_summary(db, rows, include_events=True)

    @app.get("/batches")
    def list_batches(owner: str | None = None) -> dict:
        batches = []
        for batch_id in db.register_batches():
            rows = db.events_for_batch(batch_id)
            if not rows:
                continue
            summary = build_summary(db, rows, include_events=False)
            if owner is None or summary["balances"].get(owner, 0) > 0:
                batches.append(summary)
        return {"batches": batches}

    app.include_router(prices_router)
    return app


app = create_app()
