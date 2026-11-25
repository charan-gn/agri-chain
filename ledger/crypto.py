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


def canonical_event(event: dict) -> str:
    return json.dumps(
        {field: event[field] for field in EVENT_FIELDS},
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
