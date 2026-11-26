from __future__ import annotations

import json
from pathlib import Path

import pytest

from crypto import canonical_event, canonical_json, sha256_hex

VECTORS_PATH = Path(__file__).resolve().parents[2] / "contracts" / "event-vectors.json"
VECTORS = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))["vectors"]


def test_suite_has_the_required_cases():
    names = {vector["name"] for vector in VECTORS}
    assert len(VECTORS) >= 12
    assert {
        "readme-register",
        "telugu-crop",
        "emoji-crop",
        "slash-detail",
        "angle-brackets",
        "quotes",
        "backslash-path",
        "control-escapes",
        "int64-bounds",
    } <= names


@pytest.mark.parametrize("vector", VECTORS, ids=[vector["name"] for vector in VECTORS])
def test_python_canonical_bytes_match_the_vector(vector):
    canonical = canonical_json(vector["value"])
    assert canonical == vector["canonical"]
    assert sha256_hex(canonical) == vector["sha256"]


@pytest.mark.parametrize("vector", VECTORS, ids=[vector["name"] for vector in VECTORS])
def test_event_wrapper_matches_the_vector(vector):
    assert canonical_event(vector["value"]) == vector["canonical"]
    # hash and sig are server/transport metadata and never part of the signed bytes.
    with_metadata = dict(vector["value"], hash="f" * 64, sig="ignored")
    assert canonical_event(with_metadata) == vector["canonical"]


def test_non_ascii_is_raw_utf8_not_escaped():
    canonical = canonical_json({"crop": "వరి 🌾"})
    assert "వరి 🌾" in canonical
    assert "\\u" not in canonical


def test_control_characters_use_standard_escapes():
    assert canonical_json({"note": "a\nb\tc\r\bd\fe\u0001"}) == (
        '{"note":"a\\nb\\tc\\r\\bd\\fe\\u0001"}'
    )


@pytest.mark.parametrize(
    "value",
    [
        {"bad": 1.5},
        {"bad": "\ud800"},
        {1: "non-string key"},
        {"bad\nkey": 1},
        {"tooBig": 2**63},
        {"tooSmall": -(2**63) - 1},
    ],
)
def test_invalid_values_are_rejected(value):
    with pytest.raises(ValueError):
        canonical_json(value)
