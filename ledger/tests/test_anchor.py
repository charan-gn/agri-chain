from __future__ import annotations

import hashlib
import uuid

import pytest
from fastapi.testclient import TestClient

from anchor import (
    EMPTY_ROOT,
    compare_roots,
    load_or_create_key,
    merkle_root,
    public_key_b64,
    signed_root,
    verify_root,
)
from app import create_app
from crypto import sha256_hex
from mirror import check_roots
from test_ledger import make_actor, make_event


@pytest.fixture
def client(tmp_path):
    app = create_app(str(tmp_path / "ledger.db"), str(tmp_path / "node_key.pem"))
    with TestClient(app) as test_client:
        yield test_client


def leaf(text: str) -> str:
    return sha256_hex(text)


def test_merkle_root_promotes_odd_nodes():
    a, b, c = leaf("a"), leaf("b"), leaf("c")
    assert merkle_root([]) == EMPTY_ROOT
    assert merkle_root([a]) == a

    ab = hashlib.sha256(bytes.fromhex(a) + bytes.fromhex(b)).hexdigest()
    assert merkle_root([a, b]) == ab

    cc = hashlib.sha256(bytes.fromhex(c) + bytes.fromhex(c)).hexdigest()
    assert merkle_root([a, b, c]) == hashlib.sha256(
        bytes.fromhex(ab) + bytes.fromhex(cc)
    ).hexdigest()


def test_signed_root_round_trips_and_detects_tampering(tmp_path):
    key = load_or_create_key(tmp_path / "node.pem")
    hashes = [leaf("one"), leaf("two")]
    body = signed_root(hashes, height=2, key=key, ts=1764000000000)

    assert body["root"] == merkle_root(hashes)
    assert body["height"] == 2
    assert body["eventCount"] == 2
    assert body["nodeId"]
    assert body["pubKey"] == public_key_b64(key)
    assert verify_root(body) is True

    assert verify_root(dict(body, height=3)) is False
    assert verify_root(dict(body, root="f" * 64)) is False
    assert verify_root(dict(body, sig="not-base64")) is False


def test_node_key_is_persisted(tmp_path):
    path = tmp_path / "node.pem"
    first = load_or_create_key(path)
    second = load_or_create_key(path)
    assert public_key_b64(first) == public_key_b64(second)


def test_root_endpoint_tracks_the_ledger(client):
    farmer_id, key = make_actor(client, "farmer")
    buyer_id, _ = make_actor(client, "buyer")
    batch_id = str(uuid.uuid4())
    register = make_event(
        farmer_id,
        key,
        batch_id,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
    )
    assert client.post("/events", json=register).status_code == 201

    first = client.get("/ledger/root").json()
    assert first["height"] == 1
    assert first["eventCount"] == 1
    assert first["root"] == register["hash"]
    assert verify_root(first) is True

    transfer = make_event(
        farmer_id,
        key,
        batch_id,
        "TRANSFER",
        {"toActorId": buyer_id, "tokens": 10},
        prev_hash=register["hash"],
    )
    assert client.post("/events", json=transfer).status_code == 201

    second = client.get("/ledger/root").json()
    assert second["height"] == 2
    assert second["eventCount"] == 2
    assert second["root"] == merkle_root([register["hash"], transfer["hash"]])
    assert second["root"] != first["root"]
    assert second["nodeId"] == first["nodeId"]
    assert second["pubKey"] == first["pubKey"]
    assert verify_root(second) is True


def test_mirror_check_matches_and_reports_divergence(tmp_path, capsys):
    key = load_or_create_key(tmp_path / "node.pem")
    hashes = [leaf("one"), leaf("two")]
    primary = signed_root(hashes, 2, key, ts=1764000000000)
    same = signed_root(hashes, 2, key, ts=1764000001000)
    # Signatures cover ts, but the compared anchor fields agree.
    assert compare_roots(primary, same) == []
    assert check_roots(primary, same) == 0

    divergent = signed_root([leaf("one")], 1, key, ts=1764000000000)
    assert compare_roots(primary, divergent) == ["root", "height", "eventCount"]
    assert check_roots(primary, divergent) == 1
    assert check_roots(primary, dict(divergent, sig="bad")) == 2
    captured = capsys.readouterr()
    assert "DIVERGENCE" in captured.err
    assert "invalid" in captured.err
