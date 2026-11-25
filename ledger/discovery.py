"""UDP discovery so the Android app can find this ledger on the LAN.

The app broadcasts ``AGRICHAIN_DISCOVER`` to UDP 8100; we answer with the
ledger's HTTP URL. No dependency, no mDNS, and it can be disabled with
``AGRICHAIN_DISCOVERY=0``.
"""

from __future__ import annotations

import json
import os
import socket
import threading

PROBE = b"AGRICHAIN_DISCOVER"
DEFAULT_PORT = 8100


def lan_ip() -> str:
    """Best-effort address of the interface facing the default route."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def reply_for(data: bytes, base_url: str) -> bytes | None:
    if data.strip() != PROBE:
        return None
    return json.dumps({"service": "agrichain", "baseUrl": base_url}).encode("utf-8")


class DiscoveryResponder:
    def __init__(
        self,
        http_port: int,
        port: int = DEFAULT_PORT,
        host_ip: str | None = None,
    ) -> None:
        self.host_ip = host_ip or lan_ip()
        self.base_url = f"http://{self.host_ip}:{http_port}"
        self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._socket.bind(("", port))
        self.port = int(self._socket.getsockname()[1])
        self._thread: threading.Thread | None = None

    def start(self) -> "DiscoveryResponder":
        self._thread = threading.Thread(
            target=self._serve,
            name="agrichain-discovery",
            daemon=True,
        )
        self._thread.start()
        return self

    def _serve(self) -> None:
        while True:
            try:
                data, address = self._socket.recvfrom(256)
            except OSError:
                return
            response = reply_for(data, self.base_url)
            if response is None:
                continue
            try:
                self._socket.sendto(response, address)
            except OSError:
                return

    def stop(self) -> None:
        try:
            self._socket.close()
        except OSError:
            pass
        if self._thread:
            self._thread.join(timeout=1)


def start_discovery(http_port: int) -> DiscoveryResponder | None:
    if os.environ.get("AGRICHAIN_DISCOVERY", "1") == "0":
        return None
    port = int(os.environ.get("AGRICHAIN_DISCOVERY_PORT", DEFAULT_PORT))
    try:
        return DiscoveryResponder(http_port, port).start()
    except OSError:
        # Port busy or no interface: HTTP still works, only auto-detect is off.
        return None
