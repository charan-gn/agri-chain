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
    assert response.json()["ledgerUrl"].startswith("http")

    assert "AgriChain" in client.get("/").text
    assert "Trace a batch" in client.get("/trace").text
    assert client.get("/assets/app.js").status_code == 200


def test_proxy_passes_json_and_queries(monkeypatch) -> None:
    calls: list[tuple[str, str, object]] = []

    def fake(path: str, method: str = "GET", body: object = None) -> dict:
        calls.append((path, method, body))
        return {"path": path, "method": method, "body": body}

    monkeypatch.setattr(web, "ledger_call", fake)

    assert client.get("/api/batches").json()["path"] == "/batches"
    assert client.get("/api/batches/abc").json()["path"] == "/batches/abc"
    assert client.get("/api/offers?actorId=farmer%3A1&status=open").json()["path"] == (
        "/barter/offers?actorId=farmer%3A1&status=open"
    )
    refreshed = client.post("/api/prices/refresh", json={"crop": "wheat"}).json()
    assert refreshed == {"path": "/prices/refresh", "method": "POST", "body": {"crop": "wheat"}}


def test_health_reports_ledger(monkeypatch) -> None:
    monkeypatch.setattr(web, "ledger_call", lambda path, method="GET", body=None: {"ok": True})
    payload = client.get("/api/health").json()
    assert payload["ok"] is True
    assert payload["ledger"] == {"ok": True}


def test_upstream_failure_is_502(monkeypatch) -> None:
    def fail(path: str, method: str = "GET", body: object = None) -> dict:
        raise web.LedgerError(502, "ledger unreachable")

    monkeypatch.setattr(web, "ledger_call", fail)
    response = client.get("/api/batches")
    assert response.status_code == 502
    assert response.json()["error"] == "ledger_unreachable"


def test_upstream_404_passes_through(monkeypatch) -> None:
    def missing(path: str, method: str = "GET", body: object = None) -> dict:
        raise web.LedgerError(404, "batch not found")

    monkeypatch.setattr(web, "ledger_call", missing)
    response = client.get("/api/batches/nope")
    assert response.status_code == 404
    assert response.json() == {"error": "ledger_error", "detail": "batch not found"}
