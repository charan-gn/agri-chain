from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS actors (
    actor_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    pub_key TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    batch_id TEXT NOT NULL,
    type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    ts INTEGER NOT NULL,
    lat TEXT,
    lon TEXT,
    data_json TEXT NOT NULL,
    prev_hash TEXT NOT NULL,
    hash TEXT NOT NULL UNIQUE,
    sig TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_batch_seq ON events(batch_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_batch_prev ON events(batch_id, prev_hash);
"""


class PrevMismatch(Exception):
    def __init__(self, head: str) -> None:
        self.head = head
        super().__init__("prevHash does not match the batch head")


class BatchNotFound(Exception):
    pass


class EventConflict(Exception):
    pass


class LedgerDb:
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

    def get_actor(self, actor_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute("SELECT * FROM actors WHERE actor_id = ?", (actor_id,)).fetchone()
        return dict(row) if row else None

    def add_actor(self, actor_id: str, name: str, role: str, pub_key: str, now: int) -> tuple[bool, dict]:
        with self.transaction() as conn:
            row = conn.execute("SELECT * FROM actors WHERE actor_id = ?", (actor_id,)).fetchone()
            if row:
                return False, dict(row)
            conn.execute(
                "INSERT INTO actors (actor_id, name, role, pub_key, created_at) VALUES (?, ?, ?, ?, ?)",
                (actor_id, name, role, pub_key, now),
            )
            return True, {}

    def append_event(self, event: dict, data_json: str, now: int) -> tuple[int, str]:
        with self.transaction() as conn:
            existing = conn.execute(
                "SELECT seq, hash FROM events WHERE event_id = ?", (event["eventId"],)
            ).fetchone()
            if existing:
                if existing["hash"] == event["hash"]:
                    return existing["seq"], existing["hash"]
                raise EventConflict("eventId already exists with a different hash")

            head_row = conn.execute(
                "SELECT hash FROM events WHERE batch_id = ? ORDER BY seq DESC LIMIT 1",
                (event["batchId"],),
            ).fetchone()
            head = head_row["hash"] if head_row else ""

            if not head:
                genesis_allowed = event["type"] == "REGISTER" or (
                    event["batchId"] == "market" and event["type"] == "PRICE"
                )
                if not genesis_allowed:
                    raise BatchNotFound(event["batchId"])
                if event["prevHash"] != "":
                    raise PrevMismatch("")
            else:
                if event["type"] == "REGISTER":
                    raise PrevMismatch(head)
                if event["prevHash"] != head:
                    raise PrevMismatch(head)

            cursor = conn.execute(
                """
                INSERT INTO events
                    (event_id, batch_id, type, actor_id, ts, lat, lon, data_json,
                     prev_hash, hash, sig, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event["eventId"],
                    event["batchId"],
                    event["type"],
                    event["actorId"],
                    event["ts"],
                    event["lat"],
                    event["lon"],
                    data_json,
                    event["prevHash"],
                    event["hash"],
                    event["sig"],
                    now,
                ),
            )
            return int(cursor.lastrowid), event["hash"]

    def events_for_batch(self, batch_id: str) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM events WHERE batch_id = ? ORDER BY seq", (batch_id,)
            ).fetchall()
        return [dict(row) for row in rows]

    def register_batches(self) -> list[str]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT batch_id FROM events WHERE type = 'REGISTER' ORDER BY seq"
            ).fetchall()
        return [row["batch_id"] for row in rows]

    def all_actors(self) -> dict[str, dict]:
        with self.connection() as conn:
            rows = conn.execute("SELECT * FROM actors").fetchall()
        return {row["actor_id"]: dict(row) for row in rows}
