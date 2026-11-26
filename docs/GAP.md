# v3 gap: ledger main node + offline mesh relay

Status: P0 (T29) alignment. Source of truth: the v3 build guide; the wire
contract is `contracts/MESSAGE_SPEC.md`. `contracts/contract-v2.md` is
superseded but stays readable until T38 removes the old `/events` path.

## Task IDs

T1-T28 are the frozen v1/v2 history; T26-T28 are already used by the chat-era
relay. The v3 phases therefore map to T29-T40:

| Phase | Task | Scope |
|-------|------|-------|
| P0 | T29 | Align docs, board, supersede v2 |
| P1 | T30 | Wire spec, dictionaries, reference codec, vectors, Kotlin codec |
| P2 | T31 | Ledger core: actors, ingest, receipts, hash chain, checkpoint, explorer |
| P3 | T32 | Market, orders, trades, prices, listings feed, outbox |
| P4 | T33 | Relay core + JVM simulator |
| P5 | T34 | Android service, Wi-Fi transport, gateway loop |
| P6 | T35 | SMS transport + SMS gateway + text commands |
| P7 | T36 | Meshtastic LoRa gateway |
| P8 | T37 | BLE GATT transport |
| P9 | T38 | Port batch/box/barter; remove `/events` and `mesh/` |
| P10 | T39 | Custodial SMS agent (stretch) |
| P11 | T40 | Hardening, soak, demo |

## What exists and is reused

| v3 need | Existing asset | Decision |
|---------|----------------|----------|
| FastAPI service, SQLite WAL, admin token, per-IP limit | `ledger/app.py`, `ledger/db.py` | Reuse process/config patterns; add all v3 tables and `/v3` routes alongside `/events`. |
| ECDSA P-256, canonical JSON, hashing | `ledger/crypto.py`, `android/.../core/` | Reuse P-256 primitives; v3 messages are fixed binary, not canonical JSON. |
| Actor registry | `actors` table, `POST /actors` | New `actors(actorId 8B, pubkey 33B, ...)`; old table stays until T38. |
| Ledger explorer | `ledger/web/`, `webapp/`, `/dashboard` | New `/explorer` reads v3 tables; keep the old dashboard until T38. |
| Android identity + Keystore | `ActorIdentity`, `EventSigner` | Reuse the key; add DER -> raw r||s conversion and compressed pubkey export. |
| Wi-Fi NSD/TCP, BLE GATT, SMS, Meshtastic | `mesh/WifiMeshTransport.kt`, `BleMeshTransport.kt`, `SmsTransport.kt`, `MeshtasticTransport.kt` | Port behind the v3 `Transport` interface (P5-P8); old package stays until T38. |
| Foreground service, permissions, QR, box sensors, buyer history | `box/`, `core/Permissions.kt`, `qr/`, `buyer/`, manifest | Reuse; port data flow to v3 messages in P9. |
| Test vectors | `contracts/vectors.json` (v2 event JSON) | Rename to `event-vectors.json`; v3 binary vectors take the `vectors.json` name. |
| Demo seed | `demo/seed.py` | Keep v2 until P3; add v3 e2e in P3/P4. |

## What is missing (build order)

1. **Wire codec** (P1): fixed big-endian message, envelope TTL, 12-byte msgId,
   raw ECDSA, typed bodies, vectors.
2. **Ledger v3** (P2-P3): `messages`, `listings`, `orders`, `trades`,
   `balances`, `bindings`, `price_tables`, `outbox`, `checkpoints`; serialized
   ingest, pending dependencies, receipts/notices, price median, checkpoint.
3. **Relay core** (P4): `relay/` Kotlin package, transport seam, seen LRU,
   pending uplink, caches, router, simulator.
4. **Gateway loop** (P5): pull prices/listings/outbox, inject receipts and
   ledger-signed messages, uplink batches.
5. **Transports** (P5-P8): Wi-Fi, SMS, LoRa, BLE behind the same interface.
6. **Screens/service** (P5): Prices, Market, Orders, Relay, Settings; relay
   foreground service; provisioning QR pins the ledger key.
7. **Migration** (P9): batch/box/barter/tamper onto v3; delete `/events`,
   per-actor chains and `mesh/`.

## Explicit non-goals / kept for later

- Chat and the v0.7 "halo" feed are not in the v3 catalogue. The chat panel is
  expected to leave the Relay screen when P5 builds the transport/pending UI;
  its data has no v3 message type.
- Mirror nodes, custody, Lightning and fiat settlement are out of scope.
- Old `/events`, `/trades`, `/barter/*`, `/prices` and the `mesh/` package stay
  until T38 passes so the demo keeps working during the rewrite.

## Constraints carried into every phase

- Message <= 198 bytes, envelope <= 199 bytes; LoRa uses ttl 3, mesh ttl 7.
- Money is integer paise/kg; ledger timestamps decide expiry.
- Client-signed types can never be submitted as ledger-signed.
- Rejections and pending messages are stored forever, never deleted.
- `msgId` is the primary key; replay returns the original seq/receipt.
- Log tag is `AgriMesh`; never log private keys.
- Device claims require quoted `adb`/`logcat` lines in README.

## P0 done-when

- README board has T29-T40 and the v3 contract pointer.
- `contracts/contract-v2.md` is marked superseded.
- Decisions log and Inbox record the ID remap and the vector-file split.
