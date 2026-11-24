from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app import create_app
from prices import SEED_PRICES


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as test_client:
        yield test_client


def test_prices_are_seeded_and_filterable(client):
    response = client.get("/prices")
    assert response.status_code == 200
    body = response.json()
    assert body["updatedAt"] > 0
    assert len(body["prices"]) == len(SEED_PRICES)
    assert {(item["crop"], item["market"]) for item in body["prices"]} == set(SEED_PRICES)
    for item in body["prices"]:
        assert isinstance(item["pricePerKgPaise"], int)
        assert item["pricePerKgPaise"] > 0
        assert item["source"] == "demo-mandi"
        assert isinstance(item["ts"], int)

    wheat = client.get("/prices", params={"crop": " Wheat "}).json()
    assert {item["market"] for item in wheat["prices"]} == {
        "warangal",
        "khammam",
        "nizamabad",
    }
    assert all(item["crop"] == "wheat" for item in wheat["prices"])

    single = client.get(
        "/prices", params={"crop": "wheat", "market": "warangal"}
    ).json()
    assert len(single["prices"]) == 1
    assert single["prices"][0]["pricePerKgPaise"] == SEED_PRICES[("wheat", "warangal")]


def test_refresh_updates_cache_and_returns_same_shape(client):
    before = client.get("/prices", params={"crop": "wheat"}).json()
    response = client.post("/prices/refresh", json={"crop": "wheat"})
    assert response.status_code == 200
    after = response.json()

    assert after["updatedAt"] >= before["updatedAt"]
    assert {item["market"] for item in after["prices"]} == {
        "warangal",
        "khammam",
        "nizamabad",
    }
    assert all(item["crop"] == "wheat" for item in after["prices"])
    assert all(isinstance(item["pricePerKgPaise"], int) for item in after["prices"])

    again = client.get("/prices", params={"crop": "wheat"}).json()
    assert again["updatedAt"] == after["updatedAt"]
    assert again["prices"] == after["prices"]


def test_refresh_with_no_body_updates_all_markets(client):
    response = client.post("/prices/refresh")
    assert response.status_code == 200
    body = response.json()
    assert len(body["prices"]) == len(SEED_PRICES)

    filtered = client.post(
        "/prices/refresh", json={"crop": "maize", "market": "khammam"}
    ).json()
    assert len(filtered["prices"]) == 1
    assert filtered["prices"][0]["crop"] == "maize"
    assert filtered["prices"][0]["market"] == "khammam"


def test_unknown_filters_return_empty_list(client):
    response = client.get("/prices", params={"crop": "saffron"})
    assert response.status_code == 200
    assert response.json() == {"updatedAt": 0, "prices": []}

    refresh = client.post("/prices/refresh", json={"crop": "saffron"})
    assert refresh.status_code == 200
    assert refresh.json() == {"updatedAt": 0, "prices": []}


def test_refresh_rejects_bad_payloads(client):
    array = client.post("/prices/refresh", json=[])
    assert array.status_code == 400
    assert array.json()["error"] == "bad_request"

    bad_type = client.post("/prices/refresh", json={"crop": 5})
    assert bad_type.status_code == 400
    assert bad_type.json()["error"] == "bad_request"

    bad_market = client.post("/prices/refresh", json={"market": ["warangal"]})
    assert bad_market.status_code == 400
    assert bad_market.json()["error"] == "bad_request"
