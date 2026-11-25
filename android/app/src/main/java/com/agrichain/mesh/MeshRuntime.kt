package com.agrichain.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.agrichain.core.ActorIdentity
import com.agrichain.core.Event
import com.agrichain.data.SyncStore
import java.util.UUID

/**
 * Process-wide relay. Nearby, SMS, Meshtastic LoRa and HTTP all converge here.
 */
class MeshRuntime private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("agrichain_mesh", Context.MODE_PRIVATE)
    private val store = SyncStore(app)
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

    /** Keystore-backed key for signed mesh ACKs (H6); not a ledger actor. */
    private val ackIdentity = ActorIdentity.loadOrCreate(app, "mesh")

    private val engine = RelayEngine(
        selfId,
        store,
        ledger,
        mutableListOf(sms),
        { line -> log(line) },
        ackIdentity,
    )

    @Volatile
    var logSink: ((String) -> Unit)? = null

    init {
        sms.start({ _, _ -> }, { line -> log(line) })
        meshtastic.start(
            { envelope, via -> onEnvelope(envelope, via.id, peerId = via.id) },
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
        transport.start(
            { envelope, via ->
                val peer = if (via.id == meshtastic.id) via.id else envelope.senderId
                onEnvelope(envelope, via.id, peerId = peer)
            },
            { line -> log("${transport.id}: $line") },
        )
    }

    fun unregister(transport: Transport) {
        transport.stop()
        engine.removeTransport(transport)
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
