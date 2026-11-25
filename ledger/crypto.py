from __future__ import annotations

import base64
import binascii
import hashlib
import json

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_der_public_key

EVENT_FIELDS = (
    "eventId",
    "batchId",
    "type",
    "actorId",
    "ts",
    "lat",
    "lon",
    "data",
    "prevHash",
)

ALLOWED_TYPES = {
    "REGISTER",
    "BIND",
    "TRANSFER",
    "GPS",
    "TAMPER",
    "DELIVER",
    "BARTER",
}


def stream_for(event_type: str) -> str:
    """Chain stream: GPS fixes are trace evidence, everything else is main."""
    return "trace" if event_type == "GPS" else "main"


INT64_MIN = -(2**63)
INT64_MAX = 2**63 - 1


def _check_json(value: object, label: str = "value") -> None:
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, int):
        if value < INT64_MIN or value > INT64_MAX:
            raise ValueError(f"{label} must fit in a signed 64-bit integer")
        return
    if isinstance(value, float):
        raise ValueError(f"{label} must not be a float")
    if isinstance(value, str):
        if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            raise ValueError(f"{label} contains an invalid Unicode surrogate")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _check_json(item, f"{label}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError(f"{label} keys must be strings")
            if any(not (0x20 <= ord(char) <= 0x7E) for char in key):
                raise ValueError(f"{label} keys must be ASCII printable")
            _check_json(item, f"{label}.{key}")
        return
    raise ValueError(f"{label} must be a JSON value")


def canonical_json(value: object) -> str:
    """Canonical UTF-8 JSON: sorted keys, no whitespace, raw non-ASCII."""
    _check_json(value)
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def canonical_event(event: dict) -> str:
    return canonical_json({field: event[field] for field in EVENT_FIELDS})


def sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def load_public_key(pub_key_b64: str) -> ec.EllipticCurvePublicKey:
    raw = base64.b64decode(pub_key_b64, validate=True)
    key = load_der_public_key(raw)
    if not isinstance(key, ec.EllipticCurvePublicKey):
        raise ValueError("not an EC public key")
    if not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("public key must use P-256")
    return key


def validate_public_key(pub_key_b64: str) -> None:
    load_public_key(pub_key_b64)


def verify_signature(pub_key_b64: str, sig_b64: str, message: bytes) -> bool:
    try:
        signature = base64.b64decode(sig_b64, validate=True)
        load_public_key(pub_key_b64).verify(
            signature,
            message,
            ec.ECDSA(hashes.SHA256()),
        )
        return True
    except (InvalidSignature, ValueError, TypeError, binascii.Error):
        return False
