import base64
import hashlib

from gateway.lora_gateway import (
    Gateway,
    LedgerClient,
    MAX_PACKET_BYTES,
    Reassembler,
    chunks,
    parse_part,
    short_id,
)


def envelope(size: int = 16) -> bytes:
    # ttl || version type flags ts(4) from(8) bodyLen(1) body
    body_len = max(0, size - 1 - 16)
    message = bytes([1, 0x10, 0]) + (1_700_000_000).to_bytes(4, "big") + bytes(range(8))
    message += bytes([body_len]) + bytes(body_len)
    return bytes([3]) + message


def test_short_id_is_msg_id_prefix():
    item = envelope()
    unsigned = item[1:]
    assert short_id(item) == hashlib.sha256(unsigned).hexdigest()[:6]


def test_chunks_fit_packet_and_round_trip():
    item = envelope(199)
    frames = chunks(item)
    assert len(frames) == 2
    assert all(len(frame.encode()) <= MAX_PACKET_BYTES for frame in frames)
    reassembler = Reassembler()
    result = None
    for index, frame in enumerate(reversed(frames)):
        result = reassembler.accept(frame, 1_000 + index)
    assert result == item
    assert reassembler.pending() == 0


def test_parse_rejects_foreign_and_bad_frames():
    assert parse_part("MC1:abcdef:1/1:AAAA") is None
    assert parse_part("LM1:zzzzzz:1/1:AAAA") is None
    assert parse_part("LM1:abcdef:2/1:AAAA") is None
    assert parse_part("LM1:abcdef:1/1:not base64!") is None
    assert parse_part("LM1:abcdef:1/9:AAAA") is not None


def test_reassembler_expires_and_caps_pending():
    reassembler = Reassembler(timeout_s=1, max_pending=1)
    frames = chunks(envelope(120), max_chunk=40)
    assert reassembler.accept(frames[0], 0) is None
    assert reassembler.pending() == 1
    assert reassembler.expire(10) == 1
    assert reassembler.pending() == 0


class FakeClient:
    def __init__(self):
        self.posted = []
        self.outbox = {"items": [], "nextAfterId": 0}

    def post_envelopes(self, envelopes, via="lora"):
        self.posted.extend(envelopes)
        return {"results": []}

    def pull_outbox(self, actors, after_id=0):
        return self.outbox


def test_gateway_posts_only_whole_envelopes():
    client = FakeClient()
    sent = []
    gateway = Gateway(client, sent.append, watch=["aa" * 8], clock=lambda: 1.0)
    item = envelope()
    frames = chunks(item, max_chunk=40)
    for frame in frames[:-1]:
        assert gateway.ingest(frame) is None
    assert gateway.ingest(frames[-1]) == item
    assert client.posted == [item]
    assert gateway.posted == 1


def test_gateway_drains_outbox_as_frames():
    client = FakeClient()
    item = envelope()
    client.outbox = {
        "items": [{"id": 7, "message": base64.b64encode(item).decode()}],
        "nextAfterId": 7,
    }
    sent = []
    gateway = Gateway(client, sent.append, watch=["bb" * 8])
    assert gateway.drain_outbox() == 1
    assert sent == chunks(item)
    assert gateway.after_id == 7


def test_ledger_client_payload_shape():
    captured = {}

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def read(self):
            return b'{"results": []}'

    def fake_urlopen(request, timeout):
        captured["url"] = request.full_url
        captured["body"] = request.data
        return Response()

    import gateway.lora_gateway as module

    original = module.urllib.request.urlopen
    module.urllib.request.urlopen = fake_urlopen
    try:
        client = LedgerClient("http://ledger.test/")
        client.post_envelopes([envelope()])
    finally:
        module.urllib.request.urlopen = original
    assert captured["url"] == "http://ledger.test/v3/messages"
    assert b'"via": "lora"' in captured["body"]
