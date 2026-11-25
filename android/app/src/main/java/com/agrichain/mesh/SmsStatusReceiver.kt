package com.agrichain.mesh

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Logs the carrier result for every chunk sent by [SmsTransport]. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val messageId = intent.getIntExtra(EXTRA_MESSAGE_ID, 0)
        val tail = destination.takeLast(4)
        when (intent.action) {
            ACTION_SENT -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.i(TAG, "sms: sent id $messageId to ...$tail")
                } else {
                    Log.w(TAG, "sms: send failed id $messageId to ...$tail code=$resultCode")
                }
            }

            ACTION_DELIVERED -> {
                Log.i(TAG, "sms: delivered id $messageId to ...$tail")
            }
        }
    }

    companion object {
        private const val TAG = "AgriChain"
        const val ACTION_SENT = "com.agrichain.sms.SENT"
        const val ACTION_DELIVERED = "com.agrichain.sms.DELIVERED"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_MESSAGE_ID = "messageId"
    }
}
