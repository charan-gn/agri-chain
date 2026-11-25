package com.agrichain.box

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.databinding.ActivityBoxBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.qr.BatchQrCodec
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
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toast("Denied: ${denied.joinToString()}")
        }
        if (hasLocationPermission()) {
            startLogging()
        } else {
            toast("Location permission is required to log GPS")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = BoxRuntime.get(this)
        engine.onChange = { refreshUi() }
        engine.onMessage = { message -> toast(message) }

        binding.actorId.text = engine.identity.actorId
        binding.pubKey.text = engine.identity.publicKeySpkiBase64
        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.batchIdInput.setText(engine.boundBatchId.orEmpty())
        binding.bindStatus.text = engine.boundBatchId?.let {
            "Bound from the saved session."
        } ?: "Not bound. Scan the batch QR, then bind while online."

        binding.scanButton.setOnClickListener { launchScanner() }
        binding.findLedgerButton.setOnClickListener { autoDetect() }
        binding.bindButton.setOnClickListener { bindBatch() }
        binding.registerActorButton.setOnClickListener { registerActor() }
        binding.copyKeyButton.setOnClickListener { copyPublicKey() }
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
        val head = intent.getStringExtra("ledgerHead").orEmpty()
        engine.bind(batchId, head)
        binding.batchIdInput.setText(batchId)
        binding.bindStatus.text = "Bound from the adb intent (head=${short(head)})."
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
                .setPrompt("Scan the batch QR")
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

    private fun registerActor() {
        val url = LedgerClient.normalizeBaseUrl(binding.ledgerUrlInput.text.toString())
        binding.ledgerUrlInput.setText(url)
        persistLedgerUrl(url)
        binding.registerActorButton.isEnabled = false
        binding.actorStatus.text = "Registering box actor…"

        Thread {
            val result = runCatching {
                LedgerClient.registerActor(
                    baseUrl = url,
                    actorId = engine.identity.actorId,
                    name = "Transport box",
                    role = "box",
                    pubKey = engine.identity.publicKeySpkiBase64,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.registerActorButton.isEnabled = true
                result.onSuccess {
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
        persistLedgerUrl(url)
        binding.bindButton.isEnabled = false
        binding.bindStatus.text = "Checking ledger head…"

        Thread {
            val result = runCatching { LedgerClient.fetchBatchHead(url, batchId) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.bindButton.isEnabled = true
                result.onSuccess { head -> bind(batchId, head, "online") }
                    .onFailure { error -> promptOfflineBind(batchId, error) }
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
        binding.actorStatus.text = "Searching for a ledger on USB and Wi-Fi…"
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
                    binding.actorStatus.text = "Ledger: $found"
                } else {
                    binding.actorStatus.text =
                        "No ledger found. Run ./tools/ledger.sh (Wi-Fi) or ./tools/phone.sh (USB)."
                }
            }
        }
    }

    private fun promptOfflineBind(batchId: String, error: Throwable) {
        if (engine.logging) stopLogging()
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
                bind(batchId, engine.sessionHead(batchId), "offline")
            }
            .setNegativeButton("Cancel") { _, _ -> refreshUi() }
            .show()
    }

    private fun bind(batchId: String, head: String, mode: String) {
        engine.bind(batchId, head)
        refreshUi()
        binding.bindStatus.text = "Bound ($mode). Ledger head: ${short(head)}"
        toast("Bound to batch ${batchId.take(8)}…")
    }

    private fun ensurePermissionAndStart() {
        if (engine.boundBatchId == null) {
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
        if (engine.boundBatchId == null) return
        runCatching { BoxService.start(this) }
            .onFailure { toast("Could not start the box service: ${it.message}") }
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
            toast("Bind a batch first")
            return
        }
        if (!hasLocationPermission()) {
            toast("Location permission is required to run the box service")
            return
        }
        engine.armTamper()
        if (!BoxService.running) {
            runCatching { BoxService.start(this) }
                .onFailure { toast("Could not start the box service: ${it.message}") }
        }
        refreshUi()
    }

    private fun logCurrentFix() {
        if (engine.boundBatchId == null) {
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
        engine.logCurrentFix()
    }

    private fun setGeofenceHere() {
        if (engine.boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        if (!hasLocationPermission()) {
            toast("Location permission is required for the geofence")
            return
        }
        engine.setGeofenceHere()
    }

    private fun showSimulateTamper() {
        if (engine.boundBatchId == null) {
            toast("Bind a batch first")
            return
        }
        val kinds = arrayOf("movement", "light", "proximity", "geofence")
        AlertDialog.Builder(this)
            .setTitle("Simulate tamper")
            .setItems(kinds) { _, which -> engine.simulateTamper(kinds[which]) }
            .show()
    }

    private fun refreshUi() {
        val batchId = engine.boundBatchId
        binding.toggleButton.isEnabled = batchId != null
        binding.fixNowButton.isEnabled = batchId != null
        binding.toggleButton.text = if (engine.logging) "Stop logging" else "Start logging"
        binding.tamperToggleButton.isEnabled = batchId != null
        binding.setGeofenceButton.isEnabled = batchId != null
        binding.simulateTamperButton.isEnabled = batchId != null
        binding.tamperToggleButton.text =
            if (engine.tamperArmed) "Disarm tamper detection" else "Arm tamper detection"

        if (batchId == null) {
            binding.bindStatus.text = "Not bound. Scan the batch QR, then bind while online."
            binding.chainStatus.text = ""
            binding.fixStatus.text = "No batch bound."
            binding.tamperStatus.text = "Not bound. Arm tamper detection after binding a batch."
            binding.eventLog.text = ""
            return
        }

        val localHead = engine.events.head(batchId, engine.ledgerHead)
        val batchEvents = engine.events.events(batchId)
        val gpsEvents = batchEvents.filter { it.type == "GPS" }
        val tamperEvents = batchEvents.filter { it.type == "TAMPER" }
        val lastGps = gpsEvents.lastOrNull()

        binding.bindStatus.text = "Bound: $batchId"
        binding.chainStatus.text =
            "ledger head: ${short(engine.ledgerHead)}\nlocal head:  ${short(localHead)}"
        binding.fixStatus.text = if (lastGps == null) {
            "No GPS fixes yet."
        } else {
            "GPS fixes: ${gpsEvents.size}\nLast: ${lastGps.lat}, ${lastGps.lon} " +
                "(±${lastGps.data["accuracyM"]} m)"
        }

        val fenceLat = engine.geofenceLat
        val fenceLon = engine.geofenceLon
        binding.tamperStatus.text = buildString {
            append("Sensors: ${engine.sensorSummary()}")
            append(if (engine.tamperArmed) " · armed" else " · disarmed")
            if (fenceLat != null && fenceLon != null) {
                append("\nGeofence: ${engine.geofenceRadiusM.toInt()} m around ")
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
        private const val TAG = "AgriChain"
        private val UUID_V4 = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
        )
    }
}
