# AgriChain — offline-first agri supply chain

Crop tokens (1 token = 1 kg graded produce) on a hash-chained public ledger with visible prices.
Farmers barter P2P; a spare-phone transport box logs GPS and tamper events while offline; phones relay signed events over a mesh and the first phone with internet syncs to the ledger.
Buyers scan a batch QR to see the route, tamper flags, price and full history.

## Task board

Status: `open` / `claimed` / `done` / `blocked`. Claim one task at a time. Read Contracts before coding.
Branch: `task-<ID>-<name>`. `APP` below = `android/app/src/main/java/com/agrichain`. Do not edit files listed under another task.
Audit round 2 reuses IDs T15-T24; the pre-audit T15/T16 work (laptop runner, auto-detect) is complete in git history and in the Inbox below.

| ID | Task | Status | Claimed by | Branch | Touches (files/folders) | Depends on |
|----|------|--------|------------|--------|-------------------------|------------|
| T1 | Repo skeleton + contracts | done | charan | task-T1-charan | README.md, android/ | — |
| T2 | QR batch register, generate and scan | done | charan | task-T2-charan | APP/qr/, APP/batch/, APP/core/, android/app/src/test/, android/app/build.gradle.kts, android/app/src/main/AndroidManifest.xml, android/app/src/main/res/layout/activity_{register_batch,batch_qr,scan}.xml | T1 |
| T3 | Minimal UI shell (farmer / buyer) | done | charan | task-T3-charan | APP/ui/, APP/MainActivity.kt, android/app/src/main/res/layout/, android/app/src/main/AndroidManifest.xml | T1 |
| T4 | Ledger node: hash-chained log + REST API | done | charan | task-T4-charan | ledger/ | T1 |
| T5 | Token mint/transfer + price from trades | done | charan | task-T5-charan | ledger/tokens.py, ledger/app.py, ledger/db.py, ledger/tests/, README.md | T4 |
| T6 | Box app: GPS logging + event signing | done | Laasya | task-T6-laasya | APP/box/, APP/MainActivity.kt, android/app/src/main/AndroidManifest.xml, android/app/src/main/res/layout/activity_{box,main}.xml | T1, T4 |
| T7 | Box app: tamper triggers | done | Laasya | task-T7-laasya | APP/box/, android/app/src/main/res/layout/activity_box.xml | T6 |
| T8 | Buyer history view (route, tamper flags) | done | charan | task-T8-charan | APP/buyer/, APP/net/, APP/ui/BuyerActivity.kt, APP/qr/ScanActivity.kt, android/app/src/main/res/layout/activity_{buyer,buyer_history}.xml, android/app/src/main/res/values/colors.xml, android/app/src/test/java/com/agrichain/buyer/, android/app/src/main/AndroidManifest.xml | T2, T3, T4 |
| T9 | Mandi prices: API, cache, chart | done | Laasya | task-T9-laasya | ledger/prices.py, ledger/app.py, ledger/tests/test_prices.py, APP/prices/, APP/MainActivity.kt, android/app/src/main/res/layout/activity_{main,prices}.xml, android/app/src/main/AndroidManifest.xml | T4 |
| T10 | Barter: offer / accept / swap | done | charan | task-T10-charan | ledger/barter.py, ledger/app.py, ledger/tests/, APP/barter/, APP/ui/FarmerActivity.kt, android/app/src/main/res/layout/activity_{farmer,barter}.xml, android/app/src/main/AndroidManifest.xml, README.md | T4, T5 |
| T11 | Offline SQLite queue + sync | done | charan | task-T11-charan | APP/data/, APP/MainActivity.kt, android/app/src/main/res/layout/activity_{main,sync}.xml, android/app/src/main/AndroidManifest.xml, android/app/src/test/java/com/agrichain/data/, README.md | T2, T4 |
| T12 | Mesh relay (Nearby Connections) | done | charan | task-T12-charan | APP/mesh/, android/app/build.gradle.kts, APP/MainActivity.kt, android/app/src/main/res/layout/activity_{main,mesh}.xml, android/app/src/main/AndroidManifest.xml, android/app/src/test/java/com/agrichain/mesh/, README.md | T11 |
| T13 | Demo script + polish | done | charan | task-T13-charan | demo/, README.md | all |
| T14 | SMS radio leg + relay v2 (replaces T12 scope) | done | charan | task-T14-charan | APP/mesh/, APP/data/, APP/MainActivity.kt, android/app/src/main/res/layout/activity_{main,mesh,sync}.xml, android/app/src/main/AndroidManifest.xml, android/app/src/test/java/com/agrichain/mesh/, README.md | T11, T12 |
| T15 | Contract v2: per-actor chains, BIND, permissions, admin token, pagination (docs only) | done | Laasya | task-T15-laasya | README.md | — |
| T16 | Device readiness: permissions, box foreground service, SMS default SIM, scripts/dev.sh | done | charan | task-T16-device-readiness-charan | APP/box/, APP/mesh/, APP/core/Permissions.kt, APP/MainActivity.kt, android/app/src/main/AndroidManifest.xml, android/app/src/main/res/layout/activity_{main,box,mesh}.xml, android/app/src/main/res/drawable/, android/app/build.gradle.kts, scripts/dev.sh, README.md | — |
| T17 | Canonical JSON vectors + on-device parity | claimed | Laasya | task-T17-laasya | contracts/, ledger/crypto.py, ledger/tests/, APP/core/CanonicalJson.kt, android/app/src/test/ | — |
| T18 | Remove mandi, add token price (median20) | done | Laasya | task-T18-laasya | ledger/prices.py, ledger/app.py, ledger/tokens.py, ledger/validation.py, ledger/tests/, APP/prices/, APP/buyer/, APP/mesh/HttpLedgerGateway.kt, layouts, manifest, demo/seed.py, ledger/web/, README.md | - |
| T19 | Ledger v2: chains, permissions, BIND, admin token, pagination, verify | done | Laasya | task-T19-laasya | ledger/, demo/seed.py, tools/ledger.sh | T15 |
| T20 | Android v2: per-chain writer, BIND UI, admin token, tamper traceHead, sync planner | open | — | — | APP/core/, APP/box/, APP/data/, APP/batch/, APP/buyer/, APP/ui/, tests | T15, T19 |
| T21 | SMS hardening: ACK rules, dedupe, binary v3 | open | — | — | APP/mesh/, android/app/src/test/java/com/agrichain/mesh/ | T15 |
| T22 | Tamper upgrades: movement gating, heartbeat gaps, mock warnings | open | — | — | APP/box/, ledger/, APP/buyer/, tests | T19, T20 |
| T23 | Anchoring + mirror node | open | — | — | ledger/anchor.py, ledger/mirror.py, ledger/app.py, ledger/tests/, README.md | T19 |
| T24 | Docs + demo + final device pass | open | — | — | README.md, demo/, scripts/ | all |

## Contracts

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

## Inbox

Append only. `[date time] FROM name → TO name/all: message | open / done`

- [2026-11-23 23:55] FROM charan → all: T1 done, contracts v1 frozen above. T2 is the next open task. Register all actors online during demo setup before the offline segment. | open
- [2026-11-24 00:17] FROM charan → all: T2 done. Reuse `APP/core/` for canonical JSON, events and signing (T6/T11 especially). REGISTER is signed locally; `BatchStore` uses SharedPreferences as a placeholder until T11's SQLite queue. No ledger upload yet; navigation lands with T3. | open
- [2026-11-24 00:30] FROM charan → all: T3 done. Launcher → Farmer (register, my batches, QR) or Buyer (scan QR). Buyer history is T8; ledger T4 is next. | open
- [2026-11-24 00:26] FROM Laasya → all: charan claimed T4 before my claim push, so I am claiming T6 (box GPS + signing) on task-T6-laasya. I will reuse APP/core Event/ActorIdentity and build against the ledger contract while T4 is in progress. | done
- [2026-11-24 00:55] FROM charan → all: T4 done. Ledger runs from `ledger/`; all endpoints above are live and tested. Actors must be registered via `POST /actors` (no Android registration UI yet). T5 is next. | open
- [2026-11-24 01:05] FROM charan → all: T5 done. `TRANSFER` checks sender balance; `GET /trades?crop=` returns token-weighted trade price. Reuse `ledger/tokens.py` for T10 swaps; T8 can show balances and price. | open
- [2026-11-24 11:22] FROM Laasya → all: T6 done on task-T6-laasya. Box mode has actor registration, QR bind via `GET /batches/{id}`, GPS every 3 s signed as `GPS` events with hash chaining into `BatchStore`, online head fetch + offline fallback. `testDebugUnitTest` passes (5 T6 tests + T2 tests) on JDK 17/SDK 36. T11: keep `BatchStore.all()/save()` or migrate `BoxEventLog`; T7 can extend `APP/box/` for tamper events. | done
- [2026-11-24 11:24] FROM Laasya → all: T6 merged to main; claiming T7 (tamper triggers) on task-T7-laasya. Movement/light/proximity sensors plus a 200 m geofence emit signed `TAMPER` events into the same box chain; demo simulate buttons included. | done
- [2026-11-24 11:39] FROM Laasya → all: T7 done on task-T7-laasya. Box screen arms accelerometer/light/proximity listeners and a 200 m geofence; triggers write signed `TAMPER` events (`kind` + `detail`) through `BoxEventLog` into the same local chain as GPS. Demo: bind → arm → "Set 200 m geofence here" → move the emulator fix, or use "Simulate tamper". `testDebugUnitTest` passes (8 T7 tests: 5 rules + 3 events). T8 can read `tamperCount` or filter `type=="TAMPER"`; no contract change. | open
- [2026-11-24 11:42] FROM Laasya → all: T7 merged to main; claiming T9 (mandi prices: API, cache, chart) on task-T9-laasya. I will serve `GET /prices` from `ledger/prices.py` with a seeded cache and add an offline-first `APP/prices/` repository + chart screen. T8 can call `PriceRepository` after it lands; contract unchanged. | done
- [2026-11-24 11:45] FROM Laasya → all: T9 Touches updated: the two `/prices` routes are an `APIRouter` in `ledger/prices.py` included with 2 lines in `ledger/app.py` (T4 is done), plus tests. On Android I need one launcher button and one manifest entry for the prices screen. T8 owns the manifest right now, so I will implement the rest first and rebase onto T8 before touching it; buyer files stay untouched. | done
- [2026-11-24 11:51] FROM Laasya → all: T9 done on task-T9-laasya. `ledger/prices.py` seeds 11 crop/market rates (wheat, rice, maize, chilli, cotton across Telangana mandis), caches snapshots in SQLite and serves `GET /prices` + `POST /prices/refresh` via an APIRouter (2-line hook in `app.py`). Refresh walks ±2.5% deterministically per minute; `_next_price` is the swap point for a real Agmarknet feed. Android: launcher → "Mandi prices" (crop/market spinners, custom bar chart, SharedPreferences cache, offline fallback). Tests: 20 ledger (5 new) + 23 Android (5 new) pass. Also set `usesCleartextTraffic=true`: targetSdk 36 blocks plain `http://` to the local ledger, which would break both the box and prices on a device. T8/T13 can reuse `PriceRepository` or the API; contract unchanged. | open
- [2026-11-24 11:58] FROM charan → all: T8 done on task-T8-charan. Buyer scan opens history: verified/tamper status, crop/grade, owner, token balances, last trade price, GPS route and reverse-chronological events. Buyer screen also takes a batch id manually and stores the ledger host (default `http://10.0.2.2:8000`). Last successful summary is cached per batch, so history opens offline; the trade-price line needs `/trades` online. Tests: 26 Android, 20 ledger. T10 is next. | open
- [2026-11-24 12:05] FROM charan → all: claiming T10 (barter). Published above: offers/accepts/cancels are signed BARTER events via `POST /events`; accepts mirror the offer and swap balances on both batches as an overlay. Two new read-only endpoints under `/barter/offers`. No changes to existing event fields. | open
- [2026-11-24 12:35] FROM charan → all: T10 done on task-T10-charan. Offer/accept/cancel are signed BARTER events; the ledger validates both sides and applies accepted swaps to balances, owner and later TRANSFER checks. `GET /barter/offers?actorId=&status=` lists them. Farmer → Barter: pick a ledger batch, give/want kg, counterparty batch + actor; counterparty taps Accept. Tests: 27 ledger (7 new), 28 Android (2 new). T11 is next. | open
- [2026-11-24 13:05] FROM charan → all: T11 done on task-T11-charan. Launcher → "Ledger sync": register farmer/buyer/box actors once, then upload local REGISTER/GPS/TAMPER events. A SQLite outbox (`agrichain_sync.db`) survives restarts; `SyncPlanner` walks prevHash from the server head, skips events already stored, uploads REGISTER batches first, and refetches the head on `409 prev_mismatch` before retrying. Tests: 32 Android (4 new), 27 ledger. T12 mesh is next. | open
- [2026-11-24 13:40] FROM charan → all: T12 done on task-T12-charan. Launcher → "Mesh relay": Nearby Connections (P2P_CLUSTER) advertises and discovers peers, relays the frozen v1 EVENTS envelope unchanged, and enqueues new events into the T11 SQLite outbox (dedupe by eventId). Emulator fallback: "Copy queued envelope" on one phone, paste and "Relay pasted envelope" on another; both paths feed the same queue. Tests: 36 Android (4 new), 27 ledger. T13 demo is next. | open
- [2026-11-24 14:10] FROM charan → all: T13 done on task-T13-charan. `demo/seed.py` builds the whole story against a running ledger: 4 actors, 2 batches, box GPS + tamper, 2 priced transfers, a barter offer/accept and a price refresh. It prints the batch ids/QR payloads for the Buyer screen. Run `python3 demo/seed.py --base-url http://127.0.0.1:8000`. Next: SMS radio leg + relay rewrite (T14). | open
- [2026-11-24 14:25] FROM charan → all: claiming T14 (replaces T12 scope). Nearby stays; add an SMS transport, a v2 compact envelope, one `Transport` interface, and relay v2 (persisted eventId dedupe, TTL forwarding, ACK cleanup, any internet node is a gateway). SMS only carries REGISTER/TRANSFER/TAMPER/DELIVER, chunked text messages to a configured gateway number. Contract above; no ledger changes needed (append is already idempotent by eventId). | open
- [2026-11-24 15:20] FROM charan → all: T14 done on task-T14-charan. `Transport` is shared by Nearby, SMS and the HTTP gateway. SMS text chunks are `AC1|<msgId>|<idx>/<total>|<base64>`, drained every 2 s; the gateway receiver reassembles, stores, POSTs when online and sends an ACK back to the originating number. GPS never goes over SMS. The JVM simulator proves SMS-only A → online B → ledger, loss/reorder, multi-hop, duplicate suppression, ACK cleanup and TTL drop. Tests: 51 Android (15 new), 27 ledger. Real-phone SMS/Nearby still needs the manual 2-phone pass above. | open
- [2026-11-24 17:50] FROM charan → all: claiming T15. Add `tools/ledger.sh` (auto venv + LAN URLs), `tools/phone.sh` (build/install, `adb reverse tcp:8000`, permission grants, launch), `tools/dev.sh` (both), a ledger-served web dashboard at `/`, and a "Use USB" host button in Ledger sync. No contract change; `/stats` and `/events/recent` are additive. | open
- [2026-11-25 12:00] FROM charan → all: T15 done on task-T15-charan. `./tools/ledger.sh` serves the API and a dashboard at `/` (batches, live events, barter, mandi prices, chain health, batch drill-down). `./tools/dev.sh` starts it, builds/installs the APK over USB, runs `adb reverse tcp:8000 tcp:8000`, grants runtime permissions and launches the app. In the app tap Ledger sync → "Use USB". Tests: 29 ledger (2 new), 51 Android. | open
- [2026-11-25 12:30] FROM charan → all: claiming T16. Prices kept a private URL store (default `10.0.2.2`) so the T15 USB host never reached that screen. Unify every screen on `LedgerSettings`, add LAN discovery (UDP 8100) plus USB/emulator probing, and auto-save the first healthy ledger. Also stop gating HTTP uploads on validated internet, so `adb reverse` works without Wi-Fi. | open
- [2026-11-25 13:10] FROM charan → all: T16 done on task-T16-charan. Every screen (prices, sync, buyer, box, mesh, main) now reads and writes `LedgerSettings`, so the USB host reaches prices. On launch and on "Auto-detect ledger" the app probes saved → `127.0.0.1` (adb reverse) → `10.0.2.2` (emulator) → UDP 8100 broadcast, saves the first healthy `/health`, and prices retries once after a failed fetch. `ledger/discovery.py` answers the broadcast with the LAN URL; disable with `AGRICHAIN_DISCOVERY=0`. Upload gating accepts loopback/Wi-Fi so USB needs no validated internet. Tests: 32 ledger (3 new), 57 Android (6 new). | open
- [2026-11-25 13:05] FROM charan → all: T16 verified on a real USB phone. With an unreachable saved host and `adb reverse tcp:8000 tcp:8000`, the app saved `http://127.0.0.1:8000`; with the reverse removed it saved `http://192.168.31.71:8000` via the UDP broadcast. `curl` from the phone to `/prices?crop=wheat` returned 200 over the cable. UI tap-through remains manual because the test phone was locked. | done
- [2026-11-25 13:52] FROM Laasya → all: claiming T15 (contract v2 docs) on task-T15-laasya. I will write the per-(batchId, actorId, stream) chain rules, BIND authorization, permission matrix, admin token, retry codes, pagination and `/route`. No code in this task; T19/T20 implement it. Device-only checks are listed in the new Device test checklist. | open
- [2026-11-25 14:05] FROM Laasya → all: T15 done on task-T15-laasya. Contract v2 is in README: per-(batchId, actorId, stream) chains (`trace` GPS, `main` otherwise, own genesis), BIND + permission matrix, admin token, retry codes, pagination, `/route`, median20 trades, signed roots, SMS v3, audit-finding mapping and the D1–D9 device checklist. No code yet; T19/T20 implement it. Device checks stay pending (no phone attached). | done
- [2026-11-25 14:20] FROM Laasya → all: claiming T18 on task-T18-laasya. Remove `ledger/prices.py`, `/prices` routes, the `market` batch and `PRICE` events; replace the mandi screen with a reported token price from `GET /trades` (`median20`, last 20 priced TRANSFERs, self-trades excluded, `byGrade`). Touches add `ledger/tokens.py` and `ledger/validation.py`; no T19/T20 files yet. | open
- [2026-11-25 14:33] FROM Laasya → all: T18 done on task-T18-laasya. Deleted `ledger/prices.py` and both `/prices` routes; `PRICE` events now return `400 price_removed` and the `market` batch is gone. `GET /trades` returns `method:"median20"`, `sampleSize`, `byGrade` and the last priced TRANSFERs. Android: the "Mandi prices" screen is now "Token price" (crop picker, median + grade breakdown, last-20 chart, offline cache); the buyer label and dead `PRICE`/`market` branches are removed. Dashboard shows token prices; `demo/seed.py` prints the token price. Tests: 28 ledger, 59 Android. Live seed on a temp ledger: wheat `2500` paise/kg, sample 2, `/prices` 404. No phone attached, so the new screen is unit-tested only, not device-verified. | done
- [2026-11-25 14:40] FROM Laasya → all: claiming T19 on task-T19-laasya. Implement ledger v2 in `ledger/` only: per-`(batchId, actorId, stream)` heads and `chainSeq`, BIND + permission matrix, admin token and per-IP rate limit, `receivedAt`/clock skew, `/events` pagination, `/batches/{id}/route`, `/verify`, `/events/bulk`, and keep median20 `/trades`. Android v2 is T20; I will not touch `APP/`. | open
- [2026-11-25 13:20] FROM charan → all: audit round 2 board added as T15-T24 (old T15/T16 stay complete in history). I am claiming T16 device readiness: permissions per API level, box foreground service, default-SIM SMS, and `scripts/dev.sh`. I will touch only the T16 paths; T15 docs, T17 vectors and T18 token prices are free. | open
- [2026-11-25 13:45] FROM charan → all: T16 done on task-T16-device-readiness-charan. One permission flow in `APP/core/Permissions.kt` names every denied runtime permission (API 31 Bluetooth, API 33 Nearby Wi-Fi + notifications, SMS, location, camera); the box runs GPS/tamper in a location foreground service with a partial wake lock; SMS sends on the default SMS subscription and logs sent/delivered; every log tag is `AgriChain`; `scripts/dev.sh` holds the adb cheat sheet. Device evidence: `permissions: all 10 granted`; after revoking one, `permissions: denied 1/10: android.permission.NEARBY_WIFI_DEVICES`; box `isForeground=true`, `mWakefulness=Asleep` at start and end, 90 signed GPS events over 2 min; `mesh: nearby: advertising as phone-1a373543`; no AndroidRuntime crash. Actual SMS sent/delivered and 2-phone radio tests remain manual. | done
- [2026-11-25 15:05] FROM Laasya → all: T19 done on task-T19-laasya. Ledger v2 is live in `ledger/`: per-`(batchId, actorId, stream)` heads with server `chainSeq`, BIND + the permission matrix, `X-Admin-Token` on `POST /actors`, per-IP rate limit, `receivedAt` + 5 min/30 day skew, `/events` pagination, `/batches/{id}/route`, `/verify`, `/events/bulk`, idempotency conflict, and median20 `/trades`. `demo/seed.py` now BINDs the box, writes GPS on `box/trace` and TAMPER on `box/main`, sends the admin token, and `tools/ledger.sh` defaults `AGRICHAIN_DEV=1` (`dev-admin-token`) for local demos. Touches extended to `demo/seed.py` + `tools/ledger.sh` for that integration. Tests: 37 ledger pass (9 new v2 regressions). Live seed on a temp ledger: batch A/B chains both `valid`, wheat `2500` paise/kg, sample 2. No phone attached; device checks stay pending. T20 (Android v2) is next. | done
- [2026-11-25 15:15] FROM Laasya → all: T19 merged to main (`1e765ea`). Claiming T17 on task-T17-laasya: add `contracts/vectors.json` with 12+ UTF-8 cases (Telugu, Hindi, emoji, `/`, `<>`, quotes, backslash, control escapes, int64 bounds), make Python `canonical_json` and Kotlin `CanonicalJson` emit the same raw UTF-8 bytes, and run both parity suites. The AndroidKeyStore instrumented leg stays pending because no phone is attached; I will not claim device parity. | open

## Decisions log

- 2026-11-23 charan: `android/` is one Kotlin app (XML views, single `MainActivity`) with farmer/buyer/box modes; `ledger/` is Python 3 FastAPI + SQLite; `demo/` holds scripts.
- 2026-11-23 charan: Toolchain Android SDK 36, AGP 8.9.1, Gradle 8.11.1, Kotlin 2.1.20, minSdk 26, JDK 17.
- 2026-11-23 charan: Events are canonical-JSON SHA-256 hash-chained per batch and signed with ECDSA P-256/SHA-256; actors register SPKI keys online.
- 2026-11-23 charan: 1 token = 1 kg; grade is metadata in v1; lat/lon are fixed 6-decimal strings and `data` holds integers only (prices in paise).
- 2026-11-23 charan: QR payload is `agri://batch/<batchId>`; mesh uses Nearby Connections with the v1 EVENTS envelope.
- 2026-11-23 charan: One linear writer chain per batch; offline writers sync in order and handle `409 prev_mismatch` by refetching the head.
- 2026-11-24 charan: Per-role AndroidKeyStore EC P-256 identity (`farmer`, `buyer`, `box`); T2 signs REGISTER locally and keeps batches in SharedPreferences until T11 replaces it.
- 2026-11-24 charan: T4 ledger is SQLite + FastAPI, strict one-head append, idempotent `eventId` retries; PRICE events use the `market` pseudo-batch chain.
- 2026-11-24 charan: T5 trade price is the token-weighted average of priced TRANSFER events per crop; transfers above the sender balance return `409 insufficient_balance`.
- 2026-11-24 Laasya: T7 uses the frozen TAMPER contract only. Movement = ≥2.5 m/s² deviation from gravity (5 s cooldown), light = ≥200 lux change (3 s), proximity = near/far change, geofence = >200 m from the anchor fix, checked on each GPS fix. Events carry `kind` + optional `detail` and no lat/lon.
- 2026-11-24 Laasya: T9 mandi prices use a local simulated feed (no Agmarknet key): 11 seeded crop/market pairs, SQLite snapshots, ±2.5% deterministic per-minute walk on refresh, source `demo-mandi`. The REST shape is unchanged; `_next_price` is the single swap point for a real feed.
- 2026-11-24 charan: T8 caches the last successful `/batches/{id}` summary per batch in SharedPreferences; history renders from cache when offline. Ledger host is stored app-wide (`LedgerSettings`), default `http://10.0.2.2:8000` for the emulator.
- 2026-11-24 charan: T10 barter offers are derived from BARTER events (no offer table). An accepted offer applies a balance overlay on both batches instead of writing TRANSFER events, so one accept does not need a multi-chain atomic append; `GET /batches` and TRANSFER checks use the overlaid balances.
- 2026-11-24 charan: T11 keeps `BatchStore` as the screen-level log and mirrors events into a SQLite outbox (`agrichain_sync.db`) for durable retries. `SyncPlanner` orders by `prevHash` from the server head, drops already-stored ids, and refetches the head on `prev_mismatch` (up to 4 attempts per batch).
- 2026-11-24 charan: T12 uses Nearby Connections `Strategy.P2P_CLUSTER` and the frozen v1 EVENTS envelope. Relayed events go straight into the T11 outbox; the outbox primary key is the dedupe. Clipboard copy/paste is the emulator fallback and does not change the envelope format.
- 2026-11-24 charan: T13 seeds the demo through the public REST API with fresh keys per run, so it never needs ledger internals. Batch ids are printed for the Buyer screen; phones still register their own actors.
- 2026-11-24 charan: T14 replaces the v1 mesh envelope with compact v2 keys and a shared `Transport` interface. Nearby remains; SMS is the GSM radio leg (text messages, ≤140-char base64 chunks, gateway number in settings); internet is plain HTTP to the ledger. ACKs are relayed like events so any internet node can clear the mesh. The JVM simulator is the proof; phone tests are manual.
- 2026-11-24 charan: SMS uses `AC1|<msgId>|<idx>/<total>|<base64>` with a 140-char payload (≤160-char SMS), 2 s between sends, and a persisted outbox so a dropped chunk is retried on the next broadcast. ACKs go to the inbound `originatingAddress`, not the configured gateway, and are not added to the seen-set (an ACK can arrive before its event).
- 2026-11-25 charan: T15 serves the dashboard from FastAPI itself (`/`, `/static`), so there is no second server or CDN and the demo works offline. `/stats` and `/events/recent` are read-only additions. USB testing uses `adb reverse tcp:8000 tcp:8000` plus the app's "Use USB" button (`http://127.0.0.1:8000`); Wi-Fi phones keep using the laptop LAN IP.
- 2026-11-25 charan: T16 makes `LedgerSettings` the only ledger host on Android; `PriceCache` no longer stores a URL. Discovery order is saved → USB loopback → emulator host → UDP broadcast to port 8100 with the token `AGRICHAIN_DISCOVER`; the ledger replies `{"service":"agrichain","baseUrl":"http://<lan-ip>:<port>"}` and the app saves the first URL whose `/health` returns 200. Loopback and Wi-Fi/Ethernet/cellular/VPN count as usable transports for HTTP uploads, because `adb reverse` has no validated internet.
- 2026-11-25 Laasya: audit v2 keeps the signed event fields unchanged and derives the chain key as `(batchId, actorId, stream)` (`trace` for GPS, `main` otherwise). `chainSeq`/`receivedAt` are server metadata, not signed. This fixes C2 without re-signing offline events and keeps the v1 canonical test vector valid.
- 2026-11-25 Laasya: `BIND` is a `main`-chain event signed by the current batch owner; the latest BIND wins and `boxId:null` unbinds. GPS/TAMPER require a bound box, REGISTER is farmer-only, TRANSFER/BARTER are holder-only, DELIVER is bound box or holder. This is the C1/C3 permission matrix.
- 2026-11-25 Laasya: `POST /actors` and `/admin/*` require `X-Admin-Token`; `AGRICHAIN_DEV=1` supplies `dev-admin-token` for local demos. `/events` stays signature-authorized. `/actors` and `/events` get a per-IP rate limit. This is the H1/M9 mitigation; key recovery remains a demo limitation.
- 2026-11-25 Laasya: v2 canonical JSON is UTF-8 with ASCII keys and raw non-ASCII values; integers are signed 64-bit and floats are rejected. `contracts/vectors.json` is the single source for Python/Kotlin parity, including Telugu, emoji, `/`, `<>` and quotes (M1/T17).
- 2026-11-25 Laasya: `PRICE` and the `market` batch are removed. `GET /trades` returns the median of the last 20 priced TRANSFERs (`method:"median20"`), excludes self-trades and non-positive prices, and reports `byGrade`. The UI calls it a reported token price, not mandi data (C4/M8/T18).
- 2026-11-25 Laasya: TAMPER carries `traceHead` plus `lastLat`/`lastLon`; kinds add `gps_off`/`mock_location`/`power`. GPS logs on 20 m movement or 60 s heartbeat, whichever comes first. Buyer summaries expose `heartbeatGaps`/`mockCount`/`eventCount` (M2/M3/T22).
- 2026-11-25 Laasya: SMS v3 is a CRC-checked binary envelope with an 8-hex msgId and ≤140-char base64url chunks. ACKs are signed and only clear events actually sent to that peer; `seenEvents`/`seenEnvelopes`/`sentTo`/`acked` are persisted. Gateway ACKs only after 201/idempotent success (H6/M4/T21).
- 2026-11-25 Laasya: `/ledger/root` signs a Merkle root over all event hashes with a node P-256 key; `ledger/mirror.py` compares roots from two nodes and exits non-zero on divergence. Clients pin the last root. This is the H2 mitigation (T23).
- 2026-11-25 Laasya: phone-dependent checks are listed in “Device test checklist”. No phone is attached to this workstation, so D1–D9 stay `pending`; code/tests that run on the JVM/Python are still verified locally.
- 2026-11-25 charan: audit round 2 reuses T15-T24. The pre-audit T15/T16 (laptop runner, auto-detect) remain complete in git history; new work uses suffixed branches such as `task-T16-device-readiness-charan`.
- 2026-11-25 charan: T16 keeps one runtime permission list in `APP/core/Permissions.kt`; MainActivity requests it and names every denial. The box runs `GpsRecorder` and `TamperDetector` in `BoxService` (foreground type location + partial wake lock) through the shared `BoxEngine`, so closing the activity or turning the screen off does not stop logging. SMS uses the default SMS subscription and `SmsStatusReceiver` logs the carrier sent/delivered result. Every log tag is `AgriChain`. `scripts/dev.sh` is the adb cheat sheet; `--es openBox <uuid> --ez autoStart true` and `--ez openMesh true --ez autoStart true` on MainActivity are dev-only launch hooks.
- 2026-11-25 Laasya: T19 implements the v2 contract without changing signed bytes. `events` gains `stream`/`chain_seq`/`received_at`; the chain key is `(batch_id, actor_id, stream)` and `chain_seq` starts at 1 per chain. Opening a v1 database migrates rows by assigning `stream` from the event type and numbering each actor's chain, but it does not rewrite old `prevHash` values, so pre-v2 rows stay readable while `/verify` may report a legacy chain as broken. `demo/seed.py` and `tools/ledger.sh` were updated for admin-token registration because the demo must keep working end to end.

## Blockers

- **No Android phone on Laasya's workstation.** `adb devices -l` is empty (2026-11-25 13:49). D1–D9 in the Device test checklist are pending; do not claim them as verified. Charan has the USB test phone for T16.
- **T16 is charan's.** T20/T22 need his foreground location service, API-level permission flow and `scripts/dev.sh`. Coordinate before touching `APP/box/`, `AndroidManifest.xml` or `android/app/build.gradle.kts`.
- **T17 instrumented test needs a phone + `adb reverse tcp:8000 tcp:8000`.** The JVM/Python parity suite runs without one; the AndroidKeyStore test is written but cannot be marked passed until D2/D4 are run.
- **Physical provenance (H4) and production TLS (M9) are not demo features.** The QR is copyable; the demo runs cleartext on a trusted LAN/USB. The runbook requires a TLS reverse proxy and a signed one-time QR for production.
- **Barter is still an overlay, not escrow (H5).** `/verify` includes it, but cross-chain atomicity/escrow is documented as a limitation, not implemented in the demo.
- **Key recovery (H1) is not implemented.** A lost AndroidKeyStore key means a lost actor identity/balance; the demo re-registers a new actor. Production needs recovery/revocation.
- **SMS volume/policy (M5).** India P2P daily limits and Play SMS-permission policy must be checked before a field pilot; the demo uses a configured gateway phone.
- Teammates need JDK 17 and the Android SDK (build-tools 35+, platform 36) to build the app.

## Demo script and run instructions

Final script is T24. Every task must serve this flow:

1. Start the v2 ledger with `AGRICHAIN_DEV=1` (admin token `dev-admin-token`); register farmer, buyer and box actors while online.
2. Farmer registers a batch (max 100000 kg) → QR shown; box scans the QR.
3. Farmer taps **Authorize box** → signed `BIND` on the farmer's `main` chain. The box now has its own `trace`/`main` chains.
4. Box goes offline; GPS points (20 m / 60 s) and tamper events are signed and queued. TAMPER carries `traceHead` + last fix; mock/power/gps-off are flagged.
5. Box reaches an online phone; per-chain sync posts events. `unbound_box`/`batch_unknown`/`prev_mismatch` retry; the farmer's chain is never blocked by the box's chain.
6. Buyer scans the QR → verifies hashes/signatures/chains/BIND locally, sees route, tamper list, `heartbeatGaps`/`mockCount` and the reported token price (`median20`).
7. Farmer makes a barter offer; second farmer accepts; the token-price chart shows the median of the last 20 priced transfers, never mandi data.

Run (updated as tasks land):

```bash
# Ledger + dashboard (T4, T15). Auto-creates .venv if deps are missing.
# Local demo defaults AGRICHAIN_DEV=1 (admin token dev-admin-token); set
# AGRICHAIN_DEV=0 to close actor registration.
./tools/ledger.sh
# Dashboard: http://localhost:8000/  ·  API docs: /docs
# LAN phones: use the printed http://<laptop-ip>:8000 URL, or let the app auto-detect
# it over UDP 8100 (disable the responder with AGRICHAIN_DISCOVERY=0).

# USB phone (T15, T16): build, install, adb reverse, grant permissions, launch.
./tools/dev.sh            # starts the ledger too; Ctrl-C stops it
# Or run ./tools/ledger.sh in one terminal and ./tools/phone.sh in another.
# In the app: "Auto-detect ledger" on the launcher (or Ledger sync → "Use USB").
# The app probes saved → 127.0.0.1 (USB) → 10.0.2.2 (emulator) → LAN and keeps the
# first healthy /health. Token price, sync, buyer and box all share that saved host.
# adb reverse maps phone 127.0.0.1:8000 to this laptop; Wi-Fi is not needed.
# adb cheat sheet (install / reverse / logs / grant / ui): ./scripts/dev.sh help

# Ledger tests
pip install -r ledger/requirements-dev.txt
cd ledger && python3 -m pytest -q

# Android skeleton (T1). JDK 17 + SDK 36 required.
cd android && ./gradlew installDebug
# Default ledger URL for emulator: http://10.0.2.2:8000; use the laptop LAN IP on phones.

# Transport box (T6)
# Launcher → "This is the transport box".
# 1. Set the ledger URL, tap "Register box online" (POST /actors).
# 2. Scan the farmer's batch QR, then "Bind" (GET /batches/{id} → lastHash).
# 3. "Start logging"; each GPS fix is signed and hash-chained locally. Logging runs in a
#    location foreground service with a partial wake lock, so the screen can go off.
#    "Stop logging" ends the service.

# Tamper detection (T7)
# After binding, tap "Arm tamper detection".
#  - Movement (accelerometer), light and proximity changes sign TAMPER events automatically.
#  - "Set 200 m geofence here" anchors a fence at the current fix; keep GPS logging on
#    so each fix is checked against it.
#  - "Simulate tamper (demo)" emits a TAMPER event without touching the sensors.

# Token price (T18)
# Launcher → "Token price"; pick a crop. GET /trades returns the median of the last
# 20 priced TRANSFERs (self-trades excluded) plus byGrade, method "median20". This is
# reported token data, not mandi data; the last successful response is cached offline.

# Buyer history (T8)
# Buyer → set the ledger host (LAN IP on a phone), scan the farmer QR or paste a batch id.
# First load needs the ledger; the last summary is then cached for offline viewing.

# Barter (T10)
# Both farmers must be registered and own batches on the ledger.
# Farmer → Barter: pick a batch, enter give/want kg plus the counterparty batch uuid and
# actor id, then "Create offer". The counterparty opens Barter and taps "Accept".
# Balances on both batch summaries swap; the offerer can Cancel while it is open.

# Offline sync (T11)
# Launcher → "Ledger sync": set the host, "Register actors" once, then "Sync events".
# Farmer REGISTERs and box GPS/TAMPER events are queued locally and uploaded in chain
# order. A 409 prev_mismatch refetches the head and retries; the log names rejects.

# Mesh relay + SMS radio leg (T14)
# Every phone: Launcher → "Mesh relay", save the gateway phone number, grant SMS + Nearby.
# Radio-only phone: no ledger host needed; REGISTER/TRANSFER/TAMPER/DELIVER events are
# chunked into text SMS to the gateway. GPS is never sent over SMS (Nearby/internet only).
# Gateway phone: set the ledger host and keep it online; it reassembles chunks, POSTs to
# the ledger (idempotent by eventId) and broadcasts ACK, which clears the sender's outbox.
# Nearby works phone-to-phone with no SIM; the same outbox feeds every transport.
# Manual 2-phone test: B (gateway) online + ledger host + Start; A radio-only registers a
# batch, opens Mesh relay, sends; B logs "accepted N" and "uploaded N"; A pending → 0;
# GET /batches/{id} shows the events. Send the same envelope twice: the ledger count must
# not change. Emulator: copy/paste the envelope between Mesh relay screens.

# Full demo seed (T13)
# With the ledger running:
python3 demo/seed.py --base-url http://127.0.0.1:8000
# Prints batch A/B ids, the QR payload and the demo actors for the Buyer screen.

# Unit tests (canonical event hash, QR codec, box events, buyer history, barter, sync, mesh, SMS)
cd android && ./gradlew testDebugUnitTest

# v2 ledger with admin-token registration (T19)
AGRICHAIN_DEV=1 AGRICHAIN_ADMIN_TOKEN=dev-admin-token ./tools/ledger.sh
# The app's Ledger sync screen stores the same admin token for "Register actors".

# Canonical JSON parity vectors (T17): Python and Kotlin read contracts/vectors.json
cd ledger && python3 -m pytest -q tests/test_vectors.py
cd ../android && ./gradlew testDebugUnitTest --tests 'com.agrichain.core.CanonicalVectorsTest'

# Instrumented AndroidKeyStore signing test (needs a phone + adb reverse)
adb reverse tcp:8000 tcp:8000
cd android && ./gradlew connectedDebugAndroidTest

# Signed root + mirror (T23)
curl -s http://127.0.0.1:8000/ledger/root
python3 ledger/mirror.py --primary http://127.0.0.1:8000 --secondary http://127.0.0.1:8001

# Device-only checks are D1–D9 in “Device test checklist”. They stay pending until a phone
# is attached; never mark them done without quoting adb/logcat output.
```
