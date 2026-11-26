package com.agrichain.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.data.ChatMessage
import com.agrichain.data.ChatStore
import com.agrichain.data.LedgerApi
import com.agrichain.data.SyncStore
import com.agrichain.net.LedgerSettings
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Process-wide relay. Wi-Fi NSD, BLE, Nearby, SMS, Meshtastic LoRa and HTTP all
 * converge here; the link selector attaches one local radio at a time.
 */
class MeshRuntime private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("agrichain_mesh", Context.MODE_PRIVATE)
    private val store = SyncStore(app)
    private val chatStore = ChatStore(app)
    private val sms = SmsTransport(app)
    private val ledger = HttpLedgerGateway(app)
    private val pasteSmsReassembler = SmsReassembler()
    private val pasteLoRaReassembler = MeshtasticReassembler()

    val selfId: String = prefs.getString("senderId", null)
        ?: "phone-${UUID.randomUUID().toString().take(8)}".also {
            prefs.edit().putString("senderId", it).apply()
        }

    /** Display name used by contacts and by the dashboard user list. */
    val userName: String
        get() = prefs.getString(PREF_USER_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: "Phone ${selfId.takeLast(8)}"

    /** Persistent phone-to-phone contact directory, filled by USER announcements. */
    val contacts = RelayContacts(app)

    @Volatile
    private var lastAnnounceAt = 0L

    /** Meshtastic LoRa transport (865-867 MHz IN region). */
    val meshtastic = MeshtasticTransport(app)

    /** Raw BLE GATT mesh (BitChat-style fragmentation and TTL relay). */
    val ble = BleMeshTransport(app, selfId)

    /** Wi-Fi NSD/TCP mesh, ported from the Offline Mesh Chat app. */
    val wifi = WifiMeshTransport(app, selfId)

    /** Keystore-backed key for signed mesh ACKs and chat (H6); not a ledger actor. */
    private val ackIdentity = ActorIdentity.loadOrCreate(app, "mesh")

    /** Staggers BitChat-style relay jitter without blocking the UI thread. */
    private val relayScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agrichain-relay").apply { isDaemon = true }
    }

    private val engine = RelayEngine(
        selfId,
        store,
        ledger,
        mutableListOf(sms),
        { line -> log(line) },
        ackIdentity,
        { envelope -> recordChat(envelope) },
        relayScheduler,
        { envelope -> recordUser(envelope) },
    )

    /** Picks Wi-Fi or BLE by discovered peers and attaches only that link. */
    private val relay = RelayLinkManager(
        wifi = wifi,
        ble = ble,
        attach = { engine.addTransport(it) },
        detach = { engine.removeTransport(it) },
        deliver = { envelope, viaId, peer -> onEnvelope(envelope, viaId, peer) },
        log = { line -> log("relay: $line") },
    )

    @Volatile
    var logSink: ((String) -> Unit)? = null

    @Volatile
    var chatListener: ((ChatMessage) -> Unit)? = null

    init {
        sms.start({ _, _ -> }, { line -> log(line) })
        meshtastic.startWithPeer(
            { envelope, via, linkPeer ->
                val peer = linkPeer ?: via.id
                onEnvelope(envelope, via.id, peerId = peer)
            },
            { line -> log("meshtastic: $line") },
        )
        engine.addTransport(meshtastic)
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Thread { pump() }.start()
                    }
                }
            )
        }.onFailure { log("network callback unavailable: ${it.message}") }
    }

    fun onEnvelope(
        envelope: MeshEnvelope,
        viaId: String,
        peerId: String? = null,
        replyTo: String? = null,
    ): ReceiveResult {
        if (replyTo != null) sms.replyTo = replyTo
        val peer = peerId ?: if (viaId == meshtastic.id) viaId else envelope.senderId
        val result = engine.receive(envelope, fromTransportId = viaId, fromPeerId = peer)
        log(
            "$viaId: ${result.freshEvents} new, ${result.duplicates} dup, " +
                "${result.uploaded} uploaded"
        )
        return result
    }

    /** Accepts JSON envelope, SMS AC3|/AC1| chunk, or Meshtastic MC1| chunk. Returns null if incomplete/invalid. */
    fun acceptManualInput(raw: String): Pair<String, ReceiveResult?> {
        val text = raw.trim()
        val now = System.currentTimeMillis()
        return when {
            SmsCodec.isPart(text) -> {
                val env = pasteSmsReassembler.accept(text, now)
                if (env == null) "sms-part-buffered" to null
                else "paste-sms" to onEnvelope(env, "paste-sms", peerId = "paste-sms")
            }
            MeshtasticCodec.isPart(text) -> {
                val env = pasteLoRaReassembler.accept(text, now)
                if (env == null) "lora-part-buffered" to null
                else "paste-lora" to onEnvelope(env, "paste-lora", peerId = meshtastic.id)
            }
            else -> {
                val env = MeshEnvelope.parse(text) ?: return "invalid" to null
                "paste" to onEnvelope(env, "paste", peerId = "paste")
            }
        }
    }

    fun pump(): PumpResult = engine.pump()

    fun ledgerReachable(): Boolean = ledger.available()

    fun broadcastPending(): Int = engine.broadcastPending()

    fun pending(): List<Event> = store.pending()

    fun pendingCount(): Int = store.pendingCount()

    fun register(transport: Transport) {
        engine.addTransport(transport)
        transport.startWithPeer(
            { envelope, via, linkPeer ->
                val peer = linkPeer ?: if (via.id == meshtastic.id) via.id else envelope.senderId
                onEnvelope(envelope, via.id, peerId = peer)
            },
            { line -> log("${transport.id}: $line") },
        )
    }

    fun unregister(transport: Transport) {
        transport.stop()
        engine.removeTransport(transport)
    }

    /** Starts Wi-Fi + BLE discovery and attaches the link that finds a peer. */
    fun startRelay(mode: RelayLinkMode = savedRelayMode()) {
        prefs.edit().putString(PREF_RELAY_MODE, mode.name).apply()
        relay.start(mode)
    }

    fun stopRelay() {
        relay.stop()
    }

    fun relayStatus(): RelayLinkStatus = relay.status()

    fun setRelayMode(mode: RelayLinkMode) {
        prefs.edit().putString(PREF_RELAY_MODE, mode.name).apply()
        relay.setMode(mode)
    }

    fun savedRelayMode(): RelayLinkMode =
        runCatching { RelayLinkMode.valueOf(prefs.getString(PREF_RELAY_MODE, null) ?: "AUTO") }
            .getOrDefault(RelayLinkMode.AUTO)

    /** Kept for the older chat screens; it now starts BLE through the selector. */
    fun startBleMesh(): Boolean {
        startRelay(RelayLinkMode.BLE)
        return ble.available()
    }

    fun stopBleMesh() {
        stopRelay()
    }

    fun bleRunning(): Boolean =
        relay.status().active == RelayLinkId.BLE && ble.available()

    fun blePeerCount(): Int = relay.status().blePeers

    fun smsAvailable(): Boolean = sms.available()

    /** Changes this phone's display name and re-announces it to nearby phones. */
    fun setUserName(name: String) {
        val label = name.trim().take(40)
        if (label.isEmpty()) return
        prefs.edit().putString(PREF_USER_NAME, label).apply()
        contacts.put(selfId, label)
        announceUser(force = true)
    }

    /** Tells nearby phones who this is; throttled so a UI refresh cannot flood. */
    fun announceUser(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAnnounceAt < ANNOUNCE_INTERVAL_MS) return
        lastAnnounceAt = now
        val envelope = engine.announceUser(userName) ?: return
        contacts.put(selfId, userName, envelope.sentAt)
        mirrorUser(envelope.sentAt)
    }

    /** Phone ids visible on either local radio, so contacts include the whole mesh. */
    fun discoveredPeers(): Set<String> = (wifi.knownPeers() + ble.knownPeers()) - selfId

    /** Stores a local chat message and sends it to a contact, or broadcasts it. */
    fun sendChat(text: String, recipientId: String? = null): ChatMessage? {
        val result = engine.sendChat(text, recipientId) ?: return null
        val body = result.envelope.chatText ?: return null
        val message = ChatMessage(
            envelopeId = result.envelope.envelopeId,
            senderId = selfId,
            body = body,
            ts = result.envelope.sentAt,
            outgoing = true,
            status = if (result.links > 0) "sent" else "waiting",
        )
        chatStore.add(message)
        mirrorChat(result.envelope, outgoing = true)
        return message
    }

    fun chatMessages(limit: Int = 200): List<ChatMessage> = chatStore.recent(limit)

    /**
     * Halo feed: messages posted by server-connected phones, merged with the
     * names those phones announced. The server is a hub, not a signed actor.
     */
    fun serverChat(limit: Int = 100): List<ChatMessage> = runCatching {
        val feed = LedgerApi(LedgerSettings.baseUrl(app)).relayFeed(limit)
            ?: return@runCatching emptyList()
        feed.optJSONArray("users")?.let { users ->
            for (i in 0 until users.length()) {
                val row = users.optJSONObject(i) ?: continue
                val id = row.optString("userId")
                val name = row.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    contacts.put(id, name, row.optLong("lastSeen"))
                }
            }
        }
        val messages = feed.optJSONArray("messages") ?: return@runCatching emptyList()
        (0 until messages.length()).mapNotNull { i ->
            val row = messages.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("envelopeId")
            val body = row.optString("body")
            val sender = row.optString("senderId")
            if (id.isEmpty() || body.isEmpty() || sender.isEmpty()) return@mapNotNull null
            val senderName = row.optString("senderName")
            if (senderName.isNotEmpty()) contacts.put(sender, senderName, row.optLong("ts"))
            ChatMessage(
                envelopeId = id,
                senderId = sender,
                body = body,
                ts = row.optLong("ts"),
                outgoing = sender == selfId,
                status = "server",
            )
        }.sortedBy { it.ts }
    }.getOrDefault(emptyList())

    /**
     * Re-signs and sends chat messages that were written while no radio peer
     * was connected. The envelope id and timestamp are kept, so the halo feed
     * and every phone still dedupe one message as one row.
     */
    fun flushChatOutbox(): Int {
        val status = relay.status()
        val peers = when (status.active) {
            RelayLinkId.BLE -> status.blePeers
            RelayLinkId.WIFI -> status.wifiPeers
            null -> 0
        }
        if (peers == 0) return 0
        var sent = 0
        for (pending in chatStore.outbox()) {
            // Re-sign the same id/timestamp so the halo feed keeps one row.
            val result = engine.sendChat(
                pending.body,
                envelopeId = pending.envelopeId,
                sentAt = pending.ts,
            ) ?: continue
            if (result.links <= 0) continue
            if (chatStore.replaceEnvelope(pending.envelopeId, result.envelope.envelopeId, "sent")) {
                mirrorChat(result.envelope, outgoing = true)
                sent++
            }
        }
        if (sent > 0) log("chat outbox flushed: $sent message(s)")
        return sent
    }

    private fun recordChat(envelope: MeshEnvelope) {
        val body = envelope.chatText ?: return
        val message = ChatMessage(
            envelopeId = envelope.envelopeId,
            senderId = envelope.senderId,
            body = body,
            ts = envelope.sentAt,
            outgoing = false,
            status = "received",
        )
        chatStore.add(message)
        mirrorChat(envelope, outgoing = false)
        chatListener?.invoke(message)
    }

    private fun recordUser(envelope: MeshEnvelope) {
        val name = envelope.senderName ?: return
        contacts.put(envelope.senderId, name, envelope.sentAt)
        mirrorUser(envelope.sentAt, userId = envelope.senderId, name = name)
    }

    /** Best-effort mirror into the separate dashboard feed; never blocks chat. */
    private fun mirrorUser(ts: Long, userId: String = selfId, name: String = userName) {
        val row = JSONObject()
            .put("userId", userId)
            .put("name", name)
            .put("ts", ts)
        relayScheduler.execute {
            runCatching { LedgerApi(LedgerSettings.baseUrl(app)).postRelayUsers(listOf(row)) }
                .onFailure { log("dashboard user mirror failed: ${it.message}") }
        }
    }

    private fun mirrorChat(envelope: MeshEnvelope, outgoing: Boolean) {
        val row = JSONObject()
            .put("envelopeId", envelope.envelopeId)
            .put("senderId", envelope.senderId)
            .put("senderName", if (envelope.senderId == selfId) userName else contacts.nameOf(envelope.senderId).orEmpty())
            .put("recipientId", envelope.recipientId)
            .put("recipientName", envelope.recipientId?.let { contacts.nameOf(it) }.orEmpty())
            .put("body", envelope.chatText.orEmpty())
            .put("ts", envelope.sentAt)
            .put("observerId", selfId)
            .put("direction", if (outgoing) "out" else "in")
            .put("link", relay.status().active?.transportId.orEmpty())
        relayScheduler.execute {
            runCatching { LedgerApi(LedgerSettings.baseUrl(app)).postRelayMessages(listOf(row)) }
                .onFailure { log("dashboard chat mirror failed: ${it.message}") }
        }
    }

    private fun log(line: String) {
        Log.i("AgriChain", "mesh: $line")
        logSink?.invoke(line)
    }

    companion object {
        private const val PREF_RELAY_MODE = "relayLinkMode"
        private const val PREF_USER_NAME = "relayUserName"
        private const val ANNOUNCE_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: MeshRuntime? = null

        fun get(context: Context): MeshRuntime =
            instance ?: synchronized(this) {
                instance ?: MeshRuntime(context.applicationContext).also { instance = it }
            }
    }
}
