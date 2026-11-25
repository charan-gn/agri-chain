package com.agrichain.mesh

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * LoRa radio transport via the Meshtastic companion app (IMeshService AIDL).
 * India frequency: physical nodes must use 865-867 MHz (IN region).
 */
class MeshtasticTransport(
    private val context: Context?,
    override val id: String = "meshtastic",
    private val stubMode: Boolean = context == null,
) : Transport {

    override val maxPayload: Int = MeshtasticCodec.MAX_PACKET_BYTES

    private val reassembler = MeshtasticReassembler()
    private val seenEnvelopes = LinkedHashSet<String>()
    private val handler: Handler? by lazy {
        if (stubMode) null else runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    private val scheduler: ScheduledExecutorService? by lazy {
        if (stubMode || handler != null) null else Executors.newSingleThreadScheduledExecutor()
    }

    private var onReceive: ((MeshEnvelope, Transport) -> Unit)? = null
    private var log: ((String) -> Unit)? = null

    @Volatile var stubAvailable: Boolean = false
    val sentChunks = mutableListOf<String>()

    @Volatile private var serviceBound = false
    @Volatile private var bindAttempted = false
    @Volatile private var receiverRegistered = false
    @Volatile private var meshBinder: IBinder? = null

    private val inboundReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val bytes = extractPayloadBytes(intent) ?: return
            onPacketReceived(bytes)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            meshBinder = binder
            serviceBound = true
            log?.invoke("meshtastic service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            meshBinder = null
            serviceBound = false
            log?.invoke("meshtastic service disconnected - will retry")
            val retry = Runnable { if (onReceive != null) bindService() }
            val h = handler
            if (h != null) {
                h.postDelayed(retry, REBIND_DELAY_MS)
            } else {
                scheduler?.schedule(retry, REBIND_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun available(): Boolean {
        if (stubMode) return stubAvailable
        return serviceBound && isMeshtasticInstalled()
    }

    fun isInstalled(): Boolean = !stubMode && isMeshtasticInstalled()

    override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) {
        this.onReceive = onReceive
        this.log = onLog
        if (!stubMode) {
            registerInboundReceiver()
            bindService()
        }
    }

    override fun stop() {
        onReceive = null
        log = null
        if (!stubMode) {
            unregisterInboundReceiver()
            if (bindAttempted) {
                runCatching { context?.unbindService(connection) }
                bindAttempted = false
            }
            serviceBound = false
            meshBinder = null
            handler?.removeCallbacksAndMessages(null)
            scheduler?.shutdownNow()
        }
    }

    override fun send(envelope: MeshEnvelope): Int {
        if (!available()) return 0
        val chunks = MeshtasticCodec.chunks(envelope)
        if (stubMode) {
            synchronized(sentChunks) { sentChunks.addAll(chunks) }
            log?.invoke("meshtastic stub: queued ${chunks.size} chunk(s)")
            return chunks.size
        }
        val binder = meshBinder ?: return 0
        var sent = 0
        for (chunk in chunks) {
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            if (sendViaAidl(binder, bytes)) sent++ else log?.invoke("meshtastic: send failed")
        }
        log?.invoke("meshtastic: sent $sent/${chunks.size} chunk(s) for ${envelope.envelopeId.take(8)}")
        return sent
    }

    fun onPacketReceived(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        if (!MeshtasticCodec.isPart(text)) return
        val envelope = acceptFrame(text) ?: return
        log?.invoke("meshtastic: reassembled envelope ${envelope.envelopeId.take(8)}")
        onReceive?.invoke(envelope, this)
    }

    fun simulateReceive(frame: String): MeshEnvelope? {
        val envelope = acceptFrame(frame) ?: return null
        onReceive?.invoke(envelope, this)
        return envelope
    }

    private fun acceptFrame(frame: String): MeshEnvelope? {
        val envelope = reassembler.accept(frame, System.currentTimeMillis()) ?: return null
        synchronized(seenEnvelopes) {
            if (envelope.envelopeId in seenEnvelopes) return null
            if (seenEnvelopes.size >= 256) {
                val oldest = seenEnvelopes.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            seenEnvelopes += envelope.envelopeId
        }
        return envelope
    }

    private fun registerInboundReceiver() {
        val ctx = context ?: return
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction("$MESHTASTIC_PACKAGE.RECEIVED.$PRIVATE_PORT")
            addAction("$MESHTASTIC_PACKAGE.RECEIVED.PRIVATE_APP")
            addAction("$MESHTASTIC_PACKAGE.RECEIVED_DATA")
        }
        runCatching {
            ContextCompat.registerReceiver(
                ctx,
                inboundReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }.onFailure {
            log?.invoke("meshtastic receiver failed: ${it.message}")
        }
    }

    private fun unregisterInboundReceiver() {
        val ctx = context ?: return
        if (!receiverRegistered) return
        runCatching { ctx.unregisterReceiver(inboundReceiver) }
        receiverRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun extractPayloadBytes(intent: Intent?): ByteArray? {
        if (intent == null) return null
        intent.getByteArrayExtra("bytes")?.let { return it }
        intent.getByteArrayExtra("payload")?.let { return it }
        intent.getStringExtra("text")?.let { return it.toByteArray(Charsets.UTF_8) }
        val packet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("$MESHTASTIC_PACKAGE.Payload", Any::class.java)
        } else {
            intent.getParcelableExtra<android.os.Parcelable>("$MESHTASTIC_PACKAGE.Payload")
        } ?: return null
        return runCatching {
            packet.javaClass.getMethod("getBytes").invoke(packet) as? ByteArray
        }.getOrNull() ?: runCatching {
            val field = packet.javaClass.getDeclaredField("bytes")
            field.isAccessible = true
            field.get(packet) as? ByteArray
        }.getOrNull()
    }

    private fun bindService() {
        val ctx = context ?: return
        if (!isMeshtasticInstalled()) {
            log?.invoke("meshtastic app not installed - LoRa off")
            return
        }
        val intent = Intent().apply {
            setClassName(MESHTASTIC_PACKAGE, MESHTASTIC_SERVICE)
        }
        runCatching {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindAttempted = true
        }.onFailure {
            log?.invoke("meshtastic bind failed: ${it.message}")
        }
    }

    private fun isMeshtasticInstalled(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ctx.packageManager.getPackageInfo(MESHTASTIC_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    private fun sendViaAidl(binder: IBinder, bytes: ByteArray): Boolean {
        return runCatching {
            val stubClass = Class.forName("com.geeksville.mesh.IMeshService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)
            val sendDataMethod = service.javaClass.getMethod(
                "sendData",
                String::class.java,
                ByteArray::class.java,
                Int::class.java,
                Boolean::class.java,
            )
            sendDataMethod.invoke(service, null, bytes, PRIVATE_PORT, false)
            true
        }.getOrElse {
            log?.invoke("meshtastic AIDL send error: ${it.message}")
            false
        }
    }

    companion object {
        private const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"
        private const val MESHTASTIC_SERVICE = "com.geeksville.mesh.service.MeshService"
        const val PRIVATE_PORT = 257
        private const val REBIND_DELAY_MS = 5_000L
    }
}
