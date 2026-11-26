from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from crypto import (
    canonical_event,
    sha256_hex,
    stream_for,
    validate_public_key,
    verify_signature,
)
from anchor import load_or_create_key, signed_root
from db import BatchExists, BatchNotFound, EventConflict, LedgerDb, PrevMismatch
from discovery import start_discovery
from barter import (
    BarterError,
    apply_deltas,
    barter_deltas,
    derive_offers,
    offer_public,
    validate_barter_event,
)
from tokens import compute_balances, median20_price, price_summary, trades_for_crop
from validation import PriceRemoved, ValidationError, validate_actor_registration, validate_event

DEFAULT_DB = Path(__file__).resolve().parent / "ledger.db"
WEB_DIR = Path(__file__).resolve().parent / "web"
GPS_GAP_MS = 5 * 60 * 1000
RETRY_ERRORS = {"prev_mismatch", "unbound_box", "batch_unknown", "rate_limited"}


def http_port() -> int:
    configured = os.environ.get("AGRICHAIN_HTTP_PORT") or os.environ.get("PORT")
    if configured:
        return int(configured)
    args = sys.argv
    for index, arg in enumerate(args):
        if arg == "--port" and index + 1 < len(args):
            return int(args[index + 1])
        if arg.startswith("--port="):
            return int(arg.split("=", 1)[1])
    return 8000


class ApiError(Exception):
    def __init__(
        self,
        status: int,
        error: str,
        detail: str = "",
        retry: bool | None = None,
        **extra: Any,
    ) -> None:
        self.status = status
        if retry is None:
            retry = error in RETRY_ERRORS
        self.payload = {"error": error, "detail": detail, "retry": retry, **extra}
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
        "chainSeq": int(row.get("chain_seq") or 0),
        "receivedAt": int(row.get("received_at") or row.get("created_at") or 0),
    }


def relay_user_in(payload: dict) -> dict:
    """Validate a self-reported relay display name. Not an identity claim."""
    user_id = str(payload.get("userId") or "").strip()
    name = str(payload.get("name") or "").strip()
    try:
        ts = int(payload.get("ts", 0))
    except (TypeError, ValueError):
        ts = 0
    if not user_id or not name or ts <= 0 or len(user_id) > 120 or len(name) > 40:
        raise ApiError(400, "bad_relay_user", "invalid relay user")
    return {"userId": user_id, "name": name, "ts": ts}


def relay_message_in(payload: dict) -> dict:
    """Validate an observed chat envelope for the dashboard monitor feed."""
    envelope_id = str(payload.get("envelopeId") or "").strip()
    sender_id = str(payload.get("senderId") or "").strip()
    observer_id = str(payload.get("observerId") or "").strip()
    body = str(payload.get("body") or "")
    direction = str(payload.get("direction") or "").strip()
    try:
        ts = int(payload.get("ts", 0))
    except (TypeError, ValueError):
        ts = 0
    if (
        not envelope_id or not sender_id or not observer_id or not body or ts <= 0
        or direction not in {"in", "out"}
        or len(envelope_id) > 120 or len(sender_id) > 120
        or len(observer_id) > 120 or len(body) > 2000
    ):
        raise ApiError(400, "bad_relay_message", "invalid relay message")
    recipient_id = str(payload.get("recipientId") or "").strip() or None
    return {
        "envelopeId": envelope_id,
        "senderId": sender_id,
        "senderName": str(payload.get("senderName") or "")[:40],
        "recipientId": recipient_id[:120] if recipient_id else None,
        "recipientName": str(payload.get("recipientName") or "")[:40],
        "body": body[:2000],
        "ts": ts,
        "observerId": observer_id,
        "direction": direction,
        "link": str(payload.get("link") or "")[:20],
    }


def load_batches(db: LedgerDb, before_seq: int | None = None) -> dict[str, list[dict]]:
    batches: dict[str, list[dict]] = {}
    for batch_id in db.register_batches():
        rows = db.events_for_batch(batch_id)
        if before_seq is not None:
            rows = [row for row in rows if row["seq"] < before_seq]
        batches[batch_id] = [event_out(row) for row in rows]
    return batches


def latest_bind(rows: list[dict]) -> dict | None:
    latest = None
    for row in rows:
        if row["type"] != "BIND":
            continue
        data = json.loads(row["data_json"])
        latest = {
            "boxId": data.get("boxId"),
            "expiresAt": data.get("expiresAt"),
            "seq": row["seq"],
        }
    return latest


def bind_active(bind: dict | None, at_ts: int) -> bool:
    if not bind or not bind.get("boxId"):
        return False
    expires_at = bind.get("expiresAt")
    if expires_at is not None and expires_at <= at_ts:
        return False
    return True


def holder_id(rows: list[dict], balances: dict[str, int]) -> str | None:
    if not balances:
        return None
    last_seq: dict[str, int] = {}
    for row in rows:
        if row["type"] != "TRANSFER":
            continue
        data = json.loads(row["data_json"])
        last_seq[row["actor_id"]] = row["seq"]
        last_seq[data["toActorId"]] = row["seq"]
    return max(balances, key=lambda actor: (balances[actor], last_seq.get(actor, 0)))


def state_for(
    db: LedgerDb,
    batch_id: str,
    before_seq: int | None = None,
) -> tuple[dict[str, list[dict]], dict[str, dict], dict[str, int], list[dict]]:
    batches = load_batches(db, before_seq)
    rows = [
        row
        for row in db.events_for_batch(batch_id)
        if before_seq is None or row["seq"] < before_seq
    ]
    offers = derive_offers(batches)
    balances = apply_deltas(
        compute_balances(batches.get(batch_id, [])),
        barter_deltas(offers).get(batch_id),
    )
    return batches, offers, balances, rows


def check_permission(
    event: dict,
    actor_role: str,
    rows: list[dict],
    batches: dict[str, list[dict]],
    offers: dict[str, dict],
    balances: dict[str, int],
    at_ts: int,
) -> None:
    event_type = event["type"]
    actor_id = event["actorId"]
    holder = holder_id(rows, balances)

    if event_type == "REGISTER":
        if actor_role != "farmer":
            raise ApiError(403, "forbidden", "only a farmer can register a batch")
        if any(row["type"] == "REGISTER" for row in rows):
            raise ApiError(409, "batch_exists", "batch is already registered")
        return

    if not rows:
        raise ApiError(409, "batch_unknown", "batch has no REGISTER event")

    bind = latest_bind(rows)
    bound = bind_active(bind, at_ts)

    if event_type == "BIND":
        if actor_id != holder:
            raise ApiError(403, "forbidden", "only the current holder can bind a box")
    elif event_type == "TRANSFER":
        if actor_id != holder:
            raise ApiError(403, "forbidden", "only the current holder can transfer")
        available = balances.get(actor_id, 0)
        requested = int(event["data"]["tokens"])
        if requested > available:
            raise ApiError(
                409,
                "insufficient_balance",
                "transfer exceeds the sender's balance",
                balance=available,
            )
    elif event_type in {"GPS", "TAMPER"}:
        if actor_role != "box":
            raise ApiError(403, "forbidden", "only a box can post GPS or TAMPER")
        if not bound or bind["boxId"] != actor_id:
            raise ApiError(409, "unbound_box", "box is not bound to this batch")
    elif event_type == "DELIVER":
        if actor_id != holder and not (
            actor_role == "box" and bound and bind["boxId"] == actor_id
        ):
            raise ApiError(403, "forbidden", "only the holder or the bound box can deliver")
    elif event_type == "BARTER":
        if actor_id != holder:
            raise ApiError(403, "forbidden", "only the current holder can barter")
        validate_barter_event(event, batches, offers)


def verify_chains(db: LedgerDb, rows: list[dict]) -> list[dict]:
    groups: dict[tuple[str, str], list[dict]] = {}
    for row in rows:
        groups.setdefault((row["actor_id"], row["stream"]), []).append(row)

    chains = []
    for (actor_id, stream), chain_rows in groups.items():
        chain_rows.sort(key=lambda row: row["chain_seq"])
        head = ""
        valid = True
        broken_at = None
        reason = None
        for row in chain_rows:
            event = event_out(row)
            if event["prevHash"] != head:
                valid = False
                broken_at = event["eventId"]
                reason = "prevHash does not match the preceding event"
                break
            canonical = canonical_event(event)
            if event["hash"] != sha256_hex(canonical):
                valid = False
                broken_at = event["eventId"]
                reason = "hash does not match the canonical event"
                break
            actor = db.get_actor(event["actorId"])
            if not actor or not verify_signature(
                actor["pub_key"], event["sig"], canonical.encode("utf-8")
            ):
                valid = False
                broken_at = event["eventId"]
                reason = "signature does not verify"
                break
            head = event["hash"]
        chain = {
            "actorId": actor_id,
            "stream": stream,
            "head": chain_rows[-1]["hash"],
            "count": len(chain_rows),
            "valid": valid,
        }
        if broken_at is not None:
            chain["brokenAt"] = broken_at
            chain["reason"] = reason
        chains.append(chain)
    return chains


def verify_permissions(db: LedgerDb, batch_id: str, rows: list[dict]) -> dict:
    errors = []
    for row in rows:
        event = event_out(row)
        actor = db.get_actor(event["actorId"])
        if not actor:
            errors.append(
                {
                    "eventId": event["eventId"],
                    "error": "unknown_actor",
                    "detail": "actor is not registered",
                }
            )
            continue
        batches, offers, balances, prefix = state_for(db, batch_id, before_seq=row["seq"])
        try:
            check_permission(
                event,
                actor["role"],
                prefix,
                batches,
                offers,
                balances,
                event["ts"],
            )
        except ApiError as exc:
            errors.append(
                {
                    "eventId": event["eventId"],
                    "error": exc.payload["error"],
                    "detail": exc.payload.get("detail", ""),
                }
            )
        except BarterError as exc:
            errors.append(
                {
                    "eventId": event["eventId"],
                    "error": exc.code,
                    "detail": exc.detail,
                }
            )
    return {"valid": not errors, "errors": errors}


def verify_batch_events(db: LedgerDb, batch_id: str, rows: list[dict]) -> dict:
    chains = verify_chains(db, rows)
    permissions = verify_permissions(db, batch_id, rows)
    _, _, balances, _ = state_for(db, batch_id)
    return {
        "valid": all(chain["valid"] for chain in chains) and permissions["valid"],
        "eventCount": len(rows),
        "chains": chains,
        "permissions": permissions,
        "balances": balances,
    }


def build_summary(db: LedgerDb, rows: list[dict], include_events: bool) -> dict:
    events = [event_out(row) for row in rows]
    register = next((event for event in events if event["type"] == "REGISTER"), None)
    if register is None:
        raise ApiError(404, "not_found", "batch not found")
    register_data = register["data"]
    _, _, balances, _ = state_for(db, register["batchId"])
    owner = holder_id(rows, balances)

    gps = [event for event in events if event["type"] == "GPS"]
    gps.sort(key=lambda event: (event["ts"], event["chainSeq"]))
    heartbeat_gaps = sum(
        1
        for first, second in zip(gps, gps[1:])
        if second["ts"] - first["ts"] > GPS_GAP_MS
    )
    mock_count = sum(1 for event in gps if event["data"].get("mock") is True)
    verification = verify_batch_events(db, register["batchId"], rows)

    summary = {
        "batchId": register["batchId"],
        "crop": register_data["crop"],
        "grade": register_data["grade"],
        "initialKg": int(register_data["weightKg"]),
        "balances": balances,
        "ownerId": owner,
        "tamperCount": sum(1 for event in events if event["type"] == "TAMPER"),
        "eventCount": len(rows),
        "heartbeatGaps": heartbeat_gaps,
        "mockCount": mock_count,
        "chains": db.chains_for_batch(register["batchId"]),
        "lastHash": rows[-1]["hash"] if rows else "",
        "verified": verification["valid"],
    }
    if include_events:
        summary["events"] = events[-200:]
    return summary


def admin_token() -> str | None:
    configured = os.environ.get("AGRICHAIN_ADMIN_TOKEN")
    if configured:
        return configured
    if os.environ.get("AGRICHAIN_DEV") == "1":
        return "dev-admin-token"
    return None


def create_app(db_path: str | None = None, node_key_path: str | None = None) -> FastAPI:
    app = FastAPI(title="AgriChain Ledger", version="2.0")
    db = LedgerDb(db_path or os.environ.get("LEDGER_DB", str(DEFAULT_DB)))
    app.state.db = db

    if node_key_path is None:
        node_key_path = os.environ.get("AGRICHAIN_NODE_KEY")
    if node_key_path is None:
        key_dir = Path(db_path).parent if db_path else DEFAULT_DB.parent
        node_key_path = str(key_dir / "node_key.pem")
    app.state.node_key = load_or_create_key(node_key_path)

    rate_hits: dict[str, list[float]] = {}

    if db_path is None:
        @app.on_event("startup")
        def _start_discovery() -> None:
            start_discovery(http_port())

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
            content={"error": "bad_request", "detail": "Invalid request", "retry": False},
        )

    def check_rate_limit(request: Request) -> None:
        try:
            limit = int(os.environ.get("AGRICHAIN_RATE_LIMIT", "60"))
        except ValueError:
            limit = 60
        if limit <= 0:
            return
        ip = request.client.host if request.client else "unknown"
        now = time.monotonic()
        hits = [hit for hit in rate_hits.get(ip, []) if now - hit < 60]
        if len(hits) >= limit:
            raise ApiError(429, "rate_limited", "too many requests", retry=True)
        hits.append(now)
        rate_hits[ip] = hits

    def require_admin(request: Request) -> None:
        expected = admin_token()
        if expected is None:
            raise ApiError(
                401,
                "admin_disabled",
                "actor registration is closed; set AGRICHAIN_ADMIN_TOKEN",
            )
        if request.headers.get("x-admin-token") != expected:
            raise ApiError(401, "unauthorized", "missing or invalid admin token")

    def append_one(raw: Any) -> dict:
        now = int(time.time() * 1000)
        try:
            event = validate_event(raw, now)
        except PriceRemoved as exc:
            raise ApiError(400, "price_removed", str(exc))
        except ValidationError as exc:
            raise ApiError(400, "bad_event", str(exc))

        actor = db.get_actor(event["actorId"])
        if not actor:
            raise ApiError(422, "unknown_actor", "actor is not registered")

        canonical = canonical_event(event)
        if event["hash"] != sha256_hex(canonical):
            raise ApiError(400, "bad_hash", "hash does not match the canonical event")
        if not verify_signature(actor["pub_key"], event["sig"], canonical.encode("utf-8")):
            raise ApiError(400, "bad_sig", "signature does not verify for the actor")

        stream = stream_for(event["type"])
        existing = db.event_by_id(event["eventId"])
        if existing:
            if existing["hash"] != event["hash"]:
                raise ApiError(
                    409,
                    "event_conflict",
                    "eventId already exists with a different hash",
                )
            return {
                "ok": True,
                "batchId": event["batchId"],
                "head": existing["hash"],
                "seq": existing["seq"],
                "chain": {
                    "actorId": existing["actor_id"],
                    "stream": existing["stream"],
                    "head": existing["hash"],
                    "chainSeq": int(existing["chain_seq"]),
                },
                "receivedAt": int(existing["received_at"]),
            }

        head, chain_seq = db.chain_info(event["batchId"], event["actorId"], stream)
        if event["prevHash"] != head:
            raise ApiError(
                409,
                "prev_mismatch",
                "event does not extend the current chain head",
                chain={"actorId": event["actorId"], "stream": stream},
                head=head,
                chainSeq=chain_seq,
            )

        rows = db.events_for_batch(event["batchId"])
        has_register = any(row["type"] == "REGISTER" for row in rows)
        if event["type"] == "REGISTER":
            if has_register:
                raise ApiError(409, "batch_exists", "batch is already registered")
        elif not has_register:
            raise ApiError(409, "batch_unknown", "batch has no REGISTER event")

        if event["type"] == "BIND" and event["data"].get("boxId") is not None:
            box = db.get_actor(event["data"]["boxId"])
            if not box or box["role"] != "box":
                raise ApiError(400, "bad_event", "boxId must be a registered box actor")

        batches, offers, balances, prefix_rows = state_for(db, event["batchId"])
        try:
            check_permission(
                event,
                actor["role"],
                prefix_rows,
                batches,
                offers,
                balances,
                event["ts"],
            )
        except BarterError as exc:
            raise ApiError(exc.status, exc.code, exc.detail, **exc.extra)

        data_json = json.dumps(
            event["data"],
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        )
        try:
            seq, new_head, new_chain_seq, received_at = db.append_event(
                event, data_json, now, stream
            )
        except PrevMismatch as exc:
            raise ApiError(
                409,
                "prev_mismatch",
                "event does not extend the current chain head",
                chain={"actorId": exc.actor_id, "stream": exc.stream},
                head=exc.head,
                chainSeq=exc.chain_seq,
            )
        except BatchNotFound:
            raise ApiError(409, "batch_unknown", "batch has no REGISTER event")
        except BatchExists:
            raise ApiError(409, "batch_exists", "batch is already registered")
        except EventConflict as exc:
            raise ApiError(409, "event_conflict", str(exc))

        return {
            "ok": True,
            "batchId": event["batchId"],
            "head": new_head,
            "seq": seq,
            "chain": {
                "actorId": event["actorId"],
                "stream": stream,
                "head": new_head,
                "chainSeq": new_chain_seq,
            },
            "receivedAt": received_at,
        }

    @app.get("/health")
    def health() -> dict:
        return {"ok": True, "version": 2}

    @app.get("/ledger/root")
    def ledger_root() -> dict:
        return signed_root(
            db.event_hashes(),
            db.event_height(),
            app.state.node_key,
        )

    @app.post("/actors", status_code=201)
    def register_actor(request: Request, payload: Any = Body(...)) -> dict:
        check_rate_limit(request)
        require_admin(request)
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
    def post_event(request: Request, payload: Any = Body(...)) -> dict:
        check_rate_limit(request)
        return append_one(payload)

    @app.post("/events/bulk")
    def post_events(request: Request, payload: Any = Body(...)) -> dict:
        check_rate_limit(request)
        if not isinstance(payload, dict) or not isinstance(payload.get("events"), list):
            raise ApiError(400, "bad_request", 'body must be {"events":[...]}')
        results = []
        for raw in payload["events"]:
            event_id = raw.get("eventId") if isinstance(raw, dict) else None
            try:
                result = append_one(raw)
                results.append(
                    {
                        "eventId": event_id,
                        "ok": True,
                        "status": 201,
                        "seq": result["seq"],
                        "head": result["head"],
                        "chain": result["chain"],
                        "receivedAt": result["receivedAt"],
                        "retry": False,
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
                        "retry": exc.payload.get("retry", False),
                    }
                )
        return {"results": results}

    @app.get("/events/recent")
    def recent_events(limit: int = Query(default=20, ge=1, le=100)) -> dict:
        crops = {}
        for batch_id in db.register_batches():
            rows = db.events_for_batch(batch_id)
            register = next((row for row in rows if row["type"] == "REGISTER"), None)
            if register:
                crops[batch_id] = json.loads(register["data_json"]).get("crop")
        events = []
        for row in db.recent_events(limit):
            event = event_out(row)
            event["crop"] = crops.get(event["batchId"])
            events.append(event)
        return {"events": events, "updatedAt": int(time.time() * 1000)}

    @app.get("/events")
    def list_events(
        batchId: str = Query(...),
        actorId: str | None = None,
        stream: str | None = None,
        limit: int = Query(default=200, ge=1, le=200),
        before: int | None = None,
    ) -> dict:
        rows = db.events_for_batch(batchId)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        if actorId:
            rows = [row for row in rows if row["actor_id"] == actorId]
        if stream:
            rows = [row for row in rows if row["stream"] == stream]
        chain_filter = bool(actorId and stream)
        if before is not None:
            if chain_filter:
                rows = [row for row in rows if row["chain_seq"] < before]
            else:
                rows = [row for row in rows if row["seq"] < before]
        rows.sort(
            key=lambda row: row["chain_seq"] if chain_filter else row["seq"],
            reverse=True,
        )
        page = rows[:limit]
        next_before = None
        if len(rows) > limit:
            next_before = page[-1]["chain_seq"] if chain_filter else page[-1]["seq"]
        response = {
            "batchId": batchId,
            "head": rows[0]["hash"] if rows else "",
            "events": [event_out(row) for row in page],
        }
        if chain_filter:
            response["chain"] = {"actorId": actorId, "stream": stream}
        if next_before is not None:
            response["nextBefore"] = next_before
        return response

    @app.get("/trades")
    def list_trades(
        crop: str = Query(...),
        grade: str | None = Query(default=None),
    ) -> dict:
        if grade is not None and grade not in {"A", "B", "C"}:
            raise ApiError(400, "bad_grade", "grade must be A, B or C")
        batches = list(load_batches(db).values())
        trades = trades_for_crop(batches, crop)
        selected = [
            trade for trade in trades if grade is None or trade.get("grade") == grade
        ]
        summary = price_summary(trades)
        return {
            "crop": crop,
            "grade": grade,
            "method": "median20",
            "sampleSize": min(len(selected), 20),
            "pricePerKgPaise": median20_price(selected),
            "byGrade": summary["byGrade"],
            "updatedAt": int(time.time() * 1000),
            "trades": selected,
        }

    @app.get("/barter/offers")
    def list_barter_offers(
        actorId: str | None = None,
        status: str | None = None,
    ) -> dict:
        offers = list(derive_offers(load_batches(db)).values())
        if actorId:
            offers = [
                offer
                for offer in offers
                if actorId in (offer["actorId"], offer["withActorId"])
            ]
        if status:
            offers = [offer for offer in offers if offer["status"] == status]
        offers.sort(key=lambda offer: (offer["ts"], offer["offerId"]), reverse=True)
        return {"offers": [offer_public(offer) for offer in offers]}

    @app.get("/barter/offers/{offer_id}")
    def get_barter_offer(offer_id: str) -> dict:
        offer = derive_offers(load_batches(db)).get(offer_id)
        if offer is None:
            raise ApiError(404, "not_found", "offer not found")
        return offer_public(offer)

    @app.get("/batches/{batch_id}/verify")
    def verify_batch(batch_id: str) -> dict:
        rows = db.events_for_batch(batch_id)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        return verify_batch_events(db, batch_id, rows)

    @app.get("/batches/{batch_id}/route")
    def batch_route(
        batch_id: str,
        max_points: int = Query(default=200, alias="max", ge=1, le=1000),
    ) -> dict:
        rows = db.events_for_batch(batch_id)
        if not rows:
            raise ApiError(404, "not_found", "batch not found")
        points = []
        for row in rows:
            if row["type"] != "GPS":
                continue
            event = event_out(row)
            points.append(
                {
                    "ts": event["ts"],
                    "lat": event["lat"],
                    "lon": event["lon"],
                    "accuracyM": event["data"].get("accuracyM"),
                    "mock": event["data"].get("mock") is True,
                    "actorId": event["actorId"],
                    "chainSeq": event["chainSeq"],
                }
            )
        points.sort(key=lambda point: (point["ts"], point["chainSeq"]))
        truncated = len(points) > max_points
        if truncated:
            points = points[-max_points:]
        return {"batchId": batch_id, "points": points, "truncated": truncated}

    @app.get("/batches/{batch_id}")
    def get_batch(batch_id: str) -> dict:
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

    @app.get("/stats")
    def stats(limit: int = Query(default=20, ge=1, le=100)) -> dict:
        batches = []
        for batch_id in db.register_batches():
            rows = db.events_for_batch(batch_id)
            if not rows:
                continue
            batches.append(build_summary(db, rows, include_events=False))
        crops = {batch["batchId"]: batch["crop"] for batch in batches}
        recent = []
        for row in db.recent_events(limit):
            event = event_out(row)
            event["crop"] = crops.get(event["batchId"])
            recent.append(event)
        return {
            "actors": len(db.all_actors()),
            "batches": len(batches),
            "events": db.event_count(),
            "tamperEvents": sum(batch["tamperCount"] for batch in batches),
            "validChains": sum(1 for batch in batches if batch["verified"]),
            "recent": recent,
            "updatedAt": int(time.time() * 1000),
        }

    @app.post("/relay/users", status_code=201)
    def post_relay_users(body: dict = Body(...)) -> dict:
        raw = body.get("users")
        if not isinstance(raw, list) or len(raw) > 200:
            raise ApiError(400, "bad_relay_users", "expected up to 200 users")
        users = [relay_user_in(item) for item in raw if isinstance(item, dict)]
        return {"accepted": db.upsert_relay_users(users, int(time.time() * 1000))}

    @app.get("/relay/users")
    def get_relay_users() -> dict:
        return {"users": db.relay_users(), "updatedAt": int(time.time() * 1000)}

    @app.post("/relay/messages", status_code=201)
    def post_relay_messages(body: dict = Body(...)) -> dict:
        raw = body.get("messages")
        if not isinstance(raw, list) or len(raw) > 200:
            raise ApiError(400, "bad_relay_messages", "expected up to 200 messages")
        messages = [relay_message_in(item) for item in raw if isinstance(item, dict)]
        stored = db.insert_relay_messages(messages, int(time.time() * 1000))
        return {"accepted": stored, "received": len(messages)}

    @app.get("/relay/messages")
    def get_relay_messages(limit: int = Query(default=50, ge=1, le=200)) -> dict:
        return {
            "messages": db.relay_messages(limit),
            "users": db.relay_users(),
            "updatedAt": int(time.time() * 1000),
        }

    @app.get("/", include_in_schema=False)
    @app.get("/dashboard", include_in_schema=False)
    def dashboard() -> FileResponse:
        return FileResponse(WEB_DIR / "index.html")

    app.mount("/static", StaticFiles(directory=WEB_DIR), name="static")
    return app


app = create_app()
