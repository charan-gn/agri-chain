package com.agrichain.box

import com.agrichain.core.Event
import com.agrichain.core.Signer
import java.util.Locale

object GpsEvents {

    private val COORDINATE = Regex("^-?\\d+\\.\\d{6}$")

    fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    fun create(
        batchId: String,
        signer: Signer,
        latitude: Double,
        longitude: Double,
        accuracyM: Int,
        prevHash: String,
        ts: Long = System.currentTimeMillis(),
        mock: Boolean = false,
    ): Event {
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }
        require(accuracyM >= 0) { "Accuracy must be >= 0" }

        val lat = format(latitude)
        val lon = format(longitude)
        require(COORDINATE.matches(lat) && COORDINATE.matches(lon)) { "Coordinates need 6 decimals" }

        val data = linkedMapOf<String, Any?>("accuracyM" to accuracyM)
        if (mock) data["mock"] = true

        return Event.create(
            batchId = batchId,
            type = "GPS",
            data = data,
            signer = signer,
            ts = ts,
            lat = lat,
            lon = lon,
            prevHash = prevHash,
        )
    }
}
