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
 * Gateway side of the radio leg. Reassembles text SMS, then hands the envelope to
 * the relay, which stores it and uploads it if this phone has internet.
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
                    val envelope = SmsInbox.reassembler.accept(body, now) ?: continue
                    val result = MeshRuntime.get(appContext)
                        .onEnvelope(envelope, "sms", address.takeIf { it.isNotEmpty() })
                    Log.i(
                        "AgriChainSms",
                        "from ${address.takeLast(4)}: ${result.freshEvents} new, " +
                            "${result.uploaded} uploaded",
                    )
                }
            } catch (error: Exception) {
                Log.w("AgriChainSms", "receive failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
