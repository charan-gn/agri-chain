package com.agrichain.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/** Process-wide reassembly buffer; SMS parts may arrive out of order. */
object SmsInboxV3 {
    val reassembler = SmsV3Reassembler()
}

/**
 * SMS gateway receiver. Only a phone in gateway mode forwards AM1 envelopes to
 * the relay; every phone in gateway mode answers PRICE/LISTINGS commands.
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
                val settings = V3Settings(appContext)
                if (!settings.gatewayEnabled || !settings.smsGatewayMode) {
                    Log.i(TAG, "sms: ignored (not a gateway)")
                    return@Thread
                }
                val now = System.currentTimeMillis()
                val groups = messages.groupBy { it.originatingAddress.orEmpty() }
                for ((address, parts) in groups) {
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    if (address.isBlank() || body.isBlank()) continue
                    when {
                        SmsV3Codec.isPart(body) -> receiveEnvelope(appContext, body, address, now)
                        body.trim().uppercase().startsWith("PRICE ") ||
                            body.trim().uppercase().startsWith("LISTINGS ") -> answerCommand(
                            appContext, body, address, settings,
                        )
                        else -> Log.i(TAG, "sms: ignored text from ...${address.takeLast(4)}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "sms: receive failed: ${error.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun receiveEnvelope(context: Context, body: String, address: String, now: Long) {
        val part = SmsV3Codec.parsePart(body)
        val envelope = SmsInboxV3.reassembler.accept(body, now)
        if (envelope == null) {
            Log.i(
                TAG,
                "sms: part ${part?.index}/${part?.total} (${part?.id}) from ...${address.takeLast(4)}",
            )
            return
        }
        // RelayRuntime.start() now probes the ledger synchronously; let the
        // foreground service do that work instead of blocking the receiver.
        if (RelayRuntime.core == null) RelayRuntime.startService(context)
        val outcome = kotlinx.coroutines.runBlocking {
            RelayRuntime.onSmsEnvelope(envelope, address)
        }
        Log.i(
            TAG,
            "sms: envelope from ...${address.takeLast(4)} fresh=${outcome?.fresh} " +
                "dropped=${outcome?.droppedReason}",
        )
    }

    private fun answerCommand(
        context: Context,
        body: String,
        address: String,
        settings: V3Settings,
    ) {
        val handler = SmsCommandHandler(
            HttpV3LedgerClient(settings.baseUrl(context)),
            defaultRegion = settings.region,
        )
        val reply = kotlinx.coroutines.runBlocking { handler.handle(body) }
        val sent = SmsSender.sendText(context, address, reply)
        Log.i(TAG, "sms: command reply to ...${address.takeLast(4)} sent=$sent")
    }

    companion object {
        private const val TAG = "AgriMesh"
    }
}
