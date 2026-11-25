package com.agrichain.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Process-wide reassembly buffer; chunks from one SMS may arrive in any order. */
object SmsInbox {
    val reassembler = SmsReassembler()
}

/**
 * Gateway side of the radio leg. Reassembles v3 binary chunks (or legacy AC1 text),
 * then hands the envelope to the relay, which stores it and uploads it when online.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val now = System.currentTimeMillis()
                val groups = messages.groupBy { it.originatingAddress.orEmpty() }
                for ((address, parts) in groups) {
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val part = SmsCodec.parsePart(body) ?: continue
                    val envelope = SmsInbox.reassembler.accept(body, now)
                    if (envelope == null) {
                        Log.i(
                            "AgriChain",
                            "sms: part ${part.index}/${part.total} (${part.msgId}) from ...${address.takeLast(4)}",
                        )
                        continue
                    }
                    // The originating number is the peer we sent events to; ACKs are
                    // scoped to it. Only events set the ACK destination.
                    val peer = address.takeIf { it.isNotEmpty() }
                    val reply = if (envelope.kind == MeshKind.EVENTS) peer else null
                    val result = MeshRuntime.get(appContext)
                        .onEnvelope(envelope, "sms", peerId = peer, replyTo = reply)
                    Log.i(
                        "AgriChain",
                        "sms: ${envelope.kind} from ...${address.takeLast(4)} " +
                            "${result.freshEvents} new, ${result.uploaded} uploaded",
                    )
                }
            } catch (error: Exception) {
                Log.w("AgriChain", "sms: receive failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
