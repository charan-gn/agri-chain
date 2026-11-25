from __future__ import annotations

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


def test_dashboard_and_static_assets_are_served(client):
    response = client.get("/")
    assert response.status_code == 200
    assert "AgriChain Ledger" in response.text
    assert client.get("/dashboard").status_code == 200

    stylesheet = client.get("/static/style.css")
    assert stylesheet.status_code == 200
    assert "text/css" in stylesheet.headers["content-type"]

    script = client.get("/static/app.js")
    assert script.status_code == 200
    assert "javascript" in script.headers["content-type"]


def test_stats_and_recent_events(client):
    farmer_id, key = make_actor(client)
    batch_id = str(uuid.uuid4())
    register = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )
    assert client.post("/events", json=register).status_code == 201

    tamper = make_event(
        farmer_id,
        key,
        batch_id,
        "TAMPER",
        {"kind": "light", "detail": "lid opened"},
        prev_hash=register["hash"],
        ts=1764000001000,
    )
    assert client.post("/events", json=tamper).status_code == 201

    stats = client.get("/stats?limit=5").json()
    assert stats["actors"] == 1
    assert stats["batches"] == 1
    assert stats["events"] == 2
    assert stats["tamperEvents"] == 1
    assert stats["validChains"] == 1
    assert [event["type"] for event in stats["recent"]] == ["TAMPER", "REGISTER"]
    assert stats["recent"][0]["crop"] == "wheat"

    recent = client.get("/events/recent?limit=1").json()
    assert len(recent["events"]) == 1
    assert recent["events"][0]["type"] == "TAMPER"

    assert client.get("/events/recent?limit=0").status_code == 400
