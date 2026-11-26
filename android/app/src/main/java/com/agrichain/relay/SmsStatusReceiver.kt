package com.agrichain.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Logs SMS sent/delivered callbacks. No message bodies or keys are logged. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val destination = intent.getStringExtra(EXTRA_DESTINATION).orEmpty()
        val messageId = intent.getIntExtra(EXTRA_MESSAGE_ID, 0)
        when (intent.action) {
            ACTION_SENT -> Log.i(TAG, "sms: sent id=$messageId to ...${destination.takeLast(4)}")
            ACTION_DELIVERED ->
                Log.i(TAG, "sms: delivered id=$messageId to ...${destination.takeLast(4)}")
        }
    }

    companion object {
        private const val TAG = "AgriMesh"
        const val ACTION_SENT = "com.agrichain.relay.SMS_SENT"
        const val ACTION_DELIVERED = "com.agrichain.relay.SMS_DELIVERED"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_MESSAGE_ID = "messageId"
    }
}
