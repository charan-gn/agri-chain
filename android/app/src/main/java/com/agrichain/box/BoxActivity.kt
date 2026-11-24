package com.agrichain.box

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.core.ActorIdentity
import com.agrichain.databinding.ActivityBoxBinding
import com.agrichain.qr.BatchQrCodec
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class BoxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBoxBinding
    private lateinit var session: BoxSession
    private lateinit var identity: ActorIdentity
    private lateinit var events: BoxEventLog
    private lateinit var gps: GpsRecorder
    private lateinit var tamper: TamperDetector
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var boundBatchId: String? = null
    private var ledgerHead: String = ""
    private var logging = false
    private var tamperArmed = false
    private var geofenceLat: Double? = null
    private var geofenceLon: Double? = null
    private var geofenceRadiusM = TamperRules.DEFAULT_GEOFENCE_RADIUS_M
    private var geofenceBreached = false

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val batchId = BatchQrCodec.parse(contents)
        if (batchId == null) {
            toast("Not an AgriChain batch QR")
        } else {
            binding.batchIdInput.setText(batchId)
            binding.bindStatus.text = "Scanned. Bind to fetch the ledger head."
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            startLogging()
        } else {
            toast("Location permission is required to log GPS")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityBoxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = BoxSession(this)
        identity = ActorIdentity.loadOrCreate(this, "box")
        events = BoxEventLog(this, identity)
        gps = GpsRecorder(this) { location -> onFix(location) }
        tamper = TamperDetector(this) { kind, detail -> onTamper(kind, detail) }

        binding.actorId.text = identity.actorId
        binding.pubKey.text = identity.publicKeySpkiBase64
        binding.ledgerUrlInput.setText(session.ledgerUrl())
        binding.batchIdInput.setText(session.batchId().orEmpty())
        session.batchId()?.let { batchId ->
            boundBatchId = batchId
            ledgerHead = session.ledgerHead(batchId)
        }

        binding.scanButton.setOnClickListener { launchScanner() }
        binding.bindButton.setOnClickListener { bindBatch() }
        binding.registerActorButton.setOnClickListener { registerActor() }
        binding.copyKeyButton.setOnClickListener { copyPublicKey() }
        binding.toggleButton.setOnClickListener {
            if (logging) stopLogging() else ensurePermissionAndStart()
        }
        binding.fixNowButton.setOnClickListener { logCurrentFix() }
        binding.tamperToggleButton.setOnClickListener { toggleTamper() }
        binding.setGeofenceButton.setOnClickListener { setGeofenceHere() }
        binding.simulateTamperButton.setOnClickListener { showSimulateTamper() }

        refreshUi()
    }

    override fun onDestroy() {
        gps.stop()
        tamper.disarm()
        super.onDestroy()
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the batch QR")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun copyPublicKey() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("AgriChain box public key", identity.publicKeySpkiBase64)
        )
        toast("Public key copied")
    }

    private fun registerActor() {
        val url = LedgerClient.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        binding.registerActorButton.isEnabled = false
        binding.actorStatus.text = "Registering box actor…"

        Thread {
            val result = runCatching {
                LedgerClient.registerActor(
                    baseUrl = url,
                    actorId = identity.actorId,
                    name = "Transport box",
                    role = "box",
                    pubKey = identity.publicKeySpkiBase64,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.registerActorButton.isEnabled = true
                result.onSuccess {
                    session.saveLedgerUrl(url)
                    binding.actorStatus.text = "Box actor registered on the ledger."
                }.onFailure { error ->
                    binding.actorStatus.text = "Registration failed: ${error.message}"
                }
            }
        }.start()
    }

    private fun bindBatch() {
        val batchId = binding.batchIdInput.text.toString().trim()
        if (!isUuidV4(batchId)) {
            binding.batchIdInput.error = "Enter a UUIDv4 batch ID"
            return
        }
        val url = LedgerClient.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        binding.bindButton.isEnabled = false
        binding.bindStatus.text = "Checking ledger head…"

        Thread {
            val result = runCatching { LedgerClient.fetchBatchHead(url, batchId) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.bindButton.isEnabled = true
                result.onSuccess { head -> bind(batchId, head, url, "online") }
                    .onFailure { error -> promptOfflineBind(batchId, url, error) }
            }
        }.start()
    }

    private fun promptOfflineBind(batchId: String, url: String, error: Throwable) {
        if (logging) stopLogging()
        val reason = if (error is BatchNotFoundException) {
            "This batch is not on the ledger yet."
        } else {
            "Could not reach the ledger (${error.message})."
        }
        AlertDialog.Builder(this)
            .setTitle("Bind offline?")
            .setMessage(
                "$reason\n\nOffline GPS events chain from the last local head and stay signed on " +
                    "this phone until the queue syncs."
            )
            .setPositiveButton("Bind offline") { _, _ ->
                bind(batchId, session.ledgerHead(batchId), url, "offline")
            }
            .setNegativeButton("Cancel") { _, _ -> refreshUi() }
            .show()
    }

    private fun bind(batchId: String, head: String, url: String, mode: String) {
        boundBatchId = batchId
        ledgerHead = head
        session.bind(batchId, head, url)
        if (logging) {
            gps.stop()
            logging = false
        }
        if (tamperArmed) {
            tamper.disarm()
            tamperArmed = false
        }
        geofenceLat = null
        geofenceLon = null
        geofenceBreached = false
        refreshUi()
        binding.bindStatus.text = "Bound ($mode). Ledger head: ${short(head)}"
        toast("Bound to batch ${batchId.take(8)}…")
    }

    private fun ensurePermissionAndStart() {
        if (boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        if (hasLocationPermission()) {
            startLogging()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun startLogging() {
        if (boundBatchId == null) return
        logging = gps.start()
        if (!logging) {
            toast("Enable a location provider (GPS) on this phone")
        }
        refreshUi()
    }

    private fun stopLogging() {
        gps.stop()
        logging = false
        refreshUi()
    }

    private fun logCurrentFix() {
        if (boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        if (!hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
            return
        }
        val fix = gps.lastKnown()
        if (fix == null) {
            toast("No location fix yet — set a location or move near a window")
        } else {
            onFix(fix)
        }
    }

    private fun onFix(location: Location) {
        val batchId = boundBatchId ?: return
        val accuracy = if (location.hasAccuracy()) {
            location.accuracy.roundToInt().coerceAtLeast(0)
        } else {
            0
        }
        runCatching {
            events.appendGps(
                batchId = batchId,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyM = accuracy,
                baseHead = ledgerHead,
            )
        }.onSuccess {
            checkGeofence(location)
            refreshUi()
        }.onFailure {
            toast("Could not sign GPS event: ${it.message}")
        }
    }

    private fun toggleTamper() {
        if (boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        if (tamperArmed) {
            tamper.disarm()
            tamperArmed = false
        } else {
            tamper.arm()
            tamperArmed = true
        }
        refreshUi()
    }

    private fun setGeofenceHere() {
        if (boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        if (!hasLocationPermission()) {
            toast("Location permission is required for the geofence")
            return
        }
        val fix = gps.lastKnown()
        if (fix == null) {
            toast("No location fix yet — set a location or move near a window")
            return
        }
        geofenceLat = fix.latitude
        geofenceLon = fix.longitude
        geofenceRadiusM = TamperRules.DEFAULT_GEOFENCE_RADIUS_M
        geofenceBreached = false
        refreshUi()
        toast("Geofence set: ${geofenceRadiusM.toInt()} m around this fix")
    }

    private fun showSimulateTamper() {
        if (boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        val kinds = arrayOf("movement", "light", "proximity", "geofence")
        AlertDialog.Builder(this)
            .setTitle("Simulate tamper")
            .setItems(kinds) { _, which -> onTamper(kinds[which], "demo trigger") }
            .show()
    }

    private fun onTamper(kind: String, detail: String) {
        val batchId = boundBatchId ?: run {
            toast("Bind a batch first")
            return
        }
        runCatching {
            events.appendTamper(batchId, kind, detail, ledgerHead)
        }.onSuccess {
            refreshUi()
            toast("TAMPER logged: $kind")
        }.onFailure {
            toast("Could not sign tamper event: ${it.message}")
        }
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

    private fun refreshUi() {
        val batchId = boundBatchId
        binding.toggleButton.isEnabled = batchId != null
        binding.fixNowButton.isEnabled = batchId != null
        binding.toggleButton.text = if (logging) "Stop logging" else "Start logging"
        binding.tamperToggleButton.isEnabled = batchId != null
        binding.setGeofenceButton.isEnabled = batchId != null
        binding.simulateTamperButton.isEnabled = batchId != null
        binding.tamperToggleButton.text =
            if (tamperArmed) "Disarm tamper detection" else "Arm tamper detection"

        if (batchId == null) {
            binding.bindStatus.text = "Not bound. Scan the batch QR, then bind while online."
            binding.chainStatus.text = ""
            binding.fixStatus.text = "No batch bound."
            binding.tamperStatus.text = "Not bound. Arm tamper detection after binding a batch."
            binding.eventLog.text = ""
            return
        }

        val localHead = events.head(batchId, ledgerHead)
        val batchEvents = events.events(batchId)
        val gpsEvents = batchEvents.filter { it.type == "GPS" }
        val tamperEvents = batchEvents.filter { it.type == "TAMPER" }
        val lastGps = gpsEvents.lastOrNull()

        binding.bindStatus.text = "Bound: $batchId"
        binding.chainStatus.text =
            "ledger head: ${short(ledgerHead)}\nlocal head:  ${short(localHead)}"
        binding.fixStatus.text = if (lastGps == null) {
            "No GPS fixes yet."
        } else {
            "GPS fixes: ${gpsEvents.size}\nLast: ${lastGps.lat}, ${lastGps.lon} " +
                "(±${lastGps.data["accuracyM"]} m)"
        }

        val fenceLat = geofenceLat
        val fenceLon = geofenceLon
        binding.tamperStatus.text = buildString {
            append("Sensors: ${tamper.sensorSummary()}")
            append(if (tamperArmed) " · armed" else " · disarmed")
            if (fenceLat != null && fenceLon != null) {
                append("\nGeofence: ${geofenceRadiusM.toInt()} m around ")
                append(GpsEvents.format(fenceLat))
                append(", ")
                append(GpsEvents.format(fenceLon))
            } else {
                append("\nGeofence: not set (uses the current fix when you tap the button)")
            }
            append("\nTamper events: ${tamperEvents.size}")
        }

        binding.eventLog.text = batchEvents.takeLast(12).joinToString("\n") { event ->
            val time = timeFormat.format(Date(event.ts))
            val where = if (event.type == "TAMPER") {
                val kind = event.data["kind"] ?: "?"
                val detail = event.data["detail"]
                if (detail == null) kind.toString() else "$kind ($detail)"
            } else {
                "${event.lat ?: "—"},${event.lon ?: "—"}"
            }
            "$time ${event.type.padEnd(8)} $where  #${event.hash.take(8)}"
        }.ifEmpty { "No events yet." }
    }

    private fun short(hash: String): String =
        if (hash.isEmpty()) "(none)" else "${hash.take(12)}…"

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    private fun isUuidV4(value: String): Boolean =
        UUID_V4.matches(value) && runCatching { UUID.fromString(value) }.isSuccess

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val UUID_V4 = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
    }
}
