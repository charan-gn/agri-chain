"""Regenerate contracts/vectors.json for the v3 wire format.

The unsigned forms are deterministic. The two `signedMessageHex` fixtures are
static samples (ECDSA is randomized) and are verified, never re-signed in tests.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codec  # noqa: E402

BASE_TS = 1764115200
VALID_UNTIL = 1766707200
BATCH = bytes.fromhex("0102030405060708090a0b0c")
LISTING_ID = bytes.fromhex("101112131415161718191a1b")
ORDER_ID = bytes.fromhex("202122232425262728292a2b")
OFFER_ID = bytes.fromhex("303132333435363738393a3b")
HEAD8 = bytes.fromhex("a1a2a3a4a5a6a7a8")
SCALARS = {
    "client": 1,
    "ledger": 2,
    "seller": 3,
    "buyer": 4,
    "box": 5,
}

KEYS: dict[str, dict] = {}
PRIVATE: dict[str, object] = {}
IDS: dict[str, bytes] = {}
for name, scalar in SCALARS.items():
    key = codec.private_key_from_scalar(scalar)
    pub = codec.compressed_public_key(key)
    PRIVATE[name] = key
    IDS[name] = codec.actor_id(pub)
    KEYS[name] = {
        "scalarHex": scalar.to_bytes(32, "big").hex(),
        "pubkeyCompressedHex": pub.hex(),
        "actorIdHex": IDS[name].hex(),
    }

VECTORS: list[dict] = []


def add(
    name: str,
    mtype: codec.MessageType,
    from_key: str,
    fields: dict,
    to_key: str | None = None,
    signed: bool = False,
) -> dict:
    body = codec.body_from_fields(mtype, fields)
    to_id = IDS[to_key] if to_key else None
    flags = codec.FLAG_LEDGER_SIGNED if mtype in codec.LEDGER_TYPES else 0
    unsigned = codec.encode_unsigned(
        mtype, IDS[from_key], body, BASE_TS, to_id=to_id, flags=flags
    )
    vector = {
        "name": name,
        "type": mtype.name,
        "typeId": int(mtype),
        "flags": unsigned[2],
        "ts": BASE_TS,
        "from": from_key,
        "to": to_key,
        "body": fields,
        "bodyHex": body.hex(),
        "unsignedHex": unsigned.hex(),
        "msgIdHex": codec.message_id(unsigned).hex(),
    }
    if signed:
        message = codec.Message(
            type=int(mtype),
            ts=BASE_TS,
            from_id=IDS[from_key],
            body=body,
            flags=flags,
            to_id=to_id,
        )
        vector["signedMessageHex"] = codec.sign_message(
            message, PRIVATE[from_key]
        ).hex()
    VECTORS.append(vector)
    return vector


add(
    "announce-basic",
    codec.MessageType.ANNOUNCE,
    "client",
    {"pubkey": KEYS["client"]["pubkeyCompressedHex"], "caps": 31, "nick": "Asha"},
    signed=True,
)
add(
    "announce-unicode",
    codec.MessageType.ANNOUNCE,
    "client",
    {
        "pubkey": KEYS["client"]["pubkeyCompressedHex"],
        "caps": 0b00011111,
        "nick": "వరి",
    },
)
add(
    "announce-max-nick",
    codec.MessageType.ANNOUNCE,
    "client",
    {
        "pubkey": KEYS["client"]["pubkeyCompressedHex"],
        "caps": 0b00010001,
        "nick": "Farmer12345678901234",
    },
)
listing = add(
    "listing-no-batch",
    codec.MessageType.LISTING,
    "seller",
    {
        "crop": 1,
        "qtyKg": 1200,
        "askPaise": 2500,
        "region": 1,
        "validUntil": VALID_UNTIL,
        "grade": 1,
        "batchId": bytes(12).hex(),
    },
)
add(
    "listing-with-batch",
    codec.MessageType.LISTING,
    "seller",
    {
        "crop": 2,
        "qtyKg": 800,
        "askPaise": 3100,
        "region": 2,
        "validUntil": VALID_UNTIL,
        "grade": 2,
        "batchId": BATCH.hex(),
    },
)
add(
    "order",
    codec.MessageType.ORDER,
    "buyer",
    {
        "listingId": LISTING_ID.hex(),
        "qtyKg": 500,
        "bidPaise": 2450,
        "validUntil": VALID_UNTIL,
    },
    to_key="seller",
)
add(
    "order-accept",
    codec.MessageType.ORDER_ACCEPT,
    "seller",
    {"orderId": ORDER_ID.hex()},
    to_key="buyer",
)
add(
    "order-cancel",
    codec.MessageType.ORDER_CANCEL,
    "buyer",
    {"orderId": ORDER_ID.hex()},
    to_key="seller",
)
add(
    "delivered",
    codec.MessageType.DELIVERED,
    "buyer",
    {"orderId": ORDER_ID.hex()},
    to_key="seller",
)
add(
    "barter-offer",
    codec.MessageType.BARTER_OFFER,
    "seller",
    {
        "giveCrop": 1,
        "giveKg": 100,
        "wantCrop": 2,
        "wantKg": 40,
        "region": 1,
        "validUntil": VALID_UNTIL,
    },
)
add(
    "barter-accept",
    codec.MessageType.BARTER_ACCEPT,
    "buyer",
    {"offerId": OFFER_ID.hex()},
    to_key="seller",
)
add(
    "tamper-alert",
    codec.MessageType.TAMPER_ALERT,
    "box",
    {
        "batchId": BATCH.hex(),
        "kind": 1,
        "lat": 17385000,
        "lon": 78486700,
        "detectedTs": BASE_TS - 600,
    },
)
add(
    "batch-register",
    codec.MessageType.BATCH_REGISTER,
    "seller",
    {
        "crop": 2,
        "weightKg": 800,
        "harvestTs": BASE_TS - 86400,
        "region": 2,
        "grade": 2,
    },
)
add(
    "bind",
    codec.MessageType.BIND,
    "seller",
    {"batchId": BATCH.hex(), "boxId": IDS["box"].hex()},
)

TABLE_FIELDS = {
    "region": 1,
    "version": 3,
    "asOf": BASE_TS,
    "part": 1,
    "parts": 1,
    "rows": [[1, 2500, 12], [2, 3100, 8]],
}
table = add(
    "price-table",
    codec.MessageType.PRICE_TABLE,
    "ledger",
    TABLE_FIELDS,
    signed=True,
)
table_body = bytes.fromhex(table["bodyHex"])

add(
    "price-table-max",
    codec.MessageType.PRICE_TABLE,
    "ledger",
    {
        "region": 2,
        "version": 4,
        "asOf": BASE_TS,
        "part": 2,
        "parts": 2,
        "rows": [[(i % 8) + 1, 1000000 + i, i] for i in range(19)],
    },
)
add(
    "price-beacon",
    codec.MessageType.PRICE_BEACON,
    "ledger",
    {
        "region": 1,
        "version": 3,
        "hash8": codec.price_table_hash8([table_body]).hex(),
    },
)
add(
    "receipt-accepted",
    codec.MessageType.RECEIPT,
    "ledger",
    {
        "msgId": listing["msgIdHex"],
        "status": codec.STATUS_ACCEPTED,
        "reason": codec.REASON_NONE,
        "seq": 42,
        "head8": HEAD8.hex(),
    },
    to_key="seller",
)
add(
    "notice-order-received",
    codec.MessageType.NOTICE,
    "ledger",
    {
        "kind": codec.NOTICE_ORDER_RECEIVED,
        "refId": ORDER_ID.hex(),
        "seq": 43,
        "qtyKg": 500,
        "pricePaise": 2450,
    },
    to_key="seller",
)

out = {
    "version": 1,
    "spec": "contracts/MESSAGE_SPEC.md",
    "generated": "2026-11-26",
    "baseTs": BASE_TS,
    "keys": KEYS,
    "vectors": VECTORS,
}
path = Path(__file__).resolve().parents[1] / "vectors.json"
path.write_text(json.dumps(out, indent=2, sort_keys=False) + "\n", encoding="utf-8")
print(f"wrote {path} with {len(VECTORS)} vectors")
for vector in VECTORS:
    print(f"  {vector['name']:24} {vector['type']:14} {len(vector['unsignedHex'])//2:3} bytes")
