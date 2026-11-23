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
| T6 | Box app: GPS logging + event signing | claimed | Laasya | task-T6-laasya | APP/box/, APP/crypto/ | T1, T4 |
| T7 | Box app: tamper triggers | open | — | — | APP/box/ | T6 |
| T8 | Buyer history view (route, tamper flags) | open | — | — | APP/buyer/ | T2, T3, T4 |
| T9 | Mandi prices: API, cache, chart | open | — | — | ledger/prices.py, APP/prices/ | T4 |
| T10 | Barter: offer / accept / swap | open | — | — | ledger/barter.py, APP/barter/ | T4, T5 |
| T11 | Offline SQLite queue + sync | open | — | — | APP/data/ | T2, T4 |
| T12 | Mesh relay (Nearby Connections) | open | — | — | APP/mesh/ | T11 |
| T13 | Demo script + polish | open | — | — | demo/, README.md | all |

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
| `POST /actors` | `{"actorId","name","role","pubKey"}` | `201 {"actorId"}`; `409` if key differs |
| `GET /actors/{actorId}` | — | `{"actorId","name","role","pubKey"}` |
| `POST /events` | one Event | `201 {"ok":true,"batchId","head","seq"}`; `400` bad hash/sig; `409 {"error":"prev_mismatch","head":"..."}`; `422` unknown actor |
| `POST /events/bulk` | `{"events":[Event,...]}` | `200 {"results":[{"eventId","ok","status","error"?}]}`, appended in order |
| `GET /events?batchId=<id>` | — | `{"batchId","head","events":[Event,...]}` in chain order |
| `GET /batches/{batchId}` | — | BatchSummary with `events` |
| `GET /batches?owner=<actorId>` | — | `{"batches":[BatchSummary]}` without `events` |
| `GET /batches/{batchId}/verify` | — | `{"valid":bool,"events":int,"head":str,"brokenAt"?:eventId,"reason"?}` |
| `GET /prices?crop=<crop>&market=<market>` | market optional | `{"updatedAt":ms,"prices":[{"crop","market","pricePerKgPaise","source","ts"}]}` |
| `POST /prices/refresh` | `{"crop"?,"market"?}` | same shape as `GET /prices` |
| `GET /trades?crop=<crop>` | crop required | `{"crop","updatedAt":ms,"pricePerKgPaise":int\|null,"trades":[{"batchId","eventId","ts","tokens","pricePerKgPaise","fromActorId","toActorId"}]}` |

BatchSummary:

```json
{"batchId":"uuid","crop":"wheat","grade":"A","initialKg":100,
 "balances":{"farmer:uuid":100},"ownerId":"farmer:uuid",
 "tamperCount":0,"lastHash":"<hex>","verified":true,"events":[]}
```

`ownerId` = holder of the largest balance (ties: most recent transfer). A TRANSFER may not exceed the sender's balance; the ledger rejects it with `409 insufficient_balance`. Trade price is the token-weighted average of TRANSFER events that carry `pricePerKgPaise` for that crop (`null` with no trades). Barter detail endpoints are T10's to publish before anyone depends on them.

### Mesh and QR

Mesh envelope v1: `{"v":1,"kind":"EVENTS","senderId":"...","sentAt":ms,"events":[Event,...]}`. Receivers dedupe by `eventId`, relay unchanged, queue for the ledger. New kinds need a decision.

QR payload: `agri://batch/<batchId>`. Buyer scans → `GET /batches/{batchId}`.

## Inbox

Append only. `[date time] FROM name → TO name/all: message | open / done`

- [2026-11-23 23:55] FROM charan → all: T1 done, contracts v1 frozen above. T2 is the next open task. Register all actors online during demo setup before the offline segment. | open
- [2026-11-24 00:17] FROM charan → all: T2 done. Reuse `APP/core/` for canonical JSON, events and signing (T6/T11 especially). REGISTER is signed locally; `BatchStore` uses SharedPreferences as a placeholder until T11's SQLite queue. No ledger upload yet; navigation lands with T3. | open
- [2026-11-24 00:30] FROM charan → all: T3 done. Launcher → Farmer (register, my batches, QR) or Buyer (scan QR). Buyer history is T8; ledger T4 is next. | open
- [2026-11-24 00:26] FROM Laasya → all: charan claimed T4 before my claim push, so I am claiming T6 (box GPS + signing) on task-T6-laasya. I will reuse APP/core Event/ActorIdentity and build against the ledger contract while T4 is in progress. | open
- [2026-11-24 00:55] FROM charan → all: T4 done. Ledger runs from `ledger/`; all endpoints above are live and tested. Actors must be registered via `POST /actors` (no Android registration UI yet). T5 is next. | open
- [2026-11-24 01:05] FROM charan → all: T5 done. `TRANSFER` checks sender balance; `GET /trades?crop=` returns token-weighted trade price. Reuse `ledger/tokens.py` for T10 swaps; T8 can show balances and price. | open

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
# Ledger (T4)
cd ledger && python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000

# Ledger tests
pip install -r requirements-dev.txt
python3 -m pytest -q

# Android skeleton (T1). JDK 17 + SDK 36 required.
cd android && ./gradlew installDebug
# Default ledger URL for emulator: http://10.0.2.2:8000; use the laptop LAN IP on phones.

# T2 unit tests (canonical event hash + QR codec)
cd android && ./gradlew testDebugUnitTest
```
