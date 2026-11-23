from __future__ import annotations

import base64
import uuid

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app import create_app
from crypto import canonical_event, sha256_hex


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as test_client:
        yield test_client


def make_actor(client: TestClient, role: str = "farmer") -> tuple[str, ec.EllipticCurvePrivateKey]:
    key = ec.generate_private_key(ec.SECP256R1())
    actor_id = f"{role}:{uuid.uuid4()}"
    public_key = base64.b64encode(
        key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode()
    response = client.post(
        "/actors",
        json={
            "actorId": actor_id,
            "name": f"Test {role}",
            "role": role,
            "pubKey": public_key,
        },
    )
    assert response.status_code == 201, response.text
    return actor_id, key


def make_event(
    actor_id: str,
    key: ec.EllipticCurvePrivateKey,
    batch_id: str,
    event_type: str,
    data: dict,
    *,
    prev_hash: str = "",
    ts: int = 1764000000000,
    lat: str | None = None,
    lon: str | None = None,
) -> dict:
    fields = {
        "eventId": str(uuid.uuid4()),
        "batchId": batch_id,
        "type": event_type,
        "actorId": actor_id,
        "ts": ts,
        "lat": lat,
        "lon": lon,
        "data": data,
        "prevHash": prev_hash,
    }
    canonical = canonical_event(fields)
    signature = key.sign(canonical.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    return fields | {
        "hash": sha256_hex(canonical),
        "sig": base64.b64encode(signature).decode(),
    }


@pytest.fixture
def farmer(client):
    return make_actor(client, "farmer")


def test_contract_hash_vector():
    fields = {
        "eventId": "11111111-1111-4111-8111-111111111111",
        "batchId": "22222222-2222-4222-8222-222222222222",
        "type": "REGISTER",
        "actorId": "farmer:33333333-3333-4333-8333-333333333333",
        "ts": 1764000000000,
        "lat": None,
        "lon": None,
        "data": {"crop": "wheat", "grade": "A", "weightKg": 100},
        "prevHash": "",
    }
    canonical = canonical_event(fields)
    assert canonical == (
        '{"actorId":"farmer:33333333-3333-4333-8333-333333333333",'
        '"batchId":"22222222-2222-4222-8222-222222222222",'
        '"data":{"crop":"wheat","grade":"A","weightKg":100},'
        '"eventId":"11111111-1111-4111-8111-111111111111",'
        '"lat":null,"lon":null,"prevHash":"","ts":1764000000000,"type":"REGISTER"}'
    )
    assert sha256_hex(canonical) == (
        "cfad1baad5f9915b39aba2d330ca28b79feb12706d275fc7533c376361ebed80"
    )


def test_health(client):
    assert client.get("/health").json() == {"ok": True}


def test_actor_registration_and_lookup(client):
    actor_id, _ = make_actor(client, "buyer")
    response = client.get(f"/actors/{actor_id}")
    assert response.status_code == 200
    body = response.json()
    assert body["actorId"] == actor_id
    assert body["role"] == "buyer"


def test_actor_key_cannot_change(client):
    actor_id, _ = make_actor(client, "farmer")
    other_key = ec.generate_private_key(ec.SECP256R1())
    other_public = base64.b64encode(
        other_key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).decode()
    response = client.post(
        "/actors",
        json={
            "actorId": actor_id,
            "name": "Impostor",
            "role": "farmer",
            "pubKey": other_public,
        },
    )
    assert response.status_code == 409
    assert response.json()["error"] == "actor_key_mismatch"


def test_register_batch_and_read_summary(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    event = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )

    response = client.post("/events", json=event)
    assert response.status_code == 201, response.text
    assert response.json()["head"] == event["hash"]

    events = client.get(f"/events?batchId={batch_id}").json()
    assert events["head"] == event["hash"]
    assert [item["eventId"] for item in events["events"]] == [event["eventId"]]

    summary = client.get(f"/batches/{batch_id}").json()
    assert summary["crop"] == "wheat"
    assert summary["initialKg"] == 100
    assert summary["balances"] == {actor_id: 100}
    assert summary["ownerId"] == actor_id
    assert summary["tamperCount"] == 0
    assert summary["verified"] is True
    assert len(summary["events"]) == 1

    verification = client.get(f"/batches/{batch_id}/verify").json()
    assert verification == {
        "valid": True,
        "events": 1,
        "head": event["hash"],
    }


def test_rejects_bad_hash_and_signature(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    event = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "rice", "grade": "B", "weightKg": 50},
    )

    bad_hash = dict(event)
    bad_hash["hash"] = "0" * 64
    response = client.post("/events", json=bad_hash)
    assert response.status_code == 400
    assert response.json()["error"] == "bad_hash"

    bad_sig = dict(event)
    bad_sig["sig"] = base64.b64encode(b"not a signature").decode()
    response = client.post("/events", json=bad_sig)
    assert response.status_code == 400
    assert response.json()["error"] == "bad_signature"

    unknown_actor = dict(event)
    unknown_actor["actorId"] = f"farmer:{uuid.uuid4()}"
    response = client.post("/events", json=unknown_actor)
    assert response.status_code == 422
    assert response.json()["error"] == "unknown_actor"


def test_prev_hash_must_match_head(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    register = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "maize", "grade": "A", "weightKg": 20},
    )
    assert client.post("/events", json=register).status_code == 201

    gps = make_event(
        actor_id,
        key,
        batch_id,
        "GPS",
        {"accuracyM": 8},
        prev_hash="",
        lat="17.385044",
        lon="78.486671",
    )
    response = client.post("/events", json=gps)
    assert response.status_code == 409
    assert response.json()["error"] == "prev_mismatch"
    assert response.json()["head"] == register["hash"]


def test_transfer_moves_balance(client, farmer):
    actor_id, key = farmer
    buyer_id, _ = make_actor(client, "buyer")
    batch_id = str(uuid.uuid4())
    register = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )
    assert client.post("/events", json=register).status_code == 201

    transfer = make_event(
        actor_id,
        key,
        batch_id,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 40, "pricePerKgPaise": 2450},
        prev_hash=register["hash"],
    )
    assert client.post("/events", json=transfer).status_code == 201

    summary = client.get(f"/batches/{batch_id}").json()
    assert summary["balances"] == {actor_id: 60, buyer_id: 40}
    assert summary["ownerId"] == actor_id
    assert summary["verified"] is True

    buyer_batches = client.get(f"/batches?owner={buyer_id}").json()["batches"]
    assert [batch["batchId"] for batch in buyer_batches] == [batch_id]
    assert "events" not in buyer_batches[0]


def test_duplicate_event_is_idempotent(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    event = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "chilli", "grade": "C", "weightKg": 10},
    )
    first = client.post("/events", json=event)
    second = client.post("/events", json=event)
    assert first.status_code == 201
    assert second.status_code == 201
    assert first.json()["seq"] == second.json()["seq"]
    assert len(client.get(f"/events?batchId={batch_id}").json()["events"]) == 1


def test_bulk_returns_per_event_results(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    valid = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 5},
    )
    invalid = make_event(
        actor_id,
        key,
        str(uuid.uuid4()),
        "GPS",
        {"accuracyM": 10},
        lat="17.000000",
        lon="78.000000",
    )
    invalid["hash"] = "f" * 64

    response = client.post("/events/bulk", json={"events": [valid, invalid]})
    assert response.status_code == 200
    results = response.json()["results"]
    assert results[0]["ok"] is True
    assert results[1]["ok"] is False
    assert results[1]["error"] == "bad_hash"


def test_market_price_chain(client, farmer):
    actor_id, key = farmer
    first = make_event(
        actor_id,
        key,
        "market",
        "PRICE",
        {"crop": "wheat", "market": "warangal", "pricePerKgPaise": 2450, "source": "test"},
    )
    assert client.post("/events", json=first).status_code == 201
    second = make_event(
        actor_id,
        key,
        "market",
        "PRICE",
        {"crop": "wheat", "market": "warangal", "pricePerKgPaise": 2500, "source": "test"},
        prev_hash=first["hash"],
    )
    assert client.post("/events", json=second).status_code == 201

    events = client.get("/events?batchId=market").json()
    assert len(events["events"]) == 2
    assert client.get("/batches/market").status_code == 404
    assert client.get("/batches/market/verify").status_code == 404


def test_gps_requires_coordinates(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    register = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 30},
    )
    assert client.post("/events", json=register).status_code == 201

    gps_without_coordinates = make_event(
        actor_id,
        key,
        batch_id,
        "GPS",
        {"accuracyM": 5},
        prev_hash=register["hash"],
    )
    response = client.post("/events", json=gps_without_coordinates)
    assert response.status_code == 400
    assert response.json()["error"] == "bad_event"


def test_transfer_cannot_exceed_balance(client, farmer):
    actor_id, key = farmer
    batch_id = str(uuid.uuid4())
    register = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 10},
    )
    assert client.post("/events", json=register).status_code == 201

    transfer = make_event(
        actor_id,
        key,
        batch_id,
        "TRANSFER",
        {"toActorId": f"buyer:{uuid.uuid4()}", "tokens": 11},
        prev_hash=register["hash"],
    )
    response = client.post("/events", json=transfer)
    assert response.status_code == 409
    assert response.json()["error"] == "insufficient_balance"
    assert response.json()["balance"] == 10
    assert len(client.get(f"/events?batchId={batch_id}").json()["events"]) == 1


def test_buyer_can_resell_received_tokens(client, farmer):
    farmer_id, farmer_key = farmer
    buyer_id, buyer_key = make_actor(client, "buyer")
    final_buyer_id, _ = make_actor(client, "buyer")
    batch_id = str(uuid.uuid4())

    register = make_event(
        farmer_id,
        farmer_key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )
    assert client.post("/events", json=register).status_code == 201

    farmer_transfer = make_event(
        farmer_id,
        farmer_key,
        batch_id,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 40, "pricePerKgPaise": 2000},
        prev_hash=register["hash"],
    )
    assert client.post("/events", json=farmer_transfer).status_code == 201

    buyer_transfer = make_event(
        buyer_id,
        buyer_key,
        batch_id,
        "TRANSFER",
        {"toActorId": final_buyer_id, "tokens": 15, "pricePerKgPaise": 3000},
        prev_hash=farmer_transfer["hash"],
    )
    assert client.post("/events", json=buyer_transfer).status_code == 201

    summary = client.get(f"/batches/{batch_id}").json()
    assert summary["balances"] == {
        farmer_id: 60,
        buyer_id: 25,
        final_buyer_id: 15,
    }
    assert summary["verified"] is True


def test_trade_price_is_token_weighted(client, farmer):
    actor_id, key = farmer
    buyer_id, _ = make_actor(client, "buyer")

    wheat_batch = str(uuid.uuid4())
    wheat_register = make_event(
        actor_id,
        key,
        wheat_batch,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )
    assert client.post("/events", json=wheat_register).status_code == 201

    first = make_event(
        actor_id,
        key,
        wheat_batch,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 10, "pricePerKgPaise": 2000},
        prev_hash=wheat_register["hash"],
        ts=1764000001000,
    )
    assert client.post("/events", json=first).status_code == 201

    second = make_event(
        actor_id,
        key,
        wheat_batch,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 20, "pricePerKgPaise": 3000},
        prev_hash=first["hash"],
        ts=1764000002000,
    )
    assert client.post("/events", json=second).status_code == 201

    no_price = make_event(
        actor_id,
        key,
        wheat_batch,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 5},
        prev_hash=second["hash"],
        ts=1764000003000,
    )
    assert client.post("/events", json=no_price).status_code == 201

    rice_batch = str(uuid.uuid4())
    rice_register = make_event(
        actor_id,
        key,
        rice_batch,
        "REGISTER",
        {"crop": "rice", "grade": "A", "weightKg": 50},
    )
    assert client.post("/events", json=rice_register).status_code == 201
    rice_transfer = make_event(
        actor_id,
        key,
        rice_batch,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 10, "pricePerKgPaise": 9999},
        prev_hash=rice_register["hash"],
    )
    assert client.post("/events", json=rice_transfer).status_code == 201

    trades = client.get("/trades?crop=wheat").json()
    assert trades["crop"] == "wheat"
    assert trades["pricePerKgPaise"] == 2667
    assert [trade["eventId"] for trade in trades["trades"]] == [
        first["eventId"],
        second["eventId"],
    ]
    assert trades["trades"][0]["fromActorId"] == actor_id
    assert trades["trades"][0]["toActorId"] == buyer_id

    empty = client.get("/trades?crop=maize").json()
    assert empty["pricePerKgPaise"] is None
    assert empty["trades"] == []
