package com.agrichain.box

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.Locale

class TamperDetector(
    context: Context,
    private val onTamper: (kind: String, detail: String) -> Unit,
) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var armed = false
    private var lastLightLux = -1.0
    private var lastProximity = -1.0
    private val lastTriggerAt = mutableMapOf<String, Long>()
    private val movementGate = TamperRules.MovementGate()

    val hasMovement: Boolean get() = accelerometer != null
    val hasLight: Boolean get() = light != null
    val hasProximity: Boolean get() = proximity != null

    fun sensorSummary(): String {
        val names = buildList {
            if (hasMovement) add("movement")
            if (hasLight) add("light")
            if (hasProximity) add("proximity")
        }
        return names.ifEmpty { listOf("simulate only") }.joinToString(", ")
    }

    fun arm() {
        if (armed) return
        armed = true
        lastLightLux = -1.0
        lastProximity = -1.0
        lastTriggerAt.clear()
        movementGate.reset()
        accelerometer?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        light?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        proximity?.let {
            sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun disarm() {
        if (!armed) return
        armed = false
        sensors.unregisterListener(listener)
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!armed) return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    val x = event.values[0].toDouble()
                    val y = event.values[1].toDouble()
                    val z = event.values[2].toDouble()
                    if (TamperRules.isMovement(x, y, z) && movementGate.record(SystemClock.elapsedRealtime())) {
                        val delta = String.format(Locale.US, "%.1f", TamperRules.movementDelta(x, y, z))
                        emit("movement", "delta $delta m/s2")
                    }
                }

                Sensor.TYPE_LIGHT -> {
                    val lux = event.values[0].toDouble()
                    if (TamperRules.isLightChange(lastLightLux, lux)) {
                        emit("light", "lux ${lastLightLux.toInt()} -> ${lux.toInt()}")
                    }
                    lastLightLux = lux
                }

                Sensor.TYPE_PROXIMITY -> {
                    val distance = event.values[0].toDouble()
                    if (lastProximity >= 0.0 && lastProximity != distance) {
                        emit("proximity", if (distance < 1.0) "near" else "far")
                    }
                    lastProximity = distance
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun emit(kind: String, detail: String) {
        val now = SystemClock.elapsedRealtime()
        val cooldownMs = if (kind == "movement") 5_000L else 3_000L
        if (now - (lastTriggerAt[kind] ?: 0L) < cooldownMs) return
        lastTriggerAt[kind] = now
        onTamper(kind, detail)
    }
}
