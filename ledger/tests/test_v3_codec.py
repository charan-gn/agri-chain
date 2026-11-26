from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "contracts" / "ref"))
import codec  # noqa: E402

VECTORS = json.loads((ROOT / "contracts" / "vectors.json").read_text(encoding="utf-8"))
KEYS = VECTORS["keys"]
CASES = VECTORS["vectors"]


def normalize(value):
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, tuple):
        return list(value)
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, dict):
        return {key: normalize(item) for key, item in value.items()}
    return value


def test_suite_covers_every_type_and_limits():
    covered = {case["typeId"] for case in CASES}
    assert covered == {int(item) for item in codec.MessageType}
    assert len(CASES) >= 19
    signed = [case for case in CASES if "signedMessageHex" in case]
    assert signed
    max_body = max(len(case["bodyHex"]) // 2 for case in CASES)
    assert max_body <= codec.MAX_BODY
    for case in CASES:
        assert len(case["unsignedHex"]) // 2 <= codec.MAX_MESSAGE - codec.SIG_LEN


@pytest.mark.parametrize("case", CASES, ids=[case["name"] for case in CASES])
def test_python_matches_every_vector(case):
    mtype = codec.MessageType(case["typeId"])
    body = codec.body_from_fields(mtype, case["body"])
    assert body.hex() == case["bodyHex"]
    assert normalize(codec.parse_body(mtype, body)) == normalize(case["body"])

    key = KEYS[case["from"]]
    unsigned = codec.encode_unsigned(
        mtype,
        bytes.fromhex(key["actorIdHex"]),
        body,
        case["ts"],
        to_id=(
            bytes.fromhex(KEYS[case["to"]]["actorIdHex"])
            if case["to"]
            else None
        ),
        flags=case["flags"],
    )
    assert unsigned.hex() == case["unsignedHex"]
    assert codec.message_id(unsigned).hex() == case["msgIdHex"]

    decoded = codec.decode(unsigned)
    assert decoded.type == int(mtype)
    assert decoded.ts == case["ts"]
    assert decoded.from_id.hex() == key["actorIdHex"]
    assert decoded.to_id is None if not case["to"] else decoded.to_id.hex() == KEYS[case["to"]]["actorIdHex"]
    assert decoded.signature is None
    assert codec.msg_id(decoded).hex() == case["msgIdHex"]


@pytest.mark.parametrize(
    "case",
    [case for case in CASES if "signedMessageHex" in case],
    ids=[case["name"] for case in CASES if "signedMessageHex" in case],
)
def test_static_signatures_verify_and_tampering_fails(case):
    message = bytes.fromhex(case["signedMessageHex"])
    pubkey = bytes.fromhex(KEYS[case["from"]]["pubkeyCompressedHex"])
    assert codec.verify_message(message, pubkey)
    assert not codec.verify_message(message, bytes.fromhex(KEYS["client"]["pubkeyCompressedHex"])) or case["from"] == "client"

    tampered = bytearray(message)
    tampered[len(tampered) - codec.SIG_LEN - 1] ^= 0x01
    assert not codec.verify_message(bytes(tampered), pubkey)


def test_sign_verify_roundtrip_and_der_conversion():
    key = codec.private_key_from_scalar(7)
    pubkey = codec.compressed_public_key(key)
    body = codec.order_body(bytes(range(12)), 10, 1500, 1766707200)
    unsigned = codec.encode_unsigned(
        codec.MessageType.ORDER, codec.actor_id(pubkey), body, 1764115200
    )
    signature = codec.sign_unsigned(unsigned, key)
    assert len(signature) == 64
    message = codec.decode(unsigned + signature)
    assert codec.verify_message(message, pubkey)
    assert not codec.verify_message(message, codec.compressed_public_key(codec.private_key_from_scalar(8)))


def test_envelope_roundtrip_and_limits():
    case = next(case for case in CASES if case["name"] == "listing-no-batch")
    message = bytes.fromhex(case["unsignedHex"])
    envelope = codec.encode_envelope(message, 7)
    ttl, inner = codec.decode_envelope(envelope)
    assert ttl == 7 and inner == message
    assert len(envelope) <= codec.MAX_MESSAGE + 1
    with pytest.raises(codec.CodecError):
        codec.encode_envelope(message, 0)
    with pytest.raises(codec.CodecError):
        codec.encode_envelope(b"\x00" * (codec.MAX_MESSAGE + 1), 7)


def test_body_and_message_size_limits():
    body = bytes(codec.MAX_BODY)
    with pytest.raises(codec.CodecError):
        codec.encode_unsigned(0x01, b"\x01" * 8, body + b"\x00", 1)
    unsigned = codec.encode_unsigned(0x01, b"\x01" * 8, body, 1)
    assert len(unsigned) == 126
    assert len(unsigned + b"\x01" * 64) == 190

    with_to = codec.encode_unsigned(0x01, b"\x01" * 8, body, 1, to_id=b"\x02" * 8)
    assert len(with_to) == 134
    assert len(with_to + b"\x01" * 64) == codec.MAX_MESSAGE


@pytest.mark.parametrize(
    "mutate",
    [
        lambda data: data[:2] + bytes([data[2] | 0x80]) + data[3:],
        lambda data: bytes([2]) + data[1:],
        lambda data: data[:-1],
        lambda data: data + b"\x00",
    ],
)
def test_bad_headers_are_rejected(mutate):
    case = next(case for case in CASES if case["name"] == "announce-basic")
    with pytest.raises(codec.CodecError):
        codec.decode(mutate(bytes.fromhex(case["unsignedHex"])))


def test_price_table_max_is_107_bytes_and_beacon_hash():
    case = next(case for case in CASES if case["name"] == "price-table-max")
    body = bytes.fromhex(case["bodyHex"])
    assert len(body) == 107
    assert len(codec.decode(bytes.fromhex(case["unsignedHex"])).body) == 107

    table = next(case for case in CASES if case["name"] == "price-table")
    beacon = next(case for case in CASES if case["name"] == "price-beacon")
    parsed = codec.parse_body(codec.MessageType.PRICE_BEACON, bytes.fromhex(beacon["bodyHex"]))
    assert parsed["hash8"] == codec.price_table_hash8([bytes.fromhex(table["bodyHex"])])
