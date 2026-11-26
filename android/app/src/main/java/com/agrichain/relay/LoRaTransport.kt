package com.agrichain.relay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * LoRa transport through the Meshtastic companion app (`IMeshService`).
 *
 * India-only: physical nodes must be set to the `IN` region (865-867 MHz).
 * The transport is a single broadcast link; the relay owns TTL and dedupe.
 */
class LoRaTransport(
    private val context: Context?,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
) : Transport {

    override val id: String = "lora"
    override val meshLinks: Boolean = false
    override val maxFrame: Int = LoRaV3Codec.MAX_PACKET_BYTES

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 32)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val reassembler = LoRaV3Reassembler()
    private val messageIds = AtomicInteger(3_000)
    private var queue: Channel<String>? = null
    private var worker: kotlinx.coroutines.Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var bound = false

    /** Test hook: frames that would have gone to the radio. */
    val sentFrames = mutableListOf<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val bytes = payload(intent) ?: return
            onFrame(bytes.toString(Charsets.UTF_8))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            bound = true
            Log.i(TAG, "lora: meshtastic service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            bound = false
            Log.w(TAG, "lora: meshtastic service disconnected")
        }
    }

    override suspend fun start() {
        if (running) return
        if (!enabled()) {
            _state.value = TransportState.DEGRADED
            return
        }
        if (context != null) {
            registerReceiver()
            bindService()
            if (!bound) {
                _state.value = TransportState.DEGRADED
                Log.i(TAG, "lora: meshtastic app unavailable")
                return
            }
        }
        val channel = Channel<String>(128)
        queue = channel
        running = true
        _state.value = TransportState.RUNNING
        _links.value = setOf(
            LinkInfo(
                linkId = LINK,
                transportId = id,
                peerId = LINK,
                peerName = "LoRa",
                slow = true,
            ),
        )
        worker = scope.launch { drain(channel) }
        Log.i(TAG, "lora: started")
    }

    override suspend fun stop() {
        running = false
        worker?.cancel()
        worker = null
        queue?.close()
        queue = null
        if (context != null) {
            runCatching { context?.unregisterReceiver(receiver) }
            if (bound) runCatching { context?.unbindService(connection) }
        }
        bound = false
        binder = null
        _state.value = TransportState.STOPPED
        _links.value = emptySet()
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        if (!running) return SendResult.NoLink("lora not running")
        if (linkId != null && linkId != LINK) return SendResult.NoLink("unknown lora link")
        val parts = runCatching { LoRaV3Codec.chunks(envelope) }.getOrElse {
            return SendResult.Failed(it.message ?: "lora chunking failed", retryable = false)
        }
        val channel = queue ?: return SendResult.NoLink("lora queue closed")
        for (part in parts) channel.trySend(part)
        Log.i(TAG, "lora: queued ${parts.size} frame(s)")
        return SendResult.Sent(1)
    }

    /** Test hook: feed a frame as if the radio received it. */
    fun simulateReceive(frame: String): ByteArray? {
        val envelope = reassembler.accept(frame, System.currentTimeMillis()) ?: return null
        _incoming.tryEmit(Incoming(envelope, LINK, id))
        return envelope
    }

    private fun onFrame(text: String) {
        if (!LoRaV3Codec.isPart(text)) return
        val envelope = reassembler.accept(text, System.currentTimeMillis()) ?: return
        Log.i(TAG, "lora: reassembled envelope")
        _incoming.tryEmit(Incoming(envelope, LINK, id))
    }

    private suspend fun drain(channel: Channel<String>) {
        for (frame in channel) {
            if (!running) return
            sendFrame(frame)
            delay(FRAME_GAP_MS)
        }
    }

    private fun sendFrame(frame: String) {
        if (context == null) {
            synchronized(sentFrames) { sentFrames += frame }
            return
        }
        val service = binder ?: return
        runCatching {
            val stub = Class.forName("com.geeksville.mesh.IMeshService\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val mesh = asInterface.invoke(null, service)
            val sendData = mesh.javaClass.getMethod(
                "sendData",
                String::class.java,
                ByteArray::class.java,
                Int::class.java,
                Boolean::class.java,
            )
            sendData.invoke(mesh, null, frame.toByteArray(Charsets.UTF_8), PRIVATE_PORT, false)
            Log.i(TAG, "lora: frame sent (${frame.length} chars)")
        }.onFailure { Log.w(TAG, "lora: send failed: ${it.message}") }
    }

    private fun registerReceiver() {
        val ctx = context ?: return
        val filter = IntentFilter().apply {
            addAction("$MESHTASTIC_PACKAGE.RECEIVED.$PRIVATE_PORT")
            addAction("$MESHTASTIC_PACKAGE.RECEIVED.PRIVATE_APP")
            addAction("$MESHTASTIC_PACKAGE.RECEIVED_DATA")
        }
        runCatching {
            ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { Log.w(TAG, "lora: receiver failed: ${it.message}") }
    }

    private fun bindService() {
        val ctx = context ?: return
        val installed = runCatching {
            ctx.packageManager.getPackageInfo(MESHTASTIC_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!installed) return
        val intent = Intent().apply {
            setClassName(MESHTASTIC_PACKAGE, MESHTASTIC_SERVICE)
        }
        runCatching {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "lora: bind failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun payload(intent: Intent?): ByteArray? {
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
        }.getOrNull()
    }

    companion object {
        private const val TAG = "AgriMesh"
        private const val LINK = "lora"
        private const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"
        private const val MESHTASTIC_SERVICE = "com.geeksville.mesh.service.MeshService"
        const val PRIVATE_PORT = 257
        private const val FRAME_GAP_MS = 3_000L
    }
}
