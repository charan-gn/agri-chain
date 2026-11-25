from __future__ import annotations


class InsufficientBalance(Exception):
    def __init__(self, available: int, requested: int) -> None:
        self.available = available
        self.requested = requested
        super().__init__(f"transfer of {requested} exceeds balance {available}")


def compute_balances(events: list[dict]) -> dict[str, int]:
    """Token balances for one batch, in chain order. 1 token = 1 kg."""
    if not events or events[0]["type"] != "REGISTER":
        return {}
    register = events[0]
    balances = {register["actorId"]: int(register["data"]["weightKg"])}
    for event in events[1:]:
        if event["type"] == "TRANSFER":
            _apply_transfer(balances, event)
    return balances


def _apply_transfer(balances: dict[str, int], event: dict) -> None:
    tokens = int(event["data"]["tokens"])
    source = event["actorId"]
    target = event["data"]["toActorId"]
    balances[source] = balances.get(source, 0) - tokens
    balances[target] = balances.get(target, 0) + tokens


def assert_transfer_allowed(history: list[dict], event: dict) -> None:
    if event["type"] != "TRANSFER":
        return
    available = compute_balances(history).get(event["actorId"], 0)
    requested = int(event["data"]["tokens"])
    if requested > available:
        raise InsufficientBalance(available, requested)


def trades_for_crop(batches: list[list[dict]], crop: str) -> list[dict]:
    """Priced TRANSFERs for a crop, oldest first.

    Self-trades and non-positive prices are excluded: they cannot be used as
    market evidence and would let one actor wash-trade a price.
    """
    trades = []
    for events in batches:
        if not events or events[0]["type"] != "REGISTER":
            continue
        register = events[0]["data"]
        if register.get("crop") != crop:
            continue
        batch_grade = register.get("grade")
        for event in events:
            if event["type"] != "TRANSFER":
                continue
            data = event["data"]
            if "pricePerKgPaise" not in data:
                continue
            price = int(data["pricePerKgPaise"])
            if price <= 0:
                continue
            source = event["actorId"]
            target = data.get("toActorId")
            if source == target:
                continue
            trades.append(
                {
                    "batchId": event["batchId"],
                    "eventId": event["eventId"],
                    "ts": event["ts"],
                    "tokens": int(data["tokens"]),
                    "pricePerKgPaise": price,
                    "grade": data.get("grade", batch_grade),
                    "fromActorId": source,
                    "toActorId": target,
                }
            )
    trades.sort(key=lambda trade: (trade["ts"], trade["eventId"]))
    return trades


def median20_price(trades: list[dict]) -> int | None:
    """Median of the last 20 priced trades; lower-middle for even counts."""
    prices = sorted(int(trade["pricePerKgPaise"]) for trade in trades[-20:])
    if not prices:
        return None
    middle = len(prices) // 2
    if len(prices) % 2:
        return prices[middle]
    return (prices[middle - 1] + prices[middle]) // 2


def price_summary(trades: list[dict]) -> dict:
    by_grade = {
        grade: median20_price([trade for trade in trades if trade.get("grade") == grade])
        for grade in ("A", "B", "C")
    }
    return {
        "method": "median20",
        "sampleSize": min(len(trades), 20),
        "pricePerKgPaise": median20_price(trades),
        "byGrade": by_grade,
    }
