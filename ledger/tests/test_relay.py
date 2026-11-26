from __future__ import annotations

from fastapi.testclient import TestClient

from app import create_app


def relay_row(envelope_id: str = "env-1", **overrides):
    row = {
        "envelopeId": envelope_id,
        "senderId": "phone-a",
        "senderName": "Ramesh",
        "recipientId": "phone-b",
        "recipientName": "Suresh",
        "body": "Land rate agreed",
        "ts": 1764160001000,
        "observerId": "phone-b",
        "direction": "in",
        "link": "wifi",
    }
    row.update(overrides)
    return row


def test_relay_monitor_rows_do_not_enter_the_event_chain(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as client:
        users = {"users": [{"userId": "phone-a", "name": "Ramesh", "ts": 1764160000000}]}
        assert client.post("/relay/users", json=users).status_code == 201
        message = client.post(
            "/relay/messages", json={"messages": [relay_row()]}
        )
        assert message.status_code == 201
        assert message.json() == {"accepted": 1, "received": 1}

        feed = client.get("/relay/messages").json()
        assert feed["messages"][0]["senderName"] == "Ramesh"
        assert feed["messages"][0]["recipientName"] == "Suresh"
        assert feed["users"][0]["name"] == "Ramesh"

        stats = client.get("/stats").json()
        assert stats["events"] == 0
        assert stats["batches"] == 0
        assert stats["recent"] == []


def test_relay_messages_are_deduped_by_envelope_id(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as client:
        duplicate = client.post(
            "/relay/messages",
            json={"messages": [relay_row(), relay_row(body="changed body")]},
        )
        assert duplicate.json() == {"accepted": 1, "received": 2}
        assert len(client.get("/relay/messages").json()["messages"]) == 1


def test_relay_endpoints_reject_bad_payloads(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"))
    with TestClient(app) as client:
        bad_message = relay_row(direction="sideways")
        assert client.post(
            "/relay/messages", json={"messages": [bad_message]}
        ).status_code == 400
        assert client.post(
            "/relay/users", json={"users": [{"userId": "x", "name": "", "ts": 1}]}
        ).status_code == 400
        assert client.post("/relay/messages", json={"messages": "nope"}).status_code == 400
        assert client.get("/relay/messages?limit=0").status_code == 400
