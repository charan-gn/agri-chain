# Relay backends: Wi-Fi, Bluetooth and BitChat policy

The AgriChain app owns the Relay screen, the signed `MeshEnvelope` v3 format and
`RelayEngine`. The other two apps in this repo are used as backends for the
local link, not as separate installable apps:

| Source app | What was taken | Where it lives now |
|------------|----------------|--------------------|
| Offline Mesh Chat (`app/`, package `io.github.jeiel85.offlinechat`) | NSD service discovery, ephemeral TCP server, one line per envelope, resolved peer map, multicast lock | `android/app/src/main/java/com/agrichain/mesh/WifiMeshTransport.kt` |
| BitChat (`bitchat/`, Swift) | Clean-room ports of the relay flood-control policy and bounded time-expired deduplicator | `android/app/src/main/java/com/agrichain/mesh/RelayPolicy.kt`, `RelayDedupe.kt` |
| AgriChain (`android/`, package `com.agrichain`) | UI, envelope, signing, outbox, TTL/ACK rules, transport seam | `MeshActivity.kt`, `MeshRuntime.kt`, `RelayEngine.kt`, `Transport.kt` |

## Link selection

`RelayLinkManager` starts both local radios when the relay is turned on:

- `wifi`: NSD `_agrichain._tcp.` on the local network, TCP `peerId<TAB>envelopeJson`.
- `ble`: the existing raw GATT link with `AB` fragmentation.

Both radios keep discovering, but only the selected one is attached to
`RelayEngine`, so one envelope is never sent twice. `RelayLinkSelector` picks
the link that has peers; in `AUTO` it keeps the current link on a tie and drops
it when that link has no peers. `WIFI` and `BLE` force one radio. The choice is
persisted in `agrichain_mesh/relayLinkMode`.

Chat used to be limited to `ble`/`nearby`; `wifi` is now in the same
`CHAT_TRANSPORTS` allow-list. SMS and LoRa still never carry chat.

## Users, addressing and the dashboard feed

Chat is no longer anonymous broadcast-only. Each phone stores a display name in
`agrichain_mesh/relayUserName` (default `Phone <id suffix>`). Starting the relay
announces that name with an unsigned `USER` envelope (`MeshKind.USER`, wire key
`U`), and the announcement is re-sent at most every 30 seconds while a link is
active. `RelayContacts` keeps the last 40-character name seen for each phone id;
names are UI labels, not ledger actors or identity claims.

The Relay section shows a **Contacts** list and a recipient picker. Every chat
is still signed, but `MeshEnvelope.recipientId` makes it addressable:

- `null` → broadcast; every receiving phone displays it.
- a phone id → only that phone displays it, but the envelope is still flooded
  onward so it can travel through intermediate phones.

Relay hops skip the link the envelope arrived on. `sendChat(text, recipientId)`
and `MeshActivity`'s spinner provide the sender side.

Chat is mirrored to the ledger server as *monitor data*, never as a signed
`CHAT` event. `MeshRuntime.mirrorChat()` and `mirrorUser()` POST to
`POST /relay/messages` and `POST /relay/users`, deduped on `envelopeId` /
`userId`. The dashboard polls `GET /relay/messages` and renders a Relay messages
panel with discovered users, sender → recipient, body, link and direction.
The supply-chain event contract (`REGISTER`, `BIND`, `TRANSFER`, `GPS`,
`TAMPER`, `DELIVER`, `BARTER`) is unchanged; relay rows cannot affect batch
verification.

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

- Two phones on one Wi-Fi LAN: start Relay in `AUTO`, confirm Wi-Fi peer count
  rises and chat arrives.
- Two phones with Bluetooth only: turn off Wi-Fi, confirm the active link moves
  to Bluetooth and waiting chat flushes.
- Both radios present: confirm only one link is active and no duplicate chat is
  delivered.
- Pull `adb logcat -s AgriChain` and quote it here before marking any D-check done.
