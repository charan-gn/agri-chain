from __future__ import annotations

import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app import create_app
from test_ledger import NOW_MS, make_actor, make_event


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as test_client:
        yield test_client


def register(client, farmer_id, farmer_key, crop="wheat", weight=100):
    batch_id = str(uuid.uuid4())
    event = make_event(
        farmer_id,
        farmer_key,
        batch_id,
        "REGISTER",
        {"crop": crop, "grade": "A", "weightKg": weight},
    )
    assert client.post("/events", json=event).status_code == 201
    return batch_id, event


def bind(client, farmer_id, farmer_key, batch_id, prev_hash, box_id):
    event = make_event(
        farmer_id,
        farmer_key,
        batch_id,
        "BIND",
        {"boxId": box_id},
        prev_hash=prev_hash,
    )
    assert client.post("/events", json=event).status_code == 201
    return event


def test_admin_token_gates_actor_registration(client, monkeypatch):
    monkeypatch.setenv("AGRICHAIN_ADMIN_TOKEN", "secret")
    assert client.post("/actors", json={}).status_code == 401
    assert (
        client.post("/actors", headers={"X-Admin-Token": "wrong"}, json={}).status_code
        == 401
    )
    # The token is accepted; the empty body then fails validation.
    assert (
        client.post("/actors", headers={"X-Admin-Token": "secret"}, json={}).status_code
        == 400
    )


def test_events_are_rate_limited_per_ip(client, monkeypatch):
    monkeypatch.setenv("AGRICHAIN_RATE_LIMIT", "2")
    assert client.post("/events", json={}).status_code == 400
    assert client.post("/events", json={}).status_code == 400
    limited = client.post("/events", json={})
    assert limited.status_code == 429
    assert limited.json()["error"] == "rate_limited"
    assert limited.json()["retry"] is True


def test_clock_skew_is_rejected_and_received_at_is_stored(client):
    farmer_id, key = make_actor(client, "farmer")
    batch_id = str(uuid.uuid4())
    now = int(time.time() * 1000)

    future = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 10},
        ts=now + 10 * 60 * 1000,
    )
    response = client.post("/events", json=future)
    assert response.status_code == 400
    assert response.json()["error"] == "bad_event"
    assert "future" in response.json()["detail"]

    past = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 10},
        ts=now - 31 * 24 * 60 * 60 * 1000,
    )
    response = client.post("/events", json=past)
    assert response.status_code == 400
    assert "past" in response.json()["detail"]

    valid = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 10},
        ts=now - 1000,
    )
    before = int(time.time() * 1000)
    response = client.post("/events", json=valid)
    after = int(time.time() * 1000)
    assert response.status_code == 201
    assert before <= response.json()["receivedAt"] <= after


def test_chains_are_per_actor_and_bind_gates_the_box(client):
    farmer_id, farmer_key = make_actor(client, "farmer")
    box_id, box_key = make_actor(client, "box")
    batch_id, register_event = register(client, farmer_id, farmer_key)

    unbound = make_event(
        box_id,
        box_key,
        batch_id,
        "GPS",
        {"accuracyM": 8},
        lat="17.385044",
        lon="78.486671",
    )
    response = client.post("/events", json=unbound)
    assert response.status_code == 409
    assert response.json()["error"] == "unbound_box"

    bind_event = bind(
        client, farmer_id, farmer_key, batch_id, register_event["hash"], box_id
    )
    gps = make_event(
        box_id,
        box_key,
        batch_id,
        "GPS",
        {"accuracyM": 8},
        lat="17.385044",
        lon="78.486671",
    )
    assert client.post("/events", json=gps).status_code == 201

    tamper = make_event(
        box_id,
        box_key,
        batch_id,
        "TAMPER",
        {
            "kind": "light",
            "traceHead": gps["hash"],
            "lastLat": "17.385044",
            "lastLon": "78.486671",
        },
    )
    assert client.post("/events", json=tamper).status_code == 201

    summary = client.get(f"/batches/{batch_id}").json()
    chains = {(chain["actorId"], chain["stream"]): chain for chain in summary["chains"]}
    assert chains[(farmer_id, "main")]["head"] == bind_event["hash"]
    assert chains[(farmer_id, "main")]["chainSeq"] == 2
    assert chains[(box_id, "trace")]["head"] == gps["hash"]
    assert chains[(box_id, "main")]["head"] == tamper["hash"]
    assert summary["verified"] is True

    trace = client.get(
        f"/events?batchId={batch_id}&actorId={box_id}&stream=trace"
    ).json()
    assert trace["chain"] == {"actorId": box_id, "stream": "trace"}
    assert [event["eventId"] for event in trace["events"]] == [gps["eventId"]]


def test_latest_bind_wins_and_null_unbinds(client):
    farmer_id, farmer_key = make_actor(client, "farmer")
    box_id, box_key = make_actor(client, "box")
    batch_id, register_event = register(client, farmer_id, farmer_key)
    bind_event = bind(
        client, farmer_id, farmer_key, batch_id, register_event["hash"], box_id
    )

    gps = make_event(
        box_id,
        box_key,
        batch_id,
        "GPS",
        {"accuracyM": 5},
        lat="17.385044",
        lon="78.486671",
    )
    assert client.post("/events", json=gps).status_code == 201

    unbind = make_event(
        farmer_id,
        farmer_key,
        batch_id,
        "BIND",
        {"boxId": None},
        prev_hash=bind_event["hash"],
    )
    assert client.post("/events", json=unbind).status_code == 201

    after_unbind = make_event(
        box_id,
        box_key,
        batch_id,
        "GPS",
        {"accuracyM": 5},
        prev_hash=gps["hash"],
        lat="17.385044",
        lon="78.486671",
    )
    response = client.post("/events", json=after_unbind)
    assert response.status_code == 409
    assert response.json()["error"] == "unbound_box"


def test_events_pagination_and_route(client):
    farmer_id, farmer_key = make_actor(client, "farmer")
    box_id, box_key = make_actor(client, "box")
    batch_id, register_event = register(client, farmer_id, farmer_key)
    bind(client, farmer_id, farmer_key, batch_id, register_event["hash"], box_id)

    previous = ""
    gps_events = []
    for index in range(3):
        gps = make_event(
            box_id,
            box_key,
            batch_id,
            "GPS",
            {"accuracyM": 5 + index},
            prev_hash=previous,
            ts=NOW_MS + 1000 + index,
            lat=f"17.38504{index}",
            lon="78.486671",
        )
        assert client.post("/events", json=gps).status_code == 201
        gps_events.append(gps)
        previous = gps["hash"]

    first_page = client.get(
        f"/events?batchId={batch_id}&actorId={box_id}&stream=trace&limit=2"
    ).json()
    assert [event["eventId"] for event in first_page["events"]] == [
        gps_events[2]["eventId"],
        gps_events[1]["eventId"],
    ]
    assert first_page["nextBefore"] == 2

    second_page = client.get(
        f"/events?batchId={batch_id}&actorId={box_id}&stream=trace"
        f"&limit=2&before={first_page['nextBefore']}"
    ).json()
    assert [event["eventId"] for event in second_page["events"]] == [
        gps_events[0]["eventId"]
    ]
    assert "nextBefore" not in second_page

    route = client.get(f"/batches/{batch_id}/route?max=2").json()
    assert route["truncated"] is True
    assert [point["chainSeq"] for point in route["points"]] == [2, 3]
    assert [point["lat"] for point in route["points"]] == ["17.385041", "17.385042"]


def test_idempotent_retry_and_event_id_conflict(client):
    farmer_id, key = make_actor(client, "farmer")
    batch_id = str(uuid.uuid4())
    event = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 20},
    )
    first = client.post("/events", json=event)
    second = client.post("/events", json=event)
    assert first.status_code == second.status_code == 201
    assert first.json()["seq"] == second.json()["seq"]
    assert first.json()["head"] == second.json()["head"] == event["hash"]

    conflict = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 21},
        event_id=event["eventId"],
    )
    response = client.post("/events", json=conflict)
    assert response.status_code == 409
    assert response.json()["error"] == "event_conflict"
    assert len(client.get(f"/events?batchId={batch_id}").json()["events"]) == 1


def test_only_the_holder_can_transfer(client):
    farmer_id, farmer_key = make_actor(client, "farmer")
    buyer_id, buyer_key = make_actor(client, "buyer")
    batch_id, _ = register(client, farmer_id, farmer_key)

    transfer = make_event(
        buyer_id,
        buyer_key,
        batch_id,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 1},
    )
    response = client.post("/events", json=transfer)
    assert response.status_code == 403
    assert response.json()["error"] == "forbidden"
    assert response.json()["retry"] is False


def test_non_ascii_values_round_trip(client):
    farmer_id, key = make_actor(client, "farmer")
    batch_id, event = register(client, farmer_id, key, crop="వరి 🌾", weight=7)
    summary = client.get(f"/batches/{batch_id}").json()
    assert summary["crop"] == "వరి 🌾"
    assert summary["verified"] is True
