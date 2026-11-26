package com.agrichain.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service for the v3 relay. It keeps the Wi-Fi link, the gateway
 * loop and the pending outbox alive when the screen is off.
 */
class RelayService : Service() {

    @Volatile
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting AgriMesh relay"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        Thread({
            val error = RelayRuntime.start(applicationContext)
            val text = error ?: "Relay running as ${RelayRuntime.identity?.actorHex ?: "?"}"
            Log.i(TAG, "service: $text")
            notify(text)
        }, "agrimesh-relay-start").start()
        return START_STICKY
    }

    override fun onDestroy() {
        RelayRuntime.stop()
        started = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AgriMesh relay",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Offline relay, gateway sync and pending messages"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RelayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("AgriMesh relay")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "AgriMesh"
        private const val CHANNEL_ID = "agrimesh"
        private const val NOTIFICATION_ID = 4103
        const val ACTION_STOP = "com.agrichain.relay.STOP"
    }
}
