"""The v3 ledger engine: ingest, state, prices and checkpoints.

One lock plus one SQLite ``BEGIN IMMEDIATE`` transaction per message makes the
ledger the single orderer.  Client messages are stored on every arrival
(accepted, rejected or pending); ``msgId`` makes replay idempotent.

Pending messages keep the ledger hash they were given on arrival.  When their
dependency arrives the state is re-evaluated in place and a *new* RECEIPT with
the resolved status is written to the outbox.  The arrival log is never
rewritten, so checkpoints stay stable.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import sqlite3
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Iterable

from cryptography.hazmat.primitives.asymmetric import ec

import v3_codec as v3c
from v3_db import V3Db

ALLOWED_VIA = {"wifi", "ble", "lora", "sms", "internet"}
MAX_BATCH = 50
DAY = 24 * 3600


def _json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _b64(value: bytes | None) -> str | None:
    if value is None:
        return None
    return base64.b64encode(bytes(value)).decode("ascii")


def _sha256(*parts: bytes) -> bytes:
    digest = hashlib.sha256()
    for part in parts:
        digest.update(part)
    return digest.digest()


class V3Ledger:
    def __init__(
        self,
        db_path: str,
        key_path: str | None = None,
        private_key: ec.EllipticCurvePrivateKey | None = None,
        *,
        actor_rate: int | None = None,
        gateway_rate: int | None = None,
        ip_rate: int | None = None,
        checkpoint_every: int = 20,
        checkpoint_seconds: int = 60,
    ) -> None:
        self.db = V3Db(db_path)
        if private_key is None:
            if key_path is None:
                key_path = str(Path(db_path).parent / "keys" / "ledger_key.pem")
            private_key = v3c.load_or_create_private_key(key_path)
        self.private_key = private_key
        self.pubkey = v3c.compressed_public_key(private_key)
        self.ledger_id = v3c.actor_id(self.pubkey)
        self.ledger_id_hex = self.ledger_id.hex()
        self.lock = threading.RLock()
        self.actor_rate = self._env_int("AGRICHAIN_V3_ACTOR_RATE", actor_rate, 60)
        self.gateway_rate = self._env_int("AGRICHAIN_V3_GATEWAY_RATE", gateway_rate, 600)
        self.ip_rate = self._env_int("AGRICHAIN_V3_IP_RATE", ip_rate, 600)
        self.rate_enabled = os.environ.get("AGRICHAIN_V3_RATE_LIMIT", "1") != "0"
        self.checkpoint_every = max(1, int(checkpoint_every))
        self.checkpoint_seconds = max(1, int(checkpoint_seconds))
        self._actor_hits: dict[str, deque[float]] = {}
        self._ip_hits: dict[str, deque[float]] = {}

    @staticmethod
    def _env_int(name: str, value: int | None, default: int) -> int:
        if value is not None:
            return int(value)
        raw = os.environ.get(name)
        if raw is None:
            return default
        try:
            return int(raw)
        except ValueError:
            return default

    def now(self) -> int:
        return int(time.time())

    # ------------------------------------------------------------------ info
    def info(self) -> dict:
        stats = self.db.stats()
        checkpoint = self.db.latest_checkpoint()
        return {
            "ledgerId": self.ledger_id_hex,
            "pubkey": self.pubkey.hex(),
            "version": 3,
            "protocol": 1,
            "seq": self.db.max_seq(),
            "stats": stats,
            "contracts": v3c.contracts_digest(),
            "checkpoint": self._checkpoint_json(checkpoint) if checkpoint else None,
        }

    # --------------------------------------------------------------- ingest
    def ingest_many(
        self, via: str, raw_messages: Iterable[bytes], client_ip: str = "unknown"
    ) -> list[dict]:
        results = []
        for raw in raw_messages:
            results.append(self.ingest_one(via, raw, client_ip))
        return results

    def ingest_one(self, via: str, raw: bytes, client_ip: str = "unknown") -> dict:
        if via not in ALLOWED_VIA:
            via = "internet"
        with self.lock:
            with self.db.transaction() as conn:
                return self._ingest_tx(conn, via, raw, client_ip, self.now())

    def _ingest_tx(
        self,
        conn: sqlite3.Connection,
        via: str,
        raw: bytes,
        client_ip: str,
        now: int,
    ) -> dict:
        if not isinstance(raw, (bytes, bytearray)) or not raw:
            return self._result(
                msg_id="",
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_BAD_FORMAT,
                seq=0,
                receipt=None,
                type_code=0,
                via=via,
                retry=False,
                error="empty message",
            )
        raw = bytes(raw)
        msg_id_bytes = v3c.safe_msg_id(raw)
        msg_id = msg_id_bytes.hex()

        existing = conn.execute(
            "SELECT * FROM v3_messages WHERE msg_id = ?", (msg_id,)
        ).fetchone()
        if existing:
            return self._result_from_row(existing, duplicate=True)

        decoded = None
        decode_error = None
        try:
            decoded = v3c.decode_message(raw)
        except Exception as exc:  # CodecError, ValueError, ...
            decode_error = str(exc)

        if decoded is None:
            type_code = raw[1] if len(raw) > 1 else 0
            from_id = raw[7:15].hex() if len(raw) >= 15 else "0000000000000000"
            to_id = None
            ts = 0
            decoded_json = _json({"error": decode_error, "rawLength": len(raw)})
            status, reason = v3c.STATUS_REJECTED, v3c.REASON_BAD_FORMAT
            return self._append_and_finish(
                conn,
                seq=None,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=status,
                reason=reason,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=None,
                receipt_to=None,
                error=decode_error,
            )

        type_code = int(decoded.type)
        from_id = decoded.from_id.hex()
        to_id = decoded.to_id.hex() if decoded.to_id else None
        ts = int(decoded.ts)
        actor = self._actor(conn, from_id)
        try:
            fields = v3c.parse_body(type_code, decoded.body)
            decoded_json = _json(
                {
                    "type": v3c.type_name(type_code),
                    "typeCode": type_code,
                    "fromId": from_id,
                    "toId": to_id,
                    "ts": ts,
                    "flags": int(decoded.flags),
                    "ledgerSigned": bool(decoded.flags & v3c.FLAG_LEDGER_SIGNED),
                    "bodyHex": decoded.body.hex(),
                    "fields": v3c._jsonable(fields),
                }
            )
        except Exception as exc:
            fields = {}
            decoded_json = _json(
                {
                    "type": v3c.type_name(type_code),
                    "typeCode": type_code,
                    "fromId": from_id,
                    "toId": to_id,
                    "ts": ts,
                    "flags": int(decoded.flags),
                    "ledgerSigned": bool(decoded.flags & v3c.FLAG_LEDGER_SIGNED),
                    "bodyHex": decoded.body.hex(),
                    "error": str(exc),
                }
            )
            return self._append_and_finish(
                conn,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_BAD_FORMAT,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=decoded,
                receipt_to=decoded.from_id,
                error=str(exc),
            )

        # A client can never submit a ledger-signed message.
        if decoded.flags & v3c.FLAG_LEDGER_SIGNED:
            return self._append_and_finish(
                conn,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_NOT_AUTHORIZED,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=decoded,
                receipt_to=None,
                error="client messages cannot be ledger-signed",
            )

        if not self._ip_rate_ok(client_ip, now):
            return self._result(
                msg_id=msg_id,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_RATE_LIMITED,
                seq=0,
                receipt=None,
                type_code=type_code,
                via=via,
                retry=True,
                error="rate limited; retry later",
                decoded=json.loads(decoded_json),
            )

        if actor is None:
            return self._append_and_finish(
                conn,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_UNKNOWN_ACTOR,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=decoded,
                receipt_to=decoded.from_id,
                error="actor is not registered",
            )

        if ts < now - 30 * DAY or ts > now + DAY:
            return self._append_and_finish(
                conn,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_EXPIRED,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=decoded,
                receipt_to=decoded.from_id,
                error="ts outside the accepted window",
            )

        if not v3c.verify_message(raw, bytes(actor["pubkey"])):
            return self._append_and_finish(
                conn,
                raw=raw,
                msg_id=msg_id,
                msg_id_bytes=msg_id_bytes,
                type_code=type_code,
                from_id=from_id,
                to_id=to_id,
                ts=ts,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_BAD_SIGNATURE,
                via=via,
                now=now,
                decoded_json=decoded_json,
                decoded=decoded,
                receipt_to=decoded.from_id,
                error="signature does not verify for the actor",
            )

        # Actor limits are applied only after the signature verifies so a
        # forged fromId cannot burn another actor's allowance.
        if not self._actor_rate_ok(actor, from_id, now):
            return self._result(
                msg_id=msg_id,
                status=v3c.STATUS_REJECTED,
                reason=v3c.REASON_RATE_LIMITED,
                seq=0,
                receipt=None,
                type_code=type_code,
                via=via,
                retry=True,
                error="rate limited; retry later",
                decoded=json.loads(decoded_json),
            )

        seq = self.db.next_seq(conn)
        conn.execute("SAVEPOINT v3_apply")
        try:
            status, reason, extra = self._apply(
                conn, decoded, fields, actor, seq, now, resolving=False
            )
            if status != v3c.STATUS_ACCEPTED:
                # Rejections and pending rows must not leave partial market
                # state behind.
                conn.execute("ROLLBACK TO v3_apply")
            conn.execute("RELEASE v3_apply")
        except Exception as exc:
            conn.execute("ROLLBACK TO v3_apply")
            conn.execute("RELEASE v3_apply")
            status, reason, extra = (
                v3c.STATUS_REJECTED,
                v3c.REASON_BAD_FORMAT,
                {"error": str(exc)},
            )
        if extra:
            decoded_json = _json({**json.loads(decoded_json), **extra})
        return self._append_and_finish(
            conn,
            seq=seq,
            raw=raw,
            msg_id=msg_id,
            msg_id_bytes=msg_id_bytes,
            type_code=type_code,
            from_id=from_id,
            to_id=to_id,
            ts=ts,
            status=status,
            reason=reason,
            via=via,
            now=now,
            decoded_json=decoded_json,
            decoded=decoded,
            receipt_to=decoded.from_id,
            error=None,
        )

    def _append_and_finish(
        self,
        conn: sqlite3.Connection,
        *,
        seq: int | None = None,
        raw: bytes,
        msg_id: str,
        msg_id_bytes: bytes,
        type_code: int,
        from_id: str,
        to_id: str | None,
        ts: int,
        status: int,
        reason: int,
        via: str,
        now: int,
        decoded_json: str,
        decoded: v3c.codec.Message | None,
        receipt_to: bytes | None,
        error: str | None,
    ) -> dict:
        if seq is None:
            seq = self.db.next_seq(conn)
        last = conn.execute(
            "SELECT ledger_hash FROM v3_messages ORDER BY seq DESC LIMIT 1"
        ).fetchone()
        prev = bytes(last["ledger_hash"]) if last else b"\x00" * 32
        ledger_hash = _sha256(
            prev, msg_id_bytes, bytes([int(status)]), int(seq).to_bytes(4, "big")
        )
        self.db.insert_message(
            conn,
            seq=seq,
            msg_id=msg_id,
            raw=raw,
            mtype=int(type_code),
            from_id=from_id,
            to_id=to_id,
            ts=int(ts),
            status=int(status),
            reason=int(reason),
            via=via,
            received_at=now,
            ledger_prev=prev,
            ledger_hash=ledger_hash,
            decoded_json=decoded_json,
            error=error,
        )

        receipt = None
        if receipt_to is not None and len(receipt_to) == v3c.ACTOR_ID_LEN:
            if receipt_to.hex() != self.ledger_id_hex:
                receipt = self._make_receipt(
                    conn,
                    original_msg_id=msg_id_bytes,
                    status=status,
                    reason=reason,
                    seq=seq,
                    head8=ledger_hash[:8],
                    to_id=receipt_to,
                    now=now,
                )
                self.db.set_message_receipt(conn, msg_id, receipt)

        if status == v3c.STATUS_ACCEPTED:
            self._resolve_pending(conn, now)
        self._maybe_checkpoint(conn, now)
        result = self._result(
            msg_id=msg_id,
            status=status,
            reason=reason,
            seq=seq,
            receipt=receipt,
            type_code=int(type_code),
            via=via,
            retry=False,
            decoded=json.loads(decoded_json),
            ledgerHash=ledger_hash.hex(),
            receivedAt=now,
        )
        if error:
            result["error"] = error
        return result

    # ---------------------------------------------------------- rate limits
    def _ip_rate_ok(self, client_ip: str, now: int) -> bool:
        if not self.rate_enabled or self.ip_rate <= 0:
            return True
        return self._hit(self._ip_hits, client_ip or "unknown", now, self.ip_rate)

    def _actor_rate_ok(self, actor: dict, from_id: str, now: int) -> bool:
        if not self.rate_enabled:
            return True
        limit = self.gateway_rate if actor["role"] == "gateway" else self.actor_rate
        if limit <= 0:
            return True
        return self._hit(self._actor_hits, from_id, now, limit)

    @staticmethod
    def _hit(
        store: dict[str, deque[float]], key: str, now: int, limit: int
    ) -> bool:
        hits = store.setdefault(key, deque())
        cutoff = now - 60
        while hits and hits[0] < cutoff:
            hits.popleft()
        if len(hits) >= limit:
            return False
        hits.append(float(now))
        return True

    # ------------------------------------------------------------- helpers
    @staticmethod
    def _actor(conn: sqlite3.Connection, actor_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_actors WHERE actor_id = ?", (actor_id,)
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _listing(conn: sqlite3.Connection, listing_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_listings WHERE listing_id = ?", (listing_id,)
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _order(conn: sqlite3.Connection, order_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_orders WHERE order_id = ?", (order_id,)
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _batch(conn: sqlite3.Connection, batch_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_batches WHERE batch_id = ?", (batch_id,)
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _binding(conn: sqlite3.Connection, batch_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_bindings WHERE batch_id = ?", (batch_id,)
        ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def _barter(conn: sqlite3.Connection, offer_id: str) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_barter_offers WHERE offer_id = ?", (offer_id,)
        ).fetchone()
        return dict(row) if row else None

    def _crop_balance(self, conn: sqlite3.Connection, actor_id: str, crop: int) -> int:
        row = conn.execute(
            """SELECT COALESCE(SUM(b.kg), 0) AS kg
               FROM v3_balances b JOIN v3_batches x ON x.batch_id = b.batch_id
               WHERE b.actor_id = ? AND x.crop = ?""",
            (actor_id, int(crop)),
        ).fetchone()
        return int(row["kg"])

    def _move_crop(
        self,
        conn: sqlite3.Connection,
        *,
        from_id: str,
        to_id: str,
        crop: int,
        kg: int,
    ) -> bool:
        rows = conn.execute(
            """SELECT b.batch_id, b.kg FROM v3_balances b
               JOIN v3_batches x ON x.batch_id = b.batch_id
               WHERE b.actor_id = ? AND x.crop = ? AND b.kg > 0
               ORDER BY b.batch_id""",
            (from_id, int(crop)),
        ).fetchall()
        remaining = int(kg)
        moves = []
        for row in rows:
            take = min(remaining, int(row["kg"]))
            if take:
                moves.append((row["batch_id"], take))
                remaining -= take
            if remaining <= 0:
                break
        if remaining > 0:
            return False
        for batch_id, take in moves:
            conn.execute(
                "UPDATE v3_balances SET kg = kg - ? WHERE actor_id = ? AND batch_id = ?",
                (take, from_id, batch_id),
            )
            conn.execute(
                """INSERT INTO v3_balances (actor_id, batch_id, kg) VALUES (?, ?, ?)
                   ON CONFLICT(actor_id, batch_id) DO UPDATE SET kg = kg + excluded.kg""",
                (to_id, batch_id, take),
            )
        return True

    # ------------------------------------------------------------- apply
    def _apply(
        self,
        conn: sqlite3.Connection,
        msg: v3c.codec.Message,
        fields: dict,
        actor: dict,
        seq: int,
        now: int,
        *,
        resolving: bool,
    ) -> tuple[int, int, dict]:
        handler = {
            v3c.MessageType.ANNOUNCE: self._apply_announce,
            v3c.MessageType.LISTING: self._apply_listing,
            v3c.MessageType.ORDER: self._apply_order,
            v3c.MessageType.ORDER_ACCEPT: self._apply_order_accept,
            v3c.MessageType.ORDER_CANCEL: self._apply_order_cancel,
            v3c.MessageType.DELIVERED: self._apply_delivered,
            v3c.MessageType.BARTER_OFFER: self._apply_barter_offer,
            v3c.MessageType.BARTER_ACCEPT: self._apply_barter_accept,
            v3c.MessageType.TAMPER_ALERT: self._apply_tamper_alert,
            v3c.MessageType.BATCH_REGISTER: self._apply_batch_register,
            v3c.MessageType.BIND: self._apply_bind,
        }.get(msg.type)
        if handler is None:
            return v3c.STATUS_REJECTED, v3c.REASON_BAD_FORMAT, {}
        return handler(conn, msg, fields, actor, seq, now, resolving)

    @staticmethod
    def _accept(extra: dict | None = None) -> tuple[int, int, dict]:
        return v3c.STATUS_ACCEPTED, v3c.REASON_NONE, extra or {}

    @staticmethod
    def _reject(reason: int, extra: dict | None = None) -> tuple[int, int, dict]:
        return v3c.STATUS_REJECTED, reason, extra or {}

    @staticmethod
    def _pending(kind: str, ref_id: str, extra: dict | None = None) -> tuple[int, int, dict]:
        data = {"dep": {"kind": kind, "id": ref_id}}
        if extra:
            data.update(extra)
        return v3c.STATUS_PENDING, v3c.REASON_DEPENDENCY_MISSING, data

    def _apply_announce(self, conn, msg, fields, actor, seq, now, resolving):
        if bytes(fields["pubkey"]) != bytes(actor["pubkey"]):
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        if int(fields["caps"]) & 0xE0:
            return self._reject(v3c.REASON_BAD_FORMAT)
        nick = str(fields["nick"])
        if not 1 <= len(nick.encode("utf-8")) <= 20:
            return self._reject(v3c.REASON_BAD_FORMAT)
        conn.execute(
            "UPDATE v3_actors SET caps = ?, nick = ?, updated_at = ? WHERE actor_id = ?",
            (int(fields["caps"]), nick, now, actor["actor_id"]),
        )
        return self._accept()

    def _apply_listing(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "farmer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        crop = int(fields["crop"])
        region = int(fields["region"])
        grade = int(fields["grade"])
        qty = int(fields["qtyKg"])
        ask = int(fields["askPaise"])
        valid_until = int(fields["validUntil"])
        if not v3c.known_crop(crop) or not v3c.known_region(region):
            return self._reject(v3c.REASON_BAD_FORMAT)
        if grade < 1 or grade > 5 or qty <= 0:
            return self._reject(v3c.REASON_BAD_FORMAT)
        low, high = v3c.price_bounds(crop)
        if ask < low or ask > high:
            return self._reject(v3c.REASON_PRICE_OUT_OF_BOUNDS)
        if valid_until <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if valid_until > now + 30 * DAY:
            return self._reject(v3c.REASON_BAD_FORMAT)
        batch_id = bytes(fields["batchId"]).hex()
        if batch_id == "00" * 12:
            batch_id = None
        else:
            batch = self._batch(conn, batch_id)
            if batch is None:
                return self._pending("batch", batch_id)
            if batch["owner_id"] != actor["actor_id"]:
                return self._reject(v3c.REASON_NOT_AUTHORIZED)
            if int(batch["crop"]) != crop:
                return self._reject(v3c.REASON_BAD_FORMAT)
        self.db.insert_listing(
            conn,
            listing_id=msg_id_hex(msg),
            seller_id=actor["actor_id"],
            crop=crop,
            qty_kg=qty,
            ask_paise=ask,
            region=region,
            valid_until=valid_until,
            grade=grade,
            batch_id=batch_id,
            seq=seq,
            now=now,
        )
        return self._accept()

    def _apply_order(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "buyer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        listing_id = bytes(fields["listingId"]).hex()
        qty = int(fields["qtyKg"])
        bid = int(fields["bidPaise"])
        valid_until = int(fields["validUntil"])
        if qty <= 0 or bid <= 0:
            return self._reject(v3c.REASON_BAD_FORMAT)
        # Check the order's own deadline before a missing dependency can keep
        # it pending forever.
        if valid_until <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if valid_until > now + 30 * DAY:
            return self._reject(v3c.REASON_BAD_FORMAT)
        listing = self._listing(conn, listing_id)
        if listing is None:
            return self._pending("listing", listing_id)
        if listing["status"] != "open":
            return self._reject(v3c.REASON_ALREADY_FINAL)
        if int(listing["valid_until"]) <= now:
            return self._reject(v3c.REASON_EXPIRED)
        seller = str(listing["seller_id"])
        if seller == actor["actor_id"]:
            return self._reject(v3c.REASON_SELF_TRADE)
        if msg.to_id is None or msg.to_id.hex() != seller:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        if qty > int(listing["remaining_kg"]):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        order_id = msg_id_hex(msg)
        self.db.insert_order(
            conn,
            order_id=order_id,
            listing_id=listing_id,
            buyer_id=actor["actor_id"],
            seller_id=seller,
            qty_kg=qty,
            bid_paise=bid,
            valid_until=valid_until,
            seq=seq,
            now=now,
        )
        self._notice(
            conn,
            kind=v3c.NOTICE_ORDER_RECEIVED,
            ref_id=bytes.fromhex(order_id),
            seq=seq,
            to_id=seller,
            qty=qty,
            price=bid,
            now=now,
        )
        return self._accept()

    def _apply_order_accept(self, conn, msg, fields, actor, seq, now, resolving):
        order_id = bytes(fields["orderId"]).hex()
        order = self._order(conn, order_id)
        if order is None:
            return self._pending("order", order_id)
        if order["status"] in {"delivered", "cancelled", "accepted"}:
            return self._reject(v3c.REASON_ALREADY_FINAL)
        if int(order["valid_until"]) <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if actor["actor_id"] != order["seller_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        if msg.to_id is None or msg.to_id.hex() != order["buyer_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        listing = self._listing(conn, order["listing_id"])
        if listing is None:
            return self._pending("listing", order["listing_id"])
        if int(listing["valid_until"]) <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if int(listing["remaining_kg"]) < int(order["qty_kg"]):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        remaining = int(listing["remaining_kg"]) - int(order["qty_kg"])
        self.db.set_listing_remaining(
            conn, listing["listing_id"], remaining, "open" if remaining > 0 else "sold"
        )
        self.db.set_order_status(
            conn, order_id, "accepted", seq, accepted=True
        )
        self.db.insert_trade(
            conn,
            trade_id=msg_id_hex(msg),
            order_id=order_id,
            listing_id=listing["listing_id"],
            seller_id=order["seller_id"],
            buyer_id=order["buyer_id"],
            crop=int(listing["crop"]),
            region=int(listing["region"]),
            qty_kg=int(order["qty_kg"]),
            price_paise=int(order["bid_paise"]),
            seq=seq,
            now=now,
        )
        self._notice(
            conn,
            kind=v3c.NOTICE_ORDER_ACCEPTED,
            ref_id=bytes.fromhex(order_id),
            seq=seq,
            to_id=order["buyer_id"],
            qty=int(order["qty_kg"]),
            price=int(order["bid_paise"]),
            now=now,
        )
        self._recompute_region(conn, int(listing["region"]), seq, now)
        return self._accept()

    def _apply_order_cancel(self, conn, msg, fields, actor, seq, now, resolving):
        order_id = bytes(fields["orderId"]).hex()
        order = self._order(conn, order_id)
        if order is None:
            return self._pending("order", order_id)
        if order["status"] in {"delivered", "cancelled"}:
            return self._reject(v3c.REASON_ALREADY_FINAL)
        if actor["actor_id"] not in {order["buyer_id"], order["seller_id"]}:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        counterparty = (
            order["seller_id"] if actor["actor_id"] == order["buyer_id"] else order["buyer_id"]
        )
        if msg.to_id is None or msg.to_id.hex() != counterparty:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        listing = self._listing(conn, order["listing_id"])
        if listing is None:
            return self._pending("listing", order["listing_id"])
        if order["status"] == "accepted":
            remaining = int(listing["remaining_kg"]) + int(order["qty_kg"])
            self.db.set_listing_remaining(conn, listing["listing_id"], remaining, "open")
        self.db.set_order_status(conn, order_id, "cancelled", seq)
        self._notice(
            conn,
            kind=v3c.NOTICE_ORDER_CANCELLED,
            ref_id=bytes.fromhex(order_id),
            seq=seq,
            to_id=counterparty,
            qty=int(order["qty_kg"]),
            price=int(order["bid_paise"]),
            now=now,
        )
        return self._accept()

    def _apply_delivered(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "buyer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        order_id = bytes(fields["orderId"]).hex()
        order = self._order(conn, order_id)
        if order is None:
            return self._pending("order", order_id)
        if order["status"] in {"delivered", "cancelled"}:
            return self._reject(v3c.REASON_ALREADY_FINAL)
        if order["status"] != "accepted":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        if actor["actor_id"] != order["buyer_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        # DELIVERED goes from the buyer to the seller (vectors.json).
        if msg.to_id is None or msg.to_id.hex() != order["seller_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        listing = self._listing(conn, order["listing_id"])
        if listing is None:
            return self._pending("listing", order["listing_id"])
        batch_id = listing.get("batch_id")
        if batch_id:
            moved = self.db.move_balance(
                conn,
                batch_id=batch_id,
                from_id=order["seller_id"],
                to_id=order["buyer_id"],
                kg=int(order["qty_kg"]),
            )
            if not moved:
                return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        self.db.set_order_status(conn, order_id, "delivered", seq, delivered=True)
        self._notice(
            conn,
            kind=v3c.NOTICE_DELIVERED,
            ref_id=bytes.fromhex(order_id),
            seq=seq,
            to_id=order["seller_id"],
            qty=int(order["qty_kg"]),
            price=int(order["bid_paise"]),
            now=now,
        )
        return self._accept()

    def _apply_batch_register(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "farmer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        crop = int(fields["crop"])
        region = int(fields["region"])
        grade = int(fields["grade"])
        weight = int(fields["weightKg"])
        harvest_ts = int(fields["harvestTs"])
        if not v3c.known_crop(crop) or not v3c.known_region(region):
            return self._reject(v3c.REASON_BAD_FORMAT)
        if grade < 1 or grade > 5 or not 1 <= weight <= 100000:
            return self._reject(v3c.REASON_BAD_FORMAT)
        if harvest_ts > now + DAY:
            return self._reject(v3c.REASON_BAD_FORMAT)
        self.db.insert_batch(
            conn,
            batch_id=msg_id_hex(msg),
            owner_id=actor["actor_id"],
            crop=crop,
            weight_kg=weight,
            harvest_ts=harvest_ts,
            region=region,
            grade=grade,
            seq=seq,
            now=now,
        )
        return self._accept()

    def _apply_bind(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "farmer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        batch_id = bytes(fields["batchId"]).hex()
        batch = self._batch(conn, batch_id)
        if batch is None:
            return self._pending("batch", batch_id)
        if batch["owner_id"] != actor["actor_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        box_id = bytes(fields["boxId"]).hex()
        box = self._actor(conn, box_id)
        if box is None:
            return self._pending("actor", box_id)
        if box["role"] != "box":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        self.db.upsert_binding(conn, batch_id, box_id, seq, now)
        return self._accept()

    def _apply_tamper_alert(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "box":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        batch_id = bytes(fields["batchId"]).hex()
        batch = self._batch(conn, batch_id)
        if batch is None:
            return self._pending("batch", batch_id)
        binding = self._binding(conn, batch_id)
        if binding is None:
            return self._pending("binding", batch_id)
        if binding["box_id"] != actor["actor_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        self.db.insert_tamper(
            conn,
            msg_id=msg_id_hex(msg),
            batch_id=batch_id,
            box_id=actor["actor_id"],
            kind=int(fields["kind"]),
            lat_micro=int(fields["lat"]),
            lon_micro=int(fields["lon"]),
            detected_ts=int(fields["detectedTs"]),
            seq=seq,
            now=now,
        )
        self._notice(
            conn,
            kind=v3c.NOTICE_TAMPER,
            ref_id=bytes.fromhex(batch_id),
            seq=seq,
            to_id=batch["owner_id"],
            qty=0,
            price=0,
            now=now,
        )
        return self._accept()

    def _apply_barter_offer(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "farmer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        give_crop = int(fields["giveCrop"])
        want_crop = int(fields["wantCrop"])
        region = int(fields["region"])
        give_kg = int(fields["giveKg"])
        want_kg = int(fields["wantKg"])
        valid_until = int(fields["validUntil"])
        if not v3c.known_crop(give_crop) or not v3c.known_crop(want_crop):
            return self._reject(v3c.REASON_BAD_FORMAT)
        if not v3c.known_region(region) or give_kg <= 0 or want_kg <= 0:
            return self._reject(v3c.REASON_BAD_FORMAT)
        if valid_until <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if valid_until > now + 30 * DAY:
            return self._reject(v3c.REASON_BAD_FORMAT)
        self.db.insert_barter_offer(
            conn,
            offer_id=msg_id_hex(msg),
            owner_id=actor["actor_id"],
            give_crop=give_crop,
            give_kg=give_kg,
            want_crop=want_crop,
            want_kg=want_kg,
            region=region,
            valid_until=valid_until,
            seq=seq,
            now=now,
        )
        return self._accept()

    def _apply_barter_accept(self, conn, msg, fields, actor, seq, now, resolving):
        if actor["role"] != "farmer":
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        offer_id = bytes(fields["offerId"]).hex()
        offer = self._barter(conn, offer_id)
        if offer is None:
            return self._pending("offer", offer_id)
        if offer["status"] != "open":
            return self._reject(v3c.REASON_ALREADY_FINAL)
        if int(offer["valid_until"]) <= now:
            return self._reject(v3c.REASON_EXPIRED)
        if offer["owner_id"] == actor["actor_id"]:
            return self._reject(v3c.REASON_SELF_TRADE)
        if msg.to_id is None or msg.to_id.hex() != offer["owner_id"]:
            return self._reject(v3c.REASON_NOT_AUTHORIZED)
        if self._crop_balance(conn, actor["actor_id"], int(offer["want_crop"])) < int(
            offer["want_kg"]
        ):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        if self._crop_balance(conn, offer["owner_id"], int(offer["give_crop"])) < int(
            offer["give_kg"]
        ):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        if not self._move_crop(
            conn,
            from_id=offer["owner_id"],
            to_id=actor["actor_id"],
            crop=int(offer["give_crop"]),
            kg=int(offer["give_kg"]),
        ):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        if not self._move_crop(
            conn,
            from_id=actor["actor_id"],
            to_id=offer["owner_id"],
            crop=int(offer["want_crop"]),
            kg=int(offer["want_kg"]),
        ):
            return self._reject(v3c.REASON_INSUFFICIENT_QTY)
        self.db.accept_barter_offer(conn, offer_id, actor["actor_id"], seq)
        return self._accept()

    # ----------------------------------------------------------- pending
    def resolve_pending(self) -> None:
        """Re-evaluate pending rows after an out-of-band change (e.g. actor)."""
        with self.lock:
            with self.db.transaction() as conn:
                self._resolve_pending(conn, self.now())

    def _resolve_pending(self, conn: sqlite3.Connection, now: int) -> None:
        last = conn.execute(
            "SELECT ledger_hash FROM v3_messages ORDER BY seq DESC LIMIT 1"
        ).fetchone()
        head8 = bytes(last["ledger_hash"])[:8] if last else b"\x00" * 8
        for _ in range(10):
            rows = conn.execute(
                "SELECT * FROM v3_messages WHERE status = 3 ORDER BY seq ASC"
            ).fetchall()
            if not rows:
                return
            changed = False
            for row in rows:
                try:
                    msg = v3c.decode_message(bytes(row["raw"]))
                    fields = v3c.parse_body(int(msg.type), msg.body)
                except Exception:
                    continue
                actor = self._actor(conn, row["from_id"])
                if actor is None:
                    continue
                conn.execute("SAVEPOINT v3_resolve")
                try:
                    try:
                        status, reason, extra = self._apply(
                            conn,
                            msg,
                            fields,
                            actor,
                            int(row["seq"]),
                            now,
                            resolving=True,
                        )
                    except Exception:
                        conn.execute("ROLLBACK TO v3_resolve")
                        status, reason, extra = (
                            v3c.STATUS_REJECTED,
                            v3c.REASON_BAD_FORMAT,
                            {},
                        )
                    if status != v3c.STATUS_ACCEPTED:
                        conn.execute("ROLLBACK TO v3_resolve")
                    if status == v3c.STATUS_PENDING:
                        conn.execute("RELEASE v3_resolve")
                        continue
                    receipt = self._make_receipt(
                        conn,
                        original_msg_id=bytes.fromhex(row["msg_id"]),
                        status=status,
                        reason=reason,
                        seq=int(row["seq"]),
                        head8=head8,
                        to_id=bytes.fromhex(row["from_id"]),
                        now=now,
                    )
                    self.db.resolve_message(
                        conn, row["msg_id"], status, reason, receipt
                    )
                    conn.execute("RELEASE v3_resolve")
                    changed = True
                except Exception:
                    conn.execute("ROLLBACK TO v3_resolve")
                    conn.execute("RELEASE v3_resolve")
            if not changed:
                return

    # ---------------------------------------------------------- ledger msgs
    def _make_receipt(
        self,
        conn: sqlite3.Connection,
        *,
        original_msg_id: bytes,
        status: int,
        reason: int,
        seq: int,
        head8: bytes,
        to_id: bytes,
        now: int,
    ) -> bytes:
        body = v3c.receipt_body(original_msg_id, status, reason, seq, head8)
        message = v3c.sign_ledger_message(
            self.private_key,
            v3c.MessageType.RECEIPT,
            body,
            now,
            to_id=to_id,
        )
        msg_id = v3c.message_id_of(message)
        self.db.add_outbox(
            conn,
            to_id=to_id.hex(),
            msg=message,
            msg_id=msg_id.hex(),
            kind="RECEIPT",
            created_seq=seq,
            created_at=now,
        )
        return message

    def _notice(
        self,
        conn: sqlite3.Connection,
        *,
        kind: int,
        ref_id: bytes,
        seq: int,
        to_id: str,
        qty: int,
        price: int,
        now: int,
    ) -> bytes:
        body = v3c.notice_body(kind, ref_id, seq, qty, price)
        message = v3c.sign_ledger_message(
            self.private_key,
            v3c.MessageType.NOTICE,
            body,
            now,
            to_id=bytes.fromhex(to_id),
        )
        msg_id = v3c.message_id_of(message)
        self.db.add_outbox(
            conn,
            to_id=to_id,
            msg=message,
            msg_id=msg_id.hex(),
            kind=v3c.notice_name(kind),
            created_seq=seq,
            created_at=now,
        )
        return message

    # --------------------------------------------------------------- prices
    def _price_version(self, conn: sqlite3.Connection, region: int) -> tuple[int, int]:
        row = conn.execute(
            "SELECT version, as_of FROM v3_price_versions WHERE region = ?",
            (int(region),),
        ).fetchone()
        return (int(row["version"]), int(row["as_of"])) if row else (0, 0)

    def _current_price_rows(
        self, conn: sqlite3.Connection, region: int
    ) -> dict[int, tuple[int, int]]:
        version, _ = self._price_version(conn, region)
        if version <= 0:
            return {}
        rows = conn.execute(
            """SELECT body FROM v3_price_tables
               WHERE region = ? AND version = ? ORDER BY part ASC""",
            (int(region), version),
        ).fetchall()
        out: dict[int, tuple[int, int]] = {}
        for row in rows:
            try:
                parsed = v3c.parse_body(
                    v3c.MessageType.PRICE_TABLE, bytes(row["body"])
                )
            except Exception:
                continue
            for crop, price, trades in parsed["rows"]:
                out[int(crop)] = (int(price), int(trades))
        return out

    def _price_for_crop(
        self, conn: sqlite3.Connection, region: int, crop: int, now: int
    ) -> tuple[int, int] | None:
        trades = conn.execute(
            """SELECT seller_id, buyer_id, price_paise, seq
               FROM v3_trades
               WHERE region = ? AND crop = ? AND ts >= ?
               ORDER BY seq DESC""",
            (int(region), int(crop), int(now) - 30 * DAY),
        ).fetchall()
        per_pair: dict[tuple[str, str], int] = {}
        qualified = []
        for trade in trades:
            if trade["seller_id"] == trade["buyer_id"]:
                continue
            pair = (trade["seller_id"], trade["buyer_id"])
            count = per_pair.get(pair, 0)
            if count >= 3:
                continue
            per_pair[pair] = count + 1
            qualified.append(int(trade["price_paise"]))
            if len(qualified) >= 20:
                break
        if len(qualified) >= 3:
            prices = sorted(qualified)
            middle = len(prices) // 2
            if len(prices) % 2:
                price = prices[middle]
            else:
                price = (prices[middle - 1] + prices[middle]) // 2
            return price, len(qualified)
        if qualified:
            return qualified[0], len(qualified)
        seed = conn.execute(
            "SELECT price_paise FROM v3_seed_prices WHERE region = ? AND crop = ?",
            (int(region), int(crop)),
        ).fetchone()
        if seed:
            return int(seed["price_paise"]), 0
        return None

    def _recompute_region(
        self, conn: sqlite3.Connection, region: int, seq: int, now: int
    ) -> bool:
        crop_rows = conn.execute(
            """SELECT DISTINCT crop FROM v3_trades WHERE region = ?
               UNION SELECT crop FROM v3_seed_prices WHERE region = ?""",
            (int(region), int(region)),
        ).fetchall()
        crops = sorted({int(row["crop"]) for row in crop_rows})
        new_rows: dict[int, tuple[int, int]] = {}
        for crop in crops:
            price = self._price_for_crop(conn, region, crop, now)
            if price is not None and price[0] > 0:
                new_rows[crop] = price
        old_rows = self._current_price_rows(conn, region)
        if new_rows == old_rows:
            return False
        if not new_rows:
            return False
        old_version, _ = self._price_version(conn, region)
        version = old_version + 1
        as_of = now
        self.db.set_price_version(conn, region, version, as_of)
        rows = [(crop, price, min(trades, 255)) for crop, (price, trades) in sorted(new_rows.items())]
        parts = (len(rows) + 18) // 19
        bodies: list[bytes] = []
        for part in range(1, parts + 1):
            chunk = rows[(part - 1) * 19 : part * 19]
            body = v3c.price_table_body(region, version, as_of, part, parts, chunk)
            bodies.append(body)
            message = v3c.sign_ledger_message(
                self.private_key,
                v3c.MessageType.PRICE_TABLE,
                body,
                now,
            )
            self.db.insert_price_table(
                conn,
                region=region,
                version=version,
                part=part,
                parts=parts,
                as_of=as_of,
                body=body,
                message=message,
                msg_id=v3c.message_id_of(message).hex(),
                seq=seq,
                now=now,
            )
        hash8 = v3c.price_table_hash8(bodies)
        beacon = v3c.sign_ledger_message(
            self.private_key,
            v3c.MessageType.PRICE_BEACON,
            v3c.price_beacon_body(region, version, hash8),
            now,
        )
        self.db.insert_price_beacon(
            conn,
            region=region,
            version=version,
            message=beacon,
            msg_id=v3c.message_id_of(beacon).hex(),
            hash8=hash8,
            now=now,
        )
        return True

    def seed_price(self, region: int, crop: int, price_paise: int) -> dict:
        if not v3c.known_region(region) or not v3c.known_crop(crop):
            raise ValueError("unknown region or crop")
        low, high = v3c.price_bounds(crop)
        if not low <= int(price_paise) <= high:
            raise ValueError(f"price must be within {low}..{high} paise")
        with self.lock:
            with self.db.transaction() as conn:
                now = self.now()
                self.db.set_seed_price(conn, region, crop, price_paise, now)
                row = conn.execute(
                    "SELECT COALESCE(MAX(seq), 0) AS seq FROM v3_messages"
                ).fetchone()
                self._recompute_region(conn, region, int(row["seq"]), now)
                version, as_of = self._price_version(conn, region)
        return {"region": region, "crop": crop, "pricePaise": int(price_paise), "version": version, "asOf": as_of}

    def prices(self, region: int | None, since_version: int) -> dict:
        tables = self.db.price_tables(region, since_version)
        beacons = self.db.price_beacons(region, since_version)
        prices = []
        max_version = 0
        for row in tables:
            try:
                parsed = v3c.parse_body(
                    v3c.MessageType.PRICE_TABLE, bytes(row["body"])
                )
            except Exception:
                continue
            max_version = max(max_version, int(row["version"]))
            prices.append(
                {
                    "region": int(row["region"]),
                    "version": int(row["version"]),
                    "part": int(row["part"]),
                    "parts": int(row["parts"]),
                    "asOf": int(row["as_of"]),
                    "rows": [
                        {
                            "crop": int(crop),
                            "cropName": v3c.contracts()["cropNames"].get(int(crop), str(crop)),
                            "pricePaise": int(price),
                            "trades": int(trades),
                        }
                        for crop, price, trades in parsed["rows"]
                    ],
                    "msgId": row["msg_id"],
                    "message": _b64(bytes(row["message"])),
                    "body": _b64(bytes(row["body"])),
                }
            )
        beacon_rows = []
        for row in beacons:
            max_version = max(max_version, int(row["version"]))
            beacon_rows.append(
                {
                    "region": int(row["region"]),
                    "version": int(row["version"]),
                    "hash8": bytes(row["hash8"]).hex(),
                    "msgId": row["msg_id"],
                    "message": _b64(bytes(row["message"])),
                }
            )
        return {
            "version": max_version,
            "versions": self.db.price_versions(),
            "prices": prices,
            "beacons": beacon_rows,
        }

    # ---------------------------------------------------------- checkpoints
    def _maybe_checkpoint(self, conn: sqlite3.Connection, now: int) -> None:
        latest = conn.execute(
            "SELECT * FROM v3_checkpoints ORDER BY id DESC LIMIT 1"
        ).fetchone()
        seq = int(
            conn.execute("SELECT COALESCE(MAX(seq), 0) AS seq FROM v3_messages").fetchone()["seq"]
        )
        if latest:
            if seq - int(latest["seq"]) < self.checkpoint_every and now - int(
                latest["created_at"]
            ) < self.checkpoint_seconds:
                return
            self._create_checkpoint(conn, now)
            return
        # Do not checkpoint a nearly empty ledger just because the first
        # message arrived; wait for 20 messages or the 60 s window.
        row = conn.execute(
            "SELECT COUNT(*) AS n, MIN(received_at) AS first FROM v3_messages"
        ).fetchone()
        count = int(row["n"])
        first = int(row["first"] or now)
        if count >= self.checkpoint_every or now - first >= self.checkpoint_seconds:
            self._create_checkpoint(conn, now)

    def _create_checkpoint(self, conn: sqlite3.Connection, now: int) -> dict:
        rows = conn.execute(
            "SELECT seq, ledger_hash FROM v3_messages ORDER BY seq ASC"
        ).fetchall()
        seq = int(rows[-1]["seq"]) if rows else 0
        root = v3c.merkle_root(bytes(row["ledger_hash"]) for row in rows)
        signature = v3c.sign_checkpoint(self.private_key, seq, root)
        message = v3c.checkpoint_payload(seq, root)
        self.db.insert_checkpoint(
            conn,
            seq=seq,
            root=root,
            message=message,
            signature=signature,
            now=now,
        )
        return self._checkpoint_json(
            {
                "seq": seq,
                "merkle_root": root,
                "message": message,
                "signature": signature,
                "created_at": now,
            }
        )

    def checkpoint(self, force: bool = False) -> dict:
        with self.lock:
            with self.db.transaction() as conn:
                now = self.now()
                head = int(
                    conn.execute(
                        "SELECT COALESCE(MAX(seq), 0) AS seq FROM v3_messages"
                    ).fetchone()["seq"]
                )
                latest = conn.execute(
                    "SELECT * FROM v3_checkpoints ORDER BY id DESC LIMIT 1"
                ).fetchone()
                # The endpoint always covers the current head, so a caller
                # never receives a checkpoint older than the messages it just
                # read.  The periodic writer still uses the 20/60 s cadence.
                if latest and not force and int(latest["seq"]) >= head:
                    return self._checkpoint_json(dict(latest))
                return self._create_checkpoint(conn, now)

    def _checkpoint_json(self, row: dict) -> dict:
        root = bytes(row["merkle_root"])
        seq = int(row["seq"])
        message = bytes(row["message"]) if row.get("message") else v3c.checkpoint_payload(seq, root)
        return {
            "seq": seq,
            "root": root.hex(),
            "payload": message.hex(),
            "signature": _b64(bytes(row["signature"])),
            "pubkey": self.pubkey.hex(),
            "ledgerId": self.ledger_id_hex,
            "createdAt": int(row["created_at"]),
            "prefix": v3c.CHECKPOINT_PREFIX.decode("ascii"),
        }

    # --------------------------------------------------------------- results
    def _result_from_row(self, row: sqlite3.Row, duplicate: bool = False) -> dict:
        decoded = None
        try:
            decoded = json.loads(row["decoded_json"])
        except Exception:
            decoded = None
        receipt = bytes(row["receipt"]) if row["receipt"] else None
        status = v3c.STATUS_DUPLICATE if duplicate else int(row["status"])
        reason = v3c.REASON_NONE if duplicate else int(row["reason"])
        return self._result(
            msg_id=row["msg_id"],
            status=status,
            reason=reason,
            seq=int(row["seq"]),
            receipt=receipt,
            type_code=int(row["type"]),
            via=row["via"],
            retry=False,
            decoded=decoded,
            ledgerHash=bytes(row["ledger_hash"]).hex(),
            receivedAt=int(row["received_at"]),
            originalStatus=v3c.status_name(int(row["status"])),
            originalReason=v3c.reason_name(int(row["reason"])),
        )

    def _result(
        self,
        *,
        msg_id: str,
        status: int,
        reason: int,
        seq: int,
        receipt: bytes | None,
        type_code: int,
        via: str,
        retry: bool,
        decoded: dict | None = None,
        ledgerHash: str | None = None,
        receivedAt: int | None = None,
        error: str | None = None,
        **extra,
    ) -> dict:
        out = {
            "msgId": msg_id,
            "status": v3c.status_name(status),
            "statusCode": int(status),
            "reason": v3c.reason_name(reason),
            "reasonCode": int(reason),
            "seq": int(seq),
            "receipt": _b64(receipt),
            "type": v3c.type_name(type_code),
            "typeCode": int(type_code),
            "via": via,
            "retry": bool(retry),
        }
        if decoded is not None:
            out["decoded"] = decoded
        if ledgerHash is not None:
            out["ledgerHash"] = ledgerHash
        if receivedAt is not None:
            out["receivedAt"] = int(receivedAt)
        if error:
            out["error"] = error
        out.update(extra)
        return out

    # ------------------------------------------------------------- queries
    def message_json(self, row: dict) -> dict:
        decoded = None
        try:
            decoded = json.loads(row["decoded_json"])
        except Exception:
            decoded = None
        return {
            "seq": int(row["seq"]),
            "msgId": row["msg_id"],
            "type": v3c.type_name(int(row["type"])),
            "typeCode": int(row["type"]),
            "fromId": row["from_id"],
            "toId": row["to_id"],
            "ts": int(row["ts"]),
            "status": v3c.status_name(int(row["status"])),
            "statusCode": int(row["status"]),
            # The ledger hash is computed from the arrival status.  Pending
            # rows are resolved in place, so keep both values for verifiers.
            "arrivalStatus": v3c.status_name(int(row["ingest_status"])),
            "arrivalStatusCode": int(row["ingest_status"]),
            "reason": v3c.reason_name(int(row["reason"])),
            "reasonCode": int(row["reason"]),
            "via": row["via"],
            "receivedAt": int(row["received_at"]),
            "ledgerPrev": bytes(row["ledger_prev"]).hex(),
            "ledgerHash": bytes(row["ledger_hash"]).hex(),
            "decoded": decoded,
            "raw": _b64(bytes(row["raw"])),
            "receipt": _b64(bytes(row["receipt"])) if row["receipt"] else None,
        }

    def messages(
        self,
        *,
        after_seq: int = 0,
        limit: int = 100,
        type_code: int | None = None,
        actor: str | None = None,
        status: int | None = None,
        via: str | None = None,
    ) -> dict:
        rows = self.db.list_messages(
            after_seq=after_seq,
            limit=limit,
            type_code=type_code,
            actor=actor,
            status=status,
            via=via,
        )
        messages = [self.message_json(row) for row in rows]
        next_seq = messages[-1]["seq"] if messages else int(after_seq)
        return {"messages": messages, "nextAfterSeq": next_seq}

    def message(self, msg_id: str) -> dict | None:
        row = self.db.message_by_id(msg_id)
        return self.message_json(row) if row else None

    def listings(self, region: int | None, after_seq: int) -> dict:
        rows = self.db.open_listings(region, after_seq)
        out = []
        for row in rows:
            original = self.db.message_by_id(row["listing_id"])
            out.append(
                {
                    "listingId": row["listing_id"],
                    "sellerId": row["seller_id"],
                    "crop": int(row["crop"]),
                    "cropName": v3c.contracts()["cropNames"].get(int(row["crop"]), str(row["crop"])),
                    "qtyKg": int(row["qty_kg"]),
                    "remainingKg": int(row["remaining_kg"]),
                    "askPaise": int(row["ask_paise"]),
                    "region": int(row["region"]),
                    "validUntil": int(row["valid_until"]),
                    "grade": int(row["grade"]),
                    "batchId": row["batch_id"],
                    "createdSeq": int(row["created_seq"]),
                    "message": _b64(bytes(original["raw"])) if original else None,
                }
            )
        return {
            "listings": out,
            "nextAfterSeq": int(rows[-1]["created_seq"]) if rows else int(after_seq),
        }

    def pull_outbox(self, actors: list[str], after_id: int) -> dict:
        now = self.now()
        rows = self.db.pull_outbox(actors, after_id, now)
        items = [
            {
                "id": int(row["id"]),
                "toId": row["to_id"],
                "msgId": row["msg_id"],
                "kind": row["kind"],
                "createdSeq": int(row["created_seq"]),
                "createdAt": int(row["created_at"]),
                "message": _b64(bytes(row["msg"])),
            }
            for row in rows
        ]
        return {
            "items": items,
            "nextAfterId": int(rows[-1]["id"]) if rows else int(after_id),
        }

    def explorer_rows(
        self,
        *,
        status: int | None = None,
        type_code: int | None = None,
        via: str | None = None,
        limit: int = 100,
    ) -> list[dict]:
        rows = self.db.latest_messages(
            limit=limit,
            type_code=type_code,
            status=status,
            via=via,
        )
        return [self.message_json(row) for row in rows]


def msg_id_hex(msg: v3c.codec.Message) -> str:
    return v3c.codec.msg_id(msg).hex()
