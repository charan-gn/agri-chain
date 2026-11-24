package com.agrichain.box

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class GpsRecorder(context: Context, private val onFix: (Location) -> Unit) {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) return false

        var registered = false
        providers.forEach { provider ->
            val ok = runCatching {
                manager.requestLocationUpdates(provider, MIN_TIME_MS, 0f, listener, Looper.getMainLooper())
            }.isSuccess
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

    companion object {
        private const val MIN_TIME_MS = 3_000L
        private const val FRESH_FIX_MS = 60_000L
    }
}
