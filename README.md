# AgriChain — offline-first agri supply chain

Crop tokens (1 token = 1 kg graded produce) on a hash-chained public ledger with visible prices.
Farmers barter P2P; a spare-phone transport box logs GPS and tamper events while offline; phones relay signed events over a mesh and the first phone with internet syncs to the ledger.
Buyers scan a batch QR to see the route, tamper flags, price and full history.

## Task board

Status: `open` / `claimed` / `done` / `blocked`. Claim one task at a time. Read Contracts before coding.
Branch: `task-<ID>-<name>`. `APP` below = `android/app/src/main/java/com/agrichain`. Do not edit files listed under another task.

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
| T15 | Laptop ledger runner + web dashboard + USB phone flow | done | charan | task-T15-charan | tools/, ledger/, APP/net/, APP/data/, README.md | T4, T14 |
| T16 | Auto-detect ledger + unify prices host | done | charan | task-T16-charan | APP/net/, APP/prices/, APP/data/, APP/box/, APP/MainActivity.kt, android/app/src/main/res/layout/, ledger/, tools/, README.md | T15 |

## Contracts

v1, frozen at T1. Change only with a Decisions Log entry and an Inbox notice to everyone affected. Base URL `http://<host>:8000`, JSON UTF-8. Errors: `{"error":"code","detail":"..."}`.

### Identifiers and units

- `batchId`: UUIDv4. `actorId`: `farmer:<uuid>` | `buyer:<uuid>` | `box:<uuid>`.
- Tokens: integer kilograms. 1 token = 1 kg. `grade` (A/B/C) is metadata in v1.
- `ts`: Unix epoch milliseconds (integer).
- `lat` / `lon`: decimal-degree **strings** with exactly 6 decimals, or `null`. Fixed strings keep canonical JSON identical in Kotlin and Python.
- No floats anywhere in `data`: integers only. Prices in paise, weights in kg, distance in metres, speed in cm/s.

### Event

```json
{"eventId":"uuid","batchId":"uuid","type":"GPS","actorId":"box:uuid",
 "ts":1764000000123,"lat":"17.385044","lon":"78.486671",
 "data":{"accuracyM":8},"prevHash":"<hex>","hash":"<hex>","sig":"<base64>"}
```

Types: `REGISTER | TRANSFER | GPS | TAMPER | DELIVER | BARTER | PRICE`.

| type | `data` fields |
|------|---------------|
| REGISTER | `crop` str, `grade` "A"/"B"/"C", `weightKg` int. Genesis only (`prevHash=""`). Mints `weightKg` tokens to `actorId`. |
| TRANSFER | `toActorId` str, `tokens` int, `pricePerKgPaise` int? Moves tokens. Balance must cover it. |
| GPS | `accuracyM` int. `lat`/`lon` required. |
| TAMPER | `kind` "movement" / "light" / "proximity" / "geofence", `detail` str? |
| DELIVER | `toActorId` str?, `note` str?. |
| BARTER | `offerId` uuid, `action` "offer"/"accept"/"cancel", `withActorId` str, `giveKg` int, `wantBatchId` str, `wantKg` int. |
| PRICE | `crop` str, `market` str, `pricePerKgPaise` int, `source` str. Use `batchId="market"`; not shown in batch history. |

Canonical bytes = UTF-8 JSON of `{eventId,batchId,type,actorId,ts,lat,lon,data,prevHash}` with keys sorted A-Z at every level, separators `,` and `:`, no extra whitespace, ASCII only. Exclude `hash` and `sig`.
`hash` = lowercase hex SHA-256 of canonical bytes. `sig` = base64 DER ECDSA P-256/SHA-256 over the canonical bytes. Public key = base64 SPKI DER, registered while online.

Test vector:

```
canonical = {"actorId":"farmer:33333333-3333-4333-8333-333333333333","batchId":"22222222-2222-4222-8222-222222222222","data":{"crop":"wheat","grade":"A","weightKg":100},"eventId":"11111111-1111-4111-8111-111111111111","lat":null,"lon":null,"prevHash":"","ts":1764000000000,"type":"REGISTER"}
sha256 = cfad1baad5f9915b39aba2d330ca28b79feb12706d275fc7533c376361ebed80
```

Chain rule: one linear chain per `batchId`; genesis `prevHash=""`. The server appends only if `prevHash` equals the current batch head, else `409 prev_mismatch`. One offline writer per batch at a time; the box becomes that writer when it binds after the last sync. Relay events unchanged; never re-sign.

### Ledger REST

| Method + path | Body / query | Response |
|---------------|--------------|----------|
| `GET /health` | — | `{"ok":true}` |
| `GET /` or `/dashboard` | — | HTML dashboard: batches, live events, barter, prices, chain health |
| `GET /static/*` | — | dashboard CSS/JS (no CDN, works offline) |
| `POST /actors` | `{"actorId","name","role","pubKey"}` | `201 {"actorId"}`; `409` if key differs |
| `GET /actors/{actorId}` | — | `{"actorId","name","role","pubKey"}` |
| `POST /events` | one Event | `201 {"ok":true,"batchId","head","seq"}`; `400` bad hash/sig; `409 {"error":"prev_mismatch","head":"..."}`; `422` unknown actor |
| `POST /events/bulk` | `{"events":[Event,...]}` | `200 {"results":[{"eventId","ok","status","error"?}]}`, appended in order |
| `GET /events?batchId=<id>` | — | `{"batchId","head","events":[Event,...]}` in chain order |
| `GET /events/recent?limit=20` | limit 1–100 | `{"events":[Event + seq + crop],"updatedAt":ms}` newest first, all batches |
| `GET /stats?limit=20` | limit 1–100 | `{"actors","batches","events","tamperEvents","validChains","recent","updatedAt"}` |
| `GET /batches/{batchId}` | — | BatchSummary with `events` |
| `GET /batches?owner=<actorId>` | — | `{"batches":[BatchSummary]}` without `events` |
| `GET /batches/{batchId}/verify` | — | `{"valid":bool,"events":int,"head":str,"brokenAt"?:eventId,"reason"?}` |
| `GET /prices?crop=<crop>&market=<market>` | market optional | `{"updatedAt":ms,"prices":[{"crop","market","pricePerKgPaise","source","ts"}]}` |
| `POST /prices/refresh` | `{"crop"?,"market"?}` | same shape as `GET /prices` |
| `GET /trades?crop=<crop>` | crop required | `{"crop","updatedAt":ms,"pricePerKgPaise":int\|null,"trades":[{"batchId","eventId","ts","tokens","pricePerKgPaise","fromActorId","toActorId"}]}` |
| `GET /barter/offers?actorId=<id>&status=<s>` | both optional | `{"offers":[{"offerId","status","batchId","actorId","withActorId","giveKg","wantBatchId","wantKg","ts","acceptEventId"?,"cancelEventId"?}]}` newest first |
| `GET /barter/offers/{offerId}` | — | one offer object as above |

BatchSummary:

```json
{"batchId":"uuid","crop":"wheat","grade":"A","initialKg":100,
 "balances":{"farmer:uuid":100},"ownerId":"farmer:uuid",
 "tamperCount":0,"lastHash":"<hex>","verified":true,"events":[]}
```

`ownerId` = holder of the largest balance (ties: most recent transfer). A TRANSFER may not exceed the sender's balance; the ledger rejects it with `409 insufficient_balance`. Trade price is the token-weighted average of TRANSFER events that carry `pricePerKgPaise` for that crop (`null` with no trades). Barter is a two-party signed handshake posted through `POST /events`: an `offer` event on the giver's batch, then an `accept` event on the counterparty's batch (mirrored `giveKg`/`wantKg`, same `offerId`), or a `cancel` event by the offerer. An accepted pair swaps `giveKg` on the offer batch and `wantKg` on the want batch as a balance overlay (no TRANSFER events); balances, owner and later transfer checks include accepted swaps. `409` codes: `offer_not_open`, `offer_mismatch`, `not_counterparty`, `duplicate_offer`.

### Mesh and QR

One envelope, compact keys: `{"v":2,"k":"E","id":"<uuid>","s":"<senderId>","t":ms,"ttl":6,"ev":[...]}`. `k` is `E` (events) or `A` (ack, `"ack":[eventId,...]`). Event keys: `i` eventId, `b` batchId, `y` type, `a` actorId, `t` ts, `la` lat, `lo` lon, `d` data, `p` prevHash, `g` sig. `hash` is omitted; receivers recompute it. v1 envelopes still parse. Transports implement `Transport { id, available(), maxPayload, send(envelope), onReceive }`: Nearby (Bluetooth/WiFi Direct), SMS (GSM radio), Internet (HTTP to the ledger). SMS sends only `REGISTER`/`TRANSFER`/`TAMPER`/`DELIVER` (never GPS) as text SMS, base64 chunks ≤140 chars, `AC1|<msgId>|<idx>/<total>|<base64>`, to the gateway number in settings. Receivers reassemble before relaying.

Relay rules: dedupe by `eventId` in the persisted `seen` set; any node with internet POSTs (ledger append is idempotent by `eventId`), broadcasts an ACK and marks acked events synced; without internet, queue in the SQLite outbox and forward on every available transport with `ttl-1`, dropping at 0. Retry when a transport or internet appears. No gateway election. QR payload: `agri://batch/<batchId>`.

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

## Blockers

- None. Teammates need JDK 17 and the Android SDK (build-tools 35+, platform 36) to build the app.

## Demo script and run instructions

Final script is T13. Every task must serve this flow:

1. Start ledger; register farmer, buyer and box actors while online.
2. Farmer registers a batch → QR shown; box scans/binds to the batch.
3. Box goes offline; GPS points and a tamper event are signed and queued.
4. Box comes near an online farmer phone; mesh relays events; phone posts them to the ledger.
5. Buyer scans the QR → sees route, tamper flags, balances and verified history.
6. Farmer makes a barter offer; second farmer accepts; prices panel shows mandi rates.

Run (updated as tasks land):

```bash
# Ledger + dashboard (T4, T15). Auto-creates .venv if deps are missing.
./tools/ledger.sh
# Dashboard: http://localhost:8000/  ·  API docs: /docs
# LAN phones: use the printed http://<laptop-ip>:8000 URL, or let the app auto-detect
# it over UDP 8100 (disable the responder with AGRICHAIN_DISCOVERY=0).

# USB phone (T15, T16): build, install, adb reverse, grant permissions, launch.
./tools/dev.sh            # starts the ledger too; Ctrl-C stops it
# Or run ./tools/ledger.sh in one terminal and ./tools/phone.sh in another.
# In the app: "Auto-detect ledger" on the launcher (or Ledger sync → "Use USB").
# The app probes saved → 127.0.0.1 (USB) → 10.0.2.2 (emulator) → LAN and keeps the
# first healthy /health. Prices, sync, buyer and box all share that saved host.
# adb reverse maps phone 127.0.0.1:8000 to this laptop; Wi-Fi is not needed.

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
# 3. "Start logging"; each GPS fix is signed and hash-chained locally.
#    No network is needed after binding; screen stays on while the box screen is open.

# Tamper detection (T7)
# After binding, tap "Arm tamper detection".
#  - Movement (accelerometer), light and proximity changes sign TAMPER events automatically.
#  - "Set 200 m geofence here" anchors a fence at the current fix; keep GPS logging on
#    so each fix is checked against it.
#  - "Simulate tamper (demo)" emits a TAMPER event without touching the sensors.

# Mandi prices (T9, T16)
# Launcher → "Mandi prices"; tap "Auto-detect ledger" (or keep the saved host) and pick a
# crop/market. A failed fetch retries once through discovery, so USB works without an IP.
# The ledger seeds 11 crop/market rates and caches snapshots in SQLite; the app keeps
# the last successful response in SharedPreferences, so the chart still works offline.

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
```
