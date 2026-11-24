import uuid

import pytest
from fastapi.testclient import TestClient

from app import create_app
from test_ledger import make_actor, make_event


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as test_client:
        yield test_client


def register_batch(client, actor_id, key, weight_kg=100):
    batch_id = str(uuid.uuid4())
    event = make_event(
        actor_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": weight_kg},
    )
    assert client.post("/events", json=event).status_code == 201
    return batch_id, event


def barter_event(
    actor_id,
    key,
    batch_id,
    prev_hash,
    offer_id,
    action,
    with_actor,
    give_kg,
    want_batch,
    want_kg,
    ts=1764000000000,
):
    return make_event(
        actor_id,
        key,
        batch_id,
        "BARTER",
        {
            "offerId": offer_id,
            "action": action,
            "withActorId": with_actor,
            "giveKg": give_kg,
            "wantBatchId": want_batch,
            "wantKg": want_kg,
        },
        prev_hash=prev_hash,
        ts=ts,
    )


def test_accept_swaps_balances_on_both_batches(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    give_batch, give_register = register_batch(client, giver, giver_key, 100)
    want_batch, want_register = register_batch(client, taker, taker_key, 50)

    offer_id = str(uuid.uuid4())
    offer = barter_event(
        giver,
        giver_key,
        give_batch,
        give_register["hash"],
        offer_id,
        "offer",
        taker,
        10,
        want_batch,
        5,
    )
    assert client.post("/events", json=offer).status_code == 201

    accept = barter_event(
        taker,
        taker_key,
        want_batch,
        want_register["hash"],
        offer_id,
        "accept",
        giver,
        5,
        give_batch,
        10,
    )
    assert client.post("/events", json=accept).status_code == 201

    give_summary = client.get(f"/batches/{give_batch}").json()
    assert give_summary["balances"] == {giver: 90, taker: 10}
    assert give_summary["verified"] is True

    want_summary = client.get(f"/batches/{want_batch}").json()
    assert want_summary["balances"] == {taker: 45, giver: 5}

    offers = client.get(f"/barter/offers?actorId={giver}").json()["offers"]
    assert len(offers) == 1
    assert offers[0]["status"] == "accepted"
    assert offers[0]["offerId"] == offer_id
    assert offers[0]["acceptActorId"] == taker
    assert client.get(f"/barter/offers/{offer_id}").json()["status"] == "accepted"
    assert client.get("/barter/offers?status=open").json()["offers"] == []


def test_accept_must_mirror_the_offer(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    give_batch, give_register = register_batch(client, giver, giver_key)
    want_batch, want_register = register_batch(client, taker, taker_key)

    offer_id = str(uuid.uuid4())
    offer = barter_event(
        giver, giver_key, give_batch, give_register["hash"],
        offer_id, "offer", taker, 10, want_batch, 5,
    )
    assert client.post("/events", json=offer).status_code == 201

    bad_accept = barter_event(
        taker, taker_key, want_batch, want_register["hash"],
        offer_id, "accept", giver, 6, give_batch, 10,
    )
    response = client.post("/events", json=bad_accept)
    assert response.status_code == 409
    assert response.json()["error"] == "offer_mismatch"
    assert len(client.get(f"/events?batchId={want_batch}").json()["events"]) == 1


def test_accept_requires_taker_balance(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    give_batch, give_register = register_batch(client, giver, giver_key, 100)
    want_batch, want_register = register_batch(client, taker, taker_key, 3)

    offer_id = str(uuid.uuid4())
    offer = barter_event(
        giver, giver_key, give_batch, give_register["hash"],
        offer_id, "offer", taker, 10, want_batch, 5,
    )
    assert client.post("/events", json=offer).status_code == 201

    accept = barter_event(
        taker, taker_key, want_batch, want_register["hash"],
        offer_id, "accept", giver, 5, give_batch, 10,
    )
    response = client.post("/events", json=accept)
    assert response.status_code == 409
    assert response.json()["error"] == "insufficient_balance"
    assert response.json()["balance"] == 3


def test_cancel_blocks_accept(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    give_batch, give_register = register_batch(client, giver, giver_key)
    want_batch, want_register = register_batch(client, taker, taker_key)

    offer_id = str(uuid.uuid4())
    offer = barter_event(
        giver, giver_key, give_batch, give_register["hash"],
        offer_id, "offer", taker, 10, want_batch, 5,
    )
    assert client.post("/events", json=offer).status_code == 201

    cancel = barter_event(
        giver, giver_key, give_batch, offer["hash"],
        offer_id, "cancel", taker, 10, want_batch, 5,
    )
    assert client.post("/events", json=cancel).status_code == 201
    assert client.get(f"/barter/offers/{offer_id}").json()["status"] == "canceled"

    accept = barter_event(
        taker, taker_key, want_batch, want_register["hash"],
        offer_id, "accept", giver, 5, give_batch, 10,
    )
    response = client.post("/events", json=accept)
    assert response.status_code == 409
    assert response.json()["error"] == "offer_not_open"


def test_duplicate_offer_id_is_rejected(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    give_batch, give_register = register_batch(client, giver, giver_key)
    want_batch, _ = register_batch(client, taker, taker_key)

    offer_id = str(uuid.uuid4())
    first = barter_event(
        giver, giver_key, give_batch, give_register["hash"],
        offer_id, "offer", taker, 5, want_batch, 2,
    )
    assert client.post("/events", json=first).status_code == 201

    duplicate = barter_event(
        giver, giver_key, give_batch, first["hash"],
        offer_id, "offer", taker, 5, want_batch, 2,
    )
    response = client.post("/events", json=duplicate)
    assert response.status_code == 409
    assert response.json()["error"] == "duplicate_offer"


def test_transfers_use_barter_adjusted_balances(client):
    giver, giver_key = make_actor(client, "farmer")
    taker, taker_key = make_actor(client, "farmer")
    buyer, _ = make_actor(client, "buyer")
    give_batch, give_register = register_batch(client, giver, giver_key, 100)
    want_batch, want_register = register_batch(client, taker, taker_key, 50)

    offer_id = str(uuid.uuid4())
    offer = barter_event(
        giver, giver_key, give_batch, give_register["hash"],
        offer_id, "offer", taker, 10, want_batch, 5,
    )
    assert client.post("/events", json=offer).status_code == 201
    accept = barter_event(
        taker, taker_key, want_batch, want_register["hash"],
        offer_id, "accept", giver, 5, give_batch, 10,
    )
    assert client.post("/events", json=accept).status_code == 201

    too_much = make_event(
        giver,
        giver_key,
        give_batch,
        "TRANSFER",
        {"toActorId": buyer, "tokens": 91},
        prev_hash=offer["hash"],
    )
    response = client.post("/events", json=too_much)
    assert response.status_code == 409
    assert response.json()["balance"] == 90

    exact = make_event(
        giver,
        giver_key,
        give_batch,
        "TRANSFER",
        {"toActorId": buyer, "tokens": 90},
        prev_hash=offer["hash"],
    )
    assert client.post("/events", json=exact).status_code == 201
    balances = client.get(f"/batches/{give_batch}").json()["balances"]
    assert balances == {giver: 0, taker: 10, buyer: 90}


def test_unknown_offer_and_oversized_offer(client):
    farmer, key = make_actor(client, "farmer")
    other, other_key = make_actor(client, "farmer")
    batch, register = register_batch(client, farmer, key, 5)
    want_batch, _ = register_batch(client, other, other_key, 10)

    unknown = barter_event(
        farmer, key, batch, register["hash"],
        str(uuid.uuid4()), "accept", other, 1, want_batch, 1,
    )
    response = client.post("/events", json=unknown)
    assert response.status_code == 404
    assert response.json()["error"] == "unknown_offer"

    oversized = barter_event(
        farmer, key, batch, register["hash"],
        str(uuid.uuid4()), "offer", other, 6, want_batch, 1,
    )
    response = client.post("/events", json=oversized)
    assert response.status_code == 409
    assert response.json()["balance"] == 5
