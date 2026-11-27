from __future__ import annotations

import base64
import hashlib
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
        json={
            "pubkey": pubkey.hex(),
            "role": role,
            "name": name,
            "region": 1,
        },
    )
    assert response.status_code == 201, response.text
    return response.json()["actorId"]


def sign(
    key,
    mtype,
    body: bytes,
    *,
    to_id: bytes | None = None,
    ts: int | None = None,
) -> bytes:
    return v3c.sign_client_message(
        key, mtype, body, int(ts if ts is not None else time.time()), to_id=to_id
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


def listing_body(
    *,
    crop: int = 2,
    qty: int = 100,
    ask: int = 2500,
    region: int = 1,
    valid_until: int | None = None,
    grade: int = 1,
    batch_id: bytes = ZERO12,
) -> bytes:
    return v3c.codec.listing_body(
        crop,
        qty,
        ask,
        region,
        int(valid_until if valid_until is not None else time.time() + 3600),
        grade,
        batch_id,
    )


def batch_register_body(
    *,
    crop: int = 2,
    weight: int = 100,
    region: int = 1,
    grade: int = 1,
    harvest_ts: int | None = None,
) -> bytes:
    return v3c.codec.batch_register_body(
        crop,
        weight,
        int(harvest_ts if harvest_ts is not None else time.time() - 3600),
        region,
        grade,
    )


def message_id(message: bytes) -> str:
    return v3c.codec.msg_id(v3c.decode_message(message)).hex()


def test_ledger_info_and_actor_registration(ctx):
    client, ledger = ctx
    farmer = key_for(3)
    actor_id = register(client, farmer, "farmer", "Asha")

    info = client.get("/v3/ledger").json()
    assert info["version"] == 3
    assert info["protocol"] == 1
    assert info["ledgerId"] == ledger.ledger_id_hex
    assert info["pubkey"] == ledger.pubkey.hex()
    assert info["contracts"]["cropsVersion"] == 1
    assert info["contracts"]["regionsVersion"] == 1

    actor = client.get(f"/v3/actors/{actor_id}").json()
    assert actor["role"] == "farmer"
    assert actor["name"] == "Asha"
    assert actor["pubkey"] == v3c.compressed_public_key(farmer).hex()


def test_admin_token_is_required(ctx):
    client, _ = ctx
    response = client.post(
        "/v3/actors",
        json={
            "pubkey": v3c.compressed_public_key(key_for(3)).hex(),
            "role": "farmer",
            "name": "No token",
        },
    )
    assert response.status_code == 401


def test_listing_accept_duplicate_and_bad_signature(ctx):
    client, _ = ctx
    farmer = key_for(3)
    register(client, farmer)
    message = sign(farmer, v3c.MessageType.LISTING, listing_body())

    first = post(client, [message])[0]
    assert first["status"] == "accepted", first
    assert first["seq"] == 1
    assert first["receipt"]
    listing_id = first["msgId"]

    duplicate = post(client, [message])[0]
    assert duplicate["status"] == "duplicate"
    assert duplicate["seq"] == 1
    assert duplicate["receipt"] == first["receipt"]

    # Same signature, changed body -> new msgId, invalid signature.
    tampered = bytearray(message)
    tampered[20] ^= 0x01
    bad = post(client, [bytes(tampered)])[0]
    assert bad["status"] == "rejected"
    assert bad["reason"] == "bad_signature"
    assert bad["seq"] == 2

    log = client.get("/v3/messages").json()
    assert [item["seq"] for item in log["messages"]] == [1, 2]
    assert log["messages"][0]["msgId"] == listing_id
    assert log["messages"][0]["status"] == "accepted"
    assert log["messages"][1]["status"] == "rejected"
    assert log["messages"][1]["reason"] == "bad_signature"

    one = client.get(f"/v3/messages/{listing_id}").json()
    assert one["msgId"] == listing_id
    assert one["decoded"]["type"] == "LISTING"
    assert one["decoded"]["fields"]["qtyKg"] == 100

    explorer = client.get("/explorer").text
    assert "AgriChain v3 explorer" in explorer
    assert "accepted" in explorer
    assert "bad_signature" in explorer
    assert listing_id[:12] in explorer


def test_unknown_actor_and_ledger_signed_are_rejected(ctx):
    client, ledger = ctx
    stranger = key_for(7)
    body = listing_body()
    unknown = sign(stranger, v3c.MessageType.LISTING, body)
    result = post(client, [unknown])[0]
    assert result["status"] == "rejected"
    assert result["reason"] == "unknown_actor"

    forged = v3c.sign_ledger_message(
        ledger.private_key, v3c.MessageType.LISTING, body, int(time.time())
    )
    forged_result = post(client, [forged])[0]
    assert forged_result["status"] == "rejected"
    assert forged_result["reason"] == "not_authorized"


def test_expired_ts_is_rejected(ctx):
    client, _ = ctx
    farmer = key_for(3)
    register(client, farmer)
    old = sign(farmer, v3c.MessageType.LISTING, listing_body(), ts=int(time.time()) - 40 * 86400)
    result = post(client, [old])[0]
    assert result["status"] == "rejected"
    assert result["reason"] == "expired"


def test_ledger_hash_chain_and_checkpoint(ctx):
    client, ledger = ctx
    farmer = key_for(3)
    register(client, farmer)
    messages = [
        sign(farmer, v3c.MessageType.LISTING, listing_body(qty=10 + index))
        for index in range(3)
    ]
    results = post(client, messages)
    assert [item["seq"] for item in results] == [1, 2, 3]

    log = client.get("/v3/messages").json()["messages"]
    previous = b"\x00" * 32
    for item in log:
        msg_id = bytes.fromhex(item["msgId"])
        status = item["statusCode"]
        seq = item["seq"]
        expected = hashlib.sha256(
            previous + msg_id + bytes([status]) + seq.to_bytes(4, "big")
        ).digest()
        assert item["ledgerHash"] == expected.hex()
        assert item["ledgerPrev"] == previous.hex()
        previous = expected

    checkpoint = client.get("/v3/checkpoint").json()
    assert checkpoint["seq"] == 3
    assert checkpoint["prefix"] == "AC3-CHKPT"
    assert checkpoint["root"] == v3c.merkle_root(
        [bytes.fromhex(item["ledgerHash"]) for item in log]
    ).hex()
    signature = base64.b64decode(checkpoint["signature"])
    assert v3c.verify_checkpoint(
        bytes.fromhex(checkpoint["pubkey"]),
        checkpoint["seq"],
        bytes.fromhex(checkpoint["root"]),
        signature,
    )


def test_rate_limit_is_retryable_and_not_stored(ctx):
    client, ledger = ctx
    farmer = key_for(3)
    register(client, farmer)
    ledger.rate_enabled = True
    ledger.actor_rate = 2
    ledger.ip_rate = 0
    messages = [
        sign(farmer, v3c.MessageType.LISTING, listing_body(qty=10 + index))
        for index in range(3)
    ]
    results = post(client, messages)
    assert [item["status"] for item in results[:2]] == ["accepted", "accepted"]
    assert results[2]["status"] == "rejected"
    assert results[2]["reason"] == "rate_limited"
    assert results[2]["retry"] is True
    assert results[2]["seq"] == 0
    assert len(client.get("/v3/messages").json()["messages"]) == 2


def test_message_filters_and_explorer_filters(ctx):
    client, _ = ctx
    farmer = key_for(3)
    register(client, farmer)
    post(
        client,
        [
            sign(farmer, v3c.MessageType.LISTING, listing_body(qty=10)),
            sign(farmer, v3c.MessageType.ANNOUNCE, v3c.codec.announce_body(
                v3c.compressed_public_key(farmer), 0x1F, "Asha"
            )),
        ],
        via="ble",
    )
    listings = client.get("/v3/messages?type=LISTING").json()["messages"]
    assert len(listings) == 1
    assert listings[0]["type"] == "LISTING"
    announcements = client.get("/v3/messages?type=1").json()["messages"]
    assert len(announcements) == 1
    by_via = client.get("/v3/messages?via=ble").json()["messages"]
    assert len(by_via) == 2
    accepted = client.get("/v3/messages?status=accepted").json()["messages"]
    assert len(accepted) == 2

    page = client.get("/explorer?status=accepted&type=LISTING&via=ble").text
    assert "<td>LISTING</td>" in page
    # The type dropdown always lists every type; only the table body is
    # filtered.
    assert "<td>ANNOUNCE</td>" not in page


def test_bad_format_is_stored(ctx):
    client, _ = ctx
    result = post(client, [b"\x01\x10\x00"])[0]
    assert result["status"] == "rejected"
    assert result["reason"] == "bad_format"
    assert result["seq"] == 1
    log = client.get("/v3/messages").json()["messages"]
    assert len(log) == 1
    assert log[0]["reason"] == "bad_format"


def test_pending_listing_resolves_when_batch_arrives(ctx):
    client, ledger = ctx
    farmer = key_for(3)
    register(client, farmer)
    batch = sign(farmer, v3c.MessageType.BATCH_REGISTER, batch_register_body())
    batch_id = message_id(batch)
    listing = sign(
        farmer,
        v3c.MessageType.LISTING,
        listing_body(batch_id=bytes.fromhex(batch_id)),
    )
    listing_id = message_id(listing)

    pending = post(client, [listing])[0]
    assert pending["status"] == "pending"
    assert pending["reason"] == "dependency_missing"
    assert pending["seq"] == 1
    assert pending["receipt"]

    accepted = post(client, [batch])[0]
    assert accepted["status"] == "accepted"
    assert accepted["seq"] == 2

    row = client.get(f"/v3/messages/{listing_id}").json()
    assert row["status"] == "accepted"
    assert row["reason"] == "none"
    assert row["arrivalStatus"] == "pending"
    assert row["arrivalStatusCode"] == 3

    listings = client.get("/v3/listings").json()["listings"]
    assert [item["listingId"] for item in listings] == [listing_id]
    assert listings[0]["batchId"] == batch_id

    # The arrival hash for seq 1 used status 3, not the resolved status.
    previous = b"\x00" * 32
    for item in client.get("/v3/messages").json()["messages"]:
        expected = hashlib.sha256(
            previous
            + bytes.fromhex(item["msgId"])
            + bytes([item["arrivalStatusCode"]])
            + item["seq"].to_bytes(4, "big")
        ).digest()
        assert item["ledgerHash"] == expected.hex()
        previous = expected

    # Both the pending receipt and the final receipt are pullable.
    pulled = client.post(
        "/v3/outbox/pull",
        json={"actors": [v3c.actor_id_hex(v3c.compressed_public_key(farmer))]},
    ).json()["items"]
    assert [item["kind"] for item in pulled].count("RECEIPT") >= 2


def test_pending_bind_resolves_after_box_registration(ctx):
    client, _ = ctx
    farmer = key_for(3)
    box = key_for(5)
    register(client, farmer)
    batch = sign(farmer, v3c.MessageType.BATCH_REGISTER, batch_register_body())
    batch_id = message_id(batch)
    assert post(client, [batch])[0]["status"] == "accepted"

    box_id = v3c.actor_id(v3c.compressed_public_key(box))
    bind = sign(
        farmer,
        v3c.MessageType.BIND,
        v3c.codec.bind_body(bytes.fromhex(batch_id), box_id),
    )
    bind_id = message_id(bind)
    pending = post(client, [bind])[0]
    assert pending["status"] == "pending"
    assert pending["reason"] == "dependency_missing"

    register(client, box, "box", "Box 1")

    row = client.get(f"/v3/messages/{bind_id}").json()
    assert row["status"] == "accepted"
    assert row["arrivalStatus"] == "pending"
    binding = client.get("/v3/ledger").json()
    assert binding["stats"]["messages"] == 2


def test_order_delivery_moves_tokens_and_emits_notices(ctx):
    client, _ = ctx
    seller = key_for(3)
    buyer = key_for(4)
    seller_id = register(client, seller, "farmer", "Seller")
    buyer_id = register(client, buyer, "buyer", "Buyer")

    batch = sign(seller, v3c.MessageType.BATCH_REGISTER, batch_register_body(weight=100))
    batch_id = message_id(batch)
    assert post(client, [batch])[0]["status"] == "accepted"

    listing = sign(
        seller,
        v3c.MessageType.LISTING,
        listing_body(qty=100, ask=2500, batch_id=bytes.fromhex(batch_id)),
    )
    listing_id = message_id(listing)
    assert post(client, [listing])[0]["status"] == "accepted"

    order = sign(
        buyer,
        v3c.MessageType.ORDER,
        v3c.codec.order_body(
            bytes.fromhex(listing_id),
            40,
            2500,
            int(time.time()) + 3600,
        ),
        to_id=bytes.fromhex(seller_id),
    )
    order_id = message_id(order)
    assert post(client, [order])[0]["status"] == "accepted"

    accept = sign(
        seller,
        v3c.MessageType.ORDER_ACCEPT,
        v3c.codec.order_accept_body(bytes.fromhex(order_id)),
        to_id=bytes.fromhex(buyer_id),
    )
    assert post(client, [accept])[0]["status"] == "accepted"

    delivered = sign(
        buyer,
        v3c.MessageType.DELIVERED,
        v3c.codec.delivered_body(bytes.fromhex(order_id)),
        to_id=bytes.fromhex(seller_id),
    )
    assert post(client, [delivered])[0]["status"] == "accepted"

    assert _balance(client, seller_id, batch_id) == 60
    assert _balance(client, buyer_id, batch_id) == 40

    prices = client.get("/v3/prices?region=1").json()
    assert prices["version"] == 1
    assert prices["versions"]["1"] == 1
    assert prices["prices"][0]["rows"] == [
        {"crop": 2, "cropName": "rice", "pricePaise": 2500, "trades": 1}
    ]
    assert prices["beacons"][0]["hash8"] == v3c.price_table_hash8(
        [base64.b64decode(prices["prices"][0]["body"])]
    ).hex()

    for actor in (seller_id, buyer_id):
        items = client.post(
            "/v3/outbox/pull", json={"actors": [actor]}
        ).json()["items"]
        assert any(item["kind"] in {"ORDER_RECEIVED", "ORDER_ACCEPTED", "DELIVERED"} for item in items)


def _balance(client: TestClient, actor_id: str, batch_id: str) -> int:
    # The API has no balance endpoint yet; read the ledger SQLite file via the
    # app state would be cleaner, but the batch is small and this keeps the
    # assertion at the HTTP boundary for everything else.
    import sqlite3

    # Find the database path from the app state.
    ledger = client.app.state.v3_ledger
    with sqlite3.connect(ledger.db.path) as conn:
        row = conn.execute(
            "SELECT kg FROM v3_balances WHERE actor_id = ? AND batch_id = ?",
            (actor_id, batch_id),
        ).fetchone()
    return int(row[0]) if row else 0
