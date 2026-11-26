package com.agrichain.relay

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * GSM SMS transport for v3 envelopes.
 *
 * Every part is paced at two seconds and the transport never sends more than 20
 * parts per minute. SMS is opt-in: the caller supplies the gateway number and
 * the gateway-mode flag. Inbound messages are handed to the relay by
 * [SmsReceiver]; this class owns the outbound queue and the visible links.
 */
class SmsTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val gatewayNumber: () -> String?,
    private val gatewayMode: () -> Boolean,
) : Transport {

    override val id: String = "sms"
    override val meshLinks: Boolean = true
    override val maxFrame: Int = SmsV3Codec.MAX_ENVELOPE

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 32)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val messageIds = AtomicInteger(2_000)
    private val sentTimes = ArrayDeque<Long>()
    private var queue: Channel<Outgoing>? = null
    private var worker: kotlinx.coroutines.Job? = null

    @Volatile
    private var running = false

    /** Ledger-signed messages explicitly addressed by the gateway (receipts). */
    private val allowedLedger = LinkedHashSet<String>()

    private data class Outgoing(val destination: String, val text: String)

    override suspend fun start() {
        if (running) return
        if (!hasSendPermission()) {
            _state.value = TransportState.ERROR
            Log.w(TAG, "sms: SEND_SMS not granted")
            return
        }
        if (!gatewayMode() && gatewayNumber().isNullOrBlank()) {
            _state.value = TransportState.DEGRADED
            Log.i(TAG, "sms: no gateway number and not in gateway mode")
            return
        }
        val channel = Channel<Outgoing>(256)
        queue = channel
        running = true
        _state.value = TransportState.RUNNING
        gatewayNumber()?.takeIf { it.isNotBlank() }?.let { notePeer(it) }
        worker = scope.launch { drain(channel) }
        Log.i(TAG, "sms: started (gateway=${gatewayMode()})")
    }

    override suspend fun stop() {
        running = false
        worker?.cancel()
        worker = null
        queue?.close()
        queue = null
        _state.value = TransportState.STOPPED
        _links.value = emptySet()
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        if (!running) return SendResult.NoLink("sms not running")
        val message = runCatching { V3Codec.decodeEnvelope(envelope).second }.getOrNull()
            ?: return SendResult.Failed("bad envelope", retryable = false)
        val decoded = runCatching { V3Codec.decode(message) }.getOrNull()
            ?: return SendResult.Failed("bad message", retryable = false)
        if (decoded.ledgerSigned) {
            val msgId = V3Codec.hex(V3Codec.msgId(decoded))
            synchronized(allowedLedger) {
                if (!allowedLedger.remove(msgId)) {
                    // Receipts/notices are only sent to the number that asked.
                    return SendResult.NoLink("directed ledger message")
                }
            }
        }
        val destination = destinationFor(linkId) ?: return SendResult.NoLink("no SMS number")
        val parts = runCatching { SmsV3Codec.chunks(envelope) }.getOrElse {
            return SendResult.Failed(it.message ?: "sms chunking failed", retryable = false)
        }
        val channel = queue ?: return SendResult.NoLink("sms queue closed")
        for (part in parts) channel.trySend(Outgoing(destination, part))
        Log.i(TAG, "sms: queued ${parts.size} part(s) to ...${destination.takeLast(4)}")
        return SendResult.Sent(1)
    }

    /** Send a signed ledger envelope straight to a number (gateway receipt path). */
    suspend fun sendEnvelopeTo(number: String, envelope: ByteArray): Boolean {
        if (!running || number.isBlank()) return false
        val decoded = runCatching {
            V3Codec.decode(V3Codec.decodeEnvelope(envelope).second)
        }.getOrNull() ?: return false
        if (decoded.ledgerSigned) {
            synchronized(allowedLedger) {
                allowedLedger.add(V3Codec.hex(V3Codec.msgId(decoded)))
            }
        }
        val parts = runCatching { SmsV3Codec.chunks(envelope) }.getOrElse { return false }
        val channel = queue ?: return false
        for (part in parts) channel.trySend(Outgoing(number, part))
        Log.i(TAG, "sms: receipt ${parts.size} part(s) to ...${number.takeLast(4)}")
        return true
    }

    /** Plain-text reply (feature-phone commands, <= 160 characters). */
    fun sendText(number: String, text: String): Boolean {
        if (!running || number.isBlank()) return false
        val channel = queue ?: return false
        channel.trySend(Outgoing(number, text.take(SmsV3Codec.MAX_SMS_CHARS)))
        return true
    }

    /** Remember a number seen on the radio so relay traffic can reach it. */
    fun notePeer(number: String) {
        if (number.isBlank()) return
        _links.value = _links.value + LinkInfo(
            linkId = number,
            transportId = id,
            peerId = number,
            peerName = number.takeLast(4),
            slow = true,
        )
    }

    fun deliver(envelope: ByteArray, number: String) {
        notePeer(number)
        _incoming.tryEmit(Incoming(envelope, number, id))
    }

    private fun destinationFor(linkId: String?): String? {
        val explicit = linkId?.takeIf { it.isNotBlank() && it != "gateway" }
        return explicit ?: gatewayNumber()?.takeIf { it.isNotBlank() }
    }

    private suspend fun drain(channel: Channel<Outgoing>) {
        for (item in channel) {
            pace()
            sendPart(item)
        }
    }

    private suspend fun pace() {
        val now = System.currentTimeMillis()
        synchronized(sentTimes) {
            while (sentTimes.isNotEmpty() && now - sentTimes.first() > 60_000) {
                sentTimes.removeFirst()
            }
        }
        if (synchronized(sentTimes) { sentTimes.size } >= PARTS_PER_MINUTE) {
            val wait = synchronized(sentTimes) {
                (sentTimes.firstOrNull() ?: now) + 60_000 - now
            }
            if (wait > 0) delay(wait)
        }
        delay(PART_INTERVAL_MS)
        synchronized(sentTimes) { sentTimes.addLast(System.currentTimeMillis()) }
    }

    private fun sendPart(item: Outgoing) {
        val id = messageIds.incrementAndGet()
        runCatching {
            val manager = defaultSubscriptionManager()
            manager.sendTextMessage(
                item.destination,
                null,
                item.text,
                statusIntent(SmsStatusReceiver.ACTION_SENT, id, item.destination),
                statusIntent(SmsStatusReceiver.ACTION_DELIVERED, id, item.destination),
            )
            Log.i(
                TAG,
                "sms -> ...${item.destination.takeLast(4)} (${item.text.length} chars, id $id)",
            )
        }.onFailure { Log.w(TAG, "sms: send failed: ${it.message}") }
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

    private fun defaultSubscriptionManager(): SmsManager {
        val subId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { SmsManager.getSmsManagerForSubscriptionId(subId) }
                .getOrNull()
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun hasSendPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AgriMesh"
        private const val PART_INTERVAL_MS = 2_000L
        private const val PARTS_PER_MINUTE = 20
    }
}
