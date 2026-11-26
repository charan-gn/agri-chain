"""Custodial SMS agent for feature phones (T39).

A farmer or buyer registers a phone number and a PIN once. The agent derives a
per-phone P-256 key from a master key with HKDF, registers the actor on the
ledger, and signs LISTING/ORDER messages on the user's behalf. Private keys are
never stored and PINs are stored as PBKDF2 hashes.

Commands (160-character SMS):

    REGISTER farmer 1234
    AUTH 1234
    PRICE wheat [region]
    LISTINGS wheat [region]
    LIST wheat 100 2500 [region]
    BUY <listingId-prefix> 50
    BALANCE
    MYLISTINGS
    MYORDERS
    HELP

Commands other than REGISTER/HELP/AUTH need the PIN appended (`... PIN 1234`)
unless the phone authenticated in the last 15 minutes.

Run the HTTP bridge (the Android SMS gateway posts `{"phone","text"}` to it)::

    python gateway/sms_agent.py --url http://127.0.0.1:8000 \
        --admin-token "$AGRICHAIN_ADMIN_TOKEN" --port 8099
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Iterable

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from contracts.ref import codec as v3c

P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
PIN_MIN = 4
PIN_MAX = 8
SESSION_SECONDS = 15 * 60
MAX_REPLY = 160
TTL = 7
DAY = 24 * 60 * 60


def crop_ids() -> dict[str, int]:
    path = Path(__file__).resolve().parents[1] / "contracts" / "crops.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    return {str(item["name"]).lower(): int(item["id"]) for item in data["crops"]}


def crop_name(crop: int) -> str:
    path = Path(__file__).resolve().parents[1] / "contracts" / "crops.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    for item in data["crops"]:
        if int(item["id"]) == crop:
            return str(item["name"])
    return f"crop {crop}"


def region_ids() -> dict[str, int]:
    path = Path(__file__).resolve().parents[1] / "contracts" / "regions.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    return {str(item["name"]).lower(): int(item["id"]) for item in data["regions"]}


def money(paise: int) -> str:
    return f"{paise // 100}.{paise % 100:02d}"


def hash_pin(pin: str, salt: bytes | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, 120_000)
    return salt.hex(), digest.hex()


def verify_pin(pin: str, salt_hex: str, digest_hex: str) -> bool:
    salt = bytes.fromhex(salt_hex)
    expected = bytes.fromhex(digest_hex)
    actual = hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, 120_000)
    return hmac.compare_digest(actual, expected)


def load_or_create_master(path: str | os.PathLike[str]) -> bytes:
    target = Path(path)
    if target.exists():
        data = target.read_bytes()
        if len(data) != 32:
            raise SystemExit(f"{target} must hold exactly 32 bytes")
        return data
    target.parent.mkdir(parents=True, exist_ok=True)
    data = secrets.token_bytes(32)
    target.write_bytes(data)
    try:
        os.chmod(target, 0o600)
    except OSError:  # pragma: no cover - Windows
        pass
    return data


def derive_key(master: bytes, phone: str) -> ec.EllipticCurvePrivateKey:
    material = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=phone.encode("utf-8"),
        info=b"agri-chain-sms-agent-v1",
    ).derive(master)
    scalar = int.from_bytes(material, "big") % (P256_ORDER - 1) + 1
    return ec.derive_private_key(scalar, ec.SECP256R1())


class LedgerError(RuntimeError):
    pass


class HttpLedger:
    """Small v3 ledger client used by the agent."""

    def __init__(self, base_url: str, admin_token: str = "", timeout: float = 15.0) -> None:
        self.base = base_url.rstrip("/")
        self.admin_token = admin_token
        self.timeout = timeout

    def _request(
        self,
        method: str,
        path: str,
        payload: dict | None = None,
        admin: bool = False,
    ) -> dict:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={"Accept": "application/json"},
        )
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if admin and self.admin_token:
            request.add_header("X-Admin-Token", self.admin_token)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise LedgerError(f"{method} {path} -> {error.code}: {body[:300]}") from error
        except urllib.error.URLError as error:
            raise LedgerError(f"{method} {path} failed: {error.reason}") from error

    def register_actor(
        self,
        pubkey_hex: str,
        role: str,
        name: str,
        phone: str,
        region: int,
    ) -> dict:
        return self._request(
            "POST",
            "/v3/actors",
            {
                "pubkey": pubkey_hex,
                "role": role,
                "name": name,
                "phone": phone,
                "region": region,
            },
            admin=True,
        )

    def post_messages(self, messages: Iterable[bytes]) -> dict:
        return self._request(
            "POST",
            "/v3/messages",
            {
                "via": "sms",
                "messages": [base64.b64encode(item).decode("ascii") for item in messages],
            },
        )

    def prices(self, region: int) -> dict:
        return self._request("GET", f"/v3/prices?region={region}&sinceVersion=0")

    def listings(self, region: int | None = None) -> dict:
        suffix = f"?region={region}" if region else ""
        return self._request("GET", f"/v3/listings{suffix}")

    def orders(self, actor: str) -> dict:
        return self._request("GET", f"/v3/orders?actor={actor}")


@dataclass
class User:
    phone: str
    role: str
    actor_id: str
    pubkey: str
    salt: str
    pin_hash: str
    region: int


class SmsAgent:
    """Stateless command handler with a small SQLite user table."""

    def __init__(
        self,
        db_path: str | os.PathLike[str],
        master_key: bytes,
        ledger,
        clock: Callable[[], float] = time.time,
        session_seconds: int = SESSION_SECONDS,
    ) -> None:
        self.db_path = str(db_path)
        self.master_key = master_key
        self.ledger = ledger
        self.clock = clock
        self.session_seconds = session_seconds
        self.sessions: dict[str, float] = {}
        self._crops = crop_ids()
        self._regions = region_ids()
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def _init_db(self) -> None:
        with self._connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS sms_users (
                    phone TEXT PRIMARY KEY,
                    role TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    pubkey TEXT NOT NULL,
                    salt TEXT NOT NULL,
                    pin_hash TEXT NOT NULL,
                    region INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """
            )

    def user(self, phone: str) -> User | None:
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM sms_users WHERE phone = ?", (phone,)
            ).fetchone()
        if row is None:
            return None
        return User(
            phone=row["phone"],
            role=row["role"],
            actor_id=row["actor_id"],
            pubkey=row["pubkey"],
            salt=row["salt"],
            pin_hash=row["pin_hash"],
            region=int(row["region"]),
        )

    # ---- command entry point -------------------------------------------

    def handle(self, phone: str, text: str) -> str:
        phone = self._normalise_phone(phone)
        words = text.strip().split()
        if not phone or not words:
            return "Empty message"
        command = words[0].upper()
        if command == "HELP":
            return self._help()
        if command == "REGISTER":
            return self._register(phone, words[1:])
        user = self.user(phone)
        if user is None:
            return "Not registered. Send: REGISTER farmer <pin>"

        words, pin_error = self._authenticate(phone, user, words)
        if pin_error:
            return pin_error
        command = words[0].upper()
        try:
            if command == "AUTH":
                return "PIN accepted for 15 minutes"
            if command == "PRICE":
                return self._price(user, words[1:])
            if command == "LISTINGS":
                return self._listings(user, words[1:])
            if command == "LIST":
                return self._list(user, words[1:])
            if command == "BUY":
                return self._buy(user, words[1:])
            if command == "BALANCE":
                return self._balance(user)
            if command == "MYLISTINGS":
                return self._my_listings(user)
            if command == "MYORDERS":
                return self._my_orders(user)
        except LedgerError:
            return "Ledger unreachable, try later"
        return "Unknown command. Send HELP"

    # ---- registration/auth --------------------------------------------

    def _register(self, phone: str, words: list[str]) -> str:
        if len(words) != 2:
            return "Send: REGISTER farmer|buyer <4-8 digit pin>"
        role = words[0].lower()
        pin = words[1]
        if role not in {"farmer", "buyer"}:
            return "Role must be farmer or buyer"
        if not pin.isdigit() or not PIN_MIN <= len(pin) <= PIN_MAX:
            return f"PIN must be {PIN_MIN}-{PIN_MAX} digits"
        if self.user(phone) is not None:
            return "Phone already registered"
        key = derive_key(self.master_key, phone)
        pubkey = v3c.compressed_public_key(key)
        actor_id = v3c.actor_id_hex(pubkey)
        name = f"{role}-{phone[-4:]}"
        try:
            self.ledger.register_actor(pubkey.hex(), role, name, phone, 1)
        except LedgerError as error:
            if "409" in str(error):
                return "Registration conflict, contact support"
            return "Ledger unreachable, try later"
        salt, digest = hash_pin(pin)
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO sms_users VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (phone, role, actor_id, pubkey.hex(), salt, digest, 1, int(self.clock())),
            )
        self.sessions[phone] = self.clock() + self.session_seconds
        return f"Registered {role} {actor_id[:8]}. Send HELP"

    def _authenticate(
        self,
        phone: str,
        user: User,
        words: list[str],
    ) -> tuple[list[str], str | None]:
        if len(words) >= 3 and words[-2].upper() == "PIN":
            pin = words[-1]
            words = words[:-2]
            if not verify_pin(pin, user.salt, user.pin_hash):
                return words, "Wrong PIN"
            self.sessions[phone] = self.clock() + self.session_seconds
            return words, None
        expires = self.sessions.get(phone, 0)
        if expires >= self.clock():
            return words, None
        return words, "PIN required. AUTH <pin> or add PIN <pin>"

    # ---- commands -------------------------------------------------------

    def _help(self) -> str:
        return (
            "REGISTER farmer|buyer PIN | AUTH PIN | PRICE crop [region] | "
            "LISTINGS crop [region] | LIST crop kg paise [region] | "
            "BUY listingId kg | BALANCE | MYLISTINGS | MYORDERS"
        )[:MAX_REPLY]

    def _crop(self, value: str) -> int | None:
        if value.isdigit():
            crop = int(value)
            return crop if crop_name(crop) != f"crop {crop}" else None
        return self._crops.get(value.lower())

    def _region(self, value: str | None, default: int) -> int:
        if value is None:
            return default
        if value.isdigit():
            return int(value)
        return self._regions.get(value.lower(), default)

    def _price(self, user: User, words: list[str]) -> str:
        if not words:
            return "Send: PRICE crop [region]"
        crop = self._crop(words[0])
        if crop is None:
            return "Unknown crop"
        region = self._region(words[1] if len(words) > 1 else None, user.region)
        feed = self.ledger.prices(region)
        tables = [item for item in feed.get("prices", []) if int(item["region"]) == region]
        if not tables:
            return f"No price for {crop_name(crop)} in region {region} yet"
        latest = max(int(item["version"]) for item in tables)
        rows = [
            row
            for item in tables
            if int(item["version"]) == latest
            for row in item.get("rows", [])
        ]
        row = next((item for item in rows if int(item["crop"]) == crop), None)
        if row is None:
            return f"No price for {crop_name(crop)} in region {region} yet"
        return (
            f"{crop_name(crop)} {money(int(row['pricePaise']))}/kg "
            f"({row['trades']} trades, v{latest})"
        )[:MAX_REPLY]

    def _listings(self, user: User, words: list[str]) -> str:
        if not words:
            return "Send: LISTINGS crop [region]"
        crop = self._crop(words[0])
        if crop is None:
            return "Unknown crop"
        region = self._region(words[1] if len(words) > 1 else None, user.region)
        feed = self.ledger.listings(region)
        open_items = [
            item
            for item in feed.get("listings", [])
            if int(item["crop"]) == crop
            and int(item["region"]) == region
            and int(item["remainingKg"]) > 0
        ]
        open_items.sort(key=lambda item: int(item["askPaise"]))
        if not open_items:
            return f"No open {crop_name(crop)} in region {region}"
        parts = [
            f"{item['listingId'][:6]} {item['remainingKg']}kg @{money(int(item['askPaise']))}"
            for item in open_items[:2]
        ]
        return f"{crop_name(crop)}: " + "; ".join(parts)

    def _list(self, user: User, words: list[str]) -> str:
        if user.role != "farmer":
            return "Only farmer accounts can list"
        if len(words) < 3:
            return "Send: LIST crop kg paise [region]"
        crop = self._crop(words[0])
        if crop is None:
            return "Unknown crop"
        try:
            qty = int(words[1])
            ask = int(words[2])
        except ValueError:
            return "kg and paise must be numbers"
        region = self._region(words[3] if len(words) > 3 else None, user.region)
        if qty <= 0 or ask <= 0:
            return "kg and paise must be positive"
        now = int(self.clock())
        valid_until = now + 7 * DAY
        key = derive_key(self.master_key, user.phone)
        body = v3c.listing_body(crop, qty, ask, region, valid_until, 1)
        message = v3c.encode_message(v3c.MessageType.LISTING, key, body, now)
        result = self.ledger.post_messages([v3c.encode_envelope(message, TTL)])
        return self._result_text(result, f"Listed {qty}kg {crop_name(crop)} @{money(ask)}")

    def _buy(self, user: User, words: list[str]) -> str:
        if user.role != "buyer":
            return "Only buyer accounts can order"
        if len(words) < 2:
            return "Send: BUY listingId kg"
        prefix = words[0].lower()
        try:
            qty = int(words[1])
        except ValueError:
            return "kg must be a number"
        if qty <= 0:
            return "kg must be positive"
        feed = self.ledger.listings(user.region)
        listing = next(
            (
                item
                for item in feed.get("listings", [])
                if str(item["listingId"]).lower().startswith(prefix)
                and int(item["remainingKg"]) >= qty
                and int(item["region"]) == user.region
            ),
            None,
        )
        if listing is None:
            return "No matching listing in your region"
        now = int(self.clock())
        valid_until = min(int(listing["validUntil"]), now + DAY)
        key = derive_key(self.master_key, user.phone)
        body = v3c.order_body(
            bytes.fromhex(str(listing["listingId"])),
            qty,
            int(listing["askPaise"]),
            valid_until,
        )
        message = v3c.encode_message(
            v3c.MessageType.ORDER,
            key,
            body,
            now,
            to_id=bytes.fromhex(str(listing["sellerId"])),
        )
        result = self.ledger.post_messages([v3c.encode_envelope(message, TTL)])
        return self._result_text(result, f"Order {qty}kg @{money(int(listing['askPaise']))}")

    def _balance(self, user: User) -> str:
        orders = self.ledger.orders(user.actor_id).get("orders", [])
        listings = [
            item
            for item in self.ledger.listings(user.region).get("listings", [])
            if item["sellerId"] == user.actor_id
        ]
        return (
            f"{user.role} {user.actor_id[:8]}: {len(listings)} listings, "
            f"{len(orders)} orders"
        )[:MAX_REPLY]

    def _my_listings(self, user: User) -> str:
        listings = [
            item
            for item in self.ledger.listings(user.region).get("listings", [])
            if item["sellerId"] == user.actor_id and int(item["remainingKg"]) > 0
        ]
        if not listings:
            return "No open listings"
        parts = [
            f"{item['listingId'][:6]} {item['remainingKg']}kg @{money(int(item['askPaise']))}"
            for item in listings[:2]
        ]
        return "Open: " + "; ".join(parts)

    def _my_orders(self, user: User) -> str:
        orders = self.ledger.orders(user.actor_id).get("orders", [])
        if not orders:
            return "No orders"
        parts = [
            f"{item['orderId'][:6]} {item['status']} {item['qtyKg']}kg"
            for item in orders[:2]
        ]
        return "Orders: " + "; ".join(parts)

    @staticmethod
    def _result_text(result: dict, success: str) -> str:
        items = result.get("results", [])
        if not items:
            return success
        item = items[0]
        status = item.get("status", "rejected")
        if status in {"accepted", "duplicate"}:
            return success
        reason = item.get("reason") or item.get("reasonCode") or status
        return f"Rejected: {reason}"[:MAX_REPLY]

    @staticmethod
    def _normalise_phone(phone: str) -> str:
        return "".join(ch for ch in phone.strip() if ch.isdigit() or ch == "+")[:24]


class SmsBridge:
    """HTTP bridge the Android SMS gateway can POST to."""

    def __init__(self, agent: SmsAgent) -> None:
        self.agent = agent

    def serve(self, host: str = "127.0.0.1", port: int = 8099) -> None:
        agent = self.agent

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                if self.path not in {"/sms", "/v3/sms"}:
                    self.send_error(404)
                    return
                length = int(self.headers.get("Content-Length", "0"))
                try:
                    payload = json.loads(self.rfile.read(length) or b"{}")
                    reply = agent.handle(
                        str(payload.get("phone", "")),
                        str(payload.get("text", "")),
                    )
                except Exception as error:  # pragma: no cover - defensive
                    reply = f"Agent error: {error}"
                body = json.dumps({"reply": reply}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args) -> None:  # noqa: A002
                return None

        ThreadingHTTPServer((host, port), Handler).serve_forever()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Custodial SMS agent for agri-chain v3")
    parser.add_argument("--url", default="http://127.0.0.1:8000", help="ledger base URL")
    parser.add_argument("--admin-token", default=os.environ.get("AGRICHAIN_ADMIN_TOKEN", ""))
    parser.add_argument("--db", default="gateway/sms_agent.db")
    parser.add_argument("--key", default="gateway/agent_key.bin")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--once", help="handle one 'phone|text' message and exit")
    args = parser.parse_args(argv)

    agent = SmsAgent(
        db_path=args.db,
        master_key=load_or_create_master(args.key),
        ledger=HttpLedger(args.url, args.admin_token),
    )
    if args.once:
        phone, _, text = args.once.partition("|")
        print(agent.handle(phone, text))
        return 0
    print(f"sms agent: url={args.url} bridge=http://{args.host}:{args.port}/sms")
    SmsBridge(agent).serve(args.host, args.port)
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
