package com.agrichain.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * One permission list for the whole app. The API-level rules follow the Nearby
 * Connections setup guide: Bluetooth scan/advertise/connect on Android 12+,
 * NEARBY_WIFI_DEVICES on 13+, and location for everything older.
 */
object Permissions {

    const val TAG = "AgriChain"

    data class Need(
        val permission: String,
        val label: String,
    )

    fun required(): List<Need> = buildList {
        add(Need(Manifest.permission.CAMERA, "Camera (batch QR)"))
        add(Need(Manifest.permission.ACCESS_FINE_LOCATION, "Precise location (box GPS)"))
        add(Need(Manifest.permission.ACCESS_COARSE_LOCATION, "Approximate location"))
        add(Need(Manifest.permission.SEND_SMS, "Send SMS (mesh radio)"))
        add(Need(Manifest.permission.RECEIVE_SMS, "Receive SMS (mesh relay)"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Need(Manifest.permission.BLUETOOTH_SCAN, "Bluetooth scan (Nearby)"))
            add(Need(Manifest.permission.BLUETOOTH_ADVERTISE, "Bluetooth advertise (Nearby)"))
            add(Need(Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth connect (Nearby)"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Need(Manifest.permission.NEARBY_WIFI_DEVICES, "Wi-Fi devices (Nearby)"))
            add(Need(Manifest.permission.POST_NOTIFICATIONS, "Notifications (box service)"))
        }
    }

    fun missing(context: Context): List<Need> = required().filter {
        ContextCompat.checkSelfPermission(context, it.permission) !=
            PackageManager.PERMISSION_GRANTED
    }

    fun describe(needs: List<Need>): String =
        needs.joinToString("\n") { "• ${it.label}\n  ${it.permission}" }

    fun summary(context: Context): String {
        val all = required()
        val missing = missing(context)
        return if (missing.isEmpty()) {
            "Permissions: ${all.size}/${all.size} granted"
        } else {
            "Permissions: ${all.size - missing.size}/${all.size} granted\n" +
                "Missing: ${missing.joinToString { it.label }}"
        }
    }

    /** Logs the exact denied set, so adb logcat can prove the app saw it. */
    fun logState(context: Context) {
        val missing = missing(context)
        if (missing.isEmpty()) {
            Log.i(TAG, "permissions: all ${required().size} granted")
        } else {
            Log.w(
                TAG,
                "permissions: denied ${missing.size}/${required().size}: " +
                    missing.joinToString { it.permission },
            )
        }
    }
}
