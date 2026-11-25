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
    "TRANSFER",
    "GPS",
    "TAMPER",
    "DELIVER",
    "BARTER",
    "PRICE",
}

MARKET_BATCH = "market"


INT64_MIN = -(2**63)
INT64_MAX = 2**63 - 1


def validate_canonical(value: object, *, key: bool = False) -> None:
    """Validate the v2 canonical-JSON subset before encoding it.

    Keys are printable ASCII. String values are valid Unicode (control
    characters are allowed and escaped by json.dumps). Integers must fit a
    signed 64-bit value; floats are rejected.
    """
    if isinstance(value, str):
        if key:
            if any(not (0x20 <= ord(char) <= 0x7E) for char in value):
                raise ValueError("canonical JSON object keys must be printable ASCII")
            return
        for char in value:
            if 0xD800 <= ord(char) <= 0xDFFF:
                raise ValueError("canonical JSON strings must be valid Unicode")
        return
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, int):
        if not INT64_MIN <= value <= INT64_MAX:
            raise ValueError("canonical JSON integers must fit signed 64-bit")
        return
    if isinstance(value, float):
        raise ValueError("canonical JSON rejects floats")
    if isinstance(value, dict):
        for item_key, item_value in value.items():
            if not isinstance(item_key, str):
                raise ValueError("canonical JSON object keys must be strings")
            validate_canonical(item_key, key=True)
            validate_canonical(item_value)
        return
    if isinstance(value, list):
        for item in value:
            validate_canonical(item)
        return
    raise ValueError(
        f"unsupported canonical JSON value: {type(value).__name__}"
    )


def canonical_event(event: dict) -> str:
    fields = {field: event[field] for field in EVENT_FIELDS}
    validate_canonical(fields)
    return json.dumps(
        fields,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


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
