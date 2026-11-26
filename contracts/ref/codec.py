"""Reference codec for the AgriChain v3 wire format.

Pure Python, no FastAPI. This module is normative for tests and is mirrored by
`android/app/src/main/java/com/agrichain/relay/V3Codec.kt`.

Run `python3 contracts/ref/codec.py` to print a digest of the checked-in
vectors; `python3 contracts/ref/make_vectors.py` regenerates them.
"""

from __future__ import annotations

import hashlib
import struct
from dataclasses import dataclass
from enum import IntEnum
from typing import Iterable, Sequence

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import (
    decode_dss_signature,
    encode_dss_signature,
)

VERSION = 1
MAX_MESSAGE = 198
MAX_BODY = 110
SIG_LEN = 64
ACTOR_ID_LEN = 8
MSG_ID_LEN = 12
ID12_LEN = 12

FLAG_HAS_TO = 0x01
FLAG_LEDGER_SIGNED = 0x02
FLAG_RESERVED = 0xFC


class MessageType(IntEnum):
    ANNOUNCE = 0x01
    LISTING = 0x10
    ORDER = 0x11
    ORDER_ACCEPT = 0x12
    ORDER_CANCEL = 0x13
    DELIVERED = 0x14
    BARTER_OFFER = 0x15
    BARTER_ACCEPT = 0x16
    TAMPER_ALERT = 0x20
    BATCH_REGISTER = 0x21
    BIND = 0x23
    PRICE_TABLE = 0x40
    PRICE_BEACON = 0x41
    RECEIPT = 0x42
    NOTICE = 0x43


LEDGER_TYPES = {
    MessageType.PRICE_TABLE,
    MessageType.PRICE_BEACON,
    MessageType.RECEIPT,
    MessageType.NOTICE,
}

CLIENT_TYPES = set(MessageType) - LEDGER_TYPES

STATUS_ACCEPTED = 0
STATUS_DUPLICATE = 1
STATUS_REJECTED = 2
STATUS_PENDING = 3

REASON_NONE = 0
REASON_BAD_FORMAT = 1
REASON_BAD_SIGNATURE = 2
REASON_UNKNOWN_ACTOR = 3
REASON_NOT_AUTHORIZED = 4
REASON_EXPIRED = 5
REASON_DEPENDENCY_MISSING = 6
REASON_INSUFFICIENT_QTY = 7
REASON_PRICE_OUT_OF_BOUNDS = 8
REASON_SELF_TRADE = 9
REASON_RATE_LIMITED = 10
REASON_ALREADY_FINAL = 11

NOTICE_ORDER_RECEIVED = 1
NOTICE_ORDER_ACCEPTED = 2
NOTICE_ORDER_CANCELLED = 3
NOTICE_DELIVERED = 4
NOTICE_TAMPER = 5


class CodecError(ValueError):
    pass


@dataclass(frozen=True)
class Message:
    type: int
    ts: int
    from_id: bytes
    body: bytes
    flags: int = 0
    to_id: bytes | None = None
    signature: bytes | None = None
    version: int = VERSION

    @property
    def has_to(self) -> bool:
        return bool(self.flags & FLAG_HAS_TO)

    @property
    def ledger_signed(self) -> bool:
        return bool(self.flags & FLAG_LEDGER_SIGNED)


# ---------------------------------------------------------------- primitives


def _u8(value: int, name: str) -> bytes:
    if not 0 <= value <= 0xFF:
        raise CodecError(f"{name} out of u8 range")
    return struct.pack(">B", value)


def _u24(value: int, name: str) -> bytes:
    if not 0 <= value <= 0xFFFFFF:
        raise CodecError(f"{name} out of u24 range")
    return value.to_bytes(3, "big")


def _u32(value: int, name: str) -> bytes:
    if not 0 <= value <= 0xFFFFFFFF:
        raise CodecError(f"{name} out of u32 range")
    return struct.pack(">I", value)


def _i32(value: int, name: str) -> bytes:
    if not -(2**31) <= value < 2**31:
        raise CodecError(f"{name} out of i32 range")
    return struct.pack(">i", value)


def _bytes(value: bytes, length: int, name: str) -> bytes:
    if len(value) != length:
        raise CodecError(f"{name} must be {length} bytes")
    return value


def _utf8(value: str, max_len: int, name: str) -> bytes:
    encoded = value.encode("utf-8")
    if not encoded or len(encoded) > max_len:
        raise CodecError(f"{name} must be 1..{max_len} UTF-8 bytes")
    return encoded


def _check_type(mtype: int) -> MessageType:
    try:
        return MessageType(mtype)
    except ValueError as exc:
        raise CodecError(f"unknown message type 0x{mtype:02x}") from exc


# ---------------------------------------------------------------- identities


def compressed_public_key(private_key: ec.EllipticCurvePrivateKey) -> bytes:
    numbers = private_key.public_key().public_numbers()
    return bytes([2 + (numbers.y & 1)]) + numbers.x.to_bytes(32, "big")


def actor_id(pubkey_compressed: bytes) -> bytes:
    _bytes(pubkey_compressed, 33, "pubkey")
    if pubkey_compressed[0] not in (2, 3):
        raise CodecError("pubkey must be compressed")
    return hashlib.sha256(pubkey_compressed).digest()[:ACTOR_ID_LEN]


def actor_id_hex(pubkey_compressed: bytes) -> str:
    return actor_id(pubkey_compressed).hex()


def private_key_from_scalar(scalar: int) -> ec.EllipticCurvePrivateKey:
    return ec.derive_private_key(scalar, ec.SECP256R1())


def public_key_from_compressed(pubkey_compressed: bytes) -> ec.EllipticCurvePublicKey:
    _bytes(pubkey_compressed, 33, "pubkey")
    return ec.EllipticCurvePublicKey.from_encoded_point(
        ec.SECP256R1(), pubkey_compressed
    )


# ---------------------------------------------------------------- encoding


def encode_unsigned(
    mtype: int,
    from_id: bytes,
    body: bytes,
    ts: int,
    to_id: bytes | None = None,
    flags: int = 0,
) -> bytes:
    _check_type(mtype)
    _bytes(from_id, ACTOR_ID_LEN, "fromId")
    if len(body) > MAX_BODY:
        raise CodecError(f"body over {MAX_BODY} bytes")
    if flags & FLAG_RESERVED:
        raise CodecError("reserved flag bits set")
    if to_id is not None:
        _bytes(to_id, ACTOR_ID_LEN, "toId")
        flags |= FLAG_HAS_TO
    elif flags & FLAG_HAS_TO:
        raise CodecError("hasTo set without toId")

    header = (
        bytes([VERSION, int(mtype), flags])
        + _u32(ts, "ts")
        + from_id
        + (to_id if to_id is not None else b"")
        + bytes([len(body)])
        + body
    )
    if len(header) > MAX_MESSAGE - SIG_LEN:
        raise CodecError("unsigned message too large")
    return header


def message_id(unsigned: bytes) -> bytes:
    if len(unsigned) + SIG_LEN > MAX_MESSAGE:
        raise CodecError("message too large")
    return hashlib.sha256(unsigned).digest()[:MSG_ID_LEN]


def msg_id(msg: Message | bytes) -> bytes:
    if isinstance(msg, bytes):
        msg = decode(msg)
    return message_id(encode_unsigned(
        msg.type, msg.from_id, msg.body, msg.ts, msg.to_id, msg.flags
    ))


def sign_unsigned(
    unsigned: bytes, private_key: ec.EllipticCurvePrivateKey
) -> bytes:
    der = private_key.sign(unsigned, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return r.to_bytes(32, "big") + s.to_bytes(32, "big")


def sign_message(msg: Message, private_key: ec.EllipticCurvePrivateKey) -> bytes:
    if msg.signature is not None:
        raise CodecError("message already signed")
    unsigned = encode_unsigned(
        msg.type, msg.from_id, msg.body, msg.ts, msg.to_id, msg.flags
    )
    if actor_id(compressed_public_key(private_key)) != msg.from_id:
        raise CodecError("private key does not match fromId")
    return unsigned + sign_unsigned(unsigned, private_key)


def encode_message(
    mtype: int,
    private_key: ec.EllipticCurvePrivateKey,
    body: bytes,
    ts: int,
    to_id: bytes | None = None,
    flags: int = 0,
) -> bytes:
    pubkey = compressed_public_key(private_key)
    unsigned = encode_unsigned(
        mtype, actor_id(pubkey), body, ts, to_id=to_id, flags=flags
    )
    return unsigned + sign_unsigned(unsigned, private_key)


def encode_envelope(message: bytes, ttl: int) -> bytes:
    if not 1 <= ttl <= 0xFF:
        raise CodecError("ttl out of range")
    if len(message) > MAX_MESSAGE:
        raise CodecError("message too large")
    return bytes([ttl]) + message


def decode_envelope(data: bytes) -> tuple[int, bytes]:
    if len(data) < 2 or len(data) > MAX_MESSAGE + 1:
        raise CodecError("bad envelope length")
    ttl = data[0]
    if ttl == 0:
        raise CodecError("ttl must be >= 1")
    return ttl, data[1:]


# ---------------------------------------------------------------- decoding


def decode(data: bytes) -> Message:
    if len(data) < 18 or len(data) > MAX_MESSAGE:
        raise CodecError("bad message length")
    version, mtype, flags = data[0], data[1], data[2]
    if version != VERSION:
        raise CodecError("unsupported version")
    _check_type(mtype)
    if flags & FLAG_RESERVED:
        raise CodecError("reserved flag bits set")

    has_to = bool(flags & FLAG_HAS_TO)
    header_len = 16 + (ACTOR_ID_LEN if has_to else 0)
    if len(data) < header_len:
        raise CodecError("truncated header")

    ts = struct.unpack(">I", data[3:7])[0]
    from_id = data[7:15]
    offset = 15
    to_id = None
    if has_to:
        to_id = data[15:23]
        offset = 23
    body_len = data[offset]
    if body_len > MAX_BODY:
        raise CodecError("body too large")
    unsigned_len = header_len + body_len
    if len(data) == unsigned_len:
        signature = None
    elif len(data) == unsigned_len + SIG_LEN:
        signature = data[unsigned_len:]
        if signature == b"\x00" * SIG_LEN:
            raise CodecError("empty signature")
    else:
        raise CodecError("length does not match bodyLen/signature")

    body = data[header_len:unsigned_len]
    return Message(
        type=mtype,
        ts=ts,
        from_id=from_id,
        body=body,
        flags=flags,
        to_id=to_id,
        signature=signature,
    )


def verify_message(
    msg: Message | bytes,
    pubkey_compressed: bytes,
    check_from_id: bool = True,
) -> bool:
    try:
        if isinstance(msg, bytes):
            msg = decode(msg)
        if msg.signature is None or len(msg.signature) != SIG_LEN:
            return False
        if check_from_id and actor_id(pubkey_compressed) != msg.from_id:
            return False
        unsigned = encode_unsigned(
            msg.type, msg.from_id, msg.body, msg.ts, msg.to_id, msg.flags
        )
        r = int.from_bytes(msg.signature[:32], "big")
        s = int.from_bytes(msg.signature[32:], "big")
        der = encode_dss_signature(r, s)
        public_key_from_compressed(pubkey_compressed).verify(
            der, unsigned, ec.ECDSA(hashes.SHA256())
        )
        return True
    except Exception:
        return False


# ---------------------------------------------------------------- bodies


def announce_body(pubkey_compressed: bytes, caps: int, nick: str) -> bytes:
    _bytes(pubkey_compressed, 33, "pubkey")
    nick_bytes = _utf8(nick, 20, "nick")
    return pubkey_compressed + _u8(caps, "caps") + _u8(len(nick_bytes), "nickLen") + nick_bytes


def listing_body(
    crop: int,
    qty_kg: int,
    ask_paise: int,
    region: int,
    valid_until: int,
    grade: int,
    batch_id: bytes = b"\x00" * ID12_LEN,
) -> bytes:
    return (
        _u8(crop, "crop")
        + _u32(qty_kg, "qtyKg")
        + _u32(ask_paise, "askPaise")
        + _u8(region, "region")
        + _u32(valid_until, "validUntil")
        + _u8(grade, "grade")
        + _bytes(batch_id, ID12_LEN, "batchId")
    )


def order_body(
    listing_id: bytes, qty_kg: int, bid_paise: int, valid_until: int
) -> bytes:
    return (
        _bytes(listing_id, ID12_LEN, "listingId")
        + _u32(qty_kg, "qtyKg")
        + _u32(bid_paise, "bidPaise")
        + _u32(valid_until, "validUntil")
    )


def order_accept_body(order_id: bytes) -> bytes:
    return _bytes(order_id, ID12_LEN, "orderId")


def order_cancel_body(order_id: bytes) -> bytes:
    return order_accept_body(order_id)


def delivered_body(order_id: bytes) -> bytes:
    return order_accept_body(order_id)


def barter_offer_body(
    give_crop: int,
    give_kg: int,
    want_crop: int,
    want_kg: int,
    region: int,
    valid_until: int,
) -> bytes:
    return (
        _u8(give_crop, "giveCrop")
        + _u32(give_kg, "giveKg")
        + _u8(want_crop, "wantCrop")
        + _u32(want_kg, "wantKg")
        + _u8(region, "region")
        + _u32(valid_until, "validUntil")
    )


def barter_accept_body(offer_id: bytes) -> bytes:
    return _bytes(offer_id, ID12_LEN, "offerId")


def tamper_alert_body(
    batch_id: bytes, kind: int, lat_micro: int, lon_micro: int, detected_ts: int
) -> bytes:
    return (
        _bytes(batch_id, ID12_LEN, "batchId")
        + _u8(kind, "kind")
        + _i32(lat_micro, "lat")
        + _i32(lon_micro, "lon")
        + _u32(detected_ts, "detectedTs")
    )


def batch_register_body(
    crop: int, weight_kg: int, harvest_ts: int, region: int, grade: int
) -> bytes:
    return (
        _u8(crop, "crop")
        + _u32(weight_kg, "weightKg")
        + _u32(harvest_ts, "harvestTs")
        + _u8(region, "region")
        + _u8(grade, "grade")
    )


def bind_body(batch_id: bytes, box_id: bytes) -> bytes:
    return _bytes(batch_id, ID12_LEN, "batchId") + _bytes(box_id, ACTOR_ID_LEN, "boxId")


def price_table_body(
    region: int,
    version: int,
    as_of: int,
    part: int,
    parts: int,
    rows: Sequence[tuple[int, int, int]],
) -> bytes:
    if not 1 <= len(rows) <= 19:
        raise CodecError("price table needs 1..19 rows")
    if not 1 <= part <= parts <= 0xFF:
        raise CodecError("bad part/parts")
    body = (
        _u8(region, "region")
        + _u32(version, "version")
        + _u32(as_of, "asOf")
        + _u8(part, "part")
        + _u8(parts, "parts")
        + _u8(len(rows), "n")
    )
    for crop, price_paise, trades in rows:
        body += _u8(crop, "crop") + _u24(price_paise, "pricePaise") + _u8(trades, "trades")
    return body


def price_beacon_body(region: int, version: int, table_hash8: bytes) -> bytes:
    return _u8(region, "region") + _u32(version, "version") + _bytes(
        table_hash8, 8, "hash8"
    )


def receipt_body(
    msg_id12: bytes, status: int, reason: int, seq: int, head8: bytes
) -> bytes:
    return (
        _bytes(msg_id12, MSG_ID_LEN, "msgId")
        + _u8(status, "status")
        + _u8(reason, "reason")
        + _u32(seq, "seq")
        + _bytes(head8, 8, "head8")
    )


def notice_body(
    kind: int, ref_id: bytes, seq: int, qty_kg: int, price_paise: int
) -> bytes:
    return (
        _u8(kind, "kind")
        + _bytes(ref_id, ID12_LEN, "refId")
        + _u32(seq, "seq")
        + _u32(qty_kg, "qtyKg")
        + _u32(price_paise, "pricePaise")
    )


# ---------------------------------------------------------------- body parsing


def _take(body: bytes, offset: int, size: int, name: str) -> tuple[bytes, int]:
    end = offset + size
    if end > len(body):
        raise CodecError(f"truncated {name}")
    return body[offset:end], end


def _read_u8(body: bytes, offset: int, name: str) -> tuple[int, int]:
    raw, offset = _take(body, offset, 1, name)
    return raw[0], offset


def _read_u24(body: bytes, offset: int, name: str) -> tuple[int, int]:
    raw, offset = _take(body, offset, 3, name)
    return int.from_bytes(raw, "big"), offset


def _read_u32(body: bytes, offset: int, name: str) -> tuple[int, int]:
    raw, offset = _take(body, offset, 4, name)
    return int.from_bytes(raw, "big"), offset


def _read_i32(body: bytes, offset: int, name: str) -> tuple[int, int]:
    raw, offset = _take(body, offset, 4, name)
    return int.from_bytes(raw, "big", signed=True), offset


def parse_body(mtype: int, body: bytes) -> dict:
    t = _check_type(mtype)
    if len(body) > MAX_BODY:
        raise CodecError("body too large")
    offset = 0
    out: dict = {}
    if t is MessageType.ANNOUNCE:
        out["pubkey"], offset = _take(body, offset, 33, "pubkey")
        out["caps"], offset = _read_u8(body, offset, "caps")
        nick_len, offset = _read_u8(body, offset, "nickLen")
        if not 1 <= nick_len <= 20:
            raise CodecError("bad nickLen")
        raw, offset = _take(body, offset, nick_len, "nick")
        out["nick"] = raw.decode("utf-8")
    elif t is MessageType.LISTING:
        out["crop"], offset = _read_u8(body, offset, "crop")
        out["qtyKg"], offset = _read_u32(body, offset, "qtyKg")
        out["askPaise"], offset = _read_u32(body, offset, "askPaise")
        out["region"], offset = _read_u8(body, offset, "region")
        out["validUntil"], offset = _read_u32(body, offset, "validUntil")
        out["grade"], offset = _read_u8(body, offset, "grade")
        out["batchId"], offset = _take(body, offset, ID12_LEN, "batchId")
    elif t is MessageType.ORDER:
        out["listingId"], offset = _take(body, offset, ID12_LEN, "listingId")
        out["qtyKg"], offset = _read_u32(body, offset, "qtyKg")
        out["bidPaise"], offset = _read_u32(body, offset, "bidPaise")
        out["validUntil"], offset = _read_u32(body, offset, "validUntil")
    elif t in (MessageType.ORDER_ACCEPT, MessageType.ORDER_CANCEL, MessageType.DELIVERED):
        out["orderId"], offset = _take(body, offset, ID12_LEN, "orderId")
    elif t is MessageType.BARTER_OFFER:
        out["giveCrop"], offset = _read_u8(body, offset, "giveCrop")
        out["giveKg"], offset = _read_u32(body, offset, "giveKg")
        out["wantCrop"], offset = _read_u8(body, offset, "wantCrop")
        out["wantKg"], offset = _read_u32(body, offset, "wantKg")
        out["region"], offset = _read_u8(body, offset, "region")
        out["validUntil"], offset = _read_u32(body, offset, "validUntil")
    elif t is MessageType.BARTER_ACCEPT:
        out["offerId"], offset = _take(body, offset, ID12_LEN, "offerId")
    elif t is MessageType.TAMPER_ALERT:
        out["batchId"], offset = _take(body, offset, ID12_LEN, "batchId")
        out["kind"], offset = _read_u8(body, offset, "kind")
        out["lat"], offset = _read_i32(body, offset, "lat")
        out["lon"], offset = _read_i32(body, offset, "lon")
        out["detectedTs"], offset = _read_u32(body, offset, "detectedTs")
    elif t is MessageType.BATCH_REGISTER:
        out["crop"], offset = _read_u8(body, offset, "crop")
        out["weightKg"], offset = _read_u32(body, offset, "weightKg")
        out["harvestTs"], offset = _read_u32(body, offset, "harvestTs")
        out["region"], offset = _read_u8(body, offset, "region")
        out["grade"], offset = _read_u8(body, offset, "grade")
    elif t is MessageType.BIND:
        out["batchId"], offset = _take(body, offset, ID12_LEN, "batchId")
        out["boxId"], offset = _take(body, offset, ACTOR_ID_LEN, "boxId")
    elif t is MessageType.PRICE_TABLE:
        out["region"], offset = _read_u8(body, offset, "region")
        out["version"], offset = _read_u32(body, offset, "version")
        out["asOf"], offset = _read_u32(body, offset, "asOf")
        out["part"], offset = _read_u8(body, offset, "part")
        out["parts"], offset = _read_u8(body, offset, "parts")
        n, offset = _read_u8(body, offset, "n")
        if not 1 <= n <= 19:
            raise CodecError("bad table row count")
        rows = []
        for _ in range(n):
            crop, offset = _read_u8(body, offset, "crop")
            price, offset = _read_u24(body, offset, "pricePaise")
            trades, offset = _read_u8(body, offset, "trades")
            rows.append((crop, price, trades))
        out["rows"] = rows
    elif t is MessageType.PRICE_BEACON:
        out["region"], offset = _read_u8(body, offset, "region")
        out["version"], offset = _read_u32(body, offset, "version")
        out["hash8"], offset = _take(body, offset, 8, "hash8")
    elif t is MessageType.RECEIPT:
        out["msgId"], offset = _take(body, offset, MSG_ID_LEN, "msgId")
        out["status"], offset = _read_u8(body, offset, "status")
        out["reason"], offset = _read_u8(body, offset, "reason")
        out["seq"], offset = _read_u32(body, offset, "seq")
        out["head8"], offset = _take(body, offset, 8, "head8")
    elif t is MessageType.NOTICE:
        out["kind"], offset = _read_u8(body, offset, "kind")
        out["refId"], offset = _take(body, offset, ID12_LEN, "refId")
        out["seq"], offset = _read_u32(body, offset, "seq")
        out["qtyKg"], offset = _read_u32(body, offset, "qtyKg")
        out["pricePaise"], offset = _read_u32(body, offset, "pricePaise")
    if offset != len(body):
        raise CodecError(f"trailing bytes in {t.name} body")
    return out


def price_table_hash8(bodies: Iterable[bytes]) -> bytes:
    """Hash of the concatenated table parts, used by PRICE_BEACON."""
    return hashlib.sha256(b"".join(bodies)).digest()[:8]


def hexmsg(data: bytes) -> str:
    return data.hex()


if __name__ == "__main__":
    import json
    from pathlib import Path

    path = Path(__file__).resolve().parents[1] / "vectors.json"
    vectors = json.loads(path.read_text(encoding="utf-8"))["vectors"]
    print(f"{len(vectors)} v3 vectors, sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")


def body_from_fields(mtype: int, fields: dict) -> bytes:
    """Build a body from the JSON-friendly shape used by vectors.json."""
    t = _check_type(mtype)

    def raw(name: str) -> bytes:
        value = fields[name]
        return bytes.fromhex(value) if isinstance(value, str) else value

    if t is MessageType.ANNOUNCE:
        return announce_body(raw("pubkey"), int(fields["caps"]), str(fields["nick"]))
    if t is MessageType.LISTING:
        return listing_body(
            int(fields["crop"]),
            int(fields["qtyKg"]),
            int(fields["askPaise"]),
            int(fields["region"]),
            int(fields["validUntil"]),
            int(fields["grade"]),
            raw("batchId"),
        )
    if t is MessageType.ORDER:
        return order_body(
            raw("listingId"),
            int(fields["qtyKg"]),
            int(fields["bidPaise"]),
            int(fields["validUntil"]),
        )
    if t is MessageType.ORDER_ACCEPT:
        return order_accept_body(raw("orderId"))
    if t is MessageType.ORDER_CANCEL:
        return order_cancel_body(raw("orderId"))
    if t is MessageType.DELIVERED:
        return delivered_body(raw("orderId"))
    if t is MessageType.BARTER_OFFER:
        return barter_offer_body(
            int(fields["giveCrop"]),
            int(fields["giveKg"]),
            int(fields["wantCrop"]),
            int(fields["wantKg"]),
            int(fields["region"]),
            int(fields["validUntil"]),
        )
    if t is MessageType.BARTER_ACCEPT:
        return barter_accept_body(raw("offerId"))
    if t is MessageType.TAMPER_ALERT:
        return tamper_alert_body(
            raw("batchId"),
            int(fields["kind"]),
            int(fields["lat"]),
            int(fields["lon"]),
            int(fields["detectedTs"]),
        )
    if t is MessageType.BATCH_REGISTER:
        return batch_register_body(
            int(fields["crop"]),
            int(fields["weightKg"]),
            int(fields["harvestTs"]),
            int(fields["region"]),
            int(fields["grade"]),
        )
    if t is MessageType.BIND:
        return bind_body(raw("batchId"), raw("boxId"))
    if t is MessageType.PRICE_TABLE:
        rows = [(int(c), int(p), int(n)) for c, p, n in fields["rows"]]
        return price_table_body(
            int(fields["region"]),
            int(fields["version"]),
            int(fields["asOf"]),
            int(fields["part"]),
            int(fields["parts"]),
            rows,
        )
    if t is MessageType.PRICE_BEACON:
        return price_beacon_body(
            int(fields["region"]), int(fields["version"]), raw("hash8")
        )
    if t is MessageType.RECEIPT:
        return receipt_body(
            raw("msgId"),
            int(fields["status"]),
            int(fields["reason"]),
            int(fields["seq"]),
            raw("head8"),
        )
    if t is MessageType.NOTICE:
        return notice_body(
            int(fields["kind"]),
            raw("refId"),
            int(fields["seq"]),
            int(fields["qtyKg"]),
            int(fields["pricePaise"]),
        )
    raise CodecError(f"no body builder for {t.name}")
