"""Advisory mandi reference prices from the data.gov.in Agmarknet dataset.

This module is deliberately independent of the v3 ledger price contract. It
never signs anything: every payload is marked ``advisory`` and carries a
disclaimer saying it is not a signed AgriChain ledger price.

The upstream API key is read from ``DATA_GOV_IN_API_KEY`` and is only ever
placed in the outbound request URL. It is never echoed in payloads, errors, or
logs; upstream exception text is discarded in favour of fixed short messages.
"""

from __future__ import annotations

import copy
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any

RESOURCE_ID = "9ef84268-d588-465a-a308-a864a43d0070"
SOURCE_URL = "https://agmarknet.gov.in/"
API_BASE = f"https://api.data.gov.in/resource/{RESOURCE_ID}"
DATASET = "Agmarknet daily market prices (data.gov.in)"
DISCLAIMER = "Reference market information; not a signed AgriChain ledger price."

DEMO_DATE = "2026-11-27"
DEMO_ROWS: tuple[dict[str, Any], ...] = (
    {
        "commodity": "Wheat", "variety": "Wheat", "state": "Punjab",
        "district": "Ludhiana", "market": "Ludhiana Grain Market", "date": DEMO_DATE,
        "unit": "Rs./Quintal", "minPaisePerKg": 2425, "maxPaisePerKg": 2550,
        "modalPaisePerKg": 2480, "source": "demo",
    },
    {
        "commodity": "Rice", "variety": "Rice", "state": "Punjab",
        "district": "Amritsar", "market": "Amritsar Grain Market", "date": DEMO_DATE,
        "unit": "Rs./Quintal", "minPaisePerKg": 3100, "maxPaisePerKg": 3400,
        "modalPaisePerKg": 3250, "source": "demo",
    },
    {
        "commodity": "Maize", "variety": "Maize", "state": "Karnataka",
        "district": "Davanagere", "market": "Davanagere", "date": DEMO_DATE,
        "unit": "Rs./Quintal", "minPaisePerKg": 2150, "maxPaisePerKg": 2350,
        "modalPaisePerKg": 2250, "source": "demo",
    },
    {
        "commodity": "Cotton", "variety": "Cotton", "state": "Maharashtra",
        "district": "Nagpur", "market": "Nagpur Cotton Market", "date": DEMO_DATE,
        "unit": "Rs./Quintal", "minPaisePerKg": 6200, "maxPaisePerKg": 6800,
        "modalPaisePerKg": 6450, "source": "demo",
    },
    {
        "commodity": "Chilli", "variety": "Chilli", "state": "Andhra Pradesh",
        "district": "Guntur", "market": "Guntur Mirchi Yard", "date": DEMO_DATE,
        "unit": "Rs./Quintal", "minPaisePerKg": 18500, "maxPaisePerKg": 21000,
        "modalPaisePerKg": 19500, "source": "demo",
    },
)

_CACHE: dict[tuple[Any, ...], tuple[float, dict[str, Any]]] = {}
_LOCK = threading.RLock()


def clear_cache() -> None:
    """Drop all cached upstream payloads (used by tests and operators)."""
    with _LOCK:
        _CACHE.clear()


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _limit(limit: Any) -> int:
    try:
        return max(1, min(int(limit), 1000))
    except (TypeError, ValueError):
        return 50


def _field(record: dict[str, Any], *names: str) -> Any:
    """Fetch a field by exact name first, then by case-insensitive name."""
    for name in names:
        if name in record:
            return record[name]
    lowered = {str(key).lower(): value for key, value in record.items()}
    for name in names:
        if name.lower() in lowered:
            return lowered[name.lower()]
    return None


def _text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def _decimal(value: Any) -> Decimal | None:
    text = _text(value).replace(",", "")
    if not text:
        return None
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def _unit_factor(unit: str) -> Decimal | None:
    key = "".join(char for char in unit.lower() if char.isalnum())
    return {
        "rsquintal": Decimal(1),
        "quintal": Decimal(1),
        "rskg": Decimal(100),
        "kg": Decimal(100),
        "rstonne": Decimal(1) / Decimal(10),
        "tonne": Decimal(1) / Decimal(10),
    }.get(key)


def _paise(value: Any, factor: Decimal) -> int | None:
    number = _decimal(value)
    if number is None or number < 0:
        return None
    return int((number * factor).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _first_paise(record: dict[str, Any], names: tuple[str, ...], factor: Decimal) -> int | None:
    """Use the first usable alias; datasets may leave a preferred field blank."""
    for name in names:
        value = _paise(_field(record, name), factor)
        if value is not None:
            return value
    return None


def _matches(row: dict[str, Any], state: str | None, market: str | None, crop: str | None) -> bool:
    for needle, key in ((state, "state"), (market, "market"), (crop, "commodity")):
        if needle and _text(needle).casefold() not in _text(row.get(key)).casefold():
            return False
    return True


def _dedupe(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Collapse duplicate records, preferring the most recent observed date."""
    by_key: dict[tuple[str, ...], dict[str, Any]] = {}
    for row in rows:
        key = tuple(
            _text(row.get(field)).casefold()
            for field in ("state", "market", "commodity", "variety", "date")
        )
        previous = by_key.get(key)
        if previous is None or row["modalPaisePerKg"] > previous["modalPaisePerKg"]:
            by_key[key] = row

    by_place: dict[tuple[str, ...], dict[str, Any]] = {}
    for row in by_key.values():
        key = tuple(
            _text(row.get(field)).casefold()
            for field in ("state", "market", "commodity", "variety")
        )
        previous = by_place.get(key)
        if previous is None or _text(row["date"]) > _text(previous["date"]):
            by_place[key] = row

    ordered = sorted(by_place.values(), key=lambda row: _text(row["date"]), reverse=True)
    ordered.sort(key=lambda row: _text(row["commodity"]).casefold())
    return ordered


def _parse_records(records: list[Any]) -> list[dict[str, Any]]:
    parsed: list[dict[str, Any]] = []
    for raw in records:
        if not isinstance(raw, dict):
            continue
        unit = _text(_field(raw, "unit", "price_unit"))
        factor = _unit_factor(unit)
        if factor is None:
            continue
        commodity = _text(_field(raw, "commodity", "Commodity"))
        if not commodity:
            continue
        modal = _first_paise(raw, ("modal_price", "model_price", "modal"), factor)
        if modal is None:
            continue
        minimum = _first_paise(raw, ("min_price", "min"), factor)
        maximum = _first_paise(raw, ("max_price", "max"), factor)
        parsed.append(
            {
                "commodity": commodity,
                "variety": _text(_field(raw, "variety")),
                "state": _text(_field(raw, "state")),
                "district": _text(_field(raw, "district")),
                "market": _text(_field(raw, "market")),
                "date": _text(_field(raw, "arrival_date", "date")),
                "unit": unit,
                "minPaisePerKg": modal if minimum is None else minimum,
                "maxPaisePerKg": modal if maximum is None else maximum,
                "modalPaisePerKg": modal,
                "source": "data.gov.in",
            }
        )
    return _dedupe(parsed)


def _filter_key(state: str | None, market: str | None, crop: str | None, limit: Any) -> tuple[Any, ...]:
    return (_text(state).casefold(), _text(market).casefold(), _text(crop).casefold(), _limit(limit))


def _cache_peek(key: tuple[Any, ...]) -> dict[str, Any] | None:
    with _LOCK:
        entry = _CACHE.get(key)
        return None if entry is None else entry[1]


def _cache_get(key: tuple[Any, ...]) -> dict[str, Any] | None:
    with _LOCK:
        entry = _CACHE.get(key)
        if entry is None:
            return None
        stored_at, payload = entry
        if time.time() - stored_at >= max(0.0, _env_float("MANDI_CACHE_SECONDS", 900)):
            _CACHE.pop(key, None)
            return None
        return payload


def _cache_set(key: tuple[Any, ...], payload: dict[str, Any]) -> None:
    with _LOCK:
        _CACHE[key] = (time.time(), copy.deepcopy(payload))
        if len(_CACHE) > 64:
            oldest = min(_CACHE, key=lambda item: _CACHE[item][0])
            _CACHE.pop(oldest, None)


def _safe_error(exc: Exception, api_key: str) -> str:
    """Return a fixed short error; never echo exception text or the API key."""
    if isinstance(exc, urllib.error.HTTPError):
        message = f"Upstream mandi feed returned HTTP {exc.code}."
    elif isinstance(exc, (json.JSONDecodeError, ValueError)):
        message = "Upstream mandi feed returned unexpected data."
    else:
        message = "Upstream mandi feed unavailable."
    return message.replace(api_key, "[redacted]") if api_key else message


def _demo(
    state: str | None,
    market: str | None,
    crop: str | None,
    limit: Any,
    *,
    configured: bool,
    source: str,
) -> dict[str, Any]:
    rows = [
        copy.deepcopy(row)
        for row in DEMO_ROWS
        if _matches(row, state, market, crop)
    ][: _limit(limit)]
    payload: dict[str, Any] = {
        "advisory": True,
        "configured": configured,
        "source": source,
        "sourceUrl": SOURCE_URL,
        "dataset": DATASET,
        "fetchedAt": int(time.time()),
        "observedDate": DEMO_DATE if rows else None,
        "quotes": rows,
        "disclaimer": DISCLAIMER,
    }
    return payload


def _fetch(
    api_key: str,
    state: str | None,
    market: str | None,
    crop: str | None,
    limit: Any,
) -> dict[str, Any]:
    params: dict[str, str] = {
        "api-key": api_key,
        "format": "json",
        "limit": str(_limit(limit)),
    }
    if _text(state):
        params["filters[state]"] = _text(state)
    if _text(market):
        params["filters[market]"] = _text(market)
    if _text(crop):
        params["filters[commodity]"] = _text(crop)
    url = f"{API_BASE}?{urllib.parse.urlencode(params)}"
    request = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
    timeout = max(0.1, _env_float("MANDI_TIMEOUT", 8))
    response = urllib.request.urlopen(request, timeout=timeout)
    try:
        if hasattr(response, "__enter__"):
            with response as stream:
                raw = stream.read()
        else:  # tolerate lightweight test doubles with read() only
            raw = response.read()
    finally:
        close = getattr(response, "close", None)
        if callable(close):
            close()
    document = json.loads(raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw)
    if not isinstance(document, dict):
        raise ValueError("mandi response is not an object")
    records = document.get("records", [])
    if not isinstance(records, list):
        raise ValueError("mandi response has no records list")

    rows = [row for row in _parse_records(records) if _matches(row, state, market, crop)]
    rows = rows[: _limit(limit)]
    observed = max((_text(row["date"]) for row in rows if _text(row["date"])), default="")
    return {
        "advisory": True,
        "configured": True,
        "source": "data.gov.in",
        "sourceUrl": SOURCE_URL,
        "dataset": DATASET,
        "fetchedAt": int(time.time()),
        "observedDate": observed or None,
        "quotes": rows,
        "disclaimer": DISCLAIMER,
    }


def quotes(
    state: str | None = None,
    market: str | None = None,
    crop: str | None = None,
    limit: int = 50,
) -> dict[str, Any]:
    """Return advisory mandi quotes, with a deterministic fallback feed."""
    key = _filter_key(state, market, crop, limit)
    api_key = _text(os.environ.get("DATA_GOV_IN_API_KEY"))

    if not api_key:
        return _demo(state, market, crop, limit, configured=False, source="demo")

    cached = _cache_peek(key)
    fresh = _cache_get(key)
    if fresh is not None:
        return copy.deepcopy(fresh)

    try:
        payload = _fetch(api_key, state, market, crop, limit)
    except Exception as exc:  # upstream is advisory: degrade, never crash the page
        error = _safe_error(exc, api_key)
        if cached is not None:
            stale = copy.deepcopy(cached)
            stale["source"] = "data.gov.in (cached)"
            stale["error"] = error
            return stale
        fallback = _demo(state, market, crop, limit, configured=True, source="demo fallback")
        fallback["error"] = error
        return fallback

    _cache_set(key, payload)
    return copy.deepcopy(payload)
