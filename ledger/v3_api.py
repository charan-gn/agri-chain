"""FastAPI routes for the v3 ledger.

The routes live beside the v2 `/events` API.  `install_v3` is called from
`create_app` so the demo server keeps one process, one database file and one
admin token.
"""

from __future__ import annotations

import base64
import binascii
import html
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from fastapi import Body, FastAPI, HTTPException, Query, Request
from fastapi.responses import HTMLResponse

import v3_codec as v3c
from v3_ledger import ALLOWED_VIA, V3Ledger


def _bad(detail: str, status: int = 400) -> HTTPException:
    return HTTPException(
        status_code=status,
        detail={"error": "bad_request", "detail": detail, "retry": False},
    )


def _parse_status(value: str | None) -> int | None:
    if value is None or value == "" or value.lower() in {"all", "*"}:
        return None
    text = value.strip().lower()
    if text.isdigit():
        return int(text)
    for code, name in v3c.STATUS_NAMES.items():
        if name == text:
            return code
    raise _bad(f"unknown status {value!r}")


def _parse_type(value: str | None) -> int | None:
    try:
        return v3c.parse_type_filter(value)
    except ValueError as exc:
        raise _bad(str(exc)) from exc


def _decode_pubkey(value: Any) -> bytes:
    if not isinstance(value, str) or not value:
        raise _bad("pubkey is required")
    text = value.strip()
    raw: bytes | None = None
    if len(text) == 66:
        try:
            raw = bytes.fromhex(text)
        except ValueError:
            raw = None
    if raw is None:
        try:
            raw = base64.b64decode(text, validate=True)
        except (binascii.Error, ValueError):
            raw = None
    if raw is None or len(raw) != 33 or raw[0] not in (2, 3):
        raise _bad("pubkey must be a 33-byte compressed P-256 key (hex or base64)")
    return raw


def _actor_filter(value: str | None) -> str | None:
    if value is None or not value.strip():
        return None
    text = value.strip().lower()
    if len(text) != 16:
        raise _bad("actor must be a 16-character actor id")
    try:
        bytes.fromhex(text)
    except ValueError:
        raise _bad("actor must be hex")
    return text


def _format_time(ts: int) -> str:
    try:
        return datetime.fromtimestamp(int(ts), tz=timezone.utc).strftime("%H:%M:%S")
    except (OverflowError, OSError, ValueError):
        return str(ts)


def _summary(decoded: dict | None) -> str:
    if not isinstance(decoded, dict):
        return ""
    if "error" in decoded and "fields" not in decoded:
        return str(decoded.get("error", ""))[:90]
    fields = decoded.get("fields")
    if not isinstance(fields, dict):
        return ""
    type_name = decoded.get("type", "")
    if type_name == "LISTING":
        return (
            f"crop {fields.get('crop')} qty {fields.get('qtyKg')} "
            f"ask {fields.get('askPaise')} region {fields.get('region')}"
        )
    if type_name == "ORDER":
        return (
            f"listing {str(fields.get('listingId', ''))[:8]} "
            f"qty {fields.get('qtyKg')} bid {fields.get('bidPaise')}"
        )
    if type_name in {"ORDER_ACCEPT", "ORDER_CANCEL", "DELIVERED"}:
        return f"order {str(fields.get('orderId', ''))[:12]}"
    if type_name == "ANNOUNCE":
        return f"{fields.get('nick')} caps {fields.get('caps')}"
    if type_name == "BATCH_REGISTER":
        return f"crop {fields.get('crop')} weight {fields.get('weightKg')}"
    if type_name == "BIND":
        return f"batch {str(fields.get('batchId', ''))[:8]} box {str(fields.get('boxId', ''))[:8]}"
    if type_name == "TAMPER_ALERT":
        return f"batch {str(fields.get('batchId', ''))[:8]} kind {fields.get('kind')}"
    if type_name == "PRICE_TABLE":
        return f"region {fields.get('region')} v{fields.get('version')} rows {len(fields.get('rows', []))}"
    if type_name == "RECEIPT":
        return f"for {str(fields.get('msgId', ''))[:12]} status {fields.get('status')}"
    if type_name == "NOTICE":
        return f"kind {fields.get('kind')} ref {str(fields.get('refId', ''))[:8]}"
    return ""


def _explorer_html(
    ledger: V3Ledger,
    *,
    status: int | None,
    type_code: int | None,
    via: str | None,
    limit: int = 100,
) -> str:
    rows = ledger.explorer_rows(
        status=status, type_code=type_code, via=via, limit=limit
    )
    stats = ledger.db.stats()
    checkpoint = ledger.db.latest_checkpoint()
    status_options = ["all"] + list(v3c.STATUS_NAMES.values())
    type_options = ["all"] + [item.name for item in v3c.MessageType]
    via_options = ["all"] + sorted(ALLOWED_VIA)

    def selected(current: str, value: str) -> str:
        return " selected" if current == value else ""

    status_value = "all" if status is None else v3c.status_name(status)
    type_value = "all" if type_code is None else v3c.type_name(type_code)
    via_value = via or "all"

    body_rows = []
    for row in rows:
        status_class = {
            "accepted": "ok",
            "duplicate": "dup",
            "rejected": "bad",
            "pending": "pend",
        }.get(row["status"], "")
        body_rows.append(
            "<tr class='{cls}'>"
            "<td>{seq}</td><td>{time}</td><td>{type}</td>"
            "<td class='mono'>{from_id}</td><td class='mono'>{to_id}</td>"
            "<td><b>{status}</b><br><span class='reason'>{reason}</span></td>"
            "<td>{via}</td><td class='mono'>{msg_id}</td>"
            "<td class='summary'>{summary}</td></tr>".format(
                cls=status_class,
                seq=row["seq"],
                time=_format_time(row["receivedAt"]),
                type=html.escape(row["type"]),
                from_id=html.escape(row["fromId"][:8]),
                to_id=html.escape((row["toId"] or "-")[:8]),
                status=html.escape(row["status"]),
                reason=html.escape(row["reason"]),
                via=html.escape(row["via"]),
                msg_id=html.escape(row["msgId"][:12]),
                summary=html.escape(_summary(row.get("decoded"))),
            )
        )
    if not body_rows:
        body_rows.append("<tr><td colspan='9'>No messages yet.</td></tr>")

    checkpoint_html = "no checkpoint yet"
    if checkpoint:
        checkpoint_html = (
            f"seq {int(checkpoint['seq'])} &middot; root "
            f"<span class='mono'>{bytes(checkpoint['merkle_root']).hex()[:32]}...</span>"
        )

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="5">
<title>AgriChain v3 explorer</title>
<style>
:root {{ color-scheme: light; }}
body {{ font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
       margin: 0; background: #f5f7f4; color: #17251a; }}
header {{ background: #1f4d2b; color: #fff; padding: 14px 20px; }}
header h1 {{ margin: 0 0 4px; font-size: 20px; }}
header a {{ color: #c8f0c8; margin-right: 14px; font-size: 13px; }}
.wrap {{ padding: 16px 20px 40px; }}
.stats {{ display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }}
.stat {{ background: #fff; border: 1px solid #dbe5d8; border-radius: 8px;
         padding: 8px 12px; font-size: 13px; }}
.stat b {{ display: block; font-size: 18px; }}
form {{ background: #fff; border: 1px solid #dbe5d8; border-radius: 8px;
        padding: 10px; margin-bottom: 12px; display: flex; gap: 8px;
        flex-wrap: wrap; align-items: center; }}
select, button {{ padding: 6px 8px; border-radius: 6px; border: 1px solid #b9cbb5;
                  background: #fff; }}
button {{ background: #1f4d2b; color: #fff; border-color: #1f4d2b; cursor: pointer; }}
table {{ width: 100%; border-collapse: collapse; background: #fff;
         border: 1px solid #dbe5d8; font-size: 13px; }}
th, td {{ text-align: left; padding: 7px 8px; border-bottom: 1px solid #edf2eb; }}
th {{ background: #eaf2e7; position: sticky; top: 0; }}
tr.ok td:nth-child(6) {{ color: #176b2c; }}
tr.bad td:nth-child(6) {{ color: #a32121; }}
tr.pend td:nth-child(6) {{ color: #9a6200; }}
tr.dup td:nth-child(6) {{ color: #4b5b7a; }}
.mono {{ font-family: ui-monospace, Consolas, monospace; font-size: 11px; }}
.reason {{ font-size: 11px; color: #5d6b5a; }}
.summary {{ font-size: 12px; color: #3f4a3d; }}
footer {{ margin-top: 12px; font-size: 12px; color: #5d6b5a; }}
</style>
</head>
<body>
<header>
<h1>AgriChain v3 explorer</h1>
<a href="/explorer">live log</a>
<a href="/v3/messages?limit=50">JSON log</a>
<a href="/v3/prices">prices</a>
<a href="/v3/listings">listings</a>
<a href="/v3/orders">orders</a>
<a href="/v3/trades">trades</a>
<a href="/v3/checkpoint">checkpoint</a>
<a href="/v3/ledger">ledger</a>
</header>
<div class="wrap">
<div class="stats">
<div class="stat"><b>{stats['messages']}</b>messages</div>
<div class="stat"><b>{stats['accepted']}</b>accepted</div>
<div class="stat"><b>{stats['rejected']}</b>rejected</div>
<div class="stat"><b>{stats['pending']}</b>pending</div>
<div class="stat"><b>{stats['actors']}</b>actors</div>
<div class="stat"><b>{stats['openListings']}</b>open listings</div>
<div class="stat"><b>{stats['trades']}</b>trades</div>
<div class="stat"><b>{stats['outbox']}</b>outbox</div>
</div>
<form method="get" action="/explorer">
<label>status
<select name="status">{''.join(f'<option value="{html.escape(item)}"{selected(status_value, item)}>{html.escape(item)}</option>' for item in status_options)}</select>
</label>
<label>type
<select name="type">{''.join(f'<option value="{html.escape(item)}"{selected(type_value, item)}>{html.escape(item)}</option>' for item in type_options)}</select>
</label>
<label>via
<select name="via">{''.join(f'<option value="{html.escape(item)}"{selected(via_value, item)}>{html.escape(item)}</option>' for item in via_options)}</select>
</label>
<label>limit <input name="limit" value="{limit}" size="4"></label>
<button type="submit">filter</button>
<span class="reason">auto-refresh every 5 s</span>
</form>
<table>
<thead><tr><th>seq</th><th>UTC</th><th>type</th><th>from</th><th>to</th>
<th>status</th><th>via</th><th>msgId</th><th>summary</th></tr></thead>
<tbody>{''.join(body_rows)}</tbody>
</table>
<footer>Ledger <span class="mono">{ledger.ledger_id_hex}</span>
&middot; head seq {ledger.db.max_seq()} &middot; checkpoint {checkpoint_html}
&middot; <a href="/v3/checkpoint">verify JSON</a></footer>
</div>
</body>
</html>"""


def install_v3(
    app: FastAPI,
    *,
    db_path: str,
    key_path: str | None,
    require_admin: Callable[[Request], None],
) -> V3Ledger:
    if key_path is None:
        key_path = str(Path(db_path).parent / "keys" / "ledger_key.pem")
    ledger = V3Ledger(db_path, key_path)
    app.state.v3_ledger = ledger

    @app.get("/v3/health")
    def v3_health() -> dict:
        return {"ok": True, "version": 3}

    @app.get("/v3/ledger")
    def v3_ledger_info() -> dict:
        info = ledger.info()
        data = v3c.contracts()
        info["crops"] = data["crops"]["crops"]
        info["regions"] = data["regions"]["regions"]
        return info

    @app.post("/v3/actors", status_code=201)
    def v3_register_actor(request: Request, payload: dict = Body(...)) -> dict:
        require_admin(request)
        pubkey = _decode_pubkey(payload.get("pubkey") or payload.get("pubKey"))
        role = str(payload.get("role", "")).strip().lower()
        if role not in {"farmer", "buyer", "box", "gateway"}:
            raise _bad("role must be farmer, buyer, box or gateway")
        name = str(payload.get("name", "")).strip()
        if not name or len(name) > 80:
            raise _bad("name is required (1..80 characters)")
        phone = payload.get("phone")
        if phone is not None:
            phone = str(phone).strip()
            if len(phone) > 24:
                raise _bad("phone is too long")
        region = payload.get("region")
        if region is not None:
            try:
                region = int(region)
            except (TypeError, ValueError):
                raise _bad("region must be an integer id")
            if not v3c.known_region(region):
                raise _bad(f"unknown region {region}")
        actor_id = v3c.actor_id_hex(pubkey)
        created, existing = ledger.db.upsert_actor(
            actor_id,
            pubkey,
            role,
            name,
            phone,
            region,
            int(time.time()),
        )
        if not created and existing:
            raise HTTPException(
                status_code=409,
                detail={
                    "error": "actor_key_mismatch",
                    "detail": "actorId is already registered with a different key",
                    "retry": False,
                },
            )
        # A pending BIND may be waiting for the box actor to be registered.
        ledger.resolve_pending()
        return {
            "actorId": actor_id,
            "pubkey": pubkey.hex(),
            "role": role,
            "name": name,
            "region": region,
            "phone": phone,
        }

    @app.get("/v3/actors/{actor_id}")
    def v3_get_actor(actor_id: str) -> dict:
        actor = ledger.db.get_actor(actor_id)
        if not actor:
            raise HTTPException(status_code=404, detail={"error": "not_found"})
        return {
            "actorId": actor["actor_id"],
            "pubkey": bytes(actor["pubkey"]).hex(),
            "role": actor["role"],
            "name": actor["name"],
            "region": actor["region"],
            "phone": actor["phone"],
            "caps": int(actor["caps"]),
            "nick": actor["nick"],
            "status": actor["status"],
        }

    @app.post("/v3/messages")
    def v3_post_messages(request: Request, payload: dict = Body(...)) -> dict:
        via = str(payload.get("via", "internet")).strip().lower()
        if via not in ALLOWED_VIA:
            raise _bad(f"via must be one of {sorted(ALLOWED_VIA)}")
        items = payload.get("messages")
        if not isinstance(items, list):
            raise _bad('body must be {"via":"wifi","messages":["base64", ...]}')
        if len(items) > 50:
            raise _bad("at most 50 messages per request")
        client_ip = request.client.host if request.client else "unknown"
        results = []
        for item in items:
            if not isinstance(item, str):
                results.append(
                    {
                        "msgId": "",
                        "status": "rejected",
                        "statusCode": v3c.STATUS_REJECTED,
                        "reason": "bad_format",
                        "reasonCode": v3c.REASON_BAD_FORMAT,
                        "seq": 0,
                        "receipt": None,
                        "type": "UNKNOWN",
                        "typeCode": 0,
                        "via": via,
                        "retry": False,
                        "error": "message must be a base64 string",
                    }
                )
                continue
            try:
                raw = base64.b64decode(item, validate=True)
            except (binascii.Error, ValueError):
                try:
                    raw = base64.urlsafe_b64decode(item + "=" * (-len(item) % 4))
                except (binascii.Error, ValueError):
                    results.append(
                        {
                            "msgId": "",
                            "status": "rejected",
                            "statusCode": v3c.STATUS_REJECTED,
                            "reason": "bad_format",
                            "reasonCode": v3c.REASON_BAD_FORMAT,
                            "seq": 0,
                            "receipt": None,
                            "type": "UNKNOWN",
                            "typeCode": 0,
                            "via": via,
                            "retry": False,
                            "error": "invalid base64",
                        }
                    )
                    continue
            results.append(ledger.ingest_one(via, raw, client_ip))
        return {"via": via, "results": results}

    @app.get("/v3/messages")
    def v3_get_messages(
        afterSeq: int = Query(default=0, ge=0),
        limit: int = Query(default=100, ge=1, le=200),
        type: str | None = Query(default=None),
        actor: str | None = Query(default=None),
        status: str | None = Query(default=None),
        via: str | None = Query(default=None),
    ) -> dict:
        type_code = _parse_type(type)
        status_code = _parse_status(status)
        if via and via not in ALLOWED_VIA:
            raise _bad(f"via must be one of {sorted(ALLOWED_VIA)}")
        return ledger.messages(
            after_seq=afterSeq,
            limit=limit,
            type_code=type_code,
            actor=actor,
            status=status_code,
            via=via,
        )

    @app.get("/v3/messages/{msg_id}")
    def v3_get_message(msg_id: str) -> dict:
        message = ledger.message(msg_id)
        if not message:
            raise HTTPException(status_code=404, detail={"error": "not_found"})
        return message

    @app.get("/v3/prices")
    def v3_prices(
        region: int | None = Query(default=None),
        sinceVersion: int = Query(default=0, ge=0),
    ) -> dict:
        if region is not None and not v3c.known_region(region):
            raise _bad(f"unknown region {region}")
        return ledger.prices(region, sinceVersion)

    @app.get("/v3/listings")
    def v3_listings(
        region: int | None = Query(default=None),
        afterSeq: int = Query(default=0, ge=0),
    ) -> dict:
        if region is not None and not v3c.known_region(region):
            raise _bad(f"unknown region {region}")
        return ledger.listings(region, afterSeq)

    @app.get("/v3/orders")
    def v3_orders(
        region: int | None = Query(default=None),
        crop: int | None = Query(default=None),
        status: str | None = Query(default=None),
        actor: str | None = Query(default=None),
        afterSeq: int = Query(default=0, ge=0),
        limit: int = Query(default=100, ge=1, le=200),
    ) -> dict:
        if region is not None and not v3c.known_region(region):
            raise _bad(f"unknown region {region}")
        if crop is not None and not v3c.known_crop(crop):
            raise _bad(f"unknown crop {crop}")
        order_status = None
        if status and status.lower() not in {"all", "*"}:
            order_status = status.strip().lower()
            if order_status not in {"open", "accepted", "delivered", "cancelled"}:
                raise _bad(f"unknown order status {status!r}")
        return ledger.orders(
            region=region,
            crop=crop,
            status=order_status,
            actor=_actor_filter(actor),
            after_seq=afterSeq,
            limit=limit,
        )

    @app.get("/v3/trades")
    def v3_trades(
        region: int | None = Query(default=None),
        crop: int | None = Query(default=None),
        actor: str | None = Query(default=None),
        afterSeq: int = Query(default=0, ge=0),
        limit: int = Query(default=100, ge=1, le=200),
    ) -> dict:
        if region is not None and not v3c.known_region(region):
            raise _bad(f"unknown region {region}")
        if crop is not None and not v3c.known_crop(crop):
            raise _bad(f"unknown crop {crop}")
        return ledger.trades(
            region=region,
            crop=crop,
            actor=_actor_filter(actor),
            after_seq=afterSeq,
            limit=limit,
        )

    @app.post("/v3/outbox/pull")
    def v3_outbox_pull(payload: dict = Body(...)) -> dict:
        actors = payload.get("actors")
        if not isinstance(actors, list):
            raise _bad('body must be {"actors":["hex", ...], "afterId":0}')
        cleaned = []
        for actor in actors:
            text = str(actor).strip().lower()
            if len(text) != 16:
                raise _bad("actor ids must be 16 hex characters")
            try:
                bytes.fromhex(text)
            except ValueError:
                raise _bad("actor ids must be hex")
            cleaned.append(text)
        try:
            after_id = int(payload.get("afterId", 0))
        except (TypeError, ValueError):
            raise _bad("afterId must be an integer")
        return ledger.pull_outbox(cleaned, after_id)

    @app.post("/v3/prices/seed")
    def v3_seed_prices(request: Request, payload: dict = Body(...)) -> dict:
        require_admin(request)
        rows = payload.get("rows")
        if rows is None:
            rows = [payload]
        if not isinstance(rows, list) or not rows:
            raise _bad("provide {region,crop,pricePaise} or {rows:[...]}")
        results = []
        for row in rows:
            if not isinstance(row, dict):
                raise _bad("each seed row must be an object")
            try:
                region = int(row["region"])
                crop = int(row["crop"])
                price = int(row.get("pricePaise", row.get("price_paise")))
            except (KeyError, TypeError, ValueError):
                raise _bad("seed rows need region, crop and pricePaise")
            try:
                results.append(ledger.seed_price(region, crop, price))
            except ValueError as exc:
                raise _bad(str(exc))
        return {
            "results": results,
            "versions": ledger.db.price_versions(),
        }

    @app.get("/v3/checkpoint")
    def v3_checkpoint() -> dict:
        return ledger.checkpoint()

    @app.get("/v3/stats")
    def v3_stats() -> dict:
        return ledger.db.stats()

    @app.get("/explorer", response_class=HTMLResponse, include_in_schema=False)
    def explorer(
        status: str | None = Query(default=None),
        type: str | None = Query(default=None),
        via: str | None = Query(default=None),
        limit: int = Query(default=100, ge=1, le=500),
    ) -> HTMLResponse:
        try:
            status_code = _parse_status(status)
            type_code = _parse_type(type)
        except HTTPException:
            status_code, type_code = None, None
        if via and via not in ALLOWED_VIA:
            via = None
        return HTMLResponse(
            _explorer_html(
                ledger,
                status=status_code,
                type_code=type_code,
                via=via,
                limit=limit,
            )
        )

    return ledger
