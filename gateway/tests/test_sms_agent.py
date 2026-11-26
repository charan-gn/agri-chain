import base64
import json
import time

from contracts.ref import codec as v3c
from gateway.sms_agent import SmsAgent, crop_name, derive_key, verify_pin


class FakeLedger:
    def __init__(self):
        self.actors = {}
        self.messages = []
        self.listings_data = []
        self.orders_data = []
        self.prices_data = []

    def register_actor(self, pubkey_hex, role, name, phone, region):
        actor_id = v3c.actor_id_hex(bytes.fromhex(pubkey_hex))
        self.actors[actor_id] = {
            "pubkey": pubkey_hex,
            "role": role,
            "name": name,
            "phone": phone,
            "region": region,
        }
        return {"actorId": actor_id}

    def post_messages(self, messages):
        self.messages.extend(messages)
        return {"results": [{"status": "accepted", "reasonCode": 0, "seq": len(self.messages)}]}

    def prices(self, region):
        return {"prices": self.prices_data}

    def listings(self, region=None):
        return {"listings": self.listings_data}

    def orders(self, actor):
        return {"orders": [item for item in self.orders_data if actor in {item.get("buyerId"), item.get("sellerId")}]}


def make_agent(tmp_path, clock=lambda: time.time()):
    return SmsAgent(
        db_path=tmp_path / "agent.db",
        master_key=b"\x07" * 32,
        ledger=FakeLedger(),
        clock=clock,
    )


def decode_last(agent):
    envelope = agent.ledger.messages[-1]
    ttl, message = v3c.decode_envelope(envelope)
    assert ttl == 7
    return v3c.decode(message)


def test_register_derives_key_and_stores_only_pin_hash(tmp_path):
    agent = make_agent(tmp_path)
    reply = agent.handle("+91 90000 00001", "REGISTER farmer 1234")
    assert "Registered farmer" in reply
    user = agent.user("+919000000001")
    assert user is not None
    key = derive_key(b"\x07" * 32, "+919000000001")
    assert user.actor_id == v3c.actor_id_hex(v3c.compressed_public_key(key))
    assert user.pubkey == v3c.compressed_public_key(key).hex()
    assert user.pin_hash != "1234"
    assert verify_pin("1234", user.salt, user.pin_hash)
    assert not verify_pin("9999", user.salt, user.pin_hash)
    assert len(reply) <= 160


def test_wrong_pin_and_unregistered_are_rejected(tmp_path):
    agent = make_agent(tmp_path)
    assert "Not registered" in agent.handle("+919000000002", "PRICE wheat")
    agent.handle("+919000000002", "REGISTER farmer 1234")
    agent.sessions.clear()
    assert "Wrong PIN" in agent.handle("+919000000002", "PRICE wheat PIN 9999")
    assert "PIN required" in agent.handle("+919000000002", "PRICE wheat")


def test_session_and_price_reply(tmp_path):
    agent = make_agent(tmp_path)
    agent.ledger.prices_data = [
        {
            "region": 1,
            "version": 4,
            "rows": [{"crop": 1, "pricePaise": 2500, "trades": 3}],
        }
    ]
    agent.handle("+919000000003", "REGISTER farmer 1234")
    reply = agent.handle("+919000000003", "PRICE wheat")
    assert "wheat" in reply.lower()
    assert "25.00/kg" in reply
    assert "3 trades" in reply
    assert len(reply) <= 160


def test_list_signs_a_valid_listing(tmp_path):
    agent = make_agent(tmp_path)
    agent.handle("+919000000004", "REGISTER farmer 1234")
    reply = agent.handle("+919000000004", "LIST wheat 100 2500")
    assert reply.startswith("Listed")
    message = decode_last(agent)
    user = agent.user("+919000000004")
    assert message.type == v3c.MessageType.LISTING
    assert v3c.verify_message(message, bytes.fromhex(user.pubkey))
    parsed = v3c.parse_body(v3c.MessageType.LISTING, message.body)
    assert parsed["crop"] == 1
    assert parsed["qtyKg"] == 100
    assert parsed["askPaise"] == 2500


def test_buy_targets_the_seller_and_signs_order(tmp_path):
    agent = make_agent(tmp_path)
    agent.handle("+919000000005", "REGISTER farmer 1234")
    farmer = agent.user("+919000000005")
    agent.handle("+919000000006", "REGISTER buyer 5678")
    buyer = agent.user("+919000000006")
    agent.ledger.listings_data = [
        {
            "listingId": "ab" * 12,
            "sellerId": farmer.actor_id,
            "crop": 1,
            "remainingKg": 100,
            "askPaise": 2500,
            "region": 1,
            "validUntil": int(time.time()) + 86400,
        }
    ]
    reply = agent.handle("+919000000006", "BUY ababab 50")
    assert reply.startswith("Order")
    message = decode_last(agent)
    assert message.type == v3c.MessageType.ORDER
    assert message.to_id.hex() == farmer.actor_id
    assert v3c.verify_message(message, bytes.fromhex(buyer.pubkey))
    parsed = v3c.parse_body(v3c.MessageType.ORDER, message.body)
    assert parsed["qtyKg"] == 50
    assert parsed["bidPaise"] == 2500


def test_session_expires(tmp_path):
    now = [1_000_000.0]
    agent = make_agent(tmp_path, clock=lambda: now[0])
    agent.handle("+919000000007", "REGISTER farmer 1234")
    now[0] += 16 * 60
    assert "PIN required" in agent.handle("+919000000007", "PRICE wheat")


def test_help_and_unknown_commands(tmp_path):
    agent = make_agent(tmp_path)
    assert "REGISTER" in agent.handle("+919000000008", "HELP")
    agent.handle("+919000000008", "REGISTER farmer 1234")
    assert "Unknown command" in agent.handle("+919000000008", "SELL wheat")
    assert "Only buyer" in agent.handle("+919000000008", "BUY abc 1")


def test_rejected_message_is_reported(tmp_path):
    agent = make_agent(tmp_path)
    agent.handle("+919000000009", "REGISTER farmer 1234")

    class Rejecting(FakeLedger):
        def post_messages(self, messages):
            self.messages.extend(messages)
            return {"results": [{"status": "rejected", "reason": "price_out_of_bounds"}]}

    agent.ledger = Rejecting()
    reply = agent.handle("+919000000009", "LIST wheat 100 99999999")
    assert reply.startswith("Rejected:")
    assert len(reply) <= 160
