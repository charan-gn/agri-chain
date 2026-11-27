#!/usr/bin/env python3
"""T40 v3 soak: keep signed traffic flowing and verify the ledger stays healthy.

The default is a live ledger.  For a quick offline smoke test use::

    python tools/soak.py --in-process --minutes 2 --interval 2

A full soak is one hour on the demo laptop (or a phone gateway)::

    python tools/soak.py --base-url http://127.0.0.1:8000 --minutes 60

The report is JSON so it can be attached to the README evidence table.  The
script never logs private keys; only public actor ids and message ids appear.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(ROOT / "ledger"))

import v3_codec as v3c  # noqa: E402
from e2e import LedgerClient, key_for, msg_id, register, sign  # noqa: E402


def now_ts() -> int:
    return int(time.time())


def submit(client: LedgerClient, messages: list[bytes], via: str = "wifi") -> list[dict]:
    """POST messages and return the per-message ledger results."""
    data = client.post(
        "/v3/messages",
        {
            "via": via,
            "messages": [base64.b64encode(message).decode("ascii") for message in messages],
        },
    )
    return data["results"]


def check_checkpoint(client: LedgerClient, ledger_pubkey: bytes) -> int:
    checkpoint = client.get("/v3/checkpoint")
    stats = client.get("/v3/stats")
    if checkpoint["seq"] != stats["messages"]:
        raise AssertionError(
            f"checkpoint seq {checkpoint['seq']} != stats messages {stats['messages']}"
        )
    if not v3c.verify_checkpoint(
        ledger_pubkey,
        checkpoint["seq"],
        bytes.fromhex(checkpoint["root"]),
        base64.b64decode(checkpoint["signature"]),
    ):
        raise AssertionError("bad checkpoint signature")
    return int(checkpoint["seq"])


def check_outbox(client: LedgerClient, ledger_pubkey: bytes, actors: list[str]) -> int:
    items = client.post("/v3/outbox/pull", {"actors": actors})["items"]
    for item in items:
        raw = base64.b64decode(item["message"])
        if not v3c.verify_message(raw, ledger_pubkey):
            raise AssertionError(f"bad outbox signature: {item['kind']}")
        if v3c.message_id_of(raw).hex() != item["msgId"]:
            raise AssertionError(f"outbox msgId mismatch: {item['kind']}")
    return len(items)


def run(args: argparse.Namespace) -> int:
    client = LedgerClient(args.base_url if not args.in_process else None, args.admin_token)
    started = datetime.now(timezone.utc)
    start_monotonic = time.monotonic()
    deadline = start_monotonic + args.minutes * 60
    report: dict = {
        "task": "T40",
        "startedAt": started.isoformat(),
        "minutes": args.minutes,
        "intervalSeconds": args.interval,
        "baseUrl": args.base_url or "in-process",
        "cycles": 0,
        "messages": 0,
        "accepted": 0,
        "rejected": 0,
        "latenciesMs": [],
        "outboxItemsVerified": 0,
        "checkpointSeq": 0,
        "errors": [],
        "pass": False,
    }
    try:
        info = client.get("/v3/ledger")
        ledger_pubkey = bytes.fromhex(info["pubkey"])
        health = client.get("/v3/health")
        if health != {"ok": True, "version": 3}:
            raise AssertionError(f"bad /v3/health: {health}")

        seller = key_for(21)
        buyer = key_for(22)
        box = key_for(23)
        other = key_for(24)
        seller_id = register(client, seller, "farmer", "Soak seller")
        buyer_id = register(client, buyer, "buyer", "Soak buyer")
        box_id = register(client, box, "box", "Soak box")
        other_id = register(client, other, "farmer", "Soak barter partner")

        batch_one = sign(
            seller,
            v3c.MessageType.BATCH_REGISTER,
            v3c.codec.batch_register_body(2, 100000, now_ts() - 3600, 1, 1),
        )
        batch_two = sign(
            other,
            v3c.MessageType.BATCH_REGISTER,
            v3c.codec.batch_register_body(1, 100000, now_ts() - 3600, 1, 1),
        )
        batch_one_id = bytes.fromhex(msg_id(batch_one))
        batch_two_id = bytes.fromhex(msg_id(batch_two))
        setup = submit(client, [batch_one, batch_two])
        if any(item["status"] != "accepted" for item in setup):
            raise AssertionError(f"batch setup rejected: {setup}")
        bind_one = sign(
            seller,
            v3c.MessageType.BIND,
            v3c.codec.bind_body(batch_one_id, bytes.fromhex(box_id)),
        )
        bind_two = sign(
            other,
            v3c.MessageType.BIND,
            v3c.codec.bind_body(batch_two_id, bytes.fromhex(box_id)),
        )
        setup = submit(client, [bind_one, bind_two])
        if any(item["status"] != "accepted" for item in setup):
            raise AssertionError(f"bind setup rejected: {setup}")
        tamper = sign(
            box,
            v3c.MessageType.TAMPER_ALERT,
            v3c.codec.tamper_alert_body(batch_one_id, 1, 17_385_000, 78_486_000, now_ts()),
        )
        setup = submit(client, [tamper])
        if setup[0]["status"] != "accepted":
            raise AssertionError(f"tamper setup rejected: {setup}")

        print(
            f"soak: ledger {info['ledgerId']} actors seller={seller_id} buyer={buyer_id} "
            f"box={box_id} other={other_id}",
            flush=True,
        )
        print(
            f"soak: {args.minutes} minute(s), {args.interval}s interval; Ctrl-C writes a partial report",
            flush=True,
        )

        cycle = 0
        while time.monotonic() < deadline:
            cycle += 1
            report["cycles"] = cycle
            cycle_start = time.monotonic()
            messages: list[bytes] = []
            listing = sign(
                seller,
                v3c.MessageType.LISTING,
                v3c.codec.listing_body(
                    2, 1, 2500, 1, now_ts() + 7200, 1, batch_one_id
                ),
            )
            messages.append(listing)
            listing_id = msg_id(listing)
            tamper = sign(
                box,
                v3c.MessageType.TAMPER_ALERT,
                v3c.codec.tamper_alert_body(
                    batch_one_id, cycle % 7 + 1, 17_385_000, 78_486_000, now_ts()
                ),
            )
            messages.append(tamper)

            if cycle % 5 == 0:
                order = sign(
                    buyer,
                    v3c.MessageType.ORDER,
                    v3c.codec.order_body(
                        bytes.fromhex(listing_id), 1, 2500, now_ts() + 3600
                    ),
                    to_id=bytes.fromhex(seller_id),
                )
                order_id = msg_id(order)
                messages.append(order)
                messages.append(
                    sign(
                        seller,
                        v3c.MessageType.ORDER_ACCEPT,
                        v3c.codec.order_accept_body(bytes.fromhex(order_id)),
                        to_id=bytes.fromhex(buyer_id),
                    )
                )
                messages.append(
                    sign(
                        buyer,
                        v3c.MessageType.DELIVERED,
                        v3c.codec.delivered_body(bytes.fromhex(order_id)),
                        to_id=bytes.fromhex(seller_id),
                    )
                )

            if cycle % 7 == 0:
                offer = sign(
                    seller,
                    v3c.MessageType.BARTER_OFFER,
                    v3c.codec.barter_offer_body(
                        2, 1, 1, 1, 1, now_ts() + 7200
                    ),
                )
                offer_id = msg_id(offer)
                messages.append(offer)
                messages.append(
                    sign(
                        other,
                        v3c.MessageType.BARTER_ACCEPT,
                        v3c.codec.barter_accept_body(bytes.fromhex(offer_id)),
                        to_id=bytes.fromhex(seller_id),
                    )
                )

            try:
                results = submit(client, messages)
                report["messages"] += len(messages)
                for item in results:
                    if item["status"] == "accepted":
                        report["accepted"] += 1
                    else:
                        report["rejected"] += 1
                        report["errors"].append(
                            {
                                "cycle": cycle,
                                "type": item.get("type"),
                                "status": item.get("status"),
                                "reason": item.get("reason"),
                            }
                        )
            except Exception as error:  # noqa: BLE001 - soak records failures and continues
                report["errors"].append({"cycle": cycle, "error": str(error)})

            report["latenciesMs"].append(round((time.monotonic() - cycle_start) * 1000))

            if cycle % 10 == 0:
                try:
                    report["checkpointSeq"] = check_checkpoint(client, ledger_pubkey)
                    report["outboxItemsVerified"] += check_outbox(
                        client, ledger_pubkey, [seller_id, buyer_id, box_id, other_id]
                    )
                except Exception as error:  # noqa: BLE001
                    report["errors"].append({"cycle": cycle, "check": str(error)})

            if cycle % 10 == 0 or cycle == 1:
                print(
                    f"soak: cycle {cycle} messages={report['messages']} "
                    f"accepted={report['accepted']} rejected={report['rejected']} "
                    f"checkpoint={report['checkpointSeq']}",
                    flush=True,
                )

            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(args.interval, remaining))

        report["checkpointSeq"] = check_checkpoint(client, ledger_pubkey)
        report["pass"] = report["rejected"] == 0 and not report["errors"]
    except KeyboardInterrupt:
        report["errors"].append({"interrupted": "Ctrl-C"})
    except Exception as error:  # noqa: BLE001
        report["errors"].append({"fatal": str(error)})
    finally:
        client.close()
        report["endedAt"] = datetime.now(timezone.utc).isoformat()
        report["durationSeconds"] = round(time.monotonic() - start_monotonic, 1)
        latencies = report.pop("latenciesMs", [])
        if latencies:
            report["latencyMs"] = {
                "min": min(latencies),
                "median": round(statistics.median(latencies), 1),
                "max": max(latencies),
            }
        report_path = Path(args.report or f"soak-report-{started:%Y%m%d-%H%M%S}.json")
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"\nsoak report: {report_path}", flush=True)
        print(json.dumps({k: report[k] for k in report if k != "errors"}, indent=2))
        if report["errors"]:
            print(f"\nsoak errors ({len(report['errors'])}):", flush=True)
            for error in report["errors"][:10]:
                print(f"  {error}", flush=True)
        if report["pass"]:
            print("\nSOAK PASS", flush=True)
            return 0
        print("\nSOAK FAIL", flush=True)
        return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", help="live ledger URL; omit for in-process")
    parser.add_argument("--in-process", action="store_true", help="run against a temp ledger")
    parser.add_argument(
        "--admin-token",
        default=os.environ.get("AGRICHAIN_ADMIN_TOKEN") or "dev-admin-token",
    )
    parser.add_argument("--minutes", type=float, default=60.0)
    parser.add_argument("--interval", type=float, default=10.0)
    parser.add_argument("--report", help="JSON report path")
    args = parser.parse_args()
    if not args.in_process and not args.base_url:
        parser.error("pass --base-url or --in-process")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
