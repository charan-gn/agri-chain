from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import webapp.app as web  # noqa: E402
import webapp.mandi as mandi  # noqa: E402

KEY = "SECRET-TEST-KEY-9f8e7d6c"


class FakeResponse:
    def __init__(self, payload: dict) -> None:
        self._raw = json.dumps(payload).encode("utf-8")

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def read(self) -> bytes:
        return self._raw


@pytest.fixture(autouse=True)
def _isolated(monkeypatch) -> None:
    mandi.clear_cache()
    for name in ("DATA_GOV_IN_API_KEY", "MANDI_CACHE_SECONDS", "MANDI_TIMEOUT"):
        monkeypatch.delenv(name, raising=False)
    yield
    mandi.clear_cache()


def test_demo_feed_without_key_is_advisory(monkeypatch) -> None:
    def no_network(*args: object, **kwargs: object) -> None:
        raise AssertionError("urlopen must not be called without an API key")

    monkeypatch.setattr(mandi.urllib.request, "urlopen", no_network)
    payload = mandi.quotes()

    assert payload["advisory"] is True
    assert payload["configured"] is False
    assert payload["source"] == "demo"
    assert payload["sourceUrl"] == "https://agmarknet.gov.in/"
    assert mandi.SOURCE_URL in payload["sourceUrl"]
    assert mandi.RESOURCE_ID in mandi.API_BASE
    assert "not a signed AgriChain ledger price" in payload["disclaimer"]
    assert payload["observedDate"] == mandi.DEMO_DATE
    assert payload["fetchedAt"] > 0
    assert {"advisory", "configured", "source", "sourceUrl", "dataset", "fetchedAt",
            "observedDate", "quotes", "disclaimer"} <= set(payload)

    crops = {row["commodity"] for row in payload["quotes"]}
    assert {"Wheat", "Rice", "Maize", "Cotton", "Chilli"} <= crops
    for row in payload["quotes"]:
        assert row["source"] == "demo"
        for field in ("minPaisePerKg", "maxPaisePerKg", "modalPaisePerKg"):
            assert isinstance(row[field], int)
        assert all(row[field] for field in ("market", "state", "district", "date", "unit"))

    filtered = mandi.quotes(crop="wheat")
    assert [row["commodity"] for row in filtered["quotes"]] == ["Wheat"]


def test_upstream_records_convert_units_and_field_aliases(monkeypatch) -> None:
    monkeypatch.setenv("DATA_GOV_IN_API_KEY", KEY)
    urls: list[str] = []

    records = [
        {
            "commodity": "Wheat", "variety": "Lokwan", "state": "Maharashtra",
            "district": "Nagpur", "market": "Nagpur Grain Market",
            "arrival_date": "2026-11-27", "unit": "Rs./Quintal",
            "min_price": "2200", "max_price": "2500", "modal_price": "2350",
        },
        {
            "Commodity": "Rice", "variety": "Basmati", "state": "Punjab",
            "district": "Amritsar", "market": "Amritsar Mandi",
            "arrival_date": "2026-11-26", "unit": "Rs./Kg",
            "min": "40", "max": "48", "modal": "44",
        },
        {
            "commodity": "Maize", "variety": "Hybrid", "state": "Karnataka",
            "district": "Davanagere", "market": "Davanagere Mandi",
            "arrival_date": "2026-11-27", "unit": "Rs./Tonne",
            "min_price": 22000, "max_price": 24000, "modal_price": 23000,
        },
        {
            "commodity": "Chilli", "variety": "Guntur", "state": "Andhra Pradesh",
            "district": "Guntur", "market": "Guntur Mirchi Yard",
            "date": "2026-11-25", "price_unit": "Rs./Kg",
            "min_price": "180.4", "max_price": "190.6", "model_price": "185.5",
        },
    ]

    def fake_urlopen(request, timeout=None):
        urls.append(request.full_url)
        assert timeout == 8
        return FakeResponse({"records": records})

    monkeypatch.setattr(mandi.urllib.request, "urlopen", fake_urlopen)
    payload = mandi.quotes(limit=50)

    assert payload["advisory"] is True
    assert payload["configured"] is True
    assert payload["source"] == "data.gov.in"
    assert payload["observedDate"] == "2026-11-27"
    assert KEY not in json.dumps(payload)

    query = urllib.parse.parse_qs(urllib.parse.urlparse(urls[0]).query)
    assert query["api-key"] == [KEY]
    assert query["format"] == ["json"]
    assert query["limit"] == ["50"]

    rows = {row["commodity"]: row for row in payload["quotes"]}
    assert set(rows) == {"Wheat", "Rice", "Maize", "Chilli"}

    wheat = rows["Wheat"]
    assert (wheat["minPaisePerKg"], wheat["maxPaisePerKg"], wheat["modalPaisePerKg"]) == (
        2200, 2500, 2350
    )
    assert wheat["unit"] == "Rs./Quintal"
    assert wheat["date"] == "2026-11-27"
    assert wheat["market"] == "Nagpur Grain Market"
    assert wheat["variety"] == "Lokwan"
    assert wheat["source"] == "data.gov.in"

    rice = rows["Rice"]
    assert (rice["minPaisePerKg"], rice["maxPaisePerKg"], rice["modalPaisePerKg"]) == (
        4000, 4800, 4400
    )

    maize = rows["Maize"]
    assert (maize["minPaisePerKg"], maize["maxPaisePerKg"], maize["modalPaisePerKg"]) == (
        2200, 2400, 2300
    )

    chilli = rows["Chilli"]
    assert (chilli["minPaisePerKg"], chilli["maxPaisePerKg"], chilli["modalPaisePerKg"]) == (
        18040, 19060, 18550
    )
    assert chilli["unit"] == "Rs./Kg"

    # Filters are case-insensitive and reach the upstream request.
    filtered = mandi.quotes(state="punjab", crop="rice")
    assert [row["commodity"] for row in filtered["quotes"]] == ["Rice"]
    filtered_query = urllib.parse.parse_qs(urllib.parse.urlparse(urls[-1]).query)
    assert filtered_query["filters[state]"] == ["punjab"]
    assert filtered_query["filters[commodity]"] == ["rice"]
    assert KEY not in json.dumps(filtered)


def test_unknown_unit_row_is_skipped(monkeypatch) -> None:
    monkeypatch.setenv("DATA_GOV_IN_API_KEY", KEY)
    records = [
        {"commodity": "Bogus", "state": "Bihar", "market": "Patna",
         "arrival_date": "2026-11-27", "unit": "Rs./Basket",
         "min_price": 10, "max_price": 20, "modal_price": 15},
        {"commodity": "Tur", "state": "Bihar", "market": "Patna",
         "arrival_date": "2026-11-27", "unit": "Rs./Kg",
         "min_price": 70, "max_price": 90, "modal_price": 80},
    ]
    monkeypatch.setattr(
        mandi.urllib.request, "urlopen",
        lambda request, timeout=None: FakeResponse({"records": records}),
    )

    payload = mandi.quotes()
    assert [row["commodity"] for row in payload["quotes"]] == ["Tur"]
    assert payload["quotes"][0]["modalPaisePerKg"] == 8000


def test_upstream_error_falls_back_to_demo_without_key_leak(monkeypatch) -> None:
    monkeypatch.setenv("DATA_GOV_IN_API_KEY", KEY)

    def fail(request, timeout=None):
        raise urllib.error.URLError(f"{KEY} upstream exploded")

    monkeypatch.setattr(mandi.urllib.request, "urlopen", fail)
    payload = mandi.quotes()

    assert payload["advisory"] is True
    assert payload["configured"] is True
    assert payload["source"] == "demo fallback"
    assert len(payload["quotes"]) >= 5
    assert "error" in payload
    assert KEY not in json.dumps(payload)
    assert "exploded" not in payload["error"]
    assert KEY not in payload["error"]


def test_upstream_error_uses_cached_payload(monkeypatch) -> None:
    monkeypatch.setenv("DATA_GOV_IN_API_KEY", KEY)
    records = [{
        "commodity": "Wheat", "state": "Haryana", "market": "Karnal",
        "arrival_date": "2026-11-27", "unit": "Rs./Quintal",
        "min_price": 2300, "max_price": 2400, "modal_price": 2350,
    }]
    monkeypatch.setattr(
        mandi.urllib.request, "urlopen",
        lambda request, timeout=None: FakeResponse({"records": records}),
    )
    fresh = mandi.quotes()
    assert fresh["source"] == "data.gov.in"

    def fail(request, timeout=None):
        raise urllib.error.HTTPError("https://api.data.gov.in", 503, f"bad {KEY}", {}, None)

    # Force the cached entry to be stale so the next call reaches the failing
    # upstream while the last successful payload is still available.
    monkeypatch.setenv("MANDI_CACHE_SECONDS", "0")
    monkeypatch.setattr(mandi.urllib.request, "urlopen", fail)
    cached = mandi.quotes()
    assert cached["source"] == "data.gov.in (cached)"
    assert "HTTP 503" in cached["error"]
    assert [row["commodity"] for row in cached["quotes"]] == ["Wheat"]
    assert KEY not in json.dumps(cached)

    mandi.clear_cache()
    fallback = mandi.quotes()
    assert fallback["source"] == "demo fallback"
    assert KEY not in json.dumps(fallback)


def test_mandi_endpoint_is_advisory_and_distinct_from_prices(monkeypatch) -> None:
    monkeypatch.setattr(
        web, "ledger_call",
        lambda path, method="GET", body=None: {"path": path, "rows": []},
    )
    client = TestClient(web.app)

    mandi_response = client.get("/api/mandi")
    assert mandi_response.status_code == 200
    mandi_payload = mandi_response.json()
    assert mandi_payload["advisory"] is True
    assert mandi_payload["source"] == "demo"
    assert mandi_payload["disclaimer"] == mandi.DISCLAIMER
    assert "https://agmarknet.gov.in/" in mandi_payload["sourceUrl"]

    filtered = client.get("/api/mandi?crop=rice&limit=1").json()
    assert [row["commodity"] for row in filtered["quotes"]] == ["Rice"]

    prices_payload = client.get("/api/prices").json()
    assert prices_payload == {"path": "/v3/prices", "rows": []}
    assert "advisory" not in prices_payload
    assert prices_payload != mandi_payload
