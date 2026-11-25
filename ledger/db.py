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
    stream TEXT NOT NULL DEFAULT 'main',
    chain_seq INTEGER,
    received_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_batch_seq ON events(batch_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_batch_prev ON events(batch_id, prev_hash);
"""


class PrevMismatch(Exception):
    def __init__(
        self,
        head: str,
        actor_id: str = "",
        stream: str = "",
        chain_seq: int = 0,
    ) -> None:
        self.head = head
        self.actor_id = actor_id
        self.stream = stream
        self.chain_seq = chain_seq
        super().__init__("prevHash does not match the chain head")


class BatchNotFound(Exception):
    pass


class BatchExists(Exception):
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
            self._migrate(conn)
        finally:
            conn.close()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        return conn

    def _migrate(self, conn: sqlite3.Connection) -> None:
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(events)")}
        if "stream" not in columns:
            conn.execute("ALTER TABLE events ADD COLUMN stream TEXT NOT NULL DEFAULT 'main'")
        if "chain_seq" not in columns:
            conn.execute("ALTER TABLE events ADD COLUMN chain_seq INTEGER")
        if "received_at" not in columns:
            conn.execute("ALTER TABLE events ADD COLUMN received_at INTEGER")

        conn.execute(
            "UPDATE events SET stream = CASE WHEN type = 'GPS' THEN 'trace' ELSE 'main' END"
        )
        conn.execute(
            "UPDATE events SET received_at = created_at WHERE received_at IS NULL"
        )
        conn.execute(
            """
            WITH ranked AS (
                SELECT seq, ROW_NUMBER() OVER (
                    PARTITION BY batch_id, actor_id, stream ORDER BY seq
                ) AS n
                FROM events
            )
            UPDATE events
            SET chain_seq = (SELECT n FROM ranked WHERE ranked.seq = events.seq)
            WHERE chain_seq IS NULL
            """
        )
        conn.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_events_chain_seq
            ON events(batch_id, actor_id, stream, chain_seq)
            """
        )

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

    def event_by_id(self, event_id: str) -> dict | None:
        with self.connection() as conn:
            row = conn.execute("SELECT * FROM events WHERE event_id = ?", (event_id,)).fetchone()
        return dict(row) if row else None

    def chain_info(self, batch_id: str, actor_id: str, stream: str) -> tuple[str, int]:
        with self.connection() as conn:
            row = conn.execute(
                """
                SELECT hash, chain_seq FROM events
                WHERE batch_id = ? AND actor_id = ? AND stream = ?
                ORDER BY chain_seq DESC LIMIT 1
                """,
                (batch_id, actor_id, stream),
            ).fetchone()
        if row is None:
            return "", 0
        return row["hash"], int(row["chain_seq"])

    def append_event(
        self,
        event: dict,
        data_json: str,
        now: int,
        stream: str,
    ) -> tuple[int, str, int, int]:
        with self.transaction() as conn:
            existing = conn.execute(
                "SELECT seq, hash, chain_seq, received_at FROM events WHERE event_id = ?",
                (event["eventId"],),
            ).fetchone()
            if existing:
                if existing["hash"] == event["hash"]:
                    return (
                        existing["seq"],
                        existing["hash"],
                        int(existing["chain_seq"]),
                        int(existing["received_at"]),
                    )
                raise EventConflict("eventId already exists with a different hash")

            head_row = conn.execute(
                """
                SELECT hash, chain_seq FROM events
                WHERE batch_id = ? AND actor_id = ? AND stream = ?
                ORDER BY chain_seq DESC LIMIT 1
                """,
                (event["batchId"], event["actorId"], stream),
            ).fetchone()
            head = head_row["hash"] if head_row else ""
            chain_seq = int(head_row["chain_seq"]) + 1 if head_row else 1
            if event["prevHash"] != head:
                raise PrevMismatch(
                    head,
                    event["actorId"],
                    stream,
                    chain_seq - 1 if head_row else 0,
                )

            has_register = conn.execute(
                "SELECT 1 FROM events WHERE batch_id = ? AND type = 'REGISTER' LIMIT 1",
                (event["batchId"],),
            ).fetchone()
            if event["type"] == "REGISTER":
                if head_row or has_register:
                    raise BatchExists(event["batchId"])
            elif not has_register:
                raise BatchNotFound(event["batchId"])

            cursor = conn.execute(
                """
                INSERT INTO events
                    (event_id, batch_id, type, actor_id, ts, lat, lon, data_json,
                     prev_hash, hash, sig, stream, chain_seq, received_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    stream,
                    chain_seq,
                    now,
                    now,
                ),
            )
            return int(cursor.lastrowid), event["hash"], chain_seq, now

    def events_for_batch(self, batch_id: str) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM events WHERE batch_id = ? ORDER BY seq", (batch_id,)
            ).fetchall()
        return [dict(row) for row in rows]

    def events_for_chain(self, batch_id: str, actor_id: str, stream: str) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                """
                SELECT * FROM events
                WHERE batch_id = ? AND actor_id = ? AND stream = ?
                ORDER BY chain_seq
                """,
                (batch_id, actor_id, stream),
            ).fetchall()
        return [dict(row) for row in rows]

    def chains_for_batch(self, batch_id: str) -> list[dict]:
        rows = self.events_for_batch(batch_id)
        chains: dict[tuple[str, str], dict] = {}
        for row in rows:
            key = (row["actor_id"], row["stream"])
            chain = chains.setdefault(
                key,
                {
                    "actorId": row["actor_id"],
                    "stream": row["stream"],
                    "head": "",
                    "chainSeq": 0,
                    "count": 0,
                },
            )
            chain["head"] = row["hash"]
            chain["chainSeq"] = int(row["chain_seq"])
            chain["count"] += 1
        return list(chains.values())

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

    def recent_events(self, limit: int) -> list[dict]:
        with self.connection() as conn:
            rows = conn.execute(
                "SELECT * FROM events ORDER BY seq DESC LIMIT ?", (limit,)
            ).fetchall()
        return [dict(row) for row in rows]

    def event_count(self) -> int:
        with self.connection() as conn:
            row = conn.execute("SELECT COUNT(*) AS n FROM events").fetchone()
        return int(row["n"])
