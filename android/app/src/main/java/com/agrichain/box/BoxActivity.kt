package com.agrichain.box

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.R
import com.agrichain.databinding.ActivityBoxBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.qr.BatchQrCodec
import com.agrichain.ui.UiHelpers
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BoxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBoxBinding
    private lateinit var engine: BoxEngine
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var discovering = false

    @Volatile
    private var authorized: Boolean? = null

    @Volatile
    private var ownerId: String? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        val batchId = BatchQrCodec.parse(contents)
        if (batchId == null) {
            toast("Not an AgriChain crop QR")
        } else {
            binding.batchIdInput.setText(batchId)
            binding.bindStatus.text = "QR scanned! Tap Connect to lock this box to the crop."
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toast("Denied: ${denied.joinToString()}")
        }
        if (hasLocationPermission()) {
            startLogging()
        } else {
            toast("Location permission is needed to track GPS")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.setupHeader, binding.setupBody, binding.setupChevron)

        engine = BoxRuntime.get(this)
        engine.onChange = { refreshUi() }
        engine.onMessage = { message -> toast(message) }

        binding.actorId.text = "Box ID: ${engine.identity.actorId}"
        binding.pubKey.text = "Key: ${engine.identity.publicKeySpkiBase64}"
        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.batchIdInput.setText(engine.boundBatchId.orEmpty())
        binding.bindStatus.text = engine.boundBatchId?.let {
            "Connected from last session."
        } ?: "Not connected yet. Scan the crop QR above."

        binding.scanButton.setOnClickListener { launchScanner() }
        binding.findLedgerButton.setOnClickListener { autoDetect() }
        binding.bindButton.setOnClickListener { bindBatch() }
        binding.registerActorButton.setOnClickListener { registerActor() }
        binding.copyKeyButton.setOnClickListener { copyPublicKey() }
        binding.copyBoxIdButton.setOnClickListener { copyBoxId() }
        binding.toggleButton.setOnClickListener {
            if (engine.logging) stopLogging() else ensurePermissionAndStart()
        }
        binding.fixNowButton.setOnClickListener { logCurrentFix() }
        binding.tamperToggleButton.setOnClickListener { toggleTamper() }
        binding.setGeofenceButton.setOnClickListener { setGeofenceHere() }
        binding.simulateTamperButton.setOnClickListener { showSimulateTamper() }

        refreshUi()
        handleDevIntent()
    }

    /** adb hook reached through MainActivity --es openBox <uuid> --ez autoStart true. */
    private fun handleDevIntent() {
        val batchId = intent?.getStringExtra("batchId")?.takeIf(::isUuidV4) ?: return
        val mainHead = intent.getStringExtra("ledgerMainHead").orEmpty()
        val traceHead = intent.getStringExtra("ledgerTraceHead").orEmpty()
        engine.bind(batchId, mainHead, traceHead)
        binding.batchIdInput.setText(batchId)
        binding.bindStatus.text = "Connected from adb (main=${short(mainHead)})."
        Log.i(TAG, "box activity: bound from intent batch=$batchId autoStart=" +
            intent.getBooleanExtra("autoStart", false))
        if (intent.getBooleanExtra("autoStart", false)) {
            binding.root.post { ensurePermissionAndStart() }
        }
    }

    override fun onDestroy() {
        engine.onChange = null
        engine.onMessage = null
        // The service owns GPS and the sensors; if it is not running, do not leak them.
        if (!BoxService.running) engine.shutdown()
        super.onDestroy()
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the crop QR")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun copyPublicKey() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("AgriChain box public key", engine.identity.publicKeySpkiBase64)
        )
        toast("Public key copied")
    }

    private fun copyBoxId() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("AgriChain box ID", engine.identity.actorId)
        )
        toast("Box ID copied — paste it on the farmer's Authorize box screen")
    }

    private fun registerActor() {
        val url = LedgerClient.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        persistLedgerUrl(url)
        binding.registerActorButton.isEnabled = false
        binding.actorStatus.text = "Registering box on server…"

        Thread {
            val result = runCatching {
                LedgerClient.registerActor(
                    baseUrl = url,
                    actorId = engine.identity.actorId,
                    name = "Transport box",
                    role = "box",
                    pubKey = engine.identity.publicKeySpkiBase64,
                    adminToken = LedgerSettings.adminToken(this),
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.registerActorButton.isEnabled = true
                result.onSuccess {
                    binding.actorStatus.text = "Box registered on server ✓"
                }.onFailure { error ->
                    binding.actorStatus.text = "Registration failed: ${error.message}"
                }
            }
        }.start()
    }

    private fun bindBatch() {
        val batchId = binding.batchIdInput.text.toString().trim()
        if (!isUuidV4(batchId)) {
            binding.batchIdInput.error = "Scan a crop QR or enter a valid batch code"
            return
        }
        val url = LedgerClient.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        persistLedgerUrl(url)
        binding.bindButton.isEnabled = false
        binding.bindStatus.text = "Connecting to crop…"

        Thread {
            val result = runCatching {
                LedgerClient.fetchBatchInfo(url, batchId, engine.identity.actorId)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.bindButton.isEnabled = true
                result.onSuccess { info ->
                    bind(
                        batchId = batchId,
                        mainHead = info.mainHead,
                        traceHead = info.traceHead,
                        mode = "online",
                        authorized = info.boundBoxId == engine.identity.actorId,
                        ownerId = info.ownerId,
                    )
                }.onFailure { error -> promptOfflineBind(batchId, error) }
            }
        }.start()
    }

    private fun persistLedgerUrl(url: String) {
        runCatching { LedgerSettings.setBaseUrl(this, url) }
            .onFailure { binding.actorStatus.text = it.message }
    }

    private fun autoDetect() {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        binding.actorStatus.text = "Looking for server on USB and Wi-Fi…"
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line ->
                runOnUiThread {
                    if (discovering && !isFinishing && !isDestroyed) {
                        binding.actorStatus.text = line
                    }
                }
            },
        ) { found ->
            runOnUiThread {
                discovering = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.findLedgerButton.isEnabled = true
                if (found != null) {
                    binding.ledgerUrlInput.setText(found)
                    binding.actorStatus.text = "Connected: $found"
                } else {
                    binding.actorStatus.text =
                        "No server found. Run ./tools/ledger.sh (Wi-Fi) or ./tools/phone.sh (USB)."
                }
            }
        }
    }

    private fun promptOfflineBind(batchId: String, error: Throwable) {
        if (engine.logging) stopLogging()
        val reason = if (error is BatchNotFoundException) {
            "This crop is not on the server yet."
        } else {
            "Could not reach the server (${error.message})."
        }
        AlertDialog.Builder(this)
            .setTitle("Connect offline?")
            .setMessage(
                "$reason\n\nGPS pins and tamper alerts will stay saved safely on this phone " +
                    "until you upload later."
            )
            .setPositiveButton("Connect offline") { _, _ ->
                bind(
                    batchId = batchId,
                    mainHead = engine.sessionHead(batchId, "main"),
                    traceHead = engine.sessionHead(batchId, "trace"),
                    mode = "offline",
                    authorized = null,
                    ownerId = null,
                )
            }
            .setNegativeButton("Cancel") { _, _ -> refreshUi() }
            .show()
    }

    private fun bind(
        batchId: String,
        mainHead: String,
        traceHead: String,
        mode: String,
        authorized: Boolean?,
        ownerId: String?,
    ) {
        engine.bind(batchId, mainHead, traceHead)
        this.authorized = authorized
        this.ownerId = ownerId
        refreshUi()
        binding.bindStatus.text = when (authorized) {
            true -> "Connected ($mode) · box authorized ✓"
            false -> "Connected ($mode) · waiting for the farmer to authorize this box"
            null -> "Connected ($mode) ✓"
        }
        toast("Connected to crop ${batchId.take(8)}…")
    }

    private fun ensurePermissionAndStart() {
        if (engine.boundBatchId == null) {
            toast("Connect a crop first (Step 1)")
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
        if (engine.boundBatchId == null) return
        runCatching { BoxService.start(this) }
            .onFailure { toast("Could not start tracking: ${it.message}") }
        binding.toggleButton.postDelayed({ refreshUi() }, 400)
    }

    private fun stopLogging() {
        engine.stopGps()
        BoxService.stop(this)
        refreshUi()
    }

    private fun toggleTamper() {
        if (engine.tamperArmed) {
            engine.disarmTamper()
            return
        }
        if (engine.boundBatchId == null) {
            toast("Connect a crop first (Step 1)")
            return
        }
        if (!hasLocationPermission()) {
            toast("Location permission is needed to run the box guard")
            return
        }
        engine.armTamper()
        if (!BoxService.running) {
            runCatching { BoxService.start(this) }
                .onFailure { toast("Could not start box guard: ${it.message}") }
        }
        refreshUi()
    }

    private fun logCurrentFix() {
        if (engine.boundBatchId == null) {
            toast("Connect a crop first (Step 1)")
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
        engine.logCurrentFix()
    }

    private fun setGeofenceHere() {
        if (engine.boundBatchId == null) {
            toast("Connect a crop first (Step 1)")
            return
        }
        if (!hasLocationPermission()) {
            toast("Location permission is needed for the safe zone")
            return
        }
        engine.setGeofenceHere()
    }

    private fun showSimulateTamper() {
        if (engine.boundBatchId == null) {
            toast("Connect a crop first (Step 1)")
            return
        }
        val kinds = arrayOf("movement", "light", "proximity", "geofence", "gps_off", "mock_location", "power")
        AlertDialog.Builder(this)
            .setTitle("Test tamper alarm")
            .setItems(kinds) { _, which -> engine.simulateTamper(kinds[which]) }
            .show()
    }

    private fun refreshUi() {
        val batchId = engine.boundBatchId
        val ready = batchId != null
        binding.toggleButton.isEnabled = ready
        binding.fixNowButton.isEnabled = ready
        binding.tamperToggleButton.isEnabled = ready
        binding.setGeofenceButton.isEnabled = ready
        binding.simulateTamperButton.isEnabled = ready

        binding.toggleButton.text =
            if (engine.logging) "Stop GPS Tracking" else "Start GPS Tracking"
        binding.toggleButton.backgroundTintList = ColorStateList.valueOf(
            getColor(if (engine.logging) R.color.alert_red else R.color.brand_green)
        )

        binding.tamperToggleButton.text =
            if (engine.tamperArmed) "Guard is ON (Tap to Turn Off)" else "Turn Guard ON"
        binding.tamperToggleButton.backgroundTintList = ColorStateList.valueOf(
            getColor(if (engine.tamperArmed) R.color.brand_green else R.color.box_amber)
        )

        if (batchId == null) {
            binding.bindStatus.text = "Not connected. Scan the crop QR above."
            binding.chainStatus.text = ""
            binding.fixStatus.text = "Connect a crop in Step 1 first."
            binding.tamperStatus.text = "Connect a crop in Step 1 first."
            binding.eventLog.text = ""
            return
        }

        val localMainHead = engine.events.head(batchId, "main", engine.ledgerMainHead)
        val localTraceHead = engine.events.head(batchId, "trace", engine.ledgerTraceHead)
        val batchEvents = engine.events.events(batchId)
        val gpsEvents = batchEvents.filter { it.type == "GPS" }
        val tamperEvents = batchEvents.filter { it.type == "TAMPER" }
        val lastGps = gpsEvents.lastOrNull()

        binding.bindStatus.text = when (authorized) {
            true -> "Connected to crop ${batchId.take(8)}… · authorized ✓"
            false -> "Connected to crop ${batchId.take(8)}… · waiting for farmer BIND"
            null -> "Connected to crop: ${batchId.take(8)}…"
        }
        binding.chainStatus.text = buildString {
            append("main  ledger ${short(engine.ledgerMainHead)}")
            append(" · local ${short(localMainHead)}\n")
            append("trace ledger ${short(engine.ledgerTraceHead)}")
            append(" · local ${short(localTraceHead)}")
            if (!ownerId.isNullOrBlank()) {
                append("\nowner ${ownerId!!.take(18)}…")
            }
        }
        binding.fixStatus.text = if (lastGps == null) {
            "No location pins yet."
        } else {
            "${gpsEvents.size} location pin(s) saved · Last ±${lastGps.data["accuracyM"]} m"
        }

        val fenceLat = engine.geofenceLat
        val fenceLon = engine.geofenceLon
        binding.tamperStatus.text = buildString {
            append(if (engine.tamperArmed) "Guard is ON ✓" else "Guard is OFF")
            append(" · Sensors: ${engine.sensorSummary()}")
            if (fenceLat != null && fenceLon != null) {
                append("\nSafe zone set: ${engine.geofenceRadiusM.toInt()} m radius")
            } else {
                append("\nSafe zone not set yet")
            }
            append("\nAlarms triggered: ${tamperEvents.size}")
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
        private const val TAG = "AgriChain"
        private val UUID_V4 = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
    }
}
