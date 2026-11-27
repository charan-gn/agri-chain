from __future__ import annotations

import pytest


@pytest.fixture(autouse=True)
def demo_env(monkeypatch):
    """Tests use the local-demo admin token and do not rate-limit themselves."""
    monkeypatch.setenv("AGRICHAIN_DEV", "1")
    monkeypatch.setenv("AGRICHAIN_RATE_LIMIT", "0")
    monkeypatch.setenv("AGRICHAIN_V3_RATE_LIMIT", "0")
