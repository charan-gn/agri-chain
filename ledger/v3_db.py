"""SQLite storage for the v3 ledger.

The v3 tables live beside the v2 tables in the same database file so the demo
can keep serving `/events` until T38 removes it.  Every v3 ingest runs in one
``BEGIN IMMEDIATE`` transaction and the caller serializes writers with a lock.
"""

from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS v3_actors (
    actor_id TEXT PRIMARY KEY,
    pubkey BLOB NOT NULL UNIQUE,
    role TEXT NOT NULL,
    name TEXT NOT NULL,
    phone TEXT,
    region INTEGER,
    status TEXT NOT NULL DEFAULT 'active',
    caps INTEGER NOT NULL DEFAULT 0,
    nick TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_messages (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    msg_id TEXT NOT NULL UNIQUE,
    raw BLOB NOT NULL,
    type INTEGER NOT NULL,
    from_id TEXT NOT NULL,
    to_id TEXT,
    ts INTEGER NOT NULL,
    status INTEGER NOT NULL,
    reason INTEGER NOT NULL,
    ingest_status INTEGER NOT NULL,
    via TEXT NOT NULL,
    received_at INTEGER NOT NULL,
    ledger_prev BLOB NOT NULL,
    ledger_hash BLOB NOT NULL,
    receipt BLOB,
    decoded_json TEXT NOT NULL,
    error TEXT
);
CREATE INDEX IF NOT EXISTS idx_v3_messages_status ON v3_messages(status);
CREATE INDEX IF NOT EXISTS idx_v3_messages_type ON v3_messages(type);
CREATE INDEX IF NOT EXISTS idx_v3_messages_from ON v3_messages(from_id);
CREATE INDEX IF NOT EXISTS idx_v3_messages_to ON v3_messages(to_id);
CREATE INDEX IF NOT EXISTS idx_v3_messages_received ON v3_messages(received_at);

CREATE TABLE IF NOT EXISTS v3_listings (
    listing_id TEXT PRIMARY KEY,
    seller_id TEXT NOT NULL,
    crop INTEGER NOT NULL,
    qty_kg INTEGER NOT NULL,
    remaining_kg INTEGER NOT NULL,
    ask_paise INTEGER NOT NULL,
    region INTEGER NOT NULL,
    valid_until INTEGER NOT NULL,
    grade INTEGER NOT NULL,
    batch_id TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_v3_listings_open ON v3_listings(region, status, created_seq);

CREATE TABLE IF NOT EXISTS v3_orders (
    order_id TEXT PRIMARY KEY,
    listing_id TEXT NOT NULL,
    buyer_id TEXT NOT NULL,
    seller_id TEXT NOT NULL,
    qty_kg INTEGER NOT NULL,
    bid_paise INTEGER NOT NULL,
    valid_until INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    accepted_seq INTEGER,
    delivered_seq INTEGER,
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_v3_orders_listing ON v3_orders(listing_id, status);
CREATE INDEX IF NOT EXISTS idx_v3_orders_buyer ON v3_orders(buyer_id, created_seq);
CREATE INDEX IF NOT EXISTS idx_v3_orders_seller ON v3_orders(seller_id, created_seq);

CREATE TABLE IF NOT EXISTS v3_trades (
    trade_id TEXT PRIMARY KEY,
    order_id TEXT NOT NULL,
    listing_id TEXT NOT NULL,
    seller_id TEXT NOT NULL,
    buyer_id TEXT NOT NULL,
    crop INTEGER NOT NULL,
    region INTEGER NOT NULL,
    qty_kg INTEGER NOT NULL,
    price_paise INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    ts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_v3_trades_region_crop ON v3_trades(region, crop, seq);

CREATE TABLE IF NOT EXISTS v3_batches (
    batch_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    crop INTEGER NOT NULL,
    weight_kg INTEGER NOT NULL,
    harvest_ts INTEGER NOT NULL,
    region INTEGER NOT NULL,
    grade INTEGER NOT NULL,
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_balances (
    actor_id TEXT NOT NULL,
    batch_id TEXT NOT NULL,
    kg INTEGER NOT NULL,
    PRIMARY KEY (actor_id, batch_id)
);

CREATE TABLE IF NOT EXISTS v3_bindings (
    batch_id TEXT PRIMARY KEY,
    box_id TEXT NOT NULL,
    bound_seq INTEGER NOT NULL,
    bound_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_tamper_alerts (
    msg_id TEXT PRIMARY KEY,
    batch_id TEXT NOT NULL,
    box_id TEXT NOT NULL,
    kind INTEGER NOT NULL,
    lat_micro INTEGER NOT NULL,
    lon_micro INTEGER NOT NULL,
    detected_ts INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    received_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_barter_offers (
    offer_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    give_crop INTEGER NOT NULL,
    give_kg INTEGER NOT NULL,
    want_crop INTEGER NOT NULL,
    want_kg INTEGER NOT NULL,
    region INTEGER NOT NULL,
    valid_until INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'open',
    accepted_by TEXT,
    accepted_seq INTEGER,
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_price_tables (
    region INTEGER NOT NULL,
    version INTEGER NOT NULL,
    part INTEGER NOT NULL,
    parts INTEGER NOT NULL,
    as_of INTEGER NOT NULL,
    body BLOB NOT NULL,
    message BLOB NOT NULL,
    msg_id TEXT NOT NULL,
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (region, version, part)
);

CREATE TABLE IF NOT EXISTS v3_price_beacons (
    region INTEGER NOT NULL,
    version INTEGER NOT NULL,
    message BLOB NOT NULL,
    msg_id TEXT NOT NULL,
    hash8 BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (region, version)
);

CREATE TABLE IF NOT EXISTS v3_price_versions (
    region INTEGER PRIMARY KEY,
    version INTEGER NOT NULL,
    as_of INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS v3_seed_prices (
    region INTEGER NOT NULL,
    crop INTEGER NOT NULL,
    price_paise INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (region, crop)
);

CREATE TABLE IF NOT EXISTS v3_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    to_id TEXT NOT NULL,
    msg BLOB NOT NULL,
    msg_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    created_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_v3_outbox_to ON v3_outbox(to_id, id);

CREATE TABLE IF NOT EXISTS v3_checkpoints (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    seq INTEGER NOT NULL,
    merkle_root BLOB NOT NULL,
    message BLOB NOT NULL,
    signature BLOB NOT NULL,
    created_at INTEGER NOT NULL
);
"""


class V3Db:
    def __init__(self, path: str) -> None:
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        conn = self._connect()
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(SCHEMA)
        finally:
            conn.close()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=30000")
        return conn

    @contextmanager
    def connection(self):
        conn = self._connect()
        try:
            yield conn
        finally:
            conn.close()

    @contextmanager
    def transaction(self):
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            yield conn
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
        finally:
            conn.close()

    # ---------------------------------------------------------------- actors
    def get_actor(self, actor_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_actors WHERE actor_id = ?", (actor_id,)
            ).fetchone()
        return dict(row) if row else None

    def get_actor_by_pubkey(self, pubkey: bytes) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_actors WHERE pubkey = ?", (pubkey,)
            ).fetchone()
        return dict(row) if row else None

    def upsert_actor(
        self,
        actor_id: str,
        pubkey: bytes,
        role: str,
        name: str,
        phone: str | None,
        region: int | None,
        now: int,
    ) -> tuple[bool, dict]:
        with self.transaction() as conn:
            row = conn.execute(
                "SELECT * FROM v3_actors WHERE actor_id = ?", (actor_id,)
            ).fetchone()
            if row:
                if bytes(row["pubkey"]) != bytes(pubkey):
                    return False, dict(row)
                conn.execute(
                    """UPDATE v3_actors
                       SET role = ?, name = ?, phone = ?, region = ?, updated_at = ?
                       WHERE actor_id = ?""",
                    (role, name, phone, region, now, actor_id),
                )
                updated = conn.execute(
                    "SELECT * FROM v3_actors WHERE actor_id = ?", (actor_id,)
                ).fetchone()
                return True, dict(updated)
            conn.execute(
                """INSERT INTO v3_actors
                   (actor_id, pubkey, role, name, phone, region, status, caps,
                    nick, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, 'active', 0, NULL, ?, ?)""",
                (actor_id, pubkey, role, name, phone, region, now, now),
            )
            return True, {}

    def update_actor_announce(
        self, actor_id: str, caps: int, nick: str, now: int
    ) -> None:
        with self.transaction() as conn:
            conn.execute(
                "UPDATE v3_actors SET caps = ?, nick = ?, updated_at = ? WHERE actor_id = ?",
                (int(caps), nick, now, actor_id),
            )

    # -------------------------------------------------------------- messages
    def next_seq(self, conn: sqlite3.Connection) -> int:
        row = conn.execute("SELECT COALESCE(MAX(seq), 0) + 1 AS seq FROM v3_messages").fetchone()
        return int(row["seq"])

    def last_message(self, conn: sqlite3.Connection) -> dict | None:
        row = conn.execute(
            "SELECT * FROM v3_messages ORDER BY seq DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None

    def message_by_id(self, msg_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_messages WHERE msg_id = ?", (msg_id,)
            ).fetchone()
        return dict(row) if row else None

    def insert_message(
        self,
        conn: sqlite3.Connection,
        *,
        seq: int,
        msg_id: str,
        raw: bytes,
        mtype: int,
        from_id: str,
        to_id: str | None,
        ts: int,
        status: int,
        reason: int,
        via: str,
        received_at: int,
        ledger_prev: bytes,
        ledger_hash: bytes,
        decoded_json: str,
        error: str | None = None,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_messages
               (seq, msg_id, raw, type, from_id, to_id, ts, status, reason,
                ingest_status, via, received_at, ledger_prev, ledger_hash,
                receipt, decoded_json, error)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)""",
            (
                seq,
                msg_id,
                raw,
                int(mtype),
                from_id,
                to_id,
                int(ts),
                int(status),
                int(reason),
                int(status),
                via,
                int(received_at),
                ledger_prev,
                ledger_hash,
                decoded_json,
                error,
            ),
        )

    def set_message_receipt(
        self, conn: sqlite3.Connection, msg_id: str, receipt: bytes
    ) -> None:
        conn.execute(
            "UPDATE v3_messages SET receipt = ? WHERE msg_id = ?",
            (receipt, msg_id),
        )

    def resolve_message(
        self,
        conn: sqlite3.Connection,
        msg_id: str,
        status: int,
        reason: int,
        receipt: bytes,
    ) -> None:
        conn.execute(
            "UPDATE v3_messages SET status = ?, reason = ?, receipt = ? WHERE msg_id = ?",
            (int(status), int(reason), receipt, msg_id),
        )

    def list_messages(
        self,
        *,
        after_seq: int = 0,
        limit: int = 100,
        type_code: int | None = None,
        actor: str | None = None,
        status: int | None = None,
        via: str | None = None,
        before_seq: int | None = None,
    ) -> list[dict]:
        clauses = ["seq > ?"]
        params: list[object] = [int(after_seq)]
        if before_seq is not None:
            clauses.append("seq < ?")
            params.append(int(before_seq))
        if type_code is not None:
            clauses.append("type = ?")
            params.append(int(type_code))
        if status is not None:
            clauses.append("status = ?")
            params.append(int(status))
        if via:
            clauses.append("via = ?")
            params.append(via)
        if actor:
            clauses.append("(from_id = ? OR to_id = ?)")
            params.extend([actor, actor])
        params.append(max(1, min(int(limit), 500)))
        sql = (
            "SELECT * FROM v3_messages WHERE "
            + " AND ".join(clauses)
            + " ORDER BY seq ASC LIMIT ?"
        )
        with self.connection() as conn:
            return [dict(row) for row in conn.execute(sql, params)]

    def latest_messages(
        self,
        *,
        limit: int = 100,
        type_code: int | None = None,
        actor: str | None = None,
        status: int | None = None,
        via: str | None = None,
    ) -> list[dict]:
        clauses = ["1 = 1"]
        params: list[object] = []
        if type_code is not None:
            clauses.append("type = ?")
            params.append(int(type_code))
        if status is not None:
            clauses.append("status = ?")
            params.append(int(status))
        if via:
            clauses.append("via = ?")
            params.append(via)
        if actor:
            clauses.append("(from_id = ? OR to_id = ?)")
            params.extend([actor, actor])
        params.append(max(1, min(int(limit), 500)))
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM v3_messages WHERE "
                + " AND ".join(clauses)
                + " ORDER BY seq DESC LIMIT ?",
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    def pending_messages(self, conn: sqlite3.Connection) -> list[dict]:
        rows = conn.execute(
            "SELECT * FROM v3_messages WHERE status = 3 ORDER BY seq ASC"
        ).fetchall()
        return [dict(row) for row in rows]

    def ledger_hashes(self) -> list[bytes]:
        with self.connection() as conn:
            return [
                bytes(row["ledger_hash"])
                for row in conn.execute(
                    "SELECT ledger_hash FROM v3_messages ORDER BY seq ASC"
                )
            ]

    def message_count(self) -> int:
        with self.connection() as conn:
            return int(
                conn.execute("SELECT COUNT(*) AS n FROM v3_messages").fetchone()["n"]
            )

    def max_seq(self) -> int:
        with self.connection() as conn:
            row = conn.execute("SELECT COALESCE(MAX(seq), 0) AS seq FROM v3_messages").fetchone()
        return int(row["seq"])

    # ---------------------------------------------------------------- outbox
    def add_outbox(
        self,
        conn: sqlite3.Connection,
        *,
        to_id: str,
        msg: bytes,
        msg_id: str,
        kind: str,
        created_seq: int,
        created_at: int,
    ) -> int:
        cur = conn.execute(
            """INSERT INTO v3_outbox
               (to_id, msg, msg_id, kind, created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (to_id, msg, msg_id, kind, int(created_seq), int(created_at)),
        )
        return int(cur.lastrowid)

    def pull_outbox(
        self,
        actors: Iterable[str],
        after_id: int,
        now: int,
        max_age: int = 72 * 3600,
    ) -> list[dict]:
        actor_list = [str(actor) for actor in actors if actor]
        if not actor_list:
            return []
        placeholders = ",".join("?" for _ in actor_list)
        with self.connection() as conn:
            rows = conn.execute(
                f"""SELECT * FROM v3_outbox
                    WHERE to_id IN ({placeholders}) AND id > ? AND created_at >= ?
                    ORDER BY id ASC LIMIT 500""",
                (*actor_list, int(after_id), int(now) - int(max_age)),
            ).fetchall()
            last_sent: dict[str, int] = {}
            for actor in actor_list:
                row = conn.execute(
                    "SELECT MAX(received_at) AS ts FROM v3_messages WHERE from_id = ?",
                    (actor,),
                ).fetchone()
                last_sent[actor] = int(row["ts"] or 0)
        # "Items stay re-pullable for 72 h unless the recipient has since sent
        # any message" - skip stale items once the actor is active again.
        return [
            dict(row)
            for row in rows
            if int(row["created_at"]) >= last_sent.get(row["to_id"], 0)
        ]

    def outbox_count(self, to_id: str | None = None) -> int:
        with self.connection() as conn:
            if to_id:
                row = conn.execute(
                    "SELECT COUNT(*) AS n FROM v3_outbox WHERE to_id = ?", (to_id,)
                ).fetchone()
            else:
                row = conn.execute("SELECT COUNT(*) AS n FROM v3_outbox").fetchone()
        return int(row["n"])

    # --------------------------------------------------------------- listings
    def get_listing(self, listing_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_listings WHERE listing_id = ?", (listing_id,)
            ).fetchone()
        return dict(row) if row else None

    def insert_listing(
        self,
        conn: sqlite3.Connection,
        *,
        listing_id: str,
        seller_id: str,
        crop: int,
        qty_kg: int,
        ask_paise: int,
        region: int,
        valid_until: int,
        grade: int,
        batch_id: str | None,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_listings
               (listing_id, seller_id, crop, qty_kg, remaining_kg, ask_paise,
                region, valid_until, grade, batch_id, status, created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)""",
            (
                listing_id,
                seller_id,
                int(crop),
                int(qty_kg),
                int(qty_kg),
                int(ask_paise),
                int(region),
                int(valid_until),
                int(grade),
                batch_id,
                int(seq),
                int(now),
            ),
        )

    def set_listing_remaining(
        self, conn: sqlite3.Connection, listing_id: str, remaining: int, status: str
    ) -> None:
        conn.execute(
            "UPDATE v3_listings SET remaining_kg = ?, status = ? WHERE listing_id = ?",
            (int(remaining), status, listing_id),
        )

    def open_listings(
        self, region: int | None, after_seq: int, limit: int = 200
    ) -> list[dict]:
        clauses = ["status = 'open'", "created_seq > ?"]
        params: list[object] = [int(after_seq)]
        if region is not None:
            clauses.append("region = ?")
            params.append(int(region))
        params.append(max(1, min(int(limit), 500)))
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM v3_listings WHERE "
                + " AND ".join(clauses)
                + " ORDER BY created_seq ASC LIMIT ?",
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    # ----------------------------------------------------------------- orders
    def get_order(self, order_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_orders WHERE order_id = ?", (order_id,)
            ).fetchone()
        return dict(row) if row else None

    def insert_order(
        self,
        conn: sqlite3.Connection,
        *,
        order_id: str,
        listing_id: str,
        buyer_id: str,
        seller_id: str,
        qty_kg: int,
        bid_paise: int,
        valid_until: int,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_orders
               (order_id, listing_id, buyer_id, seller_id, qty_kg, bid_paise,
                valid_until, status, created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)""",
            (
                order_id,
                listing_id,
                buyer_id,
                seller_id,
                int(qty_kg),
                int(bid_paise),
                int(valid_until),
                int(seq),
                int(now),
            ),
        )

    def set_order_status(
        self,
        conn: sqlite3.Connection,
        order_id: str,
        status: str,
        seq: int,
        *,
        accepted: bool = False,
        delivered: bool = False,
    ) -> None:
        fields = ["status = ?"]
        params: list[object] = [status]
        if accepted:
            fields.append("accepted_seq = ?")
            params.append(int(seq))
        if delivered:
            fields.append("delivered_seq = ?")
            params.append(int(seq))
        params.append(order_id)
        conn.execute(
            f"UPDATE v3_orders SET {', '.join(fields)} WHERE order_id = ?", params
        )

    # ----------------------------------------------------------------- trades
    def insert_trade(
        self,
        conn: sqlite3.Connection,
        *,
        trade_id: str,
        order_id: str,
        listing_id: str,
        seller_id: str,
        buyer_id: str,
        crop: int,
        region: int,
        qty_kg: int,
        price_paise: int,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_trades
               (trade_id, order_id, listing_id, seller_id, buyer_id, crop,
                region, qty_kg, price_paise, seq, ts)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                trade_id,
                order_id,
                listing_id,
                seller_id,
                buyer_id,
                int(crop),
                int(region),
                int(qty_kg),
                int(price_paise),
                int(seq),
                int(now),
            ),
        )

    def recent_trades(self, region: int, crop: int, since: int) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                """SELECT * FROM v3_trades
                   WHERE region = ? AND crop = ? AND ts >= ?
                   ORDER BY seq DESC""",
                (int(region), int(crop), int(since)),
            ).fetchall()
        return [dict(row) for row in rows]

    def region_crops(self, region: int) -> set[int]:
        with self.connection() as conn:
            rows = conn.execute(
                """SELECT DISTINCT crop FROM v3_trades WHERE region = ?
                   UNION SELECT crop FROM v3_seed_prices WHERE region = ?""",
                (int(region), int(region)),
            ).fetchall()
        return {int(row["crop"]) for row in rows}

    # ---------------------------------------------------------------- batches
    def get_batch(self, batch_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_batches WHERE batch_id = ?", (batch_id,)
            ).fetchone()
        return dict(row) if row else None

    def insert_batch(
        self,
        conn: sqlite3.Connection,
        *,
        batch_id: str,
        owner_id: str,
        crop: int,
        weight_kg: int,
        harvest_ts: int,
        region: int,
        grade: int,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_batches
               (batch_id, owner_id, crop, weight_kg, harvest_ts, region, grade,
                created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                batch_id,
                owner_id,
                int(crop),
                int(weight_kg),
                int(harvest_ts),
                int(region),
                int(grade),
                int(seq),
                int(now),
            ),
        )
        conn.execute(
            """INSERT INTO v3_balances (actor_id, batch_id, kg)
               VALUES (?, ?, ?)
               ON CONFLICT(actor_id, batch_id) DO UPDATE SET kg = kg + excluded.kg""",
            (owner_id, batch_id, int(weight_kg)),
        )

    def balance(self, actor_id: str, batch_id: str) -> int:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT kg FROM v3_balances WHERE actor_id = ? AND batch_id = ?",
                (actor_id, batch_id),
            ).fetchone()
        return int(row["kg"]) if row else 0

    def move_balance(
        self,
        conn: sqlite3.Connection,
        *,
        batch_id: str,
        from_id: str,
        to_id: str,
        kg: int,
    ) -> bool:
        row = conn.execute(
            "SELECT kg FROM v3_balances WHERE actor_id = ? AND batch_id = ?",
            (from_id, batch_id),
        ).fetchone()
        available = int(row["kg"]) if row else 0
        if available < int(kg):
            return False
        conn.execute(
            "UPDATE v3_balances SET kg = kg - ? WHERE actor_id = ? AND batch_id = ?",
            (int(kg), from_id, batch_id),
        )
        conn.execute(
            """INSERT INTO v3_balances (actor_id, batch_id, kg) VALUES (?, ?, ?)
               ON CONFLICT(actor_id, batch_id) DO UPDATE SET kg = kg + excluded.kg""",
            (to_id, batch_id, int(kg)),
        )
        return True

    def get_binding(self, batch_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_bindings WHERE batch_id = ?", (batch_id,)
            ).fetchone()
        return dict(row) if row else None

    def upsert_binding(
        self, conn: sqlite3.Connection, batch_id: str, box_id: str, seq: int, now: int
    ) -> None:
        conn.execute(
            """INSERT INTO v3_bindings (batch_id, box_id, bound_seq, bound_at)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(batch_id) DO UPDATE SET
                 box_id = excluded.box_id,
                 bound_seq = excluded.bound_seq,
                 bound_at = excluded.bound_at""",
            (batch_id, box_id, int(seq), int(now)),
        )

    def insert_tamper(
        self,
        conn: sqlite3.Connection,
        *,
        msg_id: str,
        batch_id: str,
        box_id: str,
        kind: int,
        lat_micro: int,
        lon_micro: int,
        detected_ts: int,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT OR IGNORE INTO v3_tamper_alerts
               (msg_id, batch_id, box_id, kind, lat_micro, lon_micro,
                detected_ts, seq, received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                msg_id,
                batch_id,
                box_id,
                int(kind),
                int(lat_micro),
                int(lon_micro),
                int(detected_ts),
                int(seq),
                int(now),
            ),
        )

    # ----------------------------------------------------------------- barter
    def get_barter_offer(self, offer_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_barter_offers WHERE offer_id = ?", (offer_id,)
            ).fetchone()
        return dict(row) if row else None

    def insert_barter_offer(
        self,
        conn: sqlite3.Connection,
        *,
        offer_id: str,
        owner_id: str,
        give_crop: int,
        give_kg: int,
        want_crop: int,
        want_kg: int,
        region: int,
        valid_until: int,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_barter_offers
               (offer_id, owner_id, give_crop, give_kg, want_crop, want_kg,
                region, valid_until, status, created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)""",
            (
                offer_id,
                owner_id,
                int(give_crop),
                int(give_kg),
                int(want_crop),
                int(want_kg),
                int(region),
                int(valid_until),
                int(seq),
                int(now),
            ),
        )

    def accept_barter_offer(
        self,
        conn: sqlite3.Connection,
        offer_id: str,
        accepted_by: str,
        seq: int,
    ) -> None:
        conn.execute(
            """UPDATE v3_barter_offers
               SET status = 'accepted', accepted_by = ?, accepted_seq = ?
               WHERE offer_id = ?""",
            (accepted_by, int(seq), offer_id),
        )

    # ----------------------------------------------------------------- prices
    def get_price_version(self, region: int) -> tuple[int, int]:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT version, as_of FROM v3_price_versions WHERE region = ?",
                (int(region),),
            ).fetchone()
        if not row:
            return 0, 0
        return int(row["version"]), int(row["as_of"])

    def price_versions(self) -> dict[int, int]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT region, version FROM v3_price_versions ORDER BY region"
            ).fetchall()
        return {int(row["region"]): int(row["version"]) for row in rows}

    def set_price_version(
        self, conn: sqlite3.Connection, region: int, version: int, as_of: int
    ) -> None:
        conn.execute(
            """INSERT INTO v3_price_versions (region, version, as_of)
               VALUES (?, ?, ?)
               ON CONFLICT(region) DO UPDATE SET
                 version = excluded.version, as_of = excluded.as_of""",
            (int(region), int(version), int(as_of)),
        )

    def current_price_rows(self, region: int) -> dict[int, tuple[int, int]]:
        version, _ = self.get_price_version(region)
        if version <= 0:
            return {}
        with self.connection() as conn:
            rows = conn.execute(
                """SELECT body FROM v3_price_tables
                   WHERE region = ? AND version = ? ORDER BY part ASC""",
                (int(region), int(version)),
            ).fetchall()
        out: dict[int, tuple[int, int]] = {}
        for row in rows:
            try:
                body = bytes(row["body"])
                parsed = _parse_price_body(body)
            except Exception:
                continue
            for crop, price, trades in parsed["rows"]:
                out[int(crop)] = (int(price), int(trades))
        return out

    def insert_price_table(
        self,
        conn: sqlite3.Connection,
        *,
        region: int,
        version: int,
        part: int,
        parts: int,
        as_of: int,
        body: bytes,
        message: bytes,
        msg_id: str,
        seq: int,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_price_tables
               (region, version, part, parts, as_of, body, message, msg_id,
                created_seq, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                int(region),
                int(version),
                int(part),
                int(parts),
                int(as_of),
                body,
                message,
                msg_id,
                int(seq),
                int(now),
            ),
        )

    def price_tables(
        self, region: int | None, since_version: int
    ) -> list[dict]:
        clauses = ["version > ?"]
        params: list[object] = [int(since_version)]
        if region is not None:
            clauses.append("region = ?")
            params.append(int(region))
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM v3_price_tables WHERE "
                + " AND ".join(clauses)
                + " ORDER BY region ASC, version ASC, part ASC",
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    def insert_price_beacon(
        self,
        conn: sqlite3.Connection,
        *,
        region: int,
        version: int,
        message: bytes,
        msg_id: str,
        hash8: bytes,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT OR REPLACE INTO v3_price_beacons
               (region, version, message, msg_id, hash8, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (int(region), int(version), message, msg_id, hash8, int(now)),
        )

    def price_beacons(self, region: int | None, since_version: int) -> list[dict]:
        # Include the current beacon even when the caller already has that
        # version: the gateway re-injects beacons every five minutes.
        clauses = ["version >= ?"]
        params: list[object] = [int(since_version)]
        if region is not None:
            clauses.append("region = ?")
            params.append(int(region))
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM v3_price_beacons WHERE "
                + " AND ".join(clauses)
                + " ORDER BY region ASC, version ASC",
                params,
            ).fetchall()
        return [dict(row) for row in rows]

    def set_seed_price(
        self, conn: sqlite3.Connection, region: int, crop: int, price: int, now: int
    ) -> None:
        conn.execute(
            """INSERT INTO v3_seed_prices (region, crop, price_paise, updated_at)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(region, crop) DO UPDATE SET
                 price_paise = excluded.price_paise,
                 updated_at = excluded.updated_at""",
            (int(region), int(crop), int(price), int(now)),
        )

    def seed_prices(self, region: int | None = None) -> list[dict]:
        with self.connection() as conn:
            if region is None:
                rows = conn.execute(
                    "SELECT * FROM v3_seed_prices ORDER BY region, crop"
                ).fetchall()
            else:
                rows = conn.execute(
                    "SELECT * FROM v3_seed_prices WHERE region = ? ORDER BY crop",
                    (int(region),),
                ).fetchall()
        return [dict(row) for row in rows]

    # ------------------------------------------------------------ checkpoints
    def insert_checkpoint(
        self,
        conn: sqlite3.Connection,
        *,
        seq: int,
        root: bytes,
        message: bytes,
        signature: bytes,
        now: int,
    ) -> None:
        conn.execute(
            """INSERT INTO v3_checkpoints
               (seq, merkle_root, message, signature, created_at)
               VALUES (?, ?, ?, ?, ?)""",
            (int(seq), root, message, signature, int(now)),
        )

    def latest_checkpoint(self) -> dict | None:
        with self.connection() as conn:
            row = conn.execute(
                "SELECT * FROM v3_checkpoints ORDER BY id DESC LIMIT 1"
            ).fetchone()
        return dict(row) if row else None

    def checkpoint_count(self) -> int:
        with self.connection() as conn:
            return int(
                conn.execute("SELECT COUNT(*) AS n FROM v3_checkpoints").fetchone()["n"]
            )

    # ------------------------------------------------------------------ stats
    def stats(self) -> dict:
        with self.connection() as conn:
            def one(sql: str) -> int:
                return int(conn.execute(sql).fetchone()[0])

            return {
                "actors": one("SELECT COUNT(*) FROM v3_actors"),
                "messages": one("SELECT COUNT(*) FROM v3_messages"),
                "accepted": one("SELECT COUNT(*) FROM v3_messages WHERE status = 0"),
                "rejected": one("SELECT COUNT(*) FROM v3_messages WHERE status = 2"),
                "pending": one("SELECT COUNT(*) FROM v3_messages WHERE status = 3"),
                "listings": one("SELECT COUNT(*) FROM v3_listings"),
                "openListings": one(
                    "SELECT COUNT(*) FROM v3_listings WHERE status = 'open'"
                ),
                "orders": one("SELECT COUNT(*) FROM v3_orders"),
                "trades": one("SELECT COUNT(*) FROM v3_trades"),
                "outbox": one("SELECT COUNT(*) FROM v3_outbox"),
                "checkpoints": one("SELECT COUNT(*) FROM v3_checkpoints"),
            }


def _parse_price_body(body: bytes) -> dict:
    import v3_codec

    return v3_codec.parse_body(v3_codec.MessageType.PRICE_TABLE, body)
