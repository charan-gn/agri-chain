package com.agrichain.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.util.ArrayDeque

/**
 * Process-wide buffer for SMS that arrives before the relay service starts.
 * Bounded: an attacker cannot grow it without bound.
 */
object SmsInboxV3 {
    private const val MAX_BUFFERED = 32

    private val queue = ArrayDeque<Pair<String, ByteArray>>()
    val reassembler = SmsV3Reassembler()

    @Synchronized
    fun add(number: String, envelope: ByteArray) {
        while (queue.size >= MAX_BUFFERED) queue.removeFirst()
        queue.addLast(number to envelope)
    }

    @Synchronized
    fun drain(): List<Pair<String, ByteArray>> {
        val items = queue.toList()
        queue.clear()
        return items
    }

    @Synchronized
    fun size(): Int = queue.size
}

/**
 * Gateway SMS receiver. Accepts only `AM1:` chunks and the two plain-text
 * commands; every other SMS is ignored without parsing further.
 */
class SmsReceiverV3 : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val pending = goAsync()
        Thread({
            try {
                val now = System.currentTimeMillis()
                val groups = messages.groupBy { it.originatingAddress.orEmpty() }
                for ((address, parts) in groups) {
                    if (address.isBlank()) continue
                    val bodies = parts.mapNotNull { it.messageBody }
                    val chunks = bodies.filter { SmsV3Codec.isPart(it) }
                    if (chunks.isNotEmpty()) {
                        for (body in chunks) {
                            val envelope = SmsInboxV3.reassembler.accept(body, now) ?: continue
                            RelayRuntime.onSmsEnvelope(address, envelope)
                        }
                        continue
                    }
                    val text = bodies.joinToString("")
                    if (SmsCommands.parse(text) != null) {
                        RelayRuntime.onSmsCommand(address, text)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "sms: receive failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }, "agrimesh-sms-receive").start()
    }

    private companion object {
        const val TAG = "AgriMesh"
    }
}
