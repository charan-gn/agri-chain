"""The ledger advertises itself over UDP for phone auto-detection on startup."""

from __future__ import annotations

from fastapi.testclient import TestClient

import app as ledger_app


class FakeResponder:
    def __init__(self, http_port: int) -> None:
        self.http_port = http_port
        self.stopped = False

    def stop(self) -> None:
        self.stopped = True


def test_create_app_starts_and_stops_discovery(monkeypatch):
    calls: list[int] = []
    responder = FakeResponder(http_port=8765)

    def fake_start_discovery(http_port: int) -> FakeResponder:
        calls.append(http_port)
        return responder

    monkeypatch.setattr(ledger_app, "start_discovery", fake_start_discovery)
    monkeypatch.setenv("AGRICHAIN_HTTP_PORT", "8765")

    with TestClient(ledger_app.app):
        assert calls == [8765]
        assert not responder.stopped

    assert responder.stopped


def test_discovery_can_be_disabled(monkeypatch):
    monkeypatch.setenv("AGRICHAIN_DISCOVERY", "0")
    assert ledger_app.start_discovery(8123) is None
