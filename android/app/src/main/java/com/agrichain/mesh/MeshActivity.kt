package com.agrichain.mesh

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.R
import com.agrichain.databinding.ActivityMeshBinding
import com.agrichain.ui.UiHelpers

class MeshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshBinding
    private lateinit var runtime: MeshRuntime
    private lateinit var nearby: NearbyMeshTransport
    private var running = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            log("Denied: ${denied.joinToString()}")
            Log.w(TAG, "mesh: permissions denied ${denied.joinToString()}")
            return@registerForActivityResult
        }
        startMesh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeshBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.advancedHeader, binding.advancedBody, binding.advancedChevron)

        runtime = MeshRuntime.get(this)
        nearby = NearbyMeshTransport(this, runtime.selfId)
        runtime.logSink = { line -> ui { log(line) } }

        binding.senderId.text = "Phone ID: ${runtime.selfId}"
        binding.gatewayNumberInput.setText(SmsSettings.gatewayNumber(this).orEmpty())
        binding.startButton.setOnClickListener { ensurePermissions() }
        binding.stopButton.setOnClickListener { stopMesh() }
        binding.saveGatewayButton.setOnClickListener { saveGateway() }
        binding.broadcastButton.setOnClickListener { broadcastPending() }
        binding.syncButton.setOnClickListener { syncNow() }
        binding.copyButton.setOnClickListener { copyPending() }
        binding.relayButton.setOnClickListener { relayPasted() }
        binding.stopButton.isEnabled = false
        updateTransports()
        refreshPending()
        if (intent?.getBooleanExtra("autoStart", false) == true) {
            binding.root.post { ensurePermissions() }
        }
    }

    override fun onResume() {
        super.onResume()
        updateTransports()
        refreshPending()
    }

    override fun onDestroy() {
        if (running) runtime.unregister(nearby)
        runtime.logSink = null
        super.onDestroy()
    }

    private fun ensurePermissions() {
        val missing = meshPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startMesh() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMesh() {
        if (running) return
        runtime.register(nearby)
        running = true
        Log.i(TAG, "mesh: starting Nearby advertising and discovery as ${runtime.selfId}")
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.status.text = "Sharing is ON ✓"
        binding.status.setTextColor(getColor(R.color.status_ok))
        updateTransports()
        Thread {
            val result = runtime.pump()
            ui {
                log("startup sync: ${result.uploaded} uploaded, ${result.pending} pending")
                updateTransports()
                refreshPending()
            }
        }.start()
    }

    private fun stopMesh() {
        if (!running) return
        runtime.unregister(nearby)
        running = false
        Log.i(TAG, "mesh: stopped")
        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.status.text = "Sharing is OFF"
        binding.status.setTextColor(getColor(R.color.status_neutral))
        updateTransports()
    }

    private fun saveGateway() {
        try {
            val saved = SmsSettings.setGatewayNumber(
                this,
                binding.gatewayNumberInput.text.toString(),
            )
            binding.gatewayNumberInput.setText(saved.orEmpty())
            log(if (saved == null) "Helper number cleared" else "Helper: $saved")
            updateTransports()
        } catch (error: IllegalArgumentException) {
            log(error.message ?: "Invalid phone number")
        }
    }

    private fun broadcastPending() {
        val sent = runtime.broadcastPending()
        log("Sent waiting items to $sent link(s)")
        refreshPending()
    }

    private fun syncNow() {
        binding.syncButton.isEnabled = false
        Thread {
            val result = runtime.pump()
            ui {
                log(
                    "Sync: ${result.uploaded} uploaded, ${result.pending} pending" +
                        (result.error?.let { " · $it" } ?: "")
                )
                binding.syncButton.isEnabled = true
                updateTransports()
                refreshPending()
            }
        }.start()
    }

    private fun copyPending() {
        val pending = runtime.pending()
        if (pending.isEmpty()) {
            log("No waiting items to copy")
            return
        }
        val text = MeshEnvelope.events(runtime.selfId, pending).encode()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AgriChain envelope", text))
        log("Copied ${pending.size} waiting item(s)")
    }

    private fun relayPasted() {
        val raw = binding.envelopeInput.text.toString().trim()
        val (status, result) = runtime.acceptManualInput(raw)
        when {
            status == "invalid" -> log("Not a valid envelope, AC1| SMS or MC1| LoRa frame")
            result == null -> {
                log("Buffered chunk ($status) — paste the remaining parts")
                binding.envelopeInput.text?.clear()
            }
            else -> {
                log("Received ($status): ${result.freshEvents} new, ${result.uploaded} uploaded")
                binding.envelopeInput.text?.clear()
                refreshPending()
            }
        }
    }

    private fun updateTransports() {
        val smsReady = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED &&
            SmsSettings.gatewayNumber(this) != null
        val loraState = when {
            runtime.meshtastic.available() -> "● ON"
            runtime.meshtastic.isInstalled() -> "◐ Ready"
            else -> "○ Off"
        }
        val internetOn = runtime.ledgerReachable()
        binding.transports.text =
            "Nearby ${if (running) "● ON" else "○ OFF"}  ·  " +
                "SMS ${if (smsReady) "● Ready" else "○ Off"}  ·  " +
                "LoRa $loraState  ·  " +
                "Net ${if (internetOn) "● ON" else "○ Off"}"
    }

    private fun refreshPending() {
        binding.status.post {
            val count = runtime.pendingCount()
            binding.status.text = if (count == 0) {
                "Nothing waiting to send ✓"
            } else {
                "$count item(s) waiting to send"
            }
            binding.status.setTextColor(
                getColor(if (count == 0) R.color.status_ok else R.color.status_neutral)
            )
        }
    }

    private fun meshPermissions(): List<String> = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun log(line: String) {
        binding.log.append(if (binding.log.text.isEmpty()) line else "\n$line")
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }

    companion object {
        private const val TAG = "AgriChain"
    }
}
