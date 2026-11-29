"""T32 market regressions: orders, trades, prices, outbox and checkpoints."""

from __future__ import annotations

import base64
import time

import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

import v3_codec as v3c
from app import create_app

ADMIN_HEADERS = {"X-Admin-Token": "dev-admin-token"}
ZERO12 = b"\x00" * 12


@pytest.fixture
def ctx(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as client:
        yield client, app.state.v3_ledger


def key_for(scalar: int) -> ec.EllipticCurvePrivateKey:
    return v3c.codec.private_key_from_scalar(scalar)


def register(client: TestClient, key, role: str = "farmer", name: str = "Test") -> str:
    pubkey = v3c.compressed_public_key(key)
    response = client.post(
        "/v3/actors",
        headers=ADMIN_HEADERS,
        json={"pubkey": pubkey.hex(), "role": role, "name": name, "region": 1},
    )
    assert response.status_code == 201, response.text
    return response.json()["actorId"]


def sign(key, mtype, body: bytes, *, to_id: bytes | None = None) -> bytes:
    return v3c.sign_client_message(
        key, mtype, body, int(time.time()), to_id=to_id
    )


def post(client: TestClient, messages: list[bytes], via: str = "wifi") -> list[dict]:
    response = client.post(
        "/v3/messages",
        json={
            "via": via,
            "messages": [base64.b64encode(message).decode("ascii") for message in messages],
        },
    )
    assert response.status_code == 200, response.text
    return response.json()["results"]


def message_id(message: bytes) -> str:
    return v3c.codec.msg_id(v3c.decode_message(message)).hex()


def batch_register_body(*, crop: int = 2, weight: int = 1000) -> bytes:
    return v3c.codec.batch_register_body(
        crop, weight, int(time.time()) - 3600, 1, 1
    )


def listing_body(*, qty: int = 100, ask: int = 2500, batch_id: bytes = ZERO12) -> bytes:
    return v3c.codec.listing_body(
        crop=2,
        qty_kg=qty,
        ask_paise=ask,
        region=1,
        valid_until=int(time.time()) + 3600,
        grade=1,
        batch_id=batch_id,
    )


def latest_table(prices: dict) -> dict:
    """`GET /v3/prices` returns every version after sinceVersion, not just the head."""
    return [item for item in prices["prices"] if item["version"] == prices["version"]][-1]


def pull(client: TestClient, actors: list[str], after_id: int = 0) -> dict:
    response = client.post(
        "/v3/outbox/pull", json={"actors": actors, "afterId": after_id}
    )
    assert response.status_code == 200, response.text
    return response.json()


def make_batch(client: TestClient, seller) -> str:
    batch = sign(seller, v3c.MessageType.BATCH_REGISTER, batch_register_body())
    assert post(client, [batch])[0]["status"] == "accepted"
    return message_id(batch)


def make_listing(
    client: TestClient,
    seller,
    *,
    batch_id: str,
    qty: int = 100,
    ask: int = 2500,
) -> str:
    listing = sign(
        seller,
        v3c.MessageType.LISTING,
        listing_body(qty=qty, ask=ask, batch_id=bytes.fromhex(batch_id)),
    )
    assert post(client, [listing])[0]["status"] == "accepted"
    return message_id(listing)


def make_order(
    client: TestClient,
    buyer,
    seller_id: str,
    *,
    listing_id: str,
    qty: int = 40,
    bid: int = 2500,
) -> str:
    order = sign(
        buyer,
        v3c.MessageType.ORDER,
        v3c.codec.order_body(
            bytes.fromhex(listing_id), qty, bid, int(time.time()) + 3600
        ),
        to_id=bytes.fromhex(seller_id),
    )
    assert post(client, [order])[0]["status"] == "accepted"
    return message_id(order)


def accept_order(client: TestClient, seller, buyer_id: str, order_id: str) -> None:
    accept = sign(
        seller,
        v3c.MessageType.ORDER_ACCEPT,
        v3c.codec.order_accept_body(bytes.fromhex(order_id)),
        to_id=bytes.fromhex(buyer_id),
    )
    assert post(client, [accept])[0]["status"] == "accepted"


def make_trade(
    client: TestClient,
    seller,
    buyer,
    *,
    seller_id: str,
    buyer_id: str,
    batch_id: str,
    bid: int,
    qty: int = 10,
) -> str:
    listing_id = make_listing(
        client, seller, batch_id=batch_id, qty=100, ask=bid
    )
    order_id = make_order(
        client, buyer, seller_id, listing_id=listing_id, qty=qty, bid=bid
    )
    accept_order(client, seller, buyer_id, order_id)
    return order_id


def test_full_market_flow_exposes_orders_and_trades(ctx):
    client, ledger = ctx
    seller = key_for(3)
    buyer = key_for(4)
    seller_id = register(client, seller, "farmer", "Seller")
    buyer_id = register(client, buyer, "buyer", "Buyer")

    batch_id = make_batch(client, seller)
    listing_id = make_listing(client, seller, batch_id=batch_id, qty=100, ask=2500)
    order_id = make_order(client, buyer, seller_id, listing_id=listing_id, qty=40, bid=2500)

    open_orders = client.get("/v3/orders?status=open").json()["orders"]
    assert [item["orderId"] for item in open_orders] == [order_id]
    assert open_orders[0]["status"] == "open"
    assert open_orders[0]["crop"] == 2
    assert open_orders[0]["cropName"] == "rice"
    assert open_orders[0]["region"] == 1
    assert open_orders[0]["buyerId"] == buyer_id
    assert open_orders[0]["sellerId"] == seller_id
    assert open_orders[0]["qtyKg"] == 40
    assert open_orders[0]["bidPaise"] == 2500
    assert open_orders[0]["acceptedSeq"] is None

    accept_order(client, seller, buyer_id, order_id)

    accepted = client.get("/v3/orders?status=accepted").json()["orders"]
    assert [item["orderId"] for item in accepted] == [order_id]
    assert accepted[0]["acceptedSeq"] is not None
    assert client.get("/v3/orders?status=open").json()["orders"] == []

    delivered = sign(
        buyer,
        v3c.MessageType.DELIVERED,
        v3c.codec.delivered_body(bytes.fromhex(order_id)),
        to_id=bytes.fromhex(seller_id),
    )
    assert post(client, [delivered])[0]["status"] == "accepted"

    final = client.get(f"/v3/orders?actor={buyer_id}").json()["orders"]
    assert len(final) == 1
    assert final[0]["status"] == "delivered"
    assert final[0]["deliveredSeq"] is not None

    trades = client.get("/v3/trades?region=1&crop=2").json()["trades"]
    assert len(trades) == 1
    assert trades[0]["orderId"] == order_id
    assert trades[0]["listingId"] == listing_id
    assert trades[0]["sellerId"] == seller_id
    assert trades[0]["buyerId"] == buyer_id
    assert trades[0]["cropName"] == "rice"
    assert trades[0]["qtyKg"] == 40
    assert trades[0]["pricePaise"] == 2500
    assert client.get(f"/v3/trades?actor={seller_id}").json()["trades"] == trades
    assert client.get("/v3/trades?actor=0000000000000000").json()["trades"] == []

    listings = client.get("/v3/listings").json()["listings"]
    assert listings[0]["remainingKg"] == 60

    stats = client.get("/v3/stats").json()
    assert stats["orders"] == 1
    assert stats["trades"] == 1

    # Outbox: receipts for every inbound message and notices for the parties.
    seller_box = pull(client, [seller_id])["items"]
    seller_kinds = {item["kind"] for item in seller_box}
    assert {"RECEIPT", "ORDER_RECEIVED", "DELIVERED"} <= seller_kinds
    buyer_box = pull(client, [buyer_id])["items"]
    buyer_kinds = {item["kind"] for item in buyer_box}
    assert {"RECEIPT", "ORDER_ACCEPTED"} <= buyer_kinds

    ledger_pubkey = bytes.fromhex(client.get("/v3/ledger").json()["pubkey"])
    for item in seller_box + buyer_box:
        raw = base64.b64decode(item["message"])
        assert v3c.verify_message(raw, ledger_pubkey)
        decoded = v3c.decode_message(raw)
        assert v3c.codec.msg_id(decoded).hex() == item["msgId"]

    # Pagination is stable: the second page starts after the first.
    first = pull(client, [seller_id, buyer_id], 0)
    assert first["nextAfterId"] > 0
    second = pull(client, [seller_id, buyer_id], first["nextAfterId"])
    first_ids = {item["id"] for item in first["items"]}
    assert first_ids.isdisjoint({item["id"] for item in second["items"]})


def test_order_cancel_restores_listing_quantity(ctx):
    client, _ = ctx
    seller = key_for(3)
    buyer = key_for(4)
    seller_id = register(client, seller, "farmer", "Seller")
    buyer_id = register(client, buyer, "buyer", "Buyer")
    batch_id = make_batch(client, seller)
    listing_id = make_listing(client, seller, batch_id=batch_id, qty=100, ask=2500)
    order_id = make_order(client, buyer, seller_id, listing_id=listing_id, qty=40)
    accept_order(client, seller, buyer_id, order_id)

    cancel = sign(
        buyer,
        v3c.MessageType.ORDER_CANCEL,
        v3c.codec.order_cancel_body(bytes.fromhex(order_id)),
        to_id=bytes.fromhex(seller_id),
    )
    assert post(client, [cancel])[0]["status"] == "accepted"

    orders = client.get("/v3/orders").json()["orders"]
    assert orders[0]["status"] == "cancelled"
    listings = client.get("/v3/listings").json()["listings"]
    assert listings[0]["remainingKg"] == 100
    seller_box = pull(client, [seller_id])["items"]
    assert "ORDER_CANCELLED" in {item["kind"] for item in seller_box}


def test_prices_use_median_with_pair_cap_and_version_bumps(ctx):
    client, _ = ctx
    seller = key_for(3)
    buyer = key_for(4)
    seller_id = register(client, seller, "farmer", "Seller")
    buyer_id = register(client, buyer, "buyer", "Buyer")
    batch_id = make_batch(client, seller)

    assert client.get("/v3/prices?region=1").json()["prices"] == []

    for index, bid in enumerate((1000, 2000, 3000)):
        make_trade(
            client,
            seller,
            buyer,
            seller_id=seller_id,
            buyer_id=buyer_id,
            batch_id=batch_id,
            bid=bid,
        )
        prices = client.get("/v3/prices?region=1").json()
        row = latest_table(prices)["rows"][0]
        expected = (1000, 2000, 2000)[index]
        assert row["pricePaise"] == expected
        assert row["trades"] == index + 1
        assert prices["version"] == index + 1

    # A fourth trade between the same pair is ignored: only the three most
    # recent trades per (seller,buyer) count, so the median is 3000.
    make_trade(
        client,
        seller,
        buyer,
        seller_id=seller_id,
        buyer_id=buyer_id,
        batch_id=batch_id,
        bid=4000,
    )
    prices = client.get("/v3/prices?region=1").json()
    row = latest_table(prices)["rows"][0]
    assert row["pricePaise"] == 3000
    assert row["trades"] == 3
    assert prices["version"] == 4

    # No new trade means no new version.
    assert client.get("/v3/prices?region=1").json()["version"] == 4

    table = latest_table(prices)
    beacon = [item for item in prices["beacons"] if item["version"] == prices["version"]][-1]
    assert table["parts"] == 1
    assert len(base64.b64decode(table["body"])) <= 198
    assert beacon["hash8"] == v3c.price_table_hash8(
        [base64.b64decode(table["body"])]
    ).hex()
    ledger_pubkey = bytes.fromhex(client.get("/v3/ledger").json()["pubkey"])
    assert v3c.verify_message(base64.b64decode(table["message"]), ledger_pubkey)
    assert v3c.verify_message(base64.b64decode(beacon["message"]), ledger_pubkey)


def test_seed_price_fallback_and_validation(ctx):
    client, _ = ctx
    response = client.post(
        "/v3/prices/seed",
        headers=ADMIN_HEADERS,
        json={"region": 1, "crop": 5, "pricePaise": 2500},
    )
    assert response.status_code == 200, response.text
    assert response.json()["results"][0]["version"] == 1

    prices = client.get("/v3/prices?region=1").json()
    assert latest_table(prices)["rows"] == [
        {"crop": 5, "cropName": "cotton", "pricePaise": 2500, "trades": 0}
    ]

    # Re-seeding the same price does not create a new version.
    client.post(
        "/v3/prices/seed",
        headers=ADMIN_HEADERS,
        json={"region": 1, "crop": 5, "pricePaise": 2500},
    )
    assert client.get("/v3/prices?region=1").json()["version"] == 1

    client.post(
        "/v3/prices/seed",
        headers=ADMIN_HEADERS,
        json={"region": 1, "crop": 5, "pricePaise": 3000},
    )
    prices = client.get("/v3/prices?region=1").json()
    assert prices["version"] == 2
    assert latest_table(prices)["rows"][0]["pricePaise"] == 3000

    # Out-of-bounds prices are rejected by the admin seed endpoint.
    bad = client.post(
        "/v3/prices/seed",
        headers=ADMIN_HEADERS,
        json={"region": 1, "crop": 2, "pricePaise": 100},
    )
    assert bad.status_code == 400

    # sinceVersion returns only the requested version and newer.
    newer = client.get("/v3/prices?region=1&sinceVersion=2").json()
    assert newer["version"] == 2
    assert all(item["version"] >= 2 for item in newer["prices"])


def test_checkpoint_covers_head_and_verifies(ctx):
    client, ledger = ctx
    seller = key_for(3)
    buyer = key_for(4)
    seller_id = register(client, seller, "farmer", "Seller")
    buyer_id = register(client, buyer, "buyer", "Buyer")
    batch_id = make_batch(client, seller)
    listing_id = make_listing(client, seller, batch_id=batch_id)
    order_id = make_order(client, buyer, seller_id, listing_id=listing_id)
    accept_order(client, seller, buyer_id, order_id)

    stats = client.get("/v3/stats").json()
    checkpoint = client.get("/v3/checkpoint").json()
    assert checkpoint["seq"] == stats["messages"]
    assert checkpoint["prefix"] == "AC3-CHKPT"
    assert checkpoint["pubkey"] == ledger.pubkey.hex()
    assert v3c.verify_checkpoint(
        bytes.fromhex(checkpoint["pubkey"]),
        checkpoint["seq"],
        bytes.fromhex(checkpoint["root"]),
        base64.b64decode(checkpoint["signature"]),
    )

    # The root is the Merkle root over every ledger hash.
    messages = client.get("/v3/messages?limit=200").json()["messages"]
    root = v3c.merkle_root(bytes.fromhex(item["ledgerHash"]) for item in messages)
    assert checkpoint["root"] == root.hex()


def test_market_filters_validate_inputs(ctx):
    client, _ = ctx
    assert client.get("/v3/orders?region=99").status_code == 400
    assert client.get("/v3/orders?crop=99").status_code == 400
    assert client.get("/v3/orders?status=weird").status_code == 400
    assert client.get("/v3/orders?actor=abc").status_code == 400
    assert client.get("/v3/trades?region=99").status_code == 400
    assert client.get("/v3/trades?crop=99").status_code == 400
    assert client.get("/v3/trades?actor=zzzzzzzzzzzzzzzz").status_code == 400
