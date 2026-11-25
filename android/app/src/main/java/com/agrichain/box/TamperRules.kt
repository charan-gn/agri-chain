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
    const val GPS_MIN_DISTANCE_M = 20.0
    const val GPS_HEARTBEAT_MS = 60_000L
    const val MOVEMENT_GATE_HITS = 2
    const val MOVEMENT_GATE_WINDOW_MS = 1_500L

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

    fun shouldRecordGps(
        lastLat: Double?,
        lastLon: Double?,
        lastTsMs: Long?,
        lat: Double,
        lon: Double,
        tsMs: Long,
    ): Boolean {
        if (lastLat == null || lastLon == null || lastTsMs == null) return true
        if (tsMs - lastTsMs >= GPS_HEARTBEAT_MS) return true
        return distanceMeters(lastLat, lastLon, lat, lon) >= GPS_MIN_DISTANCE_M
    }

    /** Requires [requiredHits] movement samples within [windowMs] to suppress single bumps. */
    class MovementGate(
        private val requiredHits: Int = MOVEMENT_GATE_HITS,
        private val windowMs: Long = MOVEMENT_GATE_WINDOW_MS,
    ) {
        private val hits = ArrayDeque<Long>()

        fun record(nowMs: Long): Boolean {
            while (hits.isNotEmpty() && nowMs - hits.first() > windowMs) {
                hits.removeFirst()
            }
            hits.addLast(nowMs)
            if (hits.size >= requiredHits) {
                hits.clear()
                return true
            }
            return false
        }

        fun reset() {
            hits.clear()
        }
    }
}
