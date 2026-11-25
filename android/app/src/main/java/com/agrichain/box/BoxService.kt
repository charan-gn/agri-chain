package com.agrichain.box

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.agrichain.R

/**
 * Keeps the transport box alive with the screen off: a location foreground
 * service plus a partial wake lock. The UI starts and stops it; GPS and the
 * tamper sensors run in [BoxRuntime], owned by this service.
 */
class BoxService : Service() {

    private lateinit var engine: BoxEngine
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        engine = BoxRuntime.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "box service: stop requested")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()
        acquireWakeLock()
        running = true
        val started = engine.startGps()
        Log.i(
            TAG,
            "box service: started id=$startId gps=$started batch=${engine.boundBatchId}",
        )
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "box service: stopping")
        running = false
        engine.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val openBox = PendingIntent.getActivity(
            this,
            0,
            Intent(this, BoxActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_box)
            .setContentTitle("AgriChain transport box")
            .setContentText("Logging GPS and tamper sensors")
            .setContentIntent(openBox)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Transport box",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "GPS and tamper logging for the transport box"
                setShowBadge(false)
            }
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.i(TAG, "box service: partial wake lock held")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AgriChain"
        private const val CHANNEL_ID = "agrichain_box"
        private const val NOTIFICATION_ID = 42
        private const val WAKE_LOCK_TAG = "AgriChain:box"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val ACTION_START = "com.agrichain.box.action.START"
        const val ACTION_STOP = "com.agrichain.box.action.STOP"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BoxService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BoxService::class.java))
        }
    }
}
