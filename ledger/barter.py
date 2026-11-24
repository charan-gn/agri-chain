from __future__ import annotations

from tokens import compute_balances


class BarterError(Exception):
    def __init__(self, status: int, code: str, detail: str, **extra: object) -> None:
        self.status = status
        self.code = code
        self.detail = detail
        self.extra = extra
        super().__init__(detail)


def _barter_events(batches: dict[str, list[dict]]) -> list[dict]:
    events = [
        event
        for batch_events in batches.values()
        for event in batch_events
        if event["type"] == "BARTER"
    ]
    events.sort(key=lambda event: (event["ts"], event["eventId"]))
    return events


def derive_offers(batches: dict[str, list[dict]]) -> dict[str, dict]:
    """Offers are derived from signed BARTER events; no separate offer table."""
    offers: dict[str, dict] = {}
    events = _barter_events(batches)

    for event in events:
        if event["data"]["action"] != "offer":
            continue
        data = event["data"]
        offer_id = data["offerId"]
        if offer_id in offers:
            continue
        offers[offer_id] = {
            "offerId": offer_id,
            "status": "open",
            "batchId": event["batchId"],
            "actorId": event["actorId"],
            "withActorId": data["withActorId"],
            "giveKg": data["giveKg"],
            "wantBatchId": data["wantBatchId"],
            "wantKg": data["wantKg"],
            "ts": event["ts"],
        }

    for event in events:
        data = event["data"]
        if data["action"] == "offer":
            continue
        offer = offers.get(data["offerId"])
        if offer is None or offer["status"] != "open":
            continue
        if data["action"] == "accept":
            offer["status"] = "accepted"
            offer["acceptEventId"] = event["eventId"]
            offer["acceptActorId"] = event["actorId"]
            offer["acceptedTs"] = event["ts"]
        else:
            offer["status"] = "canceled"
            offer["cancelEventId"] = event["eventId"]
            offer["canceledTs"] = event["ts"]

    return offers


def offer_public(offer: dict) -> dict:
    keys = (
        "offerId",
        "status",
        "batchId",
        "actorId",
        "withActorId",
        "giveKg",
        "wantBatchId",
        "wantKg",
        "ts",
        "acceptEventId",
        "acceptActorId",
        "acceptedTs",
        "cancelEventId",
        "canceledTs",
    )
    return {key: offer[key] for key in keys if key in offer}


def barter_deltas(offers: dict[str, dict]) -> dict[str, dict[str, int]]:
    """Balance changes per batch for accepted offers. 1 token = 1 kg."""
    deltas: dict[str, dict[str, int]] = {}

    def add(batch_id: str, actor_id: str, amount: int) -> None:
        if amount == 0:
            return
        batch = deltas.setdefault(batch_id, {})
        batch[actor_id] = batch.get(actor_id, 0) + amount

    for offer in offers.values():
        if offer["status"] != "accepted":
            continue
        add(offer["batchId"], offer["actorId"], -offer["giveKg"])
        add(offer["batchId"], offer["withActorId"], offer["giveKg"])
        add(offer["wantBatchId"], offer["withActorId"], -offer["wantKg"])
        add(offer["wantBatchId"], offer["actorId"], offer["wantKg"])

    return deltas


def apply_deltas(balances: dict[str, int], deltas: dict[str, int] | None) -> dict[str, int]:
    result = dict(balances)
    for actor_id, amount in (deltas or {}).items():
        result[actor_id] = result.get(actor_id, 0) + amount
    return result


def _effective_balances(
    batches: dict[str, list[dict]],
    deltas: dict[str, dict[str, int]],
    batch_id: str,
) -> dict[str, int]:
    return apply_deltas(
        compute_balances(batches.get(batch_id, [])),
        deltas.get(batch_id),
    )


def validate_barter_event(
    event: dict,
    batches: dict[str, list[dict]],
    offers: dict[str, dict],
) -> None:
    """Raise BarterError unless a BARTER event is a legal offer, accept or cancel."""
    data = event["data"]
    action = data["action"]
    offer_id = data["offerId"]

    if action == "offer":
        if offer_id in offers:
            raise BarterError(409, "duplicate_offer", "offerId already used")
        if data["withActorId"] == event["actorId"]:
            raise BarterError(400, "bad_barter", "cannot barter with yourself")
        if data["wantBatchId"] == event["batchId"]:
            raise BarterError(400, "bad_barter", "give and want batch must differ")
        if event["batchId"] not in batches:
            raise BarterError(404, "batch_not_found", "batch has no REGISTER event")
        balances = _effective_balances(batches, barter_deltas(offers), event["batchId"])
        available = balances.get(event["actorId"], 0)
        if data["giveKg"] > available:
            raise BarterError(
                409,
                "insufficient_balance",
                "offer exceeds the giver's balance",
                balance=available,
            )
        return

    offer = offers.get(offer_id)
    if offer is None:
        raise BarterError(404, "unknown_offer", "offerId is not on the ledger")
    if offer["status"] != "open":
        raise BarterError(409, "offer_not_open", f"offer is {offer['status']}")

    if action == "accept":
        if event["actorId"] != offer["withActorId"]:
            raise BarterError(409, "not_counterparty", "only the named counterparty can accept")
        if (
            data["withActorId"] != offer["actorId"]
            or event["batchId"] != offer["wantBatchId"]
            or data["wantBatchId"] != offer["batchId"]
            or data["giveKg"] != offer["wantKg"]
            or data["wantKg"] != offer["giveKg"]
        ):
            raise BarterError(409, "offer_mismatch", "accept fields must mirror the offer")
        if event["batchId"] not in batches:
            raise BarterError(404, "batch_not_found", "batch has no REGISTER event")
        deltas = barter_deltas(offers)
        taker = _effective_balances(batches, deltas, event["batchId"])
        available = taker.get(event["actorId"], 0)
        if data["giveKg"] > available:
            raise BarterError(
                409,
                "insufficient_balance",
                "accept exceeds the taker's balance",
                balance=available,
            )
        giver = _effective_balances(batches, deltas, offer["batchId"])
        giver_available = giver.get(offer["actorId"], 0)
        if offer["giveKg"] > giver_available:
            raise BarterError(
                409,
                "insufficient_balance",
                "giver no longer covers the offer",
                balance=giver_available,
            )
        return

    if action == "cancel":
        if event["actorId"] != offer["actorId"]:
            raise BarterError(409, "not_offerer", "only the offerer can cancel")
        if (
            data["withActorId"] != offer["withActorId"]
            or event["batchId"] != offer["batchId"]
            or data["wantBatchId"] != offer["wantBatchId"]
            or data["giveKg"] != offer["giveKg"]
            or data["wantKg"] != offer["wantKg"]
        ):
            raise BarterError(409, "offer_mismatch", "cancel fields must mirror the offer")
        return

    raise BarterError(400, "bad_barter", "action must be offer, accept or cancel")
