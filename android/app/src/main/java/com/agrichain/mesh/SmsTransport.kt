package com.agrichain.mesh

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * GSM radio leg. Binary v3 envelopes are base64url-chunked, queued and drained at
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
    private val messageIds = AtomicInteger(1_000)

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

    override fun peers(envelope: MeshEnvelope): Set<String> {
        val destination = if (envelope.kind == MeshKind.ACK) {
            replyTo ?: SmsSettings.gatewayNumber(context)
        } else {
            SmsSettings.gatewayNumber(context)
        } ?: return emptySet()
        return setOf(normalizePeerId(destination))
    }

    override fun carriedEventIds(envelope: MeshEnvelope): Set<String> =
        SmsPolicy.filter(envelope).events.mapTo(mutableSetOf()) { it.eventId }

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
            val messageId = messageIds.incrementAndGet()
            try {
                val manager = defaultSubscriptionManager()
                manager.sendTextMessage(
                    next.first,
                    null,
                    next.second,
                    statusIntent(SmsStatusReceiver.ACTION_SENT, messageId, next.first),
                    statusIntent(SmsStatusReceiver.ACTION_DELIVERED, messageId, next.first),
                )
                log?.invoke(
                    "sms -> ${next.first.takeLast(4)} (${next.second.length} chars, " +
                        "id $messageId)"
                )
            } catch (error: Exception) {
                log?.invoke("sms failed: ${error.message}")
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** Default SMS subscription, so a dual-SIM phone sends on the SIM chosen in Settings. */
    private fun defaultSubscriptionManager(): SmsManager {
        val subId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { SmsManager.getSmsManagerForSubscriptionId(subId) }
                .onFailure { log?.invoke("sms: default sub $subId unavailable: ${it.message}") }
                .getOrNull()
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun statusIntent(action: String, messageId: Int, destination: String): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            .putExtra(SmsStatusReceiver.EXTRA_DESTINATION, destination)
            .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
        return PendingIntent.getBroadcast(
            context,
            messageId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val MAX_EVENTS = 4
        private const val INTERVAL_MS = 2_000L
    }
}
