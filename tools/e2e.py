#!/usr/bin/env python3
"""T32 v3 end-to-end flow: listing -> order -> accept -> delivered -> verify.

By default the script runs the real FastAPI app in-process against a temporary
SQLite database, so it needs no running server or phone.  Pass ``--base-url``
to run the same signed messages against a live ledger instead.

    python tools/e2e.py
    python tools/e2e.py --base-url http://127.0.0.1:8000 --admin-token TOKEN
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "ledger"))

import v3_codec as v3c  # noqa: E402

STEPS: list[str] = []


def note(text: str) -> None:
    STEPS.append(text)
    print(f"  {text}")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


class LedgerClient:
    """Thin JSON client shared by the in-process and live modes."""

    def __init__(self, base_url: str | None, admin_token: str) -> None:
        self.admin_token = admin_token
        self._tmp = None
        self._client = None
        self.base = ""
        if base_url:
            self.base = base_url.rstrip("/")
        else:
            os.environ.setdefault("AGRICHAIN_DEV", "1")
            os.environ.setdefault("AGRICHAIN_RATE_LIMIT", "0")
            os.environ.setdefault("AGRICHAIN_V3_RATE_LIMIT", "0")
            from fastapi.testclient import TestClient

            from app import create_app

            self._tmp = tempfile.TemporaryDirectory(prefix="agrichain-e2e-")
            self._client = TestClient(
                create_app(str(Path(self._tmp.name) / "ledger.db"))
            )

    def request(self, method: str, path: str, payload: dict | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if method == "POST" and path == "/v3/actors":
            headers["X-Admin-Token"] = self.admin_token
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        if self._client is not None:
            response = self._client.request(method, path, json=payload, headers=headers)
            data = response.json()
            require(
                response.status_code < 400,
                f"{method} {path} -> {response.status_code}: {data}",
            )
            return data
        request = urllib.request.Request(
            self.base + path, data=body, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            raise AssertionError(f"{method} {path} -> {exc.code}: {detail}") from exc

    def get(self, path: str) -> dict:
        return self.request("GET", path)

    def post(self, path: str, payload: dict) -> dict:
        return self.request("POST", path, payload)

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
        if self._tmp is not None:
            self._tmp.cleanup()


def key_for(scalar: int):
    return v3c.codec.private_key_from_scalar(scalar)


def sign(key, mtype, body: bytes, to_id: bytes | None = None) -> bytes:
    return v3c.sign_client_message(key, mtype, body, int(time.time()), to_id=to_id)


def msg_id(message: bytes) -> str:
    return v3c.message_id_of(message).hex()


def register(client: LedgerClient, key, role: str, name: str) -> str:
    pubkey = v3c.compressed_public_key(key)
    data = client.post(
        "/v3/actors",
        {"pubkey": pubkey.hex(), "role": role, "name": name, "region": 1},
    )
    return data["actorId"]


def post_messages(client: LedgerClient, messages: list[bytes], via: str = "wifi") -> list[dict]:
    data = client.post(
        "/v3/messages",
        {
            "via": via,
            "messages": [base64.b64encode(message).decode("ascii") for message in messages],
        },
    )
    results = data["results"]
    for result in results:
        require(
            result["status"] == "accepted",
            f"{result['type']} rejected: {result['reason']} ({result.get('error', '')})",
        )
    return results


def run(base_url: str | None, admin_token: str) -> None:
    client = LedgerClient(base_url, admin_token)
    try:
        health = client.get("/v3/health")
        require(health == {"ok": True, "version": 3}, f"bad v3 health: {health}")
        note("GET /v3/health -> ok")

        info = client.get("/v3/ledger")
        ledger_pubkey = bytes.fromhex(info["pubkey"])
        note(f"GET /v3/ledger -> ledger {info['ledgerId']}")

        seller = key_for(3)
        buyer = key_for(4)
        seller_id = register(client, seller, "farmer", "Seller")
        buyer_id = register(client, buyer, "buyer", "Buyer")
        note(f"registered seller {seller_id} and buyer {buyer_id}")

        batch = sign(
            seller,
            v3c.MessageType.BATCH_REGISTER,
            v3c.codec.batch_register_body(2, 100, int(time.time()) - 3600, 1, 1),
        )
        batch_id = msg_id(batch)
        listing = sign(
            seller,
            v3c.MessageType.LISTING,
            v3c.codec.listing_body(
                2, 100, 2500, 1, int(time.time()) + 3600, 1, bytes.fromhex(batch_id)
            ),
        )
        listing_id = msg_id(listing)
        results = post_messages(client, [batch, listing])
        require([item["seq"] for item in results] == [1, 2], "batch/listing seq mismatch")
        note("BATCH_REGISTER + LISTING accepted")

        order = sign(
            buyer,
            v3c.MessageType.ORDER,
            v3c.codec.order_body(
                bytes.fromhex(listing_id), 40, 2500, int(time.time()) + 3600
            ),
            to_id=bytes.fromhex(seller_id),
        )
        order_id = msg_id(order)
        post_messages(client, [order])
        note(f"ORDER {order_id} accepted")

        accept = sign(
            seller,
            v3c.MessageType.ORDER_ACCEPT,
            v3c.codec.order_accept_body(bytes.fromhex(order_id)),
            to_id=bytes.fromhex(buyer_id),
        )
        post_messages(client, [accept])
        note("ORDER_ACCEPT accepted; trade and price table published")

        delivered = sign(
            buyer,
            v3c.MessageType.DELIVERED,
            v3c.codec.delivered_body(bytes.fromhex(order_id)),
            to_id=bytes.fromhex(seller_id),
        )
        post_messages(client, [delivered])
        note("DELIVERED accepted (buyer -> seller)")

        orders = client.get("/v3/orders?status=delivered")["orders"]
        require(
            any(item["orderId"] == order_id for item in orders),
            "delivered order missing from /v3/orders",
        )
        trades = client.get("/v3/trades?crop=2")["trades"]
        require(
            any(item["orderId"] == order_id and item["pricePaise"] == 2500 for item in trades),
            "trade missing from /v3/trades",
        )
        note("GET /v3/orders and /v3/trades -> order + trade present")

        prices = client.get("/v3/prices?region=1")
        require(prices["version"] >= 1, "no price version published")
        table = [item for item in prices["prices"] if item["version"] == prices["version"]][-1]
        beacon = [item for item in prices["beacons"] if item["version"] == prices["version"]][-1]
        bodies = [
            base64.b64decode(item["body"])
            for item in prices["prices"]
            if item["version"] == prices["version"]
        ]
        require(
            beacon["hash8"] == v3c.price_table_hash8(bodies).hex(),
            "PRICE_BEACON hash8 does not match the table bodies",
        )
        require(
            v3c.verify_message(base64.b64decode(table["message"]), ledger_pubkey),
            "bad PRICE_TABLE signature",
        )
        require(
            v3c.verify_message(base64.b64decode(beacon["message"]), ledger_pubkey),
            "bad PRICE_BEACON signature",
        )
        note(f"GET /v3/prices -> v{prices['version']}, signed table and beacon verified")

        checkpoint = client.get("/v3/checkpoint")
        stats = client.get("/v3/stats")
        require(
            checkpoint["seq"] == stats["messages"],
            f"checkpoint seq {checkpoint['seq']} != head {stats['messages']}",
        )
        require(
            v3c.verify_checkpoint(
                ledger_pubkey,
                checkpoint["seq"],
                bytes.fromhex(checkpoint["root"]),
                base64.b64decode(checkpoint["signature"]),
            ),
            "bad checkpoint signature",
        )
        messages = client.get("/v3/messages?limit=200")["messages"]
        root = v3c.merkle_root(bytes.fromhex(item["ledgerHash"]) for item in messages)
        require(root.hex() == checkpoint["root"], "checkpoint Merkle root mismatch")
        note(f"GET /v3/checkpoint -> seq {checkpoint['seq']} verified against ledger hashes")

        seller_box = client.post("/v3/outbox/pull", {"actors": [seller_id]})["items"]
        buyer_box = client.post("/v3/outbox/pull", {"actors": [buyer_id]})["items"]
        kinds = {item["kind"] for item in seller_box + buyer_box}
        require("RECEIPT" in kinds, "no signed receipts in the outbox")
        require("ORDER_ACCEPTED" in kinds, "ORDER_ACCEPTED notice missing")
        require("DELIVERED" in kinds, "DELIVERED notice missing")
        for item in seller_box + buyer_box:
            raw = base64.b64decode(item["message"])
            require(v3c.verify_message(raw, ledger_pubkey), f"bad signature: {item['kind']}")
            require(v3c.message_id_of(raw).hex() == item["msgId"], "outbox msgId mismatch")
        note(f"outbox pull -> {len(seller_box) + len(buyer_box)} signed receipts/notices verified")

        print(f"\nPASS: {len(STEPS)} checks completed (v3 e2e).")
    finally:
        client.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="live ledger URL; default runs in-process")
    parser.add_argument(
        "--admin-token",
        default=os.environ.get("AGRICHAIN_ADMIN_TOKEN") or "dev-admin-token",
        help="admin token for /v3/actors registration",
    )
    args = parser.parse_args()
    try:
        run(args.base_url, args.admin_token)
    except AssertionError as exc:
        print(f"\nFAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
