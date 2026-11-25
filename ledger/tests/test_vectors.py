from __future__ import annotations

import json
from pathlib import Path

import pytest

from crypto import canonical_event, sha256_hex, validate_canonical

VECTORS_PATH = Path(__file__).resolve().parents[2] / "contracts" / "vectors.json"
SUITE = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


def test_vector_suite_has_at_least_12_cases():
    assert len(SUITE["vectors"]) >= 12
    assert len(SUITE["reject"]) >= 1


@pytest.mark.parametrize("case", SUITE["vectors"], ids=lambda c: c["name"])
def test_canonical_vector_matches(case: dict):
    canonical = canonical_event(case["event"])
    assert canonical == case["canonical"]
    assert sha256_hex(canonical) == case["sha256"]


@pytest.mark.parametrize("case", SUITE["reject"], ids=lambda c: c["name"])
def test_canonical_rejects_invalid_values(case: dict):
    with pytest.raises(ValueError):
        validate_canonical(case["value"])


def test_validate_canonical_rejects_unpaired_surrogate():
    with pytest.raises(ValueError):
        validate_canonical("\ud800")
