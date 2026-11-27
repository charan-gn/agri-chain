"""Shared v3 codec helpers for the ledger.

The normative encoder/decoder is ``contracts/ref/codec.py``.  The ledger keeps
using that module directly (no second implementation) and adds the small
ledger-side conveniences below: name tables, JSON shaping, ledger signatures,
checkpoint payloads and Merkle roots.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
import time
from functools import lru_cache
from pathlib import Path
from typing import Iterable, Sequence

from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parents[1]
REF_DIR = ROOT / "contracts" / "ref"
if str(REF_DIR) not in sys.path:
    sys.path.insert(0, str(REF_DIR))

import codec  # noqa: E402  (contracts/ref/codec.py)

MessageType = codec.MessageType
CodecError = codec.CodecError

VERSION = codec.VERSION
MAX_MESSAGE = codec.MAX_MESSAGE
MAX_BODY = codec.MAX_BODY
SIG_LEN = codec.SIG_LEN
ACTOR_ID_LEN = codec.ACTOR_ID_LEN
MSG_ID_LEN = codec.MSG_ID_LEN

FLAG_HAS_TO = codec.FLAG_HAS_TO
FLAG_LEDGER_SIGNED = codec.FLAG_LEDGER_SIGNED

STATUS_ACCEPTED = codec.STATUS_ACCEPTED
STATUS_DUPLICATE = codec.STATUS_DUPLICATE
STATUS_REJECTED = codec.STATUS_REJECTED
STATUS_PENDING = codec.STATUS_PENDING

REASON_NONE = codec.REASON_NONE
REASON_BAD_FORMAT = codec.REASON_BAD_FORMAT
REASON_BAD_SIGNATURE = codec.REASON_BAD_SIGNATURE
REASON_UNKNOWN_ACTOR = codec.REASON_UNKNOWN_ACTOR
REASON_NOT_AUTHORIZED = codec.REASON_NOT_AUTHORIZED
REASON_EXPIRED = codec.REASON_EXPIRED
REASON_DEPENDENCY_MISSING = codec.REASON_DEPENDENCY_MISSING
REASON_INSUFFICIENT_QTY = codec.REASON_INSUFFICIENT_QTY
REASON_PRICE_OUT_OF_BOUNDS = codec.REASON_PRICE_OUT_OF_BOUNDS
REASON_SELF_TRADE = codec.REASON_SELF_TRADE
REASON_RATE_LIMITED = codec.REASON_RATE_LIMITED
REASON_ALREADY_FINAL = codec.REASON_ALREADY_FINAL

NOTICE_ORDER_RECEIVED = codec.NOTICE_ORDER_RECEIVED
NOTICE_ORDER_ACCEPTED = codec.NOTICE_ORDER_ACCEPTED
NOTICE_ORDER_CANCELLED = codec.NOTICE_ORDER_CANCELLED
NOTICE_DELIVERED = codec.NOTICE_DELIVERED
NOTICE_TAMPER = codec.NOTICE_TAMPER

STATUS_NAMES = {
    STATUS_ACCEPTED: "accepted",
    STATUS_DUPLICATE: "duplicate",
    STATUS_REJECTED: "rejected",
    STATUS_PENDING: "pending",
}

REASON_NAMES = {
    REASON_NONE: "none",
    REASON_BAD_FORMAT: "bad_format",
    REASON_BAD_SIGNATURE: "bad_signature",
    REASON_UNKNOWN_ACTOR: "unknown_actor",
    REASON_NOT_AUTHORIZED: "not_authorized",
    REASON_EXPIRED: "expired",
    REASON_DEPENDENCY_MISSING: "dependency_missing",
    REASON_INSUFFICIENT_QTY: "insufficient_qty",
    REASON_PRICE_OUT_OF_BOUNDS: "price_out_of_bounds",
    REASON_SELF_TRADE: "self_trade",
    REASON_RATE_LIMITED: "rate_limited",
    REASON_ALREADY_FINAL: "already_final",
}

NOTICE_NAMES = {
    NOTICE_ORDER_RECEIVED: "ORDER_RECEIVED",
    NOTICE_ORDER_ACCEPTED: "ORDER_ACCEPTED",
    NOTICE_ORDER_CANCELLED: "ORDER_CANCELLED",
    NOTICE_DELIVERED: "DELIVERED",
    NOTICE_TAMPER: "TAMPER",
}

TYPE_NAMES = {int(item): item.name for item in MessageType}
NAME_TO_TYPE = {name: int(item) for item, name in TYPE_NAMES.items()}
LEDGER_TYPE_CODES = {int(item) for item in codec.LEDGER_TYPES}
CLIENT_TYPE_CODES = {int(item) for item in codec.CLIENT_TYPES}

CHECKPOINT_PREFIX = b"AC3-CHKPT"
EMPTY_MERKLE_ROOT = hashlib.sha256(b"").digest()


def type_name(code: int) -> str:
    return TYPE_NAMES.get(int(code), f"0x{int(code):02x}")


def status_name(code: int) -> str:
    return STATUS_NAMES.get(int(code), f"status_{int(code)}")


def reason_name(code: int) -> str:
    return REASON_NAMES.get(int(code), f"reason_{int(code)}")


def notice_name(code: int) -> str:
    return NOTICE_NAMES.get(int(code), f"notice_{int(code)}")


def is_ledger_type(code: int) -> bool:
    return int(code) in LEDGER_TYPE_CODES


def parse_type_filter(value: str | int | None) -> int | None:
    if value is None or value == "":
        return None
    if isinstance(value, int):
        return value
    text = str(value).strip()
    if text.lower() in {"all", "*"}:
        return None
    if text.isdigit():
        return int(text)
    if text.startswith("0x"):
        return int(text, 16)
    upper = text.upper()
    if upper in NAME_TO_TYPE:
        return NAME_TO_TYPE[upper]
    raise ValueError(f"unknown message type {value!r}")


@lru_cache(maxsize=1)
def contracts() -> dict:
    crops = json.loads((ROOT / "contracts" / "crops.json").read_text(encoding="utf-8"))
    regions = json.loads((ROOT / "contracts" / "regions.json").read_text(encoding="utf-8"))
    bounds = json.loads((ROOT / "contracts" / "price_bounds.json").read_text(encoding="utf-8"))
    crop_names = {int(item["id"]): item["name"] for item in crops["crops"]}
    crop_labels = {int(item["id"]): item["label"] for item in crops["crops"]}
    region_names = {int(item["id"]): item["name"] for item in regions["regions"]}
    region_labels = {int(item["id"]): item["label"] for item in regions["regions"]}
    return {
        "crops": crops,
        "regions": regions,
        "bounds": bounds,
        "cropNames": crop_names,
        "cropLabels": crop_labels,
        "regionNames": region_names,
        "regionLabels": region_labels,
    }


def known_crop(crop: int) -> bool:
    return int(crop) in contracts()["cropNames"]


def known_region(region: int) -> bool:
    return int(region) in contracts()["regionNames"]


def price_bounds(crop: int) -> tuple[int, int]:
    data = contracts()["bounds"]
    row = data.get("crops", {}).get(str(int(crop)))
    if row is None:
        row = data.get("default", {"min": 1, "max": 2**32 - 1})
    return int(row["min"]), int(row["max"])


def contracts_digest() -> dict:
    data = contracts()
    crops_raw = (ROOT / "contracts" / "crops.json").read_bytes()
    regions_raw = (ROOT / "contracts" / "regions.json").read_bytes()
    return {
        "cropsVersion": int(data["crops"].get("version", 0)),
        "regionsVersion": int(data["regions"].get("version", 0)),
        "cropsSha256": hashlib.sha256(crops_raw).hexdigest()[:16],
        "regionsSha256": hashlib.sha256(regions_raw).hexdigest()[:16],
    }


def actor_id(pubkey_compressed: bytes) -> bytes:
    return codec.actor_id(pubkey_compressed)


def actor_id_hex(pubkey_compressed: bytes) -> str:
    return actor_id(pubkey_compressed).hex()


def compressed_public_key(key: ec.EllipticCurvePrivateKey) -> bytes:
    return codec.compressed_public_key(key)


def load_private_key(path: str | Path) -> ec.EllipticCurvePrivateKey:
    from cryptography.hazmat.primitives import serialization

    key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise ValueError("ledger key must be a P-256 EC private key")
    return key


def load_or_create_private_key(path: str | Path) -> ec.EllipticCurvePrivateKey:
    from cryptography.hazmat.primitives import serialization

    key_path = Path(path)
    if key_path.exists():
        return load_private_key(key_path)
    key_path.parent.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    try:
        key_path.chmod(0o600)
    except OSError:
        pass
    return key


def sign_ledger_message(
    key: ec.EllipticCurvePrivateKey,
    mtype: int | MessageType,
    body: bytes,
    ts: int,
    to_id: bytes | None = None,
) -> bytes:
    from_id = actor_id(compressed_public_key(key))
    unsigned = codec.encode_unsigned(
        int(mtype),
        from_id,
        body,
        int(ts),
        to_id=to_id,
        flags=FLAG_LEDGER_SIGNED,
    )
    return unsigned + codec.sign_unsigned(unsigned, key)


def sign_client_message(
    key: ec.EllipticCurvePrivateKey,
    mtype: int | MessageType,
    body: bytes,
    ts: int,
    to_id: bytes | None = None,
) -> bytes:
    from_id = actor_id(compressed_public_key(key))
    unsigned = codec.encode_unsigned(int(mtype), from_id, body, int(ts), to_id=to_id)
    return unsigned + codec.sign_unsigned(unsigned, key)


def message_id_of(message: bytes) -> bytes:
    """msgId from a signed or unsigned message; last 64 bytes are the sig."""
    if len(message) >= SIG_LEN + 16:
        unsigned = message[:-SIG_LEN]
        try:
            codec.decode(unsigned)
        except CodecError:
            pass
        return hashlib.sha256(unsigned).digest()[:MSG_ID_LEN]
    return hashlib.sha256(message).digest()[:MSG_ID_LEN]


def safe_msg_id(raw: bytes) -> bytes:
    try:
        msg = codec.decode(raw)
    except CodecError:
        return message_id_of(raw)
    return codec.msg_id(msg)


def decode_message(raw: bytes) -> codec.Message:
    return codec.decode(raw)


def verify_message(raw: bytes, pubkey_compressed: bytes) -> bool:
    return codec.verify_message(raw, pubkey_compressed)


def parse_body(mtype: int, body: bytes) -> dict:
    return codec.parse_body(int(mtype), body)


def _jsonable(value):
    if isinstance(value, bytes):
        return value.hex()
    if isinstance(value, tuple):
        return [_jsonable(item) for item in value]
    if isinstance(value, list):
        return [_jsonable(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _jsonable(item) for key, item in value.items()}
    return value


def body_json(msg: codec.Message) -> dict:
    fields = parse_body(msg.type, msg.body)
    return _jsonable(fields)


def message_json(msg: codec.Message) -> dict:
    out = {
        "type": type_name(msg.type),
        "typeCode": int(msg.type),
        "ts": int(msg.ts),
        "fromId": msg.from_id.hex(),
        "toId": msg.to_id.hex() if msg.to_id else None,
        "flags": int(msg.flags),
        "ledgerSigned": bool(msg.flags & FLAG_LEDGER_SIGNED),
        "body": body_json(msg),
        "msgId": codec.msg_id(msg).hex(),
    }
    if msg.signature is not None:
        out["signature"] = msg.signature.hex()
    return out


def receipt_body(
    original_msg_id: bytes,
    status: int,
    reason: int,
    seq: int,
    head8: bytes,
) -> bytes:
    return codec.receipt_body(
        original_msg_id, int(status), int(reason), int(seq), head8
    )


def notice_body(
    kind: int,
    ref_id: bytes,
    seq: int,
    qty_kg: int = 0,
    price_paise: int = 0,
) -> bytes:
    return codec.notice_body(kind, ref_id, int(seq), int(qty_kg), int(price_paise))


def price_table_body(
    region: int,
    version: int,
    as_of: int,
    part: int,
    parts: int,
    rows: Sequence[tuple[int, int, int]],
) -> bytes:
    return codec.price_table_body(region, version, as_of, part, parts, rows)


def price_beacon_body(region: int, version: int, hash8: bytes) -> bytes:
    return codec.price_beacon_body(region, version, hash8)


def price_table_hash8(bodies: Iterable[bytes]) -> bytes:
    return codec.price_table_hash8(bodies)


def merkle_root(hashes: Iterable[bytes]) -> bytes:
    level = [bytes(item) for item in hashes]
    if not level:
        return EMPTY_MERKLE_ROOT
    while len(level) > 1:
        next_level = []
        for index in range(0, len(level), 2):
            left = level[index]
            right = level[index + 1] if index + 1 < len(level) else left
            next_level.append(hashlib.sha256(left + right).digest())
        level = next_level
    return level[0]


def checkpoint_payload(seq: int, root: bytes) -> bytes:
    if len(root) != 32:
        raise ValueError("checkpoint root must be 32 bytes")
    return CHECKPOINT_PREFIX + struct.pack(">I", int(seq)) + root


def sign_checkpoint(
    key: ec.EllipticCurvePrivateKey, seq: int, root: bytes
) -> bytes:
    return codec.sign_unsigned(checkpoint_payload(seq, root), key)


def verify_checkpoint(
    pubkey_compressed: bytes, seq: int, root: bytes, signature: bytes
) -> bool:
    try:
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import ec as ec_mod
        from cryptography.hazmat.primitives.asymmetric.utils import (
            encode_dss_signature,
        )

        if len(signature) != SIG_LEN:
            return False
        r = int.from_bytes(signature[:32], "big")
        s = int.from_bytes(signature[32:], "big")
        public_key = codec.public_key_from_compressed(pubkey_compressed)
        public_key.verify(
            encode_dss_signature(r, s),
            checkpoint_payload(seq, root),
            ec_mod.ECDSA(hashes.SHA256()),
        )
        return True
    except Exception:
        return False


def now_seconds() -> int:
    return int(time.time())


def u32(value: int) -> int:
    return int(value) & 0xFFFFFFFF
