from __future__ import annotations

import sys
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import webapp.app as web  # noqa: E402

client = TestClient(web.app)


def test_config_and_static_pages() -> None:
    response = client.get("/api/config")
    assert response.status_code == 200
    assert response.json()["version"] == 3
    assert response.json()["ledgerUrl"].startswith("http")

    assert "AgriChain" in client.get("/").text
    assert "v3 explorer" in client.get("/explorer").text
    assert client.get("/assets/app.js").status_code == 200


def test_proxy_passes_v3_paths_and_queries(monkeypatch) -> None:
    calls: list[tuple[str, str, object]] = []

    def fake(path: str, method: str = "GET", body: object = None) -> dict:
        calls.append((path, method, body))
        return {"path": path, "method": method, "body": body}

    monkeypatch.setattr(web, "ledger_call", fake)

    assert client.get("/api/messages?type=ORDER&limit=5").json()["path"] == (
        "/v3/messages?type=ORDER&limit=5"
    )
    assert client.get("/api/listings?region=1&crop=2").json()["path"] == (
        "/v3/listings?region=1&crop=2"
    )
    assert client.get("/api/trades?crop=2").json()["path"] == "/v3/trades?crop=2"
    assert client.get("/api/prices?region=1").json()["path"] == "/v3/prices?region=1"
    assert client.get("/api/actors/abcdef0123456789").json()["path"] == (
        "/v3/actors/abcdef0123456789"
    )
    assert client.get("/api/checkpoint").json()["path"] == "/v3/checkpoint"
    assert all(call[1] == "GET" for call in calls)


def test_health_reports_v3_ledger(monkeypatch) -> None:
    seen = []

    def fake(path: str, method: str = "GET", body: object = None) -> dict:
        seen.append(path)
        return {"ok": True, "version": 3}

    monkeypatch.setattr(web, "ledger_call", fake)
    payload = client.get("/api/health").json()
    assert payload["ok"] is True
    assert payload["ledger"] == {"ok": True, "version": 3}
    assert seen == ["/v3/health"]


def test_upstream_failure_is_502(monkeypatch) -> None:
    def fail(path: str, method: str = "GET", body: object = None) -> dict:
        raise web.LedgerError(502, "ledger unreachable")

    monkeypatch.setattr(web, "ledger_call", fail)
    response = client.get("/api/stats")
    assert response.status_code == 502
    assert response.json()["error"] == "ledger_unreachable"


def test_upstream_404_passes_through(monkeypatch) -> None:
    def missing(path: str, method: str = "GET", body: object = None) -> dict:
        raise web.LedgerError(404, "message not found")

    monkeypatch.setattr(web, "ledger_call", missing)
    response = client.get("/api/messages/nope")
    assert response.status_code == 404
    assert response.json() == {"error": "ledger_error", "detail": "message not found"}
