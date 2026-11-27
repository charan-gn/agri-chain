package com.agrichain.relay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GSM leg of the v3 relay. Envelopes are chunked as
 * `AM1:<id6>:<i>/<n>:<base64url>` and drained at a human pace, so a phone never
 * trips Android's "sending many SMS" prompt. SMS is not a flooded mesh link:
 * [meshLinks] is false, so only the gateway fallback and receipt routing use it.
 */
class SmsTransport(
    private val context: Context,
    private val gatewayNumber: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Transport {

    override val id: String = "sms"
    override val meshLinks: Boolean = false
    override val maxFrame: Int = V3.MAX_MESSAGE + 1

    private val _state = MutableStateFlow(TransportState.STOPPED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Empty on purpose: a link would make RelayCore send an ANNOUNCE over SMS
    // on every start. The fallback hook passes the destination number directly.
    private val _links = MutableStateFlow<Set<LinkInfo>>(emptySet())
    override val links: StateFlow<Set<LinkInfo>> = _links.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 32)
    override val incoming: Flow<Incoming> = _incoming.asSharedFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val outbox = SmsOutbox(sender = ::sendNow, clock = clock)

    private var draining = false

    override suspend fun start() {
        _state.value = when {
            canSend() && canReceive() -> TransportState.RUNNING
            canSend() || canReceive() -> TransportState.DEGRADED
            else -> TransportState.ERROR
        }
        Log.i(
            TAG,
            "sms: ${_state.value.name.lowercase()} gateway=" +
                (gatewayNumber?.takeLast(4) ?: "none"),
        )
    }

    override suspend fun stop() {
        handler.removeCallbacks(drain)
        synchronized(this) { draining = false }
        outbox.clear()
        _state.value = TransportState.STOPPED
    }

    override suspend fun send(linkId: String?, envelope: ByteArray): SendResult {
        val number = linkId?.takeIf { it.isNotBlank() } ?: gatewayNumber
        if (number.isNullOrBlank()) return SendResult.NoLink("no SMS gateway number")
        if (!canSend()) return SendResult.Failed("SEND_SMS not granted", retryable = false)
        return if (enqueueEnvelope(number, envelope)) {
            SendResult.Sent(1)
        } else {
            SendResult.Failed("SMS chunking failed", retryable = false)
        }
    }

    /** Queue one whole envelope for [number]; returns false when it needs > 6 parts. */
    fun enqueueEnvelope(number: String, envelope: ByteArray): Boolean {
        val chunks = runCatching { SmsV3Codec.chunks(envelope) }.getOrElse { error ->
            Log.w(TAG, "sms: cannot chunk envelope: ${error.message}")
            return false
        }
        outbox.enqueue(number, chunks)
        schedule()
        return true
    }

    /** Queue a plain-text reply (PRICE/LISTINGS answers). */
    fun enqueueText(number: String, text: String): Boolean {
        val body = text.trim().take(SmsV3Codec.MAX_TEXT)
        if (body.isEmpty()) return false
        outbox.enqueue(number, listOf(body))
        schedule()
        return true
    }

    /** Hand an inbound envelope to the attached relay (gateway receiver path). */
    fun deliverIncoming(number: String, envelope: ByteArray) {
        _incoming.tryEmit(
            Incoming(
                envelope = envelope,
                linkId = number,
                transportId = id,
            ),
        )
    }

    private fun schedule() {
        synchronized(this) {
            if (draining) return
            draining = true
        }
        handler.post(drain)
    }

    private val drain = object : Runnable {
        override fun run() {
            val now = clock()
            outbox.drain(now)
            synchronized(this@SmsTransport) {
                if (outbox.pending() > 0) {
                    val wait = (outbox.nextAllowedAt(now) - now).coerceAtLeast(MIN_DRAIN_MS)
                    handler.postDelayed(this, wait)
                } else {
                    draining = false
                }
            }
        }
    }

    private fun sendNow(number: String, text: String): Boolean = runCatching {
        defaultSubscriptionManager().sendTextMessage(number, null, text, null, null)
        Log.i(TAG, "sms -> ...${number.takeLast(4)} (${text.length} chars)")
        true
    }.getOrElse { error ->
        Log.w(TAG, "sms: send failed: ${error.message}")
        false
    }

    /** Default SMS subscription, so a dual-SIM phone sends on the SIM the user chose. */
    private fun defaultSubscriptionManager(): SmsManager {
        val subId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
            .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            runCatching { SmsManager.getSmsManagerForSubscriptionId(subId) }
                .onFailure { Log.w(TAG, "sms: default sub $subId unavailable: ${it.message}") }
                .getOrNull()
                ?.let { return it }
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun canSend(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.SEND_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    private fun canReceive(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECEIVE_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "AgriMesh"
        const val MIN_DRAIN_MS = 250L
    }
}
