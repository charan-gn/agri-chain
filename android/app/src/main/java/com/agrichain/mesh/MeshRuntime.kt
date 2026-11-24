package com.agrichain.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.agrichain.core.Event
import com.agrichain.data.SyncStore
import java.util.UUID

/**
 * Process-wide relay. The SMS receiver and the Mesh screen share this instance so
 * a chunked envelope can arrive while the activity is closed.
 */
class MeshRuntime private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("agrichain_mesh", Context.MODE_PRIVATE)
    private val store = SyncStore(app)
    private val sms = SmsTransport(app)
    private val ledger = HttpLedgerGateway(app)

    val selfId: String = prefs.getString("senderId", null)
        ?: "phone-${UUID.randomUUID().toString().take(8)}".also {
            prefs.edit().putString("senderId", it).apply()
        }

    private val engine = RelayEngine(selfId, store, ledger, mutableListOf(sms)) { line -> log(line) }

    @Volatile
    var logSink: ((String) -> Unit)? = null

    init {
        sms.start({ _, _ -> }, { line -> log(line) })
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
        replyTo: String? = null,
    ): ReceiveResult {
        if (replyTo != null) sms.replyTo = replyTo
        val result = engine.receive(envelope)
        log(
            "$viaId: ${result.freshEvents} new, ${result.duplicates} dup, " +
                "${result.uploaded} uploaded"
        )
        return result
    }

    fun pump(): PumpResult = engine.pump()

    fun ledgerReachable(): Boolean = ledger.available()

    fun broadcastPending(): Int = engine.broadcastPending()

    fun pending(): List<Event> = store.pending()

    fun pendingCount(): Int = store.pendingCount()

    fun register(transport: Transport) {
        engine.addTransport(transport)
        transport.start(
            { envelope, via -> onEnvelope(envelope, via.id) },
            { line -> log("${transport.id}: $line") },
        )
    }

    fun unregister(transport: Transport) {
        transport.stop()
        engine.removeTransport(transport)
    }

    private fun log(line: String) {
        Log.i("AgriChainMesh", line)
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
