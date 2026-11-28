# Relay backends: Wi-Fi, Bluetooth and BitChat policy

The AgriChain app owns the Relay screen, the signed `MeshEnvelope` v3 format and
`RelayEngine`. The other two apps in this repo are used as backends for the
local link, not as separate installable apps:

| Source app | What was taken | Where it lives now |
|------------|----------------|--------------------|
| Offline Mesh Chat (`app/`, package `io.github.jeiel85.offlinechat`) | NSD service discovery, ephemeral TCP server, one line per envelope, resolved peer map, multicast lock | `android/app/src/main/java/com/agrichain/mesh/WifiMeshTransport.kt` |
| BitChat (`bitchat/`, Swift) | Clean-room ports of the relay flood-control policy and bounded time-expired deduplicator | `android/app/src/main/java/com/agrichain/mesh/RelayPolicy.kt`, `RelayDedupe.kt` |
| AgriChain (`android/`, package `com.agrichain`) | UI, envelope, signing, outbox, TTL/ACK rules, transport seam | `MeshActivity.kt`, `MeshRuntime.kt`, `RelayEngine.kt`, `Transport.kt` |

## Simultaneous Wi-Fi and BLE mesh (v3)

Every phone attaches both local radios when the relay starts; there is no
one-at-a-time selector:

- `wifi`: NSD `_agrichain._tcp.` on the local network, TCP `peerId<TAB>envelopeJson`.
- `ble`: the raw GATT link with `AB` fragmentation.

`WifiTransport` and `BleTransport` are both attached to `RelayCore`, which fans
every originated or relayed envelope out through all attached mesh links.
`V3Settings.bleEnabled` is effectively always true, and the settings screen shows
the BLE switch checked and disabled; the stored preference remains only for
compatibility. Reported capabilities always include `CAP_WIFI` and `CAP_BLE`.

One `msgId` seen-set is shared across radios, so an envelope that arrives on
Wi-Fi and then on BLE (or the other way round) is processed and relayed once.
A relayed message gets a single TTL decrement before the fan-out: a sparse mesh
sends per link, excluding only the arrival link, while a dense mesh sends one
broadcast per transport. SMS and LoRa remain optional and are not part of the
always-on pair.

The gateway is independent of the radios. `GatewayLoop` retries the ledger over
HTTP whether or not any mesh peer is present, and ledger-signed receipts,
notices and prices are injected into the same `RelayCore` and flooded over both
meshes. The ledger host is discovered automatically at relay start and rechecked
in the background: `LedgerDiscovery` probes the saved URL, the USB loopback, the
emulator host and UDP/LAN replies from `ledger/discovery.py`; the first healthy
`/health` with `version == 3` is saved and used by every screen and by the live
gateway client. No manual URL or emulator-only default is required.

## Users, addressing and the dashboard feed

Chat is no longer anonymous broadcast-only. Each phone stores a display name in
`agrichain_mesh/relayUserName` (default `Phone <id suffix>`). Starting the relay
announces that name with an unsigned `USER` envelope (`MeshKind.USER`, wire key
`U`), and the announcement is re-sent at most every 30 seconds while a link is
active. `RelayContacts` keeps the last 40-character name seen for each phone id;
names are UI labels, not ledger actors or identity claims.

The Relay section is deliberately one screen: a name field, the roster of
phones seen, one shared conversation, and a message box. The main chat is
broadcast-only, so every node and the halo see the same thread. Every chat is
still signed; the engine keeps `MeshEnvelope.recipientId` for future direct
messages (`null` broadcasts, a phone id is displayed only by that phone but is
still flooded onward). No recipient picker is shown, so the main send can never
be silently redirected.

`MeshActivity` merges two sources by `envelopeId`:

- the local `ChatStore` (radio messages this phone saw or sent);
- the **halo feed** from `GET /relay/messages`, which carries messages that
  server-connected phones mirrored from anywhere.

The halo is the ledger acting as a hub, not a signed actor. A phone with no
local peer can still send through it; a phone with no internet still chats over
Wi-Fi/BLE and flushes later. The status line reports `halo online/offline` next
to the radio peer count. Names from the feed are folded back into
`RelayContacts`, and a bubble learned from the feed is labelled `· halo`.

Chat is mirrored to the ledger server as *monitor data*, never as a signed
`CHAT` event. `MeshRuntime.mirrorChat()` and `mirrorUser()` POST to
`POST /relay/messages` and `POST /relay/users`, deduped on `envelopeId` /
`userId`. Waiting messages keep their envelope id and timestamp when they are
re-signed on flush, so one message remains one row in the feed. The dashboard
polls `GET /relay/messages` and renders a Relay messages panel with discovered
users, sender → recipient, body, link and direction. The supply-chain event
contract (`REGISTER`, `BIND`, `TRANSFER`, `GPS`, `TAMPER`, `DELIVER`, `BARTER`)
is unchanged; relay rows cannot affect batch verification.

The Crop Box screen no longer has a "Turn Guard ON" step. GPS logging and
signed `TAMPER` events remain available; arming was a manual step users skipped.

## BitChat policy port

Swift cannot run on Android and the wire formats are deliberately different, so
no Swift code, Noise session, Curve25519 identity or binary packet was copied.
Only documented behaviour was reimplemented in Kotlin and covered by JVM tests:

- `RelayPolicy.decide` mirrors `RelayController.decide`: TTL cap, self and
  link-local suppression, directed ACK priority, degree-aware broadcast caps
  (dense meshes clamp to 5, thin chains keep full depth), and jitter windows.
  `MeshRuntime` supplies a daemon scheduled executor so jitter does not block
  the UI thread; unit tests use immediate delivery.
- `RelayDedupe` mirrors `MessageDeduplicator`: bounded cache, age expiry and
  trimming to 75% of the cap. It replaces the old unbounded in-memory envelope
  set in `RelayEngine`; event ids remain in the SQLite outbox.

Not ported: CoreBluetooth, Noise/Ed25519, packet padding, source routing and
topology neighbour claims. AgriChain traffic is event flood + ACK + local chat,
not mostly directed unicast; adding route state would add privacy cost without
a measured need. Keep the clean-room position: architecture inspiration only,
no BitChat wire compatibility, no copied code.

## Device checks (pending)

- Two phones on one Wi-Fi LAN: start the relay and confirm Wi-Fi peers appear.
- Two phones with Bluetooth only: confirm BLE peers appear and messages flow.
- Both radios present: confirm both transports appear in the relay snapshot and
  that one `msgId` arriving on both is not relayed twice.
- With the ledger running: confirm the app finds the host without a manual URL
  and that gateway receipts reach phones over both meshes.
- Pull `adb logcat -s AgriMesh` and quote it here before marking any D-check done.
