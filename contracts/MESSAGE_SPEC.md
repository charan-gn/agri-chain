# AgriChain v3 message spec

Normative wire contract for the ledger main node and the offline mesh relay.
Everything is big-endian. There are no floats and no JSON on the wire. Money is
always integer **paise per kg**.

## 1. Limits

| Item | Limit |
|------|-------|
| Version | `1` |
| Signed message with `toId` | 198 bytes |
| Signed message without `toId` | 190 bytes |
| Body | 110 bytes |
| Envelope | `ttl(1) || message` <= 199 bytes |
| LoRa envelope | ttl 3 (one Meshtastic packet) |
| Mesh envelope | ttl 7 |
| Nickname | UTF-8, <= 20 bytes |
| Price-table rows | <= 19 per part |

A decoder MUST reject version != 1, a body over 110 bytes, a message over 198
bytes, a non-zero reserved flag bit, a missing signature where one is required,
and a `toId` whose presence disagrees with `flags.bit0`.

## 2. Message layout

```
offset size field
0      1    ver = 1
1      1    type
2      1    flags: bit0 hasTo, bit1 ledgerSigned; bits 2-7 = 0
3      4    ts, unix seconds (informational; the ledger clock decides expiry)
7      8    fromId = first 8 bytes of SHA-256(compressed P-256 public key)
15     8    toId, only when flags.bit0 = 1
15/23  1    bodyLen
..     N    body (N <= 110)
..     64   signature: ECDSA P-256/SHA-256, raw r||s, over every preceding byte
```

The 64-byte signature is present on the wire. The intermediate "unsigned"
message used for signing, `msgId`, and test vectors is the same bytes without
the signature.

`msgId = first 12 bytes of SHA-256(all bytes before the signature)`. It is
never transmitted; every node and the ledger recompute it. It is the primary
key for idempotency.

### Envelope

```
ttl (1 byte) || message
```

`ttl` is not signed and relays decrement it. The originator uses ttl 7 on mesh
links and ttl 3 on LoRa. A relay drops at ttl <= 1.

### Signatures

- Curve: NIST P-256 (`secp256r1`), SHA-256, deterministic or randomized ECDSA.
- On the wire: 32-byte big-endian `r` followed by 32-byte big-endian `s`.
- Android Keystore returns DER: convert DER -> raw r||s before sending, raw ->
  DER before verifying.
- `fromId` is derived from the **compressed** public key (`02/03 || X`).
- Ledger-signed messages set `flags.bit1` and use the pinned ledger key; their
  `fromId` is the ledgerId. Clients MUST NOT accept a client-signed message
  that claims to be ledger-signed, or vice versa.

## 3. Type catalogue

All field widths are bytes. `crop`, `region`, `grade` are ids from
`crops.json` and `regions.json`; `id12` fields are 12 raw bytes.

### Client-signed

| Code | Type | Body |
|------|------|------|
| 0x01 | ANNOUNCE | `pubkey33` (compressed) `caps1` `nickLen1` `nick` (<=20, UTF-8) |
| 0x10 | LISTING | `crop1 qtyKg4 askPaise4 region1 validUntil4 grade1 batchId12` (`batchId` zeros if none) |
| 0x11 | ORDER | `listingId12 qtyKg4 bidPaise4 validUntil4`; `hasTo` = seller |
| 0x12 | ORDER_ACCEPT | `orderId12`; `hasTo` = buyer |
| 0x13 | ORDER_CANCEL | `orderId12`; `hasTo` = the other party |
| 0x14 | DELIVERED | `orderId12`; `hasTo` = seller (sent by the buyer) |
| 0x15 | BARTER_OFFER | `giveCrop1 giveKg4 wantCrop1 wantKg4 region1 validUntil4` |
| 0x16 | BARTER_ACCEPT | `offerId12`; `hasTo` = offer owner |
| 0x20 | TAMPER_ALERT | `batchId12 kind1 lat4 lon4 detectedTs4` (`i32` microdegrees) |
| 0x21 | BATCH_REGISTER | `crop1 weightKg4 harvestTs4 region1 grade1` |
| 0x23 | BIND | `batchId12 boxId8` |

`caps` bits: 0 BLE, 1 Wi-Fi, 2 LoRa, 3 SMS, 4 gateway. Bits 5-7 reserved zero.
`grade`: 1 A, 2 B, 3 C, 4 D, 5 mixed. `kind` for TAMPER_ALERT is implementation
defined; 1 movement, 2 light, 3 geofence, 4 power, 5 gps loss, 6 mock location.

### Ledger-signed (`flags.bit1 = 1`, `fromId = ledgerId`)

| Code | Type | Body |
|------|------|------|
| 0x40 | PRICE_TABLE | `region1 version4 asOf4 part1 parts1 n1` then `n x (crop1 pricePaise3 trades1)` |
| 0x41 | PRICE_BEACON | `region1 version4 hash8`; `hash8` = first 8 bytes of SHA-256 of the concatenated `PRICE_TABLE` bodies for parts 1..parts of that region/version |
| 0x42 | RECEIPT | `msgId12 status1 reason1 seq4 head8`; `hasTo` = original sender |
| 0x43 | NOTICE | `kind1 refId12 seq4 qtyKg4 pricePaise4`; `hasTo` = party below |

`PRICE_TABLE` `version` is per region, `part` is 1-based, `parts` is the total
number of parts, and `n` is 1..19. `trades` is a saturating 1-byte count.
`PRICE_BEACON.hash8` covers the whole table, not a single part.

`RECEIPT.status`: 0 accepted, 1 duplicate, 2 rejected, 3 pending.
`RECEIPT.reason`: 0 none, 1 bad_format, 2 bad_signature, 3 unknown_actor,
4 not_authorized, 5 expired, 6 dependency_missing, 7 insufficient_qty,
8 price_out_of_bounds, 9 self_trade, 10 rate_limited, 11 already_final.
`RECEIPT.head8` is the first 8 bytes of the ledger hash at decision time.

`NOTICE.kind`: 1 ORDER_RECEIVED -> seller, 2 ORDER_ACCEPTED -> buyer,
3 ORDER_CANCELLED -> counterparty, 4 DELIVERED -> seller, 5 TAMPER -> batch
owner. `seq` is the seq of the message that caused the notice; `qtyKg` and
`pricePaise` are zero where not applicable.

## 4. Validation rules

- `ts` must be within `[ledgerNow - 30 d, ledgerNow + 1 d]`; expiry checks use
  ledger time, never the phone clock.
- LISTING: known crop/region/grade, `qtyKg > 0`, `askPaise` inside
  `price_bounds.json`, `validUntil <= ledgerNow + 30 d`.
- ORDER: listing open and unexpired, `qtyKg <= remaining`, seller != buyer,
  `bidPaise > 0`. Emits NOTICE 1 to the seller.
- ORDER_ACCEPT: only the listing seller, order open. Reserves qty, records the
  trade at the bid, recomputes prices, emits NOTICE 2.
- ORDER_CANCEL: either party before DELIVERED; releases reserved qty; emits
  NOTICE 3 to the other party.
- DELIVERED: only the buyer, after accept; final. Emits NOTICE 4 to the seller
  and moves tokens when the listing has a batch.
- BATCH_REGISTER: farmer only; mints `weightKg` tokens, cap 100000 per batch;
  `batchId = msgId`.
- BIND: batch owner only; binds `boxId` to `batchId`.
- TAMPER_ALERT: only the bound box, only for its batch.
- A missing dependency stores the message as `pending` and re-evaluates when the
  dependency arrives or at `validUntil`.
- Rate limit: 60 messages/min per actor, 600/min per gateway. `reason=10` is
  retryable.

## 5. Ledger hash and checkpoints

```
ledgerHash = SHA-256(prevLedgerHash || msgId || statusByte || seq_u32_be)
```

`seq` starts at 1. Every arrival, including rejected and pending messages, gets
the next seq. A signed checkpoint is the Merkle root of `ledgerHash` values
every 20 messages or 60 seconds.

## 6. Test vectors

`contracts/vectors.json` is the single suite for `ref/codec.py`, the ledger and
Kotlin. Each vector has the typed body, `bodyHex`, `unsignedHex`, `msgIdHex`,
and one static `signedMessageHex` fixture from a fixed test key. Signing is
randomized, so signed fixtures are verify-only. The v2 canonical-JSON suite is
frozen at `contracts/event-vectors.json`.

## 7. Versioning

This file is v3.1 (2026-11-26). Breaking wire changes require a new `ver` and a
new Decisions Log entry. Additive type codes are allowed while `ver = 1`.
