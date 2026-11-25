#!/usr/bin/env python3
"""Seed a running AgriChain ledger with the full v2 demo story.

    AGRICHAIN_DEV=1 ./tools/ledger.sh
    python3 demo/seed.py --base-url http://127.0.0.1:8000

Registers four actors, two batches, a BIND, box GPS + tamper events on their
own chains, two priced transfers, and a barter offer/accept. Prints the batch
ids to paste into the Android Buyer screen and the reported token price
(median of the last 20 priced transfers).

`POST /actors` needs `X-Admin-Token`. The default is `dev-admin-token`, which
the ledger accepts when it runs with `AGRICHAIN_DEV=1`; pass `--admin-token`
or set `AGRICHAIN_ADMIN_TOKEN` to use another token.
"""

from __future__ import annotations

import argparse
import base64
import itertools
import json
import os
import sys
import time
import uuid
from pathlib import Path
from urllib import error, request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "ledger"))
from crypto import canonical_event, sha256_hex  # noqa: E402

ADMIN_TOKEN = os.environ.get("AGRICHAIN_ADMIN_TOKEN", "dev-admin-token")


def call(base: str, method: str, path: str, payload: dict | None = None) -> tuple[int, dict]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = request.Request(
        base + path,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Admin-Token": ADMIN_TOKEN,
        },
    )
    try:
        with request.urlopen(req, timeout=15) as response:
            body = response.read().decode("utf-8")
            return response.status, (json.loads(body) if body else {})
    except error.HTTPError as exc:
        detail = exc.read().decode("utf-8")
        raise SystemExit(f"{method} {path} -> HTTP {exc.code}\n{detail}")


class Actor:
    def __init__(self, role: str, name: str) -> None:
        self.role = role
        self.name = name
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.actor_id = f"{role}:{uuid.uuid4()}"
        spki = self.key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self.pub_key = base64.b64encode(spki).decode("ascii")

    def register(self, base: str) -> "Actor":
        call(
            base,
            "POST",
            "/actors",
            {
                "actorId": self.actor_id,
                "name": self.name,
                "role": self.role,
                "pubKey": self.pub_key,
            },
        )
        return self

    def event(
        self,
        batch_id: str,
        event_type: str,
        data: dict,
        prev_hash: str,
        *,
        ts: int,
        lat: str | None = None,
        lon: str | None = None,
    ) -> dict:
        fields = {
            "eventId": str(uuid.uuid4()),
            "batchId": batch_id,
            "type": event_type,
            "actorId": self.actor_id,
            "ts": ts,
            "lat": lat,
            "lon": lon,
            "data": data,
            "prevHash": prev_hash,
        }
        canonical = canonical_event(fields)
        signature = self.key.sign(canonical.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        return fields | {
            "hash": sha256_hex(canonical),
            "sig": base64.b64encode(signature).decode("ascii"),
        }


def post(base: str, event: dict, label: str) -> str:
    _, body = call(base, "POST", "/events", event)
    chain = body.get("chain", {})
    print(
        f"  {label:<22} {event['type']:<8} "
        f"{chain.get('actorId', '?').split(':', 1)[0]}/{chain.get('stream', '?')} "
        f"#{chain.get('chainSeq', '?')} {body['head'][:12]}…"
    )
    return body["head"]


def main() -> None:
    global ADMIN_TOKEN

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    parser.add_argument(
        "--admin-token",
        default=ADMIN_TOKEN,
        help="X-Admin-Token for POST /actors (default: env or dev-admin-token)",
    )
    args = parser.parse_args()
    ADMIN_TOKEN = args.admin_token
    base = args.base_url.rstrip("/")

    _, health = call(base, "GET", "/health")
    if health.get("ok") is not True:
        raise SystemExit(f"ledger at {base} is not healthy")
    print(f"Ledger {base}")

    farmer_a = Actor("farmer", "Ravi").register(base)
    farmer_b = Actor("farmer", "Meena").register(base)
    buyer = Actor("buyer", "Buyer Co").register(base)
    box = Actor("box", "Box 7").register(base)
    print("Actors registered: Ravi, Meena, Buyer Co, Box 7")

    clock = itertools.count()
    start = int(time.time() * 1000)

    def now() -> int:
        return start + next(clock)

    batch_a = str(uuid.uuid4())
    register_a = farmer_a.event(
        batch_a,
        "REGISTER",
        {"crop": "wheat", "grade": "A", "weightKg": 100},
        "",
        ts=now(),
    )
    head_a = post(base, register_a, "Ravi registers wheat")

    batch_b = str(uuid.uuid4())
    register_b = farmer_b.event(
        batch_b,
        "REGISTER",
        {"crop": "rice", "grade": "B", "weightKg": 50},
        "",
        ts=now(),
    )
    head_b = post(base, register_b, "Meena registers rice")

    bind = farmer_a.event(
        batch_a,
        "BIND",
        {"boxId": box.actor_id},
        head_a,
        ts=now(),
    )
    head_a = post(base, bind, "Ravi authorizes Box 7")

    # The box has its own trace chain for GPS and its own main chain for TAMPER.
    gps = box.event(
        batch_a,
        "GPS",
        {"accuracyM": 6, "mock": False},
        "",
        ts=now(),
        lat="17.385044",
        lon="78.486671",
    )
    gps_head = post(base, gps, "Box GPS fix")

    tamper = box.event(
        batch_a,
        "TAMPER",
        {
            "kind": "light",
            "detail": "lid opened",
            "traceHead": gps_head,
            "lastLat": "17.385044",
            "lastLon": "78.486671",
        },
        "",
        ts=now(),
    )
    post(base, tamper, "Box tamper")

    transfer_a = farmer_a.event(
        batch_a,
        "TRANSFER",
        {"toActorId": buyer.actor_id, "tokens": 10, "pricePerKgPaise": 2000},
        head_a,
        ts=now(),
    )
    head_a = post(base, transfer_a, "Ravi sells 10 kg")

    transfer_b = farmer_a.event(
        batch_a,
        "TRANSFER",
        {"toActorId": farmer_b.actor_id, "tokens": 5, "pricePerKgPaise": 3000},
        head_a,
        ts=now(),
    )
    head_a = post(base, transfer_b, "Ravi sells 5 kg to Meena")

    offer_id = str(uuid.uuid4())
    offer = farmer_a.event(
        batch_a,
        "BARTER",
        {
            "offerId": offer_id,
            "action": "offer",
            "withActorId": farmer_b.actor_id,
            "giveKg": 10,
            "wantBatchId": batch_b,
            "wantKg": 5,
        },
        head_a,
        ts=now(),
    )
    post(base, offer, "Ravi offers barter")

    accept = farmer_b.event(
        batch_b,
        "BARTER",
        {
            "offerId": offer_id,
            "action": "accept",
            "withActorId": farmer_a.actor_id,
            "giveKg": 5,
            "wantBatchId": batch_a,
            "wantKg": 10,
        },
        head_b,
        ts=now(),
    )
    post(base, accept, "Meena accepts barter")

    _, trades = call(base, "GET", "/trades?crop=wheat")
    _, summary_a = call(base, "GET", f"/batches/{batch_a}")
    _, summary_b = call(base, "GET", f"/batches/{batch_b}")
    _, verify_a = call(base, "GET", f"/batches/{batch_a}/verify")
    _, verify_b = call(base, "GET", f"/batches/{batch_b}/verify")

    print("\nLedger state")
    price = trades.get("pricePerKgPaise")
    print(
        f"  token price         {price if price is not None else 'none'} paise/kg "
        f"({trades.get('method', 'median20')}, sample {trades.get('sampleSize', 0)})"
    )
    print(f"  batch A balances    {summary_a['balances']}")
    print(f"  batch B balances    {summary_b['balances']}")
    print(f"  batch A tamper      {summary_a['tamperCount']} event(s)")
    print(f"  batch A chains      {'valid' if verify_a['valid'] else 'BROKEN'}")
    print(f"  batch B chains      {'valid' if verify_b['valid'] else 'BROKEN'}")
    print(f"  batch A owner       {summary_a['ownerId']}")

    print("\nAndroid Buyer screen (scan or paste the batch id)")
    print(f"  QR payload          agri://batch/{batch_a}")
    print(f"  batch A (wheat)     {batch_a}")
    print(f"  batch B (rice)      {batch_b}")

    print("\nActors for two-phone demos (the phones register their own keys via Ledger sync)")
    print(f"  farmer A            {farmer_a.actor_id}")
    print(f"  farmer B            {farmer_b.actor_id}")
    print(f"  buyer               {buyer.actor_id}")
    print(f"  box                 {box.actor_id}")


if __name__ == "__main__":
    main()
