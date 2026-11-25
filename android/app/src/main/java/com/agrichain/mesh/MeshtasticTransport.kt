package com.agrichain.mesh

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * LoRa radio transport via the Meshtastic companion app (IMeshService AIDL).
 *
 * ## How it works
 * 1. We bind to `com.geeksville.mesh/.service.MeshService` using the AIDL
 *    interface. The Meshtastic app must be installed; if it isn't, [available]
 *    returns false and the relay simply skips this transport.
 * 2. Outbound envelopes are serialised to JSON, base64-encoded, and split into
 *    ≤210-char chunks (prefix "MC1|…"), keeping the full Meshtastic packet ≤231
 *    bytes after framing.
 * 3. Inbound data is reassembled via [MeshtasticReassembler] and delivered to
 *    the relay engine — identical path as SMS reassembly.
 *
 * ## JVM / simulator
 * On the JVM (unit tests), there is no Android context and no AIDL.
 * In that environment this class operates in "stub" mode: [available] is
 * settable, [send] counts chunks and stores them, and [simulateReceive] lets a
 * test inject a text frame directly into the reassembler.
 *
 * ## India frequency note
 * Physical Meshtastic hardware MUST use the **865–867 MHz** variant (IN region
 * in the Meshtastic app). 433 MHz and 915 MHz are not permitted in India.
 * Buy only boards labelled for 865 MHz / EU / IN.
 *
 * ## Private port
 * We use Meshtastic portnum **257** (range 256–511 is reserved for private apps).
 * This avoids collisions with Meshtastic built-in portnums.
 */
class MeshtasticTransport(
    private val context: Context?,
    override val id: String = "meshtastic",
    /** Injected in unit tests to skip Android service binding. */
    private val stubMode: Boolean = context == null,
) : Transport {

    override val maxPayload: Int = MeshtasticCodec.MAX_PACKET_BYTES

    private val reassembler = MeshtasticReassembler()
    // Handler is only used on a real Android device (rebind scheduling).
    // We lazily initialise it so unit tests running on the JVM (stub mode) never
    // call Looper.getMainLooper(), which is not mocked in the test runner.
    private val handler: Handler? by lazy {
        if (stubMode) null
        else runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    // Fallback scheduler for environments where Handler is unavailable (JVM tests).
    private val scheduler: ScheduledExecutorService? by lazy {
        if (stubMode) null
        else if (handler == null) Executors.newSingleThreadScheduledExecutor()
        else null
    }
    private var onReceive: ((MeshEnvelope, Transport) -> Unit)? = null
    private var log: ((String) -> Unit)? = null

    // ---- stub-mode state (unit tests) ----
    @Volatile var stubAvailable: Boolean = false
    val sentChunks = mutableListOf<String>()

    // ---- AIDL binding state ----
    @Volatile private var serviceBound = false
    @Volatile private var bindAttempted = false

    // Raw IBinder: we avoid importing the generated stub so the module compiles
    // without the Meshtastic AIDL sources on the JVM test classpath.
    @Volatile private var meshBinder: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            meshBinder = binder
            serviceBound = true
            log?.invoke("meshtastic service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            meshBinder = null
            serviceBound = false
            log?.invoke("meshtastic service disconnected – will retry")
            // Schedule a re-bind attempt after a short delay.
            handler.postDelayed({ bindService() }, REBIND_DELAY_MS)
        }
    }

    override fun available(): Boolean {
        if (stubMode) return stubAvailable
        return serviceBound && isMeshtasticInstalled()
    }

    override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) {
        this.onReceive = onReceive
        this.log = onLog
        if (!stubMode) bindService()
    }

    override fun stop() {
        onReceive = null
        log = null
        if (!stubMode && bindAttempted) {
            runCatching { context!!.unbindService(connection) }
            bindAttempted = false
            serviceBound = false
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
        // Real device path: send each chunk as a Meshtastic data packet on port 257.
        // We serialise the string as UTF-8 bytes and hand it to IMeshService.sendData().
        // If the binder is not yet attached we silently drop (available() guards this).
        val binder = meshBinder ?: return 0
        var sent = 0
        for (chunk in chunks) {
            val bytes = chunk.toByteArray(Charsets.UTF_8)
            val delivered = sendViaAidl(binder, bytes)
            if (delivered) sent++ else log?.invoke("meshtastic: send failed for chunk")
        }
        log?.invoke("meshtastic: sent $sent/${chunks.size} chunk(s) for envelope ${envelope.envelopeId.take(8)}")
        return sent
    }

    /**
     * Called by the Meshtastic AIDL callback or by an inbound BroadcastReceiver
     * whenever a data packet on port [PRIVATE_PORT] arrives.
     * Thread-safe; may be called from any thread.
     */
    fun onPacketReceived(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        if (!MeshtasticCodec.isPart(text)) return
        val envelope = reassembler.accept(text, System.currentTimeMillis()) ?: return
        log?.invoke("meshtastic: reassembled envelope ${envelope.envelopeId.take(8)}")
        onReceive?.invoke(envelope, this)
    }

    /**
     * Test-only: inject a raw chunk frame as if received over LoRa.
     */
    fun simulateReceive(frame: String): MeshEnvelope? {
        return reassembler.accept(frame, System.currentTimeMillis()).also {
            if (it != null) onReceive?.invoke(it, this)
        }
    }

    // ---- private helpers ----

    private fun bindService() {
        val ctx = context ?: return
        if (!isMeshtasticInstalled()) {
            log?.invoke("meshtastic app not installed – transport unavailable")
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

    /**
     * Dispatches [bytes] to IMeshService via reflection so we don't need the
     * generated AIDL stub on the compile classpath. The real method signature is:
     *   `IMeshService.sendData(toId: String?, bytes: ByteArray, portNum: Int, wantAck: Boolean)`
     * We call it with `toId = null` (broadcast to all mesh peers).
     */
    private fun sendViaAidl(binder: IBinder, bytes: ByteArray): Boolean {
        return runCatching {
            // Obtain the stub proxy via reflection.
            val stubClass = Class.forName("com.geeksville.mesh.IMeshService\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)
            val sendDataMethod = service.javaClass.getMethod(
                "sendData",
                String::class.java,     // toId (null = broadcast)
                ByteArray::class.java,  // bytes
                Int::class.java,        // portNum
                Boolean::class.java,    // wantAck
            )
            sendDataMethod.invoke(service, null, bytes, PRIVATE_PORT, false)
            true
        }.getOrElse {
            log?.invoke("meshtastic AIDL send error: ${it.message}")
            false
        }
    }

    companion object {
        private const val TAG = "AgriChain"
        private const val MESHTASTIC_PACKAGE = "com.geeksville.mesh"
        private const val MESHTASTIC_SERVICE = "com.geeksville.mesh.service.MeshService"

        /** Private app portnum (256–511 reserved for private use in Meshtastic). */
        const val PRIVATE_PORT = 257

        private const val REBIND_DELAY_MS = 5_000L
    }
}
