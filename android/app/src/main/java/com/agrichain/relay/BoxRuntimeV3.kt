package com.agrichain.relay

import android.content.Context
import android.location.Location
import android.util.Log
import com.agrichain.box.GpsRecorder
import com.agrichain.box.TamperDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * v3 transport box: GPS stays local, while TAMPER_ALERT is signed and queued in
 * the relay. The box never puts GPS traces on LoRa or SMS; an alert carries the
 * last fix so the ledger can show where the box was when it triggered.
 */
object BoxRuntimeV3 {

    private const val TAG = "AgriMesh"
    private const val PREFS = "agrichain_v3_box"
    private const val KEY_BATCH = "batchId"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAT = "lastLat"
    private const val KEY_LON = "lastLon"

    private data class Alert(
        val batchId: String,
        val kind: Int,
        val lat: Int,
        val lon: Int,
        val detectedTs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("batchId", batchId)
            .put("kind", kind)
            .put("lat", lat)
            .put("lon", lon)
            .put("detectedTs", detectedTs)

        companion object {
            fun fromJson(json: JSONObject): Alert = Alert(
                batchId = json.getString("batchId"),
                kind = json.optInt("kind", 1),
                lat = json.optInt("lat", 0),
                lon = json.optInt("lon", 0),
                detectedTs = json.optLong("detectedTs", 0),
            )
        }
    }

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var ticker: Job? = null
    private var gps: GpsRecorder? = null
    private var tamper: TamperDetector? = null
    private val pending = ArrayDeque<Alert>()
    private val recent = ArrayDeque<String>()

    @Volatile
    var boundBatchId: String? = null
        private set

    @Volatile
    var lastLat: Double? = null
        private set

    @Volatile
    var lastLon: Double? = null
        private set

    @Volatile
    var logging: Boolean = false
        private set

    @Volatile
    var armed: Boolean = false
        private set

    var onChange: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        boundBatchId = prefs.getString(KEY_BATCH, null)?.takeIf { it.length == 24 }
        lastLat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull()
        lastLon = prefs.getString(KEY_LON, null)?.toDoubleOrNull()
        loadPending(prefs)
        gps = GpsRecorder(
            app,
            onFix = { location -> onFix(location) },
            onProviderDisabled = { provider ->
                if (armed) sendTamper("gps_off", "provider disabled: $provider")
            },
        )
        tamper = TamperDetector(app) { kind, detail -> sendTamper(kind, detail) }
        ticker = scope.launch {
            while (true) {
                runCatching { flush() }
                delay(5_000)
            }
        }
        Log.i(TAG, "box: runtime ready batch=$boundBatchId")
    }

    fun bind(batchId: String) {
        require(batchId.length == 24) { "batch id must be 24 hex characters" }
        boundBatchId = batchId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BATCH, batchId).apply()
        Log.i(TAG, "box: bound batch $batchId")
        onChange?.invoke()
    }

    fun startGps(): Boolean {
        val recorder = gps ?: return false
        if (logging) return true
        logging = recorder.start()
        Log.i(TAG, "box: gps logging=$logging")
        onChange?.invoke()
        return logging
    }

    fun stopGps() {
        gps?.stop()
        logging = false
        onChange?.invoke()
    }

    fun arm(): Boolean {
        val detector = tamper ?: return false
        detector.arm()
        armed = true
        Log.i(TAG, "box: tamper armed (${detector.sensorSummary()})")
        onChange?.invoke()
        return true
    }

    fun disarm() {
        tamper?.disarm()
        armed = false
        onChange?.invoke()
    }

    fun simulate(kind: String) {
        sendTamper(kind, "simulated from the box screen")
    }

    fun sendTamper(kind: String, detail: String) {
        val batchId = boundBatchId
        if (batchId == null) {
            Log.w(TAG, "box: tamper ignored, no bound batch ($kind)")
            return
        }
        val alert = Alert(
            batchId = batchId,
            kind = kindCode(kind),
            lat = ((lastLat ?: 0.0) * 1_000_000.0).roundToInt(),
            lon = ((lastLon ?: 0.0) * 1_000_000.0).roundToInt(),
            detectedTs = System.currentTimeMillis() / 1000,
        )
        synchronized(lock) {
            pending.addLast(alert)
            if (pending.size > 200) pending.removeFirst()
        }
        persistPending()
        recent.addFirst("$kind @ ${alert.lat / 1e6}, ${alert.lon / 1e6} - $detail")
        while (recent.size > 20) recent.removeLast()
        Log.i(TAG, "box: queued tamper $kind batch=${batchId.take(12)}")
        scope.launch { runCatching { flush() } }
        onChange?.invoke()
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun recentLines(): List<String> = synchronized(lock) { recent.toList() }

    fun sensorSummary(): String = tamper?.sensorSummary() ?: "not initialised"

    suspend fun flush() {
        val relay = RelayRuntime.core ?: return
        val ready = synchronized(lock) { pending.toList() }
        for (alert in ready) {
            val body = V3Body.TamperAlert(
                batchId = V3Codec.hex(alert.batchId),
                kind = alert.kind,
                lat = alert.lat,
                lon = alert.lon,
                detectedTs = alert.detectedTs,
            )
            val result = runCatching { relay.originate(V3Type.TAMPER_ALERT, body) }.getOrNull()
                ?: continue
            synchronized(lock) { pending.remove(alert) }
            persistPending()
            Log.i(TAG, "box: sent TAMPER_ALERT ${result.msgId.take(12)}")
        }
    }

    private fun onFix(location: Location) {
        lastLat = location.latitude
        lastLon = location.longitude
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAT, location.latitude.toString())
            .putString(KEY_LON, location.longitude.toString())
            .apply()
        onChange?.invoke()
    }

    private fun persistPending() {
        val array = JSONArray()
        synchronized(lock) { pending.forEach { array.put(it.toJson()) } }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PENDING, array.toString()).apply()
    }

    private fun loadPending(prefs: android.content.SharedPreferences) {
        val raw = prefs.getString(KEY_PENDING, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            synchronized(lock) {
                for (index in 0 until array.length()) {
                    pending.addLast(Alert.fromJson(array.getJSONObject(index)))
                }
            }
        }.onFailure { Log.w(TAG, "box: pending alerts unreadable: ${it.message}") }
    }

    /** Message-spec kind values used by the v3 TAMPER_ALERT body. */
    fun kindCode(kind: String): Int = when (kind) {
        "movement" -> 1
        "light" -> 2
        "proximity" -> 3
        "geofence" -> 4
        "gps_off" -> 5
        "mock_location" -> 6
        "power" -> 7
        else -> 1
    }

    fun kindName(code: Int): String = when (code) {
        1 -> "movement"
        2 -> "light"
        3 -> "proximity"
        4 -> "geofence"
        5 -> "gps_off"
        6 -> "mock_location"
        7 -> "power"
        else -> "kind $code"
    }

    val kinds: List<String> = listOf(
        "movement", "light", "proximity", "geofence", "gps_off", "mock_location", "power",
    )
}
