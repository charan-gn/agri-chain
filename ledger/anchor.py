from __future__ import annotations

import base64
import hashlib
import time
from pathlib import Path
from typing import Iterable

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from crypto import canonical_json, verify_signature

ROOT_FIELDS = ("height", "eventCount", "root", "ts", "nodeId", "pubKey")
EMPTY_ROOT = "0" * 64


def merkle_root(event_hashes: Iterable[str]) -> str:
    """Merkle root over lowercase hex event hashes, promoting odd nodes."""
    level = [bytes.fromhex(event_hash) for event_hash in event_hashes]
    if not level:
        return EMPTY_ROOT
    while len(level) > 1:
        next_level = []
        for index in range(0, len(level), 2):
            left = level[index]
            right = level[index + 1] if index + 1 < len(level) else left
            next_level.append(hashlib.sha256(left + right).digest())
        level = next_level
    return level[0].hex()


def load_or_create_key(path: str | Path) -> ec.EllipticCurvePrivateKey:
    key_path = Path(path)
    if key_path.exists():
        key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
        if not isinstance(key, ec.EllipticCurvePrivateKey):
            raise ValueError("node key must be an EC private key")
        if not isinstance(key.curve, ec.SECP256R1):
            raise ValueError("node key must use P-256")
        return key

    key_path.parent.mkdir(parents=True, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    key_path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    return key


def public_key_b64(key: ec.EllipticCurvePrivateKey) -> str:
    spki = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(spki).decode("ascii")


def node_id(key: ec.EllipticCurvePrivateKey) -> str:
    spki = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(spki).hexdigest()[:16]


def signed_root(
    event_hashes: Iterable[str],
    height: int,
    key: ec.EllipticCurvePrivateKey,
    ts: int | None = None,
) -> dict:
    hash_list = list(event_hashes)
    payload = {
        "height": int(height),
        "eventCount": len(hash_list),
        "root": merkle_root(hash_list),
        "ts": int(ts if ts is not None else time.time() * 1000),
        "nodeId": node_id(key),
        "pubKey": public_key_b64(key),
    }
    canonical = canonical_json(payload)
    signature = key.sign(canonical.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    return payload | {"sig": base64.b64encode(signature).decode("ascii")}


def verify_root(body: dict) -> bool:
    try:
        payload = {field: body[field] for field in ROOT_FIELDS}
        if not isinstance(payload["height"], int) or payload["height"] < 0:
            return False
        if not isinstance(payload["eventCount"], int) or payload["eventCount"] < 0:
            return False
        if not isinstance(payload["ts"], int):
            return False
        if not isinstance(payload["root"], str) or len(payload["root"]) != 64:
            return False
        canonical = canonical_json(payload)
        return verify_signature(
            payload["pubKey"],
            body["sig"],
            canonical.encode("utf-8"),
        )
    except (KeyError, TypeError, ValueError):
        return False


def compare_roots(primary: dict, secondary: dict) -> list[str]:
    """Return the differing fields; an empty list means the roots agree."""
    return [
        field
        for field in ("root", "height", "eventCount")
        if primary.get(field) != secondary.get(field)
    ]
