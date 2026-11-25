package com.agrichain.box

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

class GpsRecorder(context: Context, private val onFix: (Location) -> Unit) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "gps: provider enabled $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "gps: provider disabled $provider")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? =
        enabledProviders()
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val providers = enabledProviders()
        Log.i(TAG, "gps: enabled providers [${providers.joinToString()}]")
        if (providers.isEmpty()) return false

        var registered = false
        providers.forEach { provider ->
            val ok = runCatching {
                manager.requestLocationUpdates(provider, MIN_TIME_MS, 0f, listener, Looper.getMainLooper())
            }.isSuccess
            Log.i(TAG, "gps: listen $provider=$ok")
            registered = registered || ok
        }
        if (!registered) return false

        running = true
        lastKnown()
            ?.takeIf { System.currentTimeMillis() - it.time < FRESH_FIX_MS }
            ?.let(onFix)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        runCatching { manager.removeUpdates(listener) }
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun enabledProviders(): List<String> =
        runCatching {
            manager.getProviders(true)
                .filter { it != LocationManager.PASSIVE_PROVIDER }
                .distinct()
        }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "AgriChain"
        private const val MIN_TIME_MS = 3_000L
        private const val FRESH_FIX_MS = 60_000L
    }
}
