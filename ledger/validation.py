from __future__ import annotations

import base64
import binascii
import re
import uuid
from typing import Any

from crypto import ALLOWED_TYPES

ROLES = {"farmer", "buyer", "box"}
TAMPER_KINDS = {
    "movement",
    "light",
    "proximity",
    "geofence",
    "gps_off",
    "mock_location",
    "power",
}
BARTER_ACTIONS = {"offer", "accept", "cancel"}
HASH_RE = re.compile(r"^[0-9a-f]{64}$")
LATLON_RE = re.compile(r"^-?\d{1,3}\.\d{6}$")
INT64_MIN = -(2**63)
INT64_MAX = 2**63 - 1
FUTURE_SKEW_MS = 5 * 60 * 1000
PAST_SKEW_MS = 30 * 24 * 60 * 60 * 1000

EVENT_FIELDS = {
    "eventId",
    "batchId",
    "type",
    "actorId",
    "ts",
    "lat",
    "lon",
    "data",
    "prevHash",
    "hash",
    "sig",
}


class ValidationError(ValueError):
    pass


class PriceRemoved(ValidationError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


def is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def require_int64(value: int, label: str) -> int:
    if value < INT64_MIN or value > INT64_MAX:
        fail(f"{label} must fit in a signed 64-bit integer")
    return value


def require_ascii(value: str, label: str) -> None:
    if not isinstance(value, str) or not value:
        fail(f"{label} must be a non-empty string")
    if any(not (0x20 <= ord(char) <= 0x7E) for char in value):
        fail(f"{label} must be ASCII printable")


def require_text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        fail(f"{label} must be a non-empty string")
    if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
        fail(f"{label} contains an invalid Unicode surrogate")
    return value


def parse_uuid(value: str, label: str, version: int | None = None) -> uuid.UUID:
    if not isinstance(value, str):
        fail(f"{label} must be a string")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError, TypeError):
        fail(f"{label} must be a UUID")
    if version is not None and parsed.version != version:
        fail(f"{label} must be a UUIDv{version}")
    return parsed


def parse_actor_id(value: str) -> tuple[str, uuid.UUID]:
    if not isinstance(value, str) or ":" not in value:
        fail("actorId must look like <role>:<uuid>")
    role, raw_id = value.split(":", 1)
    if role not in ROLES:
        fail("actorId role must be farmer, buyer or box")
    return role, parse_uuid(raw_id, "actorId")


def validate_json_value(value: Any, label: str) -> None:
    if value is None or isinstance(value, bool):
        return
    if is_int(value):
        require_int64(value, label)
        return
    if isinstance(value, str):
        if any(0xD800 <= ord(char) <= 0xDFFF for char in value):
            fail(f"{label} contains an invalid Unicode surrogate")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            validate_json_value(item, f"{label}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            require_ascii(key, f"{label} key")
            validate_json_value(item, f"{label}.{key}")
        return
    fail(f"{label} must contain integers, strings, booleans, nulls, lists or objects")


def require_int(data: dict, key: str, *, positive: bool = False, non_negative: bool = False) -> int:
    value = data.get(key)
    if not is_int(value):
        fail(f"data.{key} must be an integer")
    require_int64(value, f"data.{key}")
    if positive and value <= 0:
        fail(f"data.{key} must be positive")
    if non_negative and value < 0:
        fail(f"data.{key} must be zero or positive")
    return value


def validate_lat_lon(lat: Any, lon: Any) -> None:
    if lat is None and lon is None:
        return
    if not isinstance(lat, str) or not isinstance(lon, str):
        fail("lat and lon must both be strings or both be null")
    if not LATLON_RE.match(lat) or not LATLON_RE.match(lon):
        fail("lat and lon must have exactly 6 decimal places")
    if not -90 <= float(lat) <= 90:
        fail("lat is out of range")
    if not -180 <= float(lon) <= 180:
        fail("lon is out of range")


def validate_type_data(event_type: str, data: dict) -> None:
    if event_type == "REGISTER":
        require_text(data.get("crop"), "data.crop")
        if data.get("grade") not in {"A", "B", "C"}:
            fail("data.grade must be A, B or C")
        weight = require_int(data, "weightKg", positive=True)
        if weight > 100000:
            fail("data.weightKg must be at most 100000")
    elif event_type == "BIND":
        box_id = data.get("boxId")
        if box_id is not None:
            role, _ = parse_actor_id(box_id)
            if role != "box":
                fail("data.boxId must be a box actor or null")
        if "expiresAt" in data and data["expiresAt"] is not None:
            require_int(data, "expiresAt", positive=True)
    elif event_type == "TRANSFER":
        parse_actor_id(data.get("toActorId"))
        require_int(data, "tokens", positive=True)
        if "pricePerKgPaise" in data:
            require_int(data, "pricePerKgPaise", non_negative=True)
        if "grade" in data and data["grade"] not in {"A", "B", "C"}:
            fail("data.grade must be A, B or C")
    elif event_type == "GPS":
        require_int(data, "accuracyM", non_negative=True)
        if "mock" in data and not isinstance(data["mock"], bool):
            fail("data.mock must be a boolean")
    elif event_type == "TAMPER":
        if data.get("kind") not in TAMPER_KINDS:
            fail(
                "data.kind must be movement, light, proximity, geofence, "
                "gps_off, mock_location or power"
            )
        if "detail" in data:
            require_text(data["detail"], "data.detail")
        if "traceHead" in data and data["traceHead"] is not None:
            if not isinstance(data["traceHead"], str) or not HASH_RE.match(data["traceHead"]):
                fail("data.traceHead must be 64 lowercase hex characters")
        if "lastLat" in data or "lastLon" in data:
            validate_lat_lon(data.get("lastLat"), data.get("lastLon"))
    elif event_type == "DELIVER":
        if "toActorId" in data:
            parse_actor_id(data["toActorId"])
        if "note" in data:
            require_text(data["note"], "data.note")
    elif event_type == "BARTER":
        parse_uuid(data.get("offerId"), "data.offerId")
        if data.get("action") not in BARTER_ACTIONS:
            fail("data.action must be offer, accept or cancel")
        parse_actor_id(data.get("withActorId"))
        require_int(data, "giveKg", positive=True)
        parse_uuid(data.get("wantBatchId"), "data.wantBatchId", version=4)
        require_int(data, "wantKg", positive=True)


def validate_event(raw: Any, now_ms: int | None = None) -> dict:
    if not isinstance(raw, dict):
        fail("event must be a JSON object")
    if set(raw.keys()) != EVENT_FIELDS:
        fail("event must contain exactly the contract fields")

    for field in ("eventId", "batchId", "type", "actorId", "hash", "sig"):
        require_ascii(raw[field], field)
    if not isinstance(raw["prevHash"], str):
        fail("prevHash must be a string")
    if raw["prevHash"]:
        require_ascii(raw["prevHash"], "prevHash")
    parse_uuid(raw["eventId"], "eventId")

    event_type = raw["type"]
    if event_type == "PRICE":
        raise PriceRemoved("PRICE events were removed in v2; use priced TRANSFERs")
    if event_type not in ALLOWED_TYPES:
        fail("unknown event type")

    parse_uuid(raw["batchId"], "batchId", version=4)
    parse_actor_id(raw["actorId"])

    if not is_int(raw["ts"]) or raw["ts"] <= 0:
        fail("ts must be a positive integer (epoch ms)")
    require_int64(raw["ts"], "ts")
    if now_ms is not None:
        if raw["ts"] > now_ms + FUTURE_SKEW_MS:
            fail("ts is more than 5 minutes in the future")
        if raw["ts"] < now_ms - PAST_SKEW_MS:
            fail("ts is more than 30 days in the past")

    validate_lat_lon(raw["lat"], raw["lon"])
    if event_type == "GPS" and (raw["lat"] is None or raw["lon"] is None):
        fail("GPS events require lat and lon")

    if raw["prevHash"] and not HASH_RE.match(raw["prevHash"]):
        fail("prevHash must be empty or 64 lowercase hex characters")
    if not HASH_RE.match(raw["hash"]):
        fail("hash must be 64 lowercase hex characters")

    data = raw["data"]
    if not isinstance(data, dict):
        fail("data must be a JSON object")
    validate_json_value(data, "data")
    validate_type_data(event_type, data)

    return raw


def validate_actor_registration(raw: Any) -> dict:
    if not isinstance(raw, dict):
        fail("actor must be a JSON object")
    if set(raw.keys()) != {"actorId", "name", "role", "pubKey"}:
        fail("actor must contain actorId, name, role and pubKey")

    role = raw["role"]
    if role not in ROLES:
        fail("role must be farmer, buyer or box")
    parsed_role, _ = parse_actor_id(raw["actorId"])
    if parsed_role != role:
        fail("actorId prefix must match role")
    require_text(raw["name"], "name")

    pub_key = raw["pubKey"]
    require_ascii(pub_key, "pubKey")
    try:
        base64.b64decode(pub_key, validate=True)
    except (binascii.Error, ValueError):
        fail("pubKey must be base64 SPKI DER")

    return raw
