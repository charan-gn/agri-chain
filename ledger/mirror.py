#!/usr/bin/env python3
"""Compare signed Merkle roots from two AgriChain ledger nodes.

    python3 ledger/mirror.py --primary http://127.0.0.1:8000 \
        --secondary http://127.0.0.1:8001

Exit codes: 0 = roots and signatures agree, 1 = divergence, 2 = fetch/signature
failure. Run it against a second node (or a restored backup) after a demo to
detect silent truncation or rewriting (H2).
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

from anchor import compare_roots, verify_root


def fetch_root(base_url: str, timeout: float = 10.0) -> dict:
    url = base_url.rstrip("/") + "/ledger/root"
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def check_roots(primary: dict, secondary: dict) -> int:
    for label, body in (("primary", primary), ("secondary", secondary)):
        if not verify_root(body):
            print(f"mirror: {label} root signature is invalid", file=sys.stderr)
            return 2

    differences = compare_roots(primary, secondary)
    if differences:
        print("mirror: DIVERGENCE " + ", ".join(differences), file=sys.stderr)
        for field in differences:
            print(
                f"  {field}: primary={primary.get(field)!r} "
                f"secondary={secondary.get(field)!r}",
                file=sys.stderr,
            )
        return 1

    print(
        f"mirror: roots match at height {primary['height']} "
        f"({primary['eventCount']} events)"
    )
    print(f"  root {primary['root']}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--primary", required=True, help="primary base URL")
    parser.add_argument("--secondary", required=True, help="mirror base URL")
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args(argv)

    try:
        primary = fetch_root(args.primary, args.timeout)
        secondary = fetch_root(args.secondary, args.timeout)
    except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"mirror: cannot fetch roots: {exc}", file=sys.stderr)
        return 2
    return check_roots(primary, secondary)


if __name__ == "__main__":
    raise SystemExit(main())
