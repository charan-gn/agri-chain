package com.agrichain.box

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object TamperRules {

    const val MOVEMENT_DELTA_MS2 = 2.5
    const val LIGHT_DELTA_LUX = 200.0
    const val DEFAULT_GEOFENCE_RADIUS_M = 200.0

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val GRAVITY_MS2 = 9.80665

    fun movementDelta(x: Double, y: Double, z: Double): Double =
        abs(sqrt(x * x + y * y + z * z) - GRAVITY_MS2)

    fun isMovement(x: Double, y: Double, z: Double): Boolean =
        movementDelta(x, y, z) >= MOVEMENT_DELTA_MS2

    fun isLightChange(previousLux: Double, currentLux: Double): Boolean =
        previousLux >= 0.0 && abs(currentLux - previousLux) >= LIGHT_DELTA_LUX

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun isOutsideGeofence(
        anchorLat: Double,
        anchorLon: Double,
        lat: Double,
        lon: Double,
        radiusM: Double,
    ): Boolean = distanceMeters(anchorLat, anchorLon, lat, lon) > radiusM
}
