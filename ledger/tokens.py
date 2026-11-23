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
    trades = []
    for events in batches:
        if not events or events[0]["type"] != "REGISTER":
            continue
        if events[0]["data"].get("crop") != crop:
            continue
        for event in events:
            if event["type"] != "TRANSFER" or "pricePerKgPaise" not in event["data"]:
                continue
            trades.append(
                {
                    "batchId": event["batchId"],
                    "eventId": event["eventId"],
                    "ts": event["ts"],
                    "tokens": int(event["data"]["tokens"]),
                    "pricePerKgPaise": int(event["data"]["pricePerKgPaise"]),
                    "fromActorId": event["actorId"],
                    "toActorId": event["data"]["toActorId"],
                }
            )
    trades.sort(key=lambda trade: (trade["ts"], trade["eventId"]))
    return trades


def weighted_price(trades: list[dict]) -> int | None:
    total_tokens = sum(trade["tokens"] for trade in trades)
    if total_tokens == 0:
        return None
    total_value = sum(trade["tokens"] * trade["pricePerKgPaise"] for trade in trades)
    return round(total_value / total_tokens)
