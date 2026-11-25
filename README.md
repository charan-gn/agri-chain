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
| T17 | Canonical JSON vectors + on-device parity | done | Laasya | task-T17-laasya | contracts/, ledger/crypto.py, ledger/tests/, APP/core/CanonicalJson.kt, android/app/src/test/, android/app/build.gradle.kts | — |
| T18 | Remove mandi, add token price (median20) | done | Laasya | task-T18-laasya | ledger/prices.py, ledger/app.py, ledger/tokens.py, ledger/validation.py, ledger/tests/, APP/prices/, APP/buyer/, APP/mesh/HttpLedgerGateway.kt, layouts, manifest, demo/seed.py, ledger/web/, README.md | - |
| T19 | Ledger v2: chains, permissions, BIND, admin token, pagination, verify | done | Laasya | task-T19-laasya | ledger/, demo/seed.py, tools/ledger.sh | T15 |
| T20 | Android v2: per-chain writer, BIND UI, admin token, tamper traceHead, sync planner | done | bhashith | main (6700e20) | APP/core/, APP/box/, APP/data/, APP/batch/, APP/buyer/, APP/ui/, tests | T15, T19 |
| T21 | SMS hardening: ACK rules, dedupe, binary v3 | done | bhashith | main (6700e20) | APP/mesh/, android/app/src/test/java/com/agrichain/mesh/ | T15 |
| T22 | Tamper upgrades: movement gating, heartbeat gaps, mock warnings | done | charan | task-T22-charan | APP/box/, ledger/, APP/buyer/, tests | T19, T20 |
| T23 | Anchoring + mirror node | done | Laasya | task-T23-laasya | ledger/anchor.py, ledger/mirror.py, ledger/app.py, ledger/db.py, ledger/tests/, README.md | T19 |
| T24 | Docs + demo + final device pass | done | charan | task-T22-charan | README.md, demo/, scripts/ | all |
| T25 | Meshtastic LoRa transport | done | charan | task-T25-charan | android/app/src/main/java/com/agrichain/mesh/MeshtasticCodec.kt, android/app/src/main/java/com/agrichain/mesh/MeshtasticTransport.kt, android/app/src/test/java/com/agrichain/mesh/MeshtasticCodecTest.kt, AndroidManifest.xml | T14, T21 |
| T26 | BLE chat mesh + chat-first launcher | done | charan | task-T26-ble-mesh | APP/MainActivity.kt, APP/mesh/BleMeshCodec.kt, APP/mesh/BleMeshTransport.kt, APP/mesh/MeshEnvelope.kt, APP/mesh/RelayEngine.kt, APP/mesh/MeshRuntime.kt, APP/mesh/Transport.kt, APP/data/ChatStore.kt, APP/core/Permissions.kt, android/app/src/main/res/layout/activity_main.xml, android/app/src/main/AndroidManifest.xml, android/app/src/test/java/com/agrichain/mesh/, README.md | T14, T21 |
| T27 | Public web viewer + deploy | done | charan | task-T27-webapp-charan | webapp/, deploy/, README.md | T18, T19 |

## Contracts

Full v2 spec, REST routes, retry table, audit-finding map and D1-D11 device checklist: `contracts/contract-v2.md`. v1 events remain readable.

- IDs: `batchId` UUIDv4; `actorId` `farmer:<uuid>` | `buyer:<uuid>` | `box:<uuid>`. Tokens are integer kg; `ts` is epoch ms; `lat`/`lon` are fixed 6-decimal strings or null; no floats.
- Event: `{eventId,batchId,type,actorId,ts,lat,lon,data,prevHash,hash,sig}`. Types: `REGISTER | BIND | TRANSFER | GPS | TAMPER | DELIVER | BARTER`. `PRICE` is removed (`400 price_removed`).
- Chains: one per `(batchId, actorId, stream)`, where `stream` is `trace` for GPS and `main` otherwise; each starts at `prevHash=""`. The server returns `409 prev_mismatch` with the chain head; on conflict, sign a **new** event with the new head and discard the old one.
- Permissions: `REGISTER` farmer-only; `BIND`/`TRANSFER`/`BARTER` holder-only; `GPS`/`TAMPER` bound-box-only; `DELIVER` bound box or holder. The latest `BIND` authorizes the box for that batch.
- Canonical bytes: UTF-8 JSON of the 9 signed fields, keys sorted A-Z at every level, `,`/`:` separators, no whitespace. Object keys are ASCII printable; string values are UTF-8 with raw non-ASCII and standard control escapes; integers are signed 64-bit and floats are rejected.
- `hash` = lowercase SHA-256 hex of the canonical bytes; `sig` = base64 DER ECDSA P-256/SHA-256. `POST /actors` and `/admin/*` require `X-Admin-Token`; events are signature-authorized.
- Mesh: one v3 envelope (`E` events, `A` signed ACK, `C` signed chat) over BLE GATT, Nearby, SMS (binary AC3) and Meshtastic LoRa; chat stays off the ledger/SMS/LoRa. Parity: `contracts/vectors.json` is the Python/Kotlin suite.
- Device checks D1-D11 are pending; quote `adb`/`logcat` output before marking one done.

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
- [2026-11-25 15:35] FROM Laasya → all: T17 done on task-T17-laasya. `contracts/vectors.json` has 14 event-shaped vectors covering Telugu, Hindi, emoji, `/`, `<>`, quotes, backslash, control escapes and int64 bounds; Python `crypto.canonical_json` and Kotlin `CanonicalJson` both emit sorted-key raw UTF-8 and reject floats/non-ASCII keys/unpaired surrogates. Python reads the vectors in `ledger/tests/test_vectors.py`; Kotlin reads the same file from the test classpath in `CanonicalVectorsTest`. Tests: 74 ledger, 62 Android, 0 failures. The AndroidKeyStore instrumented parity leg remains a pending device item (no phone); the JVM/Python byte parity is verified. | done
- [2026-11-25 15:45] FROM Laasya → all: T17 merged to main (`6ccff0d`). Claiming T23 on task-T23-laasya: add `ledger/anchor.py` (persistent node P-256 key, Merkle root over event hashes, signed `GET /ledger/root`) and `ledger/mirror.py` (fetch two roots, verify signatures, exit non-zero on divergence). No contract shape change; `height`/`eventCount`/`root`/`ts`/`nodeId`/`pubKey`/`sig` stay as documented. | open
- [2026-11-25 16:05] FROM Laasya → all: T23 done on task-T23-laasya. `GET /ledger/root` now returns a P-256-signed Merkle root over all event hashes in `seq` order (odd nodes promoted, empty root all zeros); the canonical signed payload is `{height,eventCount,root,ts,nodeId,pubKey}` and the node key persists at `ledger/node_key.pem` (gitignored, override with `AGRICHAIN_NODE_KEY`). `ledger/mirror.py` fetches both roots, verifies both signatures, compares root/height/eventCount and exits 0 match / 1 divergence / 2 fetch-or-signature failure. Tests: 79 ledger pass (5 new). Live two-node check: copied seeded DB, both nodes height 9, root `e734f7e79c858c79…`, mirror exit 0; an empty third node produced `DIVERGENCE root, height, eventCount` and exit 1. | done
- [2026-11-25 16:29] FROM charan → all: claiming T22 (tamper upgrades: movement gating, heartbeat gaps, mock warnings) on task-T22-charan and T24 (docs + demo + final device pass) on task-T24-charan. | done
- [2026-11-25 16:39] FROM charan → all: T22 + T24 done on task-T22-charan. T22: added `MovementGate` (2 hits / 1.5 s) and `shouldRecordGps` (20 m move or 60 s heartbeat) in `TamperRules.kt`, `traceHead`/`lastLat`/`lastLon` + `gps_off`/`mock_location`/`power` in `TamperEvents.kt`/`BoxEventLog.kt`/`BoxEngine.kt`, `mock` bool in `GpsEvents.kt`, and `heartbeatGaps`/`mockCount`/`eventCount` warnings in `BatchHistory.kt`/`BuyerHistoryActivity.kt`. T24: added TLS reverse-proxy (M9) and DPDP/coarse-route (M10) runbook below; verified `demo/seed.py` + `ledger/mirror.py` against a live temp ledger (`median20` 2500 paise/kg, batch A/B chains valid, mirror height 9 root match). Tests: 79 ledger pytest pass; `testDebugUnitTest` passes. D1-D9 2-phone / 5-10 min device checks stay `pending` until run manually. | open
- [2026-11-25 17:05] FROM charan → all: claiming T25 (Meshtastic LoRa transport) on task-T25-charan. Adds `MeshtasticCodec` (MC1 chunking, 231-byte MTU, `MeshtasticReassembler`) and `MeshtasticTransport` (binds to `com.geeksville.mesh.IMeshService` AIDL, private portnum 257, stub/JVM mode for tests). Transport plugs into `RelayEngine` unchanged — dedupe, TTL and ACK logic untouched. India band: 865–867 MHz only. Skipped: Bridgefy/Briar/Bitchat (Indian I4C takedowns), MeshCore (Meshtastic-incompatible), OpenMANET (hardware unavailable). D10 device test stays pending until two 865 MHz boards are available. | open
- [2026-11-25 17:45] FROM charan → all: T26 done on task-T26-ble-mesh. The launcher is now chat-first: signed mesh chat is the home screen and the farmer/buyer/box/prices/sync/radio screens sit under **Tools**. New clean-room BLE GATT transport (`ble`) uses a 128-bit service UUID, lower-hash central election, MTU-aware fragmentation/reassembly, HELLO peer hash, and the existing `RelayEngine` for TTL/dedupe/store-and-forward. Chat envelopes (`k:"C"`) are ECDSA P-256 signed with TOFU keys, stored in `agrichain_chat.db`, and retried when a peer appears; they never touch the ledger, SMS or LoRa. **No BitChat code was copied** (bitchat-android is GPL-3.0 despite its public-domain README, and this repo has no license), and the wire format is not BitChat-compatible. The July 2026 I4C takedown risk recorded under T25 still applies; confirm before any public release. Tests: 108 Android pass. D11 two-phone BLE test is pending (no BLE hardware attached). | done
- [2026-11-25 22:10] FROM charan → all: T20 + T21 are on main as `6700e20` (authored by bhashith): per-chain writer, farmer BIND UI, admin token, tamper `traceHead`, buyer local verification, per-chain sync planner, signed ACKs with `sentTo`/`acked` scoping, and binary SMS v3 with CRC. Tests: 108 Android pass. D4/D5/D6 device checks stay pending. | done
- [2026-11-25 22:10] FROM charan → all: T25 is merged on main (`44b083b`): Meshtastic MC1 codec, transport and JVM tests. D10 stays pending until two 865-867 MHz boards are available. | done

- [2026-11-25 22:20] FROM charan → all: claiming T27 (public web viewer + deploy) on task-T27-webapp-charan. Reviving Sresta's read-only dashboard/buyer-trace UI, replacing the removed `/prices` proxy with v2 `/trades` (median20), and keeping only the Docker/host deploy files. The redundant USB presets are skipped because T15/T16 already cover USB. | open
- [2026-11-25 22:30] FROM charan → all: T27 done on task-T27-webapp-charan. `webapp/` is a read-only dashboard + buyer trace (`/trace`) proxying `/api/batches`, `/api/offers` and `/api/trades?crop=…` (median20); the removed `/prices` and `/prices/refresh` proxies are gone. `deploy/` adds Linux/WSL and PowerShell hosts plus Docker Compose; the redundant USB presets were dropped. Evidence: `webapp/tests` 5 pass; live smoke on a temp v2 ledger returned health v2, 2 batches, wheat `2500` paise/kg sample 2, 1 offer, trace page 200, `/api/prices` 404. | done

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
- 2026-11-25 Laasya: T17 puts the parity vectors in `contracts/vectors.json` and makes `canonical_json` the one Python encoder (`canonical_event` delegates to it). Kotlin now emits raw UTF-8 values and standard control escapes instead of rejecting non-ASCII. The AndroidKeyStore instrumented leg is not claimed without a phone.
- 2026-11-25 Laasya: T23 signs `{height,eventCount,root,ts,nodeId,pubKey}` as canonical JSON with the node's P-256 key. `root` is a Merkle root over `events.hash` in `seq` order (odd nodes are promoted by duplicating the last node; empty is 64 zeros), `height` is the max `seq`, and `nodeId` is the first 16 hex of SHA-256(SPKI). The node key lives at `ledger/node_key.pem` or `AGRICHAIN_NODE_KEY`; it is not committed. `mirror.py` treats a signature failure as exit 2 and a root/height/eventCount mismatch as exit 1.
- 2026-11-25 charan: T25 adds Meshtastic LoRa as a fourth transport behind the shared `Transport` interface. Chunking uses prefix `MC1|<8hexId>|<idx>/<total>|<base64>` (distinct from SMS `AC1`), max 210 chars per chunk so the full Meshtastic frame stays ≤231 bytes. Binding uses `IMeshService` AIDL via reflection to avoid AIDL-generated stub on the compile classpath; private portnum 257 (range 256–511). `MeshtasticTransport` runs in stub mode on the JVM (no Android context) so unit tests exercise chunking, reassembly and dedup without hardware. India frequency: **865–867 MHz** only (IN region setting). Ruled out: Bridgefy/Briar/Bitchat (Indian I4C takedown notices, July 2026), MeshCore (incompatible with Meshtastic), OpenMANET (Morse Micro HaLow hardware unavailable in India). Real LoRa device test (D10) requires two 865 MHz Meshtastic boards and is marked pending.
- 2026-11-25 charan: T26 adds a clean-room BLE GATT chat transport behind `Transport` and makes chat the launcher. The BitChat Android repo is GPL-3.0 (despite a public-domain claim in its README) and agri-chain has no license file, so no BitChat code is copied and the wire format is intentionally **not** BitChat-compatible; only the link-layer architecture is borrowed (advertise/scan one service, one characteristic, lower-hash central election, fragmentation, TTL relay). Chat envelopes are signed (`k:"C"`, TOFU key) but not encrypted, and are restricted to BLE/Nearby; they never enter the ledger outbox, SMS or LoRa. Waiting messages are persisted and re-signed when a peer appears. The I4C takedown risk from T25 remains a deployment decision, not a code decision.

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

### Optional public web viewer (T27)

```bash
./deploy/serve-linux.sh          # ledger :8000 + viewer :8080
docker compose -f deploy/docker-compose.yml up --build   # or Docker
```

Read-only dashboard + buyer trace at `http://<host>:8080/trace`; it proxies `/api/batches`, `/api/offers` and `/api/trades` (median20) and never writes events. The redundant USB presets were dropped because `tools/phone.sh` covers USB.

### Production runbook (M9 TLS & M10 privacy)

- **TLS & admin hardening (M9):** In production, run Uvicorn bound to `127.0.0.1:8000` behind a TLS reverse proxy (Caddy or nginx), set `AGRICHAIN_DEV=0` with a high-entropy `AGRICHAIN_ADMIN_TOKEN`, keep `AGRICHAIN_DISCOVERY=0`, and build Android release APKs with `usesCleartextTraffic=false`.
- **Route privacy & DPDP (M10):** Exact 6-decimal GPS traces reveal farm and warehouse locations. The local demo keeps exact coordinates for route verification; before a field pilot, obtain consent/notice for transporter location logging and serve coarse-rounded coordinates (e.g. 2-3 decimals) or role-gated `/batches/{id}/route` access to unauthenticated buyers.
