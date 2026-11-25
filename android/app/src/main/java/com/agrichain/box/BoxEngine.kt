package com.agrichain.box

import android.content.Context
import android.location.Location
import android.util.Log
import com.agrichain.core.ActorIdentity
import kotlin.math.roundToInt

/**
 * Box state shared by the activity and [BoxService]. GPS and the tamper sensors
 * live here, so they keep running when the activity is closed or the screen is off.
 */
class BoxEngine(context: Context) {

    private val app = context.applicationContext
    private val session = BoxSession(app)
    val identity: ActorIdentity = ActorIdentity.loadOrCreate(app, "box")
    val events = BoxEventLog(app, identity)

    private val gps = GpsRecorder(app) { location -> onFix(location) }
    private val tamper = TamperDetector(app) { kind, detail -> onTamper(kind, detail) }

    @Volatile
    var boundBatchId: String? = null
        private set

    @Volatile
    var ledgerHead: String = ""
        private set

    @Volatile
    var logging: Boolean = false
        private set

    @Volatile
    var tamperArmed: Boolean = false
        private set

    @Volatile
    var geofenceLat: Double? = null
        private set

    @Volatile
    var geofenceLon: Double? = null
        private set

    @Volatile
    var geofenceRadiusM: Double = TamperRules.DEFAULT_GEOFENCE_RADIUS_M
        private set

    private var geofenceBreached = false

    /** Called on the main thread after any state change, so the activity can redraw. */
    var onChange: (() -> Unit)? = null

    /** User-facing status, shown as a toast by the activity. */
    var onMessage: ((String) -> Unit)? = null

    init {
        session.batchId()?.let { batchId ->
            boundBatchId = batchId
            ledgerHead = session.ledgerHead(batchId)
        }
    }

    fun bind(batchId: String, head: String) {
        if (logging) stopGps()
        if (tamperArmed) disarmTamper()
        boundBatchId = batchId
        ledgerHead = head
        geofenceLat = null
        geofenceLon = null
        geofenceBreached = false
        session.bind(batchId, head)
        Log.i(TAG, "box: bound $batchId head=${short(head)}")
        notifyChange()
    }

    fun startGps(): Boolean {
        val batchId = boundBatchId ?: return false
        if (logging) return true
        logging = gps.start()
        if (logging) {
            Log.i(TAG, "box: gps logging started for $batchId")
        } else {
            Log.w(TAG, "box: no enabled location provider for $batchId")
            onMessage?.invoke("Enable a location provider (GPS) on this phone")
        }
        notifyChange()
        return logging
    }

    fun stopGps() {
        if (!logging) return
        gps.stop()
        logging = false
        Log.i(TAG, "box: gps logging stopped")
        notifyChange()
    }

    fun lastKnown(): Location? = gps.lastKnown()

    fun sessionHead(batchId: String): String = session.ledgerHead(batchId)

    fun logCurrentFix(): Boolean {
        if (boundBatchId == null) {
            onMessage?.invoke("Bind a batch first")
            return false
        }
        val fix = gps.lastKnown()
        if (fix == null) {
            onMessage?.invoke("No location fix yet — set a location or move near a window")
            return false
        }
        onFix(fix)
        return true
    }

    fun toggleTamper() {
        if (tamperArmed) disarmTamper() else armTamper()
    }

    fun armTamper() {
        if (boundBatchId == null) {
            onMessage?.invoke("Bind a batch first")
            return
        }
        tamper.arm()
        tamperArmed = true
        Log.i(TAG, "box: tamper detection armed (${tamper.sensorSummary()})")
        notifyChange()
    }

    fun disarmTamper() {
        if (!tamperArmed) return
        tamper.disarm()
        tamperArmed = false
        Log.i(TAG, "box: tamper detection disarmed")
        notifyChange()
    }

    fun sensorSummary(): String = tamper.sensorSummary()

    fun setGeofenceHere(): Boolean {
        if (boundBatchId == null) {
            onMessage?.invoke("Bind a batch first")
            return false
        }
        val fix = gps.lastKnown()
        if (fix == null) {
            onMessage?.invoke("No location fix yet — set a location or move near a window")
            return false
        }
        geofenceLat = fix.latitude
        geofenceLon = fix.longitude
        geofenceRadiusM = TamperRules.DEFAULT_GEOFENCE_RADIUS_M
        geofenceBreached = false
        Log.i(
            TAG,
            "box: geofence ${geofenceRadiusM.toInt()} m at ${GpsEvents.format(fix.latitude)}," +
                GpsEvents.format(fix.longitude),
        )
        notifyChange()
        onMessage?.invoke("Geofence set: ${geofenceRadiusM.toInt()} m around this fix")
        return true
    }

    fun simulateTamper(kind: String) {
        onTamper(kind, "demo trigger")
    }

    fun shutdown() {
        stopGps()
        disarmTamper()
    }

    private fun onFix(location: Location) {
        val batchId = boundBatchId ?: return
        val accuracy = if (location.hasAccuracy()) {
            location.accuracy.roundToInt().coerceAtLeast(0)
        } else {
            0
        }
        val event = runCatching {
            events.appendGps(
                batchId = batchId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = accuracy,
                baseHead = ledgerHead,
            )
        }.getOrElse { error ->
            Log.w(TAG, "box: gps event failed: ${error.message}")
            onMessage?.invoke("Could not sign GPS event: ${error.message}")
            return
        }
        Log.i(
            TAG,
            "box: GPS ${event.lat},${event.lon} ±${accuracy}m provider=${location.provider} " +
                "hash=${short(event.hash)}",
        )
        checkGeofence(location)
        notifyChange()
    }

    private fun onTamper(kind: String, detail: String) {
        val batchId = boundBatchId ?: run {
            onMessage?.invoke("Bind a batch first")
            return
        }
        val event = runCatching {
            events.appendTamper(batchId, kind, detail, ledgerHead)
        }.getOrElse { error ->
            Log.w(TAG, "box: tamper event failed: ${error.message}")
            onMessage?.invoke("Could not sign tamper event: ${error.message}")
            return
        }
        Log.i(TAG, "box: TAMPER $kind ($detail) hash=${short(event.hash)}")
        notifyChange()
        onMessage?.invoke("TAMPER logged: $kind")
    }

    private fun checkGeofence(location: Location) {
        if (!tamperArmed) return
        val anchorLat = geofenceLat ?: return
        val anchorLon = geofenceLon ?: return
        val distance = TamperRules.distanceMeters(
            anchorLat,
            anchorLon,
            location.latitude,
            location.longitude,
        )
        if (distance > geofenceRadiusM) {
            if (!geofenceBreached) {
                geofenceBreached = true
                onTamper(
                    "geofence",
                    "${distance.toInt()} m outside ${geofenceRadiusM.toInt()} m fence",
                )
            }
        } else {
            geofenceBreached = false
        }
    }

    private fun notifyChange() {
        onChange?.invoke()
    }

    private fun short(hash: String): String =
        if (hash.isEmpty()) "(none)" else "${hash.take(12)}…"

    companion object {
        const val TAG = "AgriChain"
    }
}

/** One engine per process; the activity and the foreground service share it. */
object BoxRuntime {

    @Volatile
    private var engine: BoxEngine? = null

    fun get(context: Context): BoxEngine =
        engine ?: synchronized(this) {
            engine ?: BoxEngine(context.applicationContext).also { engine = it }
        }
}
