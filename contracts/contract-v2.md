# AgriChain contract v2 (audit round 2)

This is the full v2 specification referenced by README. It is normative for
T17-T24. The README keeps the task board, short contract summary, Inbox,
Decisions Log, Blockers and run instructions; this file keeps the detailed
event rules, REST routes, audit-finding map and device checklist.

## Contract

v2 (audit round 2). Change only with a Decisions Log entry and an Inbox notice to everyone affected. Base URL `http://<host>:8000`, JSON UTF-8. Errors: `{"error":"code","detail":"...","retry":true|false}`. v1 events remain readable; v2 writers use per-chain heads and the permission matrix below.

### Identifiers, units and time

- `batchId`: UUIDv4. `actorId`: `farmer:<uuid>` | `buyer:<uuid>` | `box:<uuid>`.
- Tokens: integer kilograms. 1 token = 1 kg. `grade` is A/B/C metadata and part of the price key.
- `ts`: Unix epoch milliseconds (integer), device clock. The ledger stores `receivedAt` and rejects `ts` more than 5 minutes in the future or 30 days in the past.
- `lat` / `lon`: decimal-degree **strings** with exactly 6 decimals, or `null`. Fixed strings keep canonical JSON identical in Kotlin and Python.
- No floats anywhere in `data`: integers only. Prices in paise, weights in kg, distance in metres, speed in cm/s.
- Strings are UTF-8. Object keys are ASCII printable; string **values** may be non-ASCII (Telugu, Hindi, emoji). Canonical JSON emits non-ASCII as raw UTF-8, never `\uXXXX` escapes; control characters use the standard JSON escapes. `contracts/vectors.json` is the parity suite (T17).

### Event v2

```json
{"eventId":"uuid","batchId":"uuid","type":"GPS","actorId":"box:uuid",
 "ts":1764000000123,"lat":"17.385044","lon":"78.486671",
 "data":{"accuracyM":8,"mock":false},"prevHash":"<hex>","hash":"<hex>","sig":"<base64>"}
```

Types: `REGISTER | BIND | TRANSFER | GPS | TAMPER | DELIVER | BARTER`. `PRICE` is removed in v2 (T18); the ledger rejects it with `400 price_removed`.

The signed fields are unchanged from v1: `{eventId,batchId,type,actorId,ts,lat,lon,data,prevHash}`. The chain key is `(batchId, actorId, stream)`; `stream` is derived from the type (`trace` for `GPS`, `main` for everything else) and is not part of the signed bytes. The ledger adds `chainSeq` and `receivedAt` to reads.

| type | allowed signer | chain | `data` fields |
|------|----------------|-------|---------------|
| REGISTER | farmer | main | `crop` str, `grade` "A"/"B"/"C", `weightKg` int 1..100000. Genesis only (`prevHash=""`); batch must be unused. Mints `weightKg` tokens to `actorId`. |
| BIND | current batch owner | main | `boxId` box actor or null, `expiresAt` int? A BIND replaces the previous binding; `boxId:null` unbinds. Signed by the owner. |
| TRANSFER | current holder | main | `toActorId` str, `tokens` int, `pricePerKgPaise` int?, `grade` "A"/"B"/"C"? Moves tokens. Balance must cover it. Priced transfers are **reported** trade data, not attested. |
| GPS | currently bound box | trace | `accuracyM` int, `mock` bool? `lat`/`lon` required. |
| TAMPER | currently bound box | main | `kind` "movement"/"light"/"proximity"/"geofence"/"gps_off"/"mock_location"/"power", `detail` str?, `traceHead` hex?, `lastLat`/`lastLon` str?. |
| DELIVER | bound box or current holder | main | `toActorId` str?, `note` str?. |
| BARTER | current holder | main | `offerId` uuid, `action` "offer"/"accept"/"cancel", `withActorId` str, `giveKg` int, `wantBatchId` str, `wantKg` int. |

**Owner/holder.** The current owner is the actor with the largest token balance for the batch; ties go to the most recent transfer. `BIND`, `TRANSFER`, `BARTER` and holder-`DELIVER` require the signer to be the holder at append time. `REGISTER` is farmer-only. `GPS`/`TAMPER` are box-only and only after a BIND for that batch.

**BIND.** The farmer (or later owner) signs `BIND{boxId}` on its own `main` chain. The box does not need the owner's head; its GPS/TAMPER chains are independent. The latest BIND wins. A box may be bound to several batches. `409 unbound_box` is retryable: the box should wait for the BIND to sync, then retry.

**Chain rule.** One linear chain per `(batchId, actorId, stream)`, each with its own genesis `prevHash=""`. The server appends only if `prevHash` equals that chain's head, else `409 prev_mismatch` with `{chain:{actorId,stream},head,chainSeq}`. `chainSeq` is server-assigned (starts at 1 per chain), returned in reads, and is not signed. Relay events unchanged; never re-sign. On `prev_mismatch`, refetch the chain head and create a **new** event (new `eventId`, new `ts`) with the new `prevHash`; discard the old signed event. This is what keeps an offline box and an online farmer from destroying each other's evidence (C2).

**Canonical bytes.** UTF-8 JSON of `{eventId,batchId,type,actorId,ts,lat,lon,data,prevHash}` with keys sorted A-Z at every level, separators `,` and `:`, no extra whitespace. Keys are ASCII printable; string values are UTF-8 and non-ASCII is emitted raw. Integers are signed 64-bit; floats are rejected. Exclude `hash` and `sig`.
`hash` = lowercase hex SHA-256 of canonical bytes. `sig` = base64 DER ECDSA P-256/SHA-256 over the canonical bytes. Public key = base64 SPKI DER, registered while online.

Test vector (unchanged; non-ASCII cases live in `contracts/vectors.json`):

```
canonical = {"actorId":"farmer:33333333-3333-4333-8333-333333333333","batchId":"22222222-2222-4222-8222-222222222222","data":{"crop":"wheat","grade":"A","weightKg":100},"eventId":"11111111-1111-4111-8111-111111111111","lat":null,"lon":null,"prevHash":"","ts":1764000000000,"type":"REGISTER"}
sha256 = cfad1baad5f9915b39aba2d330ca28b79feb12706d275fc7533c376361ebed80
```

**Retry policy.**

| HTTP | error | retry? | action |
|------|-------|--------|--------|
| 409 | `prev_mismatch` | yes | refetch this chain's head; create and sign a new event with the new `prevHash`; never mutate the old signed event |
| 409 | `unbound_box` | yes | wait for the owner's `BIND` to sync, then retry |
| 409 | `batch_unknown` | yes | wait for `REGISTER`, then retry |
| 409 | `insufficient_balance`, `offer_not_open`, `offer_mismatch`, `not_counterparty`, `duplicate_offer` | no | fix or discard the event |
| 400 | `bad_hash`, `bad_sig`, `bad_event`, `price_removed` | no | discard |
| 403 | `forbidden` | no | wrong signer/permission |
| 422 | `unknown_actor`, validation | no | register the actor or fix the fields |
| network | — | yes | exponential backoff 1 s → 60 s, jitter |

**Admin token.** `POST /actors` and `/admin/*` require `X-Admin-Token` equal to `AGRICHAIN_ADMIN_TOKEN`. For local demos `AGRICHAIN_DEV=1` sets the default `dev-admin-token`; if the variable is unset, actor registration is closed. `POST /events` is authorized by signatures, not the admin token. `/prices/refresh` is removed. `/actors` and `/events` are rate-limited per IP (default 60/min, `AGRICHAIN_RATE_LIMIT`).

### Ledger REST v2

| Method + path | Body / query | Response |
|---------------|--------------|----------|
| `GET /health` | — | `{"ok":true,"version":2}` |
| `GET /` or `/dashboard` | — | HTML dashboard: batches, live events, barter, token prices, chain health |
| `GET /static/*` | — | dashboard CSS/JS (no CDN, works offline) |
| `POST /actors` | admin token; `{"actorId","name","role","pubKey"}` | `201 {"actorId"}`; `409` if key differs; `401` without token |
| `GET /actors/{actorId}` | — | `{"actorId","name","role","pubKey"}` |
| `POST /events` | one Event | `201 {"ok":true,"batchId","chain":{"actorId","stream","head","chainSeq"},"receivedAt"}`; `400` bad hash/sig/fields; `403 forbidden`; `409 prev_mismatch` / `unbound_box` / `batch_unknown` / `insufficient_balance`; `422 unknown_actor` |
| `POST /events/bulk` | `{"events":[Event,...]}` | `200 {"results":[{"eventId","ok","status","error"?,"retry":bool}]}`, appended in order; a failed event does not block later independent chains |
| `GET /events?batchId=&actorId=&stream=&limit=200&before=<chainSeq>` | `limit` 1–200; `before` exclusive | `{"batchId","chain"?:{"actorId","stream"},"events":[Event + chainSeq + receivedAt],"nextBefore"?:int}` newest first |
| `GET /events/recent?limit=20` | limit 1–100 | `{"events":[Event + chainSeq + crop],"updatedAt":ms}` newest first, all chains |
| `GET /stats?limit=20` | limit 1–100 | `{"actors","batches","events","tamperEvents","validChains","recent","updatedAt"}` |
| `GET /batches/{batchId}` | — | BatchSummary with latest 200 events, `eventCount`, `chains`, `heartbeatGaps`, `mockCount` |
| `GET /batches?owner=<actorId>` | — | `{"batches":[BatchSummary]}` without `events` |
| `GET /batches/{batchId}/verify` | — | `{"valid":bool,"eventCount":int,"chains":[{"actorId","stream","head","count","valid","brokenAt"?,"reason"?}],"permissions":{"valid":bool,"errors":[]},"balances":{...}}` |
| `GET /batches/{batchId}/route?max=200` | `max` 1–1000 | `{"batchId","points":[{"ts","lat","lon","accuracyM","mock","actorId","chainSeq"}],"truncated":bool}` oldest first |
| `GET /trades?crop=<crop>&grade=<A\|B\|C>` | crop required; grade optional | `{"crop","grade"?,"method":"median20","sampleSize":int,"pricePerKgPaise":int\|null,"byGrade":{...},"updatedAt":ms,"trades":[...]}` |
| `GET /barter/offers?actorId=<id>&status=<s>` | both optional | `{"offers":[{"offerId","status","batchId","actorId","withActorId","giveKg","wantBatchId","wantKg","ts","acceptEventId"?,"cancelEventId"?}]}` newest first |
| `GET /barter/offers/{offerId}` | — | one offer object as above |
| `GET /ledger/root` | — | `{"height","eventCount","root","ts","nodeId","pubKey","sig"}` signed Merkle root (T23) |

BatchSummary v2:

```json
{"batchId":"uuid","crop":"wheat","grade":"A","initialKg":100,
 "balances":{"farmer:uuid":100},"ownerId":"farmer:uuid",
 "tamperCount":0,"eventCount":42,"heartbeatGaps":0,"mockCount":0,
 "chains":[{"actorId":"farmer:uuid","stream":"main","head":"<hex>","chainSeq":3}],
 "verified":true,"events":[]}
```

`ownerId` = holder of the largest balance (ties: most recent transfer). A TRANSFER may not exceed the sender's balance; the ledger rejects it with `409 insufficient_balance`. Barter is a two-party signed handshake posted through `POST /events`: an `offer` event on the giver's batch, then an `accept` event on the counterparty's batch (mirrored `giveKg`/`wantKg`, same `offerId`), or a `cancel` event by the offerer. An accepted pair swaps `giveKg` on the offer batch and `wantKg` on the want batch as a balance overlay (no TRANSFER events); balances, owner and later transfer checks include accepted swaps. The overlay is included in `/verify`; it is not a substitute for escrow. `409` codes: `offer_not_open`, `offer_mismatch`, `not_counterparty`, `duplicate_offer`.

`GET /trades` uses the **median of the last 20 priced TRANSFER events** for the crop (and grade, when given), excluding self-trades (`fromActorId == toActorId`), non-positive prices and malformed rows. `method:"median20"`; even counts use the lower-middle integer. The UI labels this **reported token price**, not mandi data. `byGrade` is always returned so the chart can show A/B/C separately. There is no `PRICE` event and no `market` batch in v2.

`GET /batches/{id}/route` merges all bound-box `trace` chains and returns the latest `max` points. `heartbeatGaps` counts gaps > 5 minutes between consecutive GPS fixes; `mockCount` counts GPS events with `data.mock=true`. Buyers must verify hashes/signatures/chains locally and treat the server's `verified` as a hint only.

### Mesh and QR v3

One envelope, compact keys: `{"v":3,"k":"E","id":"<8hex>","s":"<senderId>","t":ms,"ttl":6,"ev":[...]}`. `k` is `E` (events) or `A` (ack, `"ack":[eventId,...]`, `"sig":"<base64>"`). Event keys: `i` eventId, `b` batchId, `y` type, `a` actorId, `t` ts, `la` lat, `lo` lon, `d` data, `p` prevHash, `g` sig. `hash` is omitted; receivers recompute it. v2 envelopes still parse. Transports implement `Transport { id, available(), maxPayload, send(envelope), onReceive }`: Nearby (Bluetooth/WiFi Direct), SMS (GSM radio), Internet (HTTP to the ledger).

SMS v3 is a compact **binary** envelope (magic `AC3`, version, kind, 8-hex msgId, ttl, event count, events, CRC32), base64url-chunked as `AC3|<msgId>|<idx>/<total>|<base64url>` with ≤140 payload characters per chunk. The short msgId and 140-char payload keep a chunk inside one 160-character SMS (M4). SMS sends only `REGISTER`/`BIND`/`TRANSFER`/`TAMPER`/`DELIVER`; GPS `trace` events never go over SMS. Receivers reassemble by msgId, verify the CRC, then decode. `BIND` must be delivered before the box's `TAMPER` on the same SMS path; per-chain heads make that possible without GPS predecessors (C3).

Relay rules: persist `seenEvents` (eventId), `seenEnvelopes` (envelope id), `sentTo` (peer → eventIds) and `acked` (peer+eventId). Any node with internet POSTs (ledger append is idempotent by eventId), then broadcasts a **signed** ACK. A node clears an event only when an ACK from a peer it actually sent the event to verifies; an unsigned/spoofed ACK is ignored (H6). The gateway ACKs only after `201` or an idempotent success, never after a reject. Without internet, queue in the SQLite outbox and forward on every available transport with `ttl-1`, dropping at 0. Retry when a transport or internet appears. No gateway election. QR payload: `agri://batch/<batchId>`; the QR identifies the batch but is not a physical anti-counterfeit seal (H4, see Blockers).

## Audit round 2: findings → fixes

The external audit read only this README, so its *(verify in code)* items were checked against the actual tree. The table is the contract for the fixes; T15–T24 implement it.

| Finding | Fix in v2 | Task |
|---------|-----------|------|
| C1 any registered actor can write any batch | `BIND` owner authorization + permission matrix + ledger enforcement | T15, T19, T20 |
| C2 one linear chain loses offline data | per-`(batchId, actorId, stream)` chains, own genesis; box `trace` is separate from farmer `main` | T15, T19, T20 |
| C3 SMS TAMPER needs missing GPS predecessor | per-chain heads; SMS carries `TAMPER` on the box `main` chain, GPS stays on `trace` | T15, T19, T21 |
| C4 self-declared mint/price | REGISTER cap 100000 kg; owner-only BIND; `median20` reported token price; exclude self-trades; per grade | T15, T18, T19 |
| H1 first-come identity, lost key | admin-token registration; document key loss/recovery as a demo limitation; no silent key rotation | T15, T19, T24 |
| H2 one node can truncate | node key + signed Merkle root (`/ledger/root`) + `mirror.py` divergence check | T23 |
| H3 buyer trusts `verified` | buyer verifies hashes/signatures/chains/BIND locally; server flag is a hint | T20 |
| H4 QR copyable | one-time/signed QR is not in the demo; documented as a physical-provenance blocker | T24 |
| H5 barter overlay breaks per-chain proof | overlay included in `/verify`; escrow/cross-chain atomicity documented as a limitation | T19, T24 |
| H6 unauthenticated ACK | signed ACKs + `sentTo`/`acked` per peer; gateway ACKs only on success | T21 |
| M1 canonical JSON drift | `contracts/vectors.json` ≥12 cases incl. Telugu, emoji, `/`, `<>`, quotes; Python/Kotlin parity | T17 |
| M2 tamper noise, no tamper location | movement gating, route-based geofence, `traceHead` + last fix on TAMPER, `gps_off`/`mock_location`/`power`, heartbeat gaps | T22 |
| M3 GPS volume | 20 m / 60 s adaptive logging, pagination, latest 200 in summary, `/route?max=` | T19, T22 |
| M4 SMS sizing | short 8-hex msgId, 140-char base64url chunks, binary v3 + CRC | T21 |
| M5 SMS limits/Play policy | documented as an operational blocker; default-handler/SMS-limit checks in the device checklist | T24 |
| M6 device-clock timestamps | server `receivedAt`, future/past skew rejection | T19 |
| M7 idempotency conflict | same `eventId` + different hash stays a hard `409`, with a regression test | T19 |
| M8 PRICE breaks contract | remove `PRICE`, `market` batch, mandi API/cache/chart; replace with token price | T18 |
| M9 cleartext/DoS | admin token, per-IP rate limit, cleartext debug-only network config, TLS/reverse-proxy runbook | T19, T24 |
| M10 privacy | document DPDP/route sensitivity; add coarse-route option/runbook (demo keeps exact route) | T24 |
| Background execution | API-level permissions, foreground location service + wake lock | T16 (charan) |
| Unproven phone claims | two-phone script + final device pass below | T24 |
| Hygiene | LICENSE/CI/repo metadata tracked separately; not required for the demo | T24 |

## Device test checklist (needs an Android phone)

Status: **pending — no phone is attached to Laasya's workstation.** `adb devices -l` returned an empty list at 2026-11-25 13:49. Do not mark any item below `done` without pasting the actual `adb`/`logcat` output in the Inbox.

Common setup (PowerShell; the bundled platform-tools path is in `scripts/dev.sh`):

```powershell
adb devices -l
adb reverse tcp:8000 tcp:8000
adb logcat -c
adb logcat -s AgriChain:V
```

| ID | Check | How | Pass condition |
|----|-------|-----|----------------|
| D1 | Runtime permissions per API level (T16) | fresh install on API 26/29/31/33/36; deny then grant location/SMS/Nearby | app asks at the right time; denied permissions show a recoverable message; no crash |
| D2 | Foreground GPS with screen off (T16) | start box logging, lock screen, wait 10 min, `adb shell dumpsys location` | `AgriChain` logcat shows fixes; foreground notification stays; no OEM kill |
| D3 | SMS receive + dual-SIM send (T16) | send `AC3\|...` from a second phone; pick SIM1/SIM2 in settings | receiver fires; `SmsManager` uses the selected subscription; gateway ACK arrives |
| D4 | BIND + per-chain sync (T20) | farmer registers, box scans QR, farmer taps "Authorize box", box logs offline, farmer syncs | box GPS/TAMPER land on box chains; farmer main chain is untouched; no `prev_mismatch` |
| D5 | Tamper evidence (T20/T22) | mock location on; power off charger; leave GPS off 5 min; shake box | TAMPER kinds `mock_location`/`power`/`gps_off`/`movement` with `traceHead` + last fix; buyer shows them |
| D6 | SMS/Nearby two-phone pass (T21) | phone A radio-only, phone B online gateway; drop/reorder/duplicate chunks; spoof an ACK | events converge; duplicate count unchanged; spoofed ACK does not clear the outbox |
| D7 | GPS cadence + gaps (T22) | drive/walk 20 m, wait 60 s, then disable GPS 5 min | fixes at 20 m or 60 s; buyer shows `heartbeatGaps`/`mockCount`/`eventCount` |
| D8 | Signed root + mirror (T23) | run ledger, `curl /ledger/root`, run `mirror.py` against a second node | signatures verify; mirror exits 0 on match, non-zero with the divergent height on tamper |
| D9 | Final demo (T24) | two phones, full flow, screen-off segment, buyer scan | README demo script passes; every unverified item is listed in Blockers |
