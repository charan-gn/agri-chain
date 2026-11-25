import json
import socket

from discovery import DiscoveryResponder, reply_for


def test_reply_for_probe_advertises_the_http_url():
    reply = reply_for(b"AGRICHAIN_DISCOVER", "http://192.168.1.5:8000")
    assert reply is not None
    assert json.loads(reply) == {
        "service": "agrichain",
        "baseUrl": "http://192.168.1.5:8000",
    }


def test_reply_for_ignores_other_payloads():
    assert reply_for(b"hello", "http://x:8000") is None
    assert reply_for(b"", "http://x:8000") is None
    assert reply_for(b"AGRICHAIN_DISCOVER extra", "http://x:8000") is None


def test_udp_roundtrip():
    responder = DiscoveryResponder(
        http_port=8123,
        port=0,
        host_ip="192.168.31.71",
    ).start()
    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client.settimeout(2)
    try:
        client.sendto(b"AGRICHAIN_DISCOVER", ("127.0.0.1", responder.port))
        data, _ = client.recvfrom(512)
    finally:
        client.close()
        responder.stop()

    assert json.loads(data) == {
        "service": "agrichain",
        "baseUrl": "http://192.168.31.71:8123",
    }
