"""Meshtastic LoRa gateway for agri-chain v3 envelopes.

The gateway is a laptop (or any always-on host) with a Meshtastic radio
attached over USB serial. It reassembles ``LM1`` frames from the radio, posts
whole v3 envelopes to the ledger, and writes ledger outbox envelopes back to
the radio.

India: set the Meshtastic region to ``IN`` (865-867 MHz). 433 MHz and 915 MHz
are not permitted for this deployment.

Run::

    python gateway/lora_gateway.py --url http://127.0.0.1:8000 \
        --serial COM5 --watch <actorHex> --watch <actorHex>

Without a radio, ``--simulate`` reads one frame per line from stdin and writes
outbound frames to stdout, which is enough for the JVM/ledger test harness.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Iterable

PREFIX = "LM1"
MAX_PACKET_BYTES = 231
MAX_CHUNK = 210
MAX_PARTS = 9
MAX_ENVELOPE = 199


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    padded = text + "=" * (-len(text) % 4)
    return base64.urlsafe_b64decode(padded.encode("ascii"))


def short_id(envelope: bytes) -> str:
    """First 6 hex characters of msgId, with a SHA-256 fallback."""
    if 2 <= len(envelope) <= MAX_ENVELOPE:
        message = envelope[1:]
        if len(message) >= 16:
            flags = message[2]
            header = 16 + (8 if flags & 0x01 else 0)
            if len(message) >= header:
                body_len = message[header - 1]
                unsigned = message[:header + body_len]
                if len(unsigned) == header + body_len:
                    return hashlib.sha256(unsigned).hexdigest()[:6]
    return hashlib.sha256(envelope).hexdigest()[:6]


def chunks(envelope: bytes, max_chunk: int = MAX_CHUNK) -> list[str]:
    """Split one v3 envelope into ``LM1`` frames that fit a Meshtastic packet."""
    if not 2 <= len(envelope) <= MAX_ENVELOPE:
        raise ValueError(f"envelope must be 2..{MAX_ENVELOPE} bytes")
    size = max(1, min(int(max_chunk), MAX_CHUNK))
    body = _b64(envelope)
    total = (len(body) + size - 1) // size
    if total > MAX_PARTS:
        raise ValueError(f"message needs {total} parts (max {MAX_PARTS})")
    ident = short_id(envelope)
    frames = []
    for index in range(total):
        part = body[index * size:(index + 1) * size]
        line = f"{PREFIX}:{ident}:{index + 1}/{total}:{part}"
        if len(line.encode("utf-8")) > MAX_PACKET_BYTES:
            raise ValueError(f"frame is {len(line.encode('utf-8'))} bytes")
        frames.append(line)
    return frames


@dataclass(frozen=True)
class Part:
    ident: str
    index: int
    total: int
    payload: str


def parse_part(text: str) -> Part | None:
    text = text.strip()
    if not text.startswith(f"{PREFIX}:"):
        return None
    pieces = text.split(":", 3)
    if len(pieces) != 4:
        return None
    _, ident, counter, payload = pieces
    if len(ident) != 6 or any(ch not in "0123456789abcdef" for ch in ident):
        return None
    try:
        index_text, total_text = counter.split("/")
        index, total = int(index_text), int(total_text)
    except ValueError:
        return None
    if not 1 <= total <= MAX_PARTS or not 1 <= index <= total:
        return None
    if not payload or len(payload) > MAX_CHUNK:
        return None
    if any(not (ch.isalnum() or ch in "-_") for ch in payload):
        return None
    return Part(ident, index, total, payload)


@dataclass
class _Assembly:
    total: int
    parts: dict[int, str] = field(default_factory=dict)
    updated_at: float = 0.0


class Reassembler:
    """Collects out-of-order frames by their 6-hex id."""

    def __init__(self, timeout_s: float = 600.0, max_pending: int = 32) -> None:
        self.timeout_s = timeout_s
        self.max_pending = max_pending
        self._pending: dict[str, _Assembly] = {}

    def accept(self, text: str, now: float | None = None) -> bytes | None:
        part = parse_part(text)
        if part is None:
            return None
        now = time.time() if now is None else now
        self.expire(now)
        current = self._pending.get(part.ident)
        if current is not None and current.total != part.total:
            self._pending.pop(part.ident, None)
            return None
        if current is None:
            if len(self._pending) >= self.max_pending:
                self._pending.pop(next(iter(self._pending)))
            current = _Assembly(part.total, updated_at=now)
            self._pending[part.ident] = current
        current.parts[part.index] = part.payload
        current.updated_at = now
        if len(current.parts) < current.total:
            return None
        body = "".join(current.parts.get(index, "") for index in range(1, current.total + 1))
        if not body:
            return None
        self._pending.pop(part.ident, None)
        try:
            envelope = _unb64(body)
        except Exception:
            return None
        if not 2 <= len(envelope) <= MAX_ENVELOPE:
            return None
        return envelope

    def expire(self, now: float | None = None) -> int:
        now = time.time() if now is None else now
        stale = [key for key, item in self._pending.items() if now - item.updated_at > self.timeout_s]
        for key in stale:
            self._pending.pop(key, None)
        return len(stale)

    def pending(self) -> int:
        return len(self._pending)


class LedgerError(RuntimeError):
    pass


class LedgerClient:
    """Minimal v3 ledger client (stdlib only)."""

    def __init__(self, base_url: str, timeout: float = 15.0) -> None:
        self.base = base_url.rstrip("/")
        self.timeout = timeout

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={"Accept": "application/json"},
        )
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise LedgerError(f"{method} {path} -> {error.code}: {body[:300]}") from error
        except urllib.error.URLError as error:
            raise LedgerError(f"{method} {path} failed: {error.reason}") from error

    def post_envelopes(self, envelopes: Iterable[bytes], via: str = "lora") -> dict:
        messages = [base64.b64encode(item).decode("ascii") for item in envelopes]
        return self._request("POST", "/v3/messages", {"via": via, "messages": messages})

    def pull_outbox(self, actors: Iterable[str], after_id: int = 0) -> dict:
        return self._request(
            "POST",
            "/v3/outbox/pull",
            {"actors": list(actors), "afterId": after_id},
        )


class Gateway:
    """Frame reassembly plus ledger uplink/downlink."""

    def __init__(
        self,
        client: LedgerClient,
        send_frame: Callable[[str], None],
        watch: Iterable[str] = (),
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.client = client
        self.send_frame = send_frame
        self.watch = list(watch)
        self.clock = clock
        self.reassembler = Reassembler()
        self.after_id = 0
        self.posted = 0
        self.sent = 0

    def ingest(self, line: str) -> bytes | None:
        envelope = self.reassembler.accept(line, self.clock())
        if envelope is None:
            return None
        self.client.post_envelopes([envelope])
        self.posted += 1
        return envelope

    def drain_outbox(self) -> int:
        if not self.watch:
            return 0
        page = self.client.pull_outbox(self.watch, self.after_id)
        items = page.get("items", [])
        for item in items:
            raw = item.get("message")
            if not raw:
                continue
            envelope = base64.b64decode(raw)
            for frame in chunks(envelope):
                self.send_frame(frame)
                self.sent += 1
        self.after_id = int(page.get("nextAfterId", self.after_id))
        return len(items)


class SerialLink:
    """Meshtastic serial link; requires ``pyserial``."""

    def __init__(self, port: str, baud: int = 115200) -> None:
        try:
            import serial  # type: ignore
        except ImportError as error:  # pragma: no cover - depends on host
            raise SystemExit("pyserial is required for --serial; pip install pyserial") from error
        self._serial = serial.Serial(port, baud, timeout=1)

    def read_line(self) -> str | None:
        line = self._serial.readline()
        return line.decode("utf-8", "replace").strip() or None

    def write_line(self, line: str) -> None:
        self._serial.write((line + "\n").encode("utf-8"))
        self._serial.flush()

    def close(self) -> None:
        self._serial.close()


class SimulatedLink:
    """Reads frames from stdin and writes outbound frames to stdout."""

    def read_line(self) -> str | None:
        line = sys.stdin.readline()
        return line.strip() or None if line else None

    def write_line(self, line: str) -> None:
        print(line, flush=True)

    def close(self) -> None:
        return None


def run(link, gateway: Gateway, poll_s: float = 5.0) -> None:  # pragma: no cover - loop
    last_poll = 0.0
    while True:
        line = link.read_line()
        if line:
            gateway.ingest(line)
        now = time.time()
        if now - last_poll >= poll_s:
            gateway.drain_outbox()
            last_poll = now


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Meshtastic LoRa gateway for agri-chain v3")
    parser.add_argument("--url", default="http://127.0.0.1:8000", help="ledger base URL")
    parser.add_argument("--serial", help="serial port, e.g. COM5 or /dev/ttyUSB0")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--watch", action="append", default=[], help="actor hex to pull outbox for")
    parser.add_argument("--simulate", action="store_true", help="use stdin/stdout instead of a radio")
    parser.add_argument("--poll", type=float, default=5.0)
    args = parser.parse_args(argv)

    link = SimulatedLink() if args.simulate or not args.serial else SerialLink(args.serial, args.baud)
    client = LedgerClient(args.url)
    gateway = Gateway(client, link.write_line, watch=args.watch)
    print(
        f"lora gateway: url={args.url} watch={len(gateway.watch)} "
        f"mode={'simulate' if isinstance(link, SimulatedLink) else args.serial}",
        file=sys.stderr,
    )
    try:
        run(link, gateway, poll_s=args.poll)
    except KeyboardInterrupt:
        return 0
    finally:
        link.close()
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
