package com.agrichain.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.data.ChatMessage
import com.agrichain.data.ChatStore
import com.agrichain.data.SyncStore
import java.util.UUID

/**
 * Process-wide relay. Nearby, SMS, Meshtastic LoRa and HTTP all converge here.
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

    /** Meshtastic LoRa transport (865-867 MHz IN region). */
    val meshtastic = MeshtasticTransport(app)

    /** Raw BLE GATT mesh (BitChat-style fragmentation and TTL relay). */
    val ble = BleMeshTransport(app, selfId)

    /** Keystore-backed key for signed mesh ACKs and chat (H6); not a ledger actor. */
    private val ackIdentity = ActorIdentity.loadOrCreate(app, "mesh")

    private val engine = RelayEngine(
        selfId,
        store,
        ledger,
        mutableListOf(sms),
        { line -> log(line) },
        ackIdentity,
        { envelope -> recordChat(envelope) },
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

    /** Starts raw BLE advertising + scanning. Safe to call more than once. */
    fun startBleMesh(): Boolean {
        register(ble)
        return ble.available()
    }

    fun stopBleMesh() {
        unregister(ble)
    }

    fun bleRunning(): Boolean = ble.available()

    fun blePeerCount(): Int = ble.connectedPeerCount()

    fun smsAvailable(): Boolean = sms.available()

    /** Stores a local chat message and broadcasts it to every ready link. */
    fun sendChat(text: String): ChatMessage? {
        val result = engine.sendChat(text) ?: return null
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
        return message
    }

    fun chatMessages(limit: Int = 200): List<ChatMessage> = chatStore.recent(limit)

    /**
     * Re-signs and sends chat messages that were written while no BLE peer was
     * connected. The row keeps its position but adopts the new envelope id, so
     * relayed copies still dedupe by id.
     */
    fun flushChatOutbox(): Int {
        if (ble.connectedPeerCount() == 0) return 0
        var sent = 0
        for (pending in chatStore.outbox()) {
            val result = engine.sendChat(pending.body) ?: continue
            if (result.links <= 0) continue
            if (chatStore.replaceEnvelope(pending.envelopeId, result.envelope.envelopeId, "sent")) {
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
        chatListener?.invoke(message)
    }

    private fun log(line: String) {
        Log.i("AgriChain", "mesh: $line")
        logSink?.invoke(line)
    }

    companion object {
        @Volatile
        private var instance: MeshRuntime? = null

        fun get(context: Context): MeshRuntime =
            instance ?: synchronized(this) {
                instance ?: MeshRuntime(context.applicationContext).also { instance = it }
            }
    }
}
