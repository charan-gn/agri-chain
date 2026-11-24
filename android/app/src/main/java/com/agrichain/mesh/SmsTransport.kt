package com.agrichain.mesh

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

/**
 * GSM radio leg. Text SMS only; events are base64-chunked, queued and drained at
 * a human pace so Android does not show the "sending many SMS" prompt.
 */
class SmsTransport(
    private val context: Context,
    override val id: String = "sms",
) : Transport {

    override val maxPayload: Int = SmsCodec.MAX_CHUNK

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<String, String>>()
    private var draining = false
    private var log: ((String) -> Unit)? = null

    /** Number the last inbound SMS came from; ACKs go back there, not to ourselves. */
    @Volatile
    var replyTo: String? = null

    override fun available(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        return granted && (replyTo != null || SmsSettings.gatewayNumber(context) != null)
    }

    override fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit) {
        log = onLog
    }

    override fun stop() {
        log = null
    }

    override fun send(envelope: MeshEnvelope): Int {
        if (!available()) return 0
        val filtered = SmsPolicy.filter(envelope)
        if (filtered.events.isEmpty() && filtered.ackedEventIds.isEmpty()) return 0

        val destination = if (envelope.kind == MeshKind.ACK) {
            replyTo ?: SmsSettings.gatewayNumber(context)
        } else {
            SmsSettings.gatewayNumber(context)
        } ?: return 0

        val envelopes = if (filtered.kind == MeshKind.EVENTS && filtered.events.size > MAX_EVENTS) {
            filtered.events.chunked(MAX_EVENTS).map { group ->
                filtered.copy(envelopeId = UUID.randomUUID().toString(), events = group)
            }
        } else {
            listOf(filtered)
        }

        var sent = 0
        for (part in envelopes) {
            val chunks = SmsCodec.chunks(part)
            synchronized(queue) {
                chunks.forEach { queue.addLast(destination to it) }
            }
            sent += chunks.size
        }
        schedule()
        return sent
    }

    private fun schedule() {
        synchronized(queue) {
            if (draining) return
            draining = true
        }
        handler.post(drain)
    }

    private val drain = object : Runnable {
        override fun run() {
            val next = synchronized(queue) {
                queue.pollFirst().also { if (it == null) draining = false }
            }
            if (next == null) return
            try {
                val manager = context.getSystemService(SmsManager::class.java)
                manager.sendTextMessage(next.first, null, next.second, null, null)
                log?.invoke("sms -> ${next.first.takeLast(4)} (${next.second.length} chars)")
            } catch (error: Exception) {
                log?.invoke("sms failed: ${error.message}")
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    companion object {
        private const val MAX_EVENTS = 4
        private const val INTERVAL_MS = 2_000L
    }
}
