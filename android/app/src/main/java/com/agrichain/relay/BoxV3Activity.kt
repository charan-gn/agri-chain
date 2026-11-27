package com.agrichain.relay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.databinding.ActivityBoxV3Binding

/**
 * v3 transport box screen. GPS fixes stay on the phone; every tamper event is
 * signed as TAMPER_ALERT and queued in the relay so it can leave over any radio.
 */
class BoxV3Activity : AppCompatActivity() {

    private lateinit var binding: ActivityBoxV3Binding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startGps()
        } else {
            binding.statusText.text = "Location permission is needed to record fixes"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBoxV3Binding.inflate(layoutInflater)
        setContentView(binding.root)

        BoxRuntimeV3.init(this)
        BoxRuntimeV3.onChange = { runOnUiThread { render() } }

        binding.backButton.setOnClickListener { finish() }
        binding.kindSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            BoxRuntimeV3.kinds,
        )
        binding.boxIdText.text = runCatching {
            V3Identity.loadOrCreate(this).actorHex
        }.getOrElse { "identity unavailable: ${it.message}" }

        binding.bindButton.setOnClickListener { bindBatch() }
        binding.relayButton.setOnClickListener {
            RelayRuntime.startService(this)
            binding.statusText.text = "Starting the v3 relay..."
        }
        binding.serviceButton.setOnClickListener {
            RelayRuntime.startService(this)
            binding.statusText.text = "Relay runs in the background while the screen is off"
        }
        binding.gpsButton.setOnClickListener {
            if (BoxRuntimeV3.logging) {
                BoxRuntimeV3.stopGps()
            } else {
                ensureLocationAndStart()
            }
        }
        binding.armButton.setOnClickListener {
            if (BoxRuntimeV3.armed) BoxRuntimeV3.disarm() else BoxRuntimeV3.arm()
            render()
        }
        binding.simulateButton.setOnClickListener {
            val kind = BoxRuntimeV3.kinds[binding.kindSpinner.selectedItemPosition]
            BoxRuntimeV3.simulate(kind)
            render()
        }

        intent.getStringExtra(EXTRA_BATCH_ID)?.takeIf { it.length == 24 }?.let { batchId ->
            binding.batchIdInput.setText(batchId)
            BoxRuntimeV3.bind(batchId)
        }
        binding.batchIdInput.setText(BoxRuntimeV3.boundBatchId.orEmpty())
        render()
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            binding.root.post { ensureLocationAndStart() }
        }
    }

    override fun onDestroy() {
        BoxRuntimeV3.onChange = null
        super.onDestroy()
    }

    private fun bindBatch() {
        val batchId = binding.batchIdInput.text.toString().trim()
        if (batchId.length != 24 || runCatching { V3Codec.hex(batchId) }.isFailure) {
            binding.statusText.text = "Batch id must be 24 hex characters"
            return
        }
        BoxRuntimeV3.bind(batchId)
        binding.statusText.text = "Box bound to batch ${batchId.take(12)}"
        render()
    }

    private fun ensureLocationAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startGps()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    private fun startGps() {
        val started = BoxRuntimeV3.startGps()
        binding.statusText.text = if (started) {
            "Recording GPS fixes locally"
        } else {
            "No location provider is enabled on this phone"
        }
        render()
    }

    private fun render() {
        val batch = BoxRuntimeV3.boundBatchId
        binding.statusText.text = buildString {
            append("Batch: ").append(batch?.take(12) ?: "not bound")
            append("  |  GPS: ").append(if (BoxRuntimeV3.logging) "on" else "off")
            append("  |  sensors: ").append(if (BoxRuntimeV3.armed) "armed" else "disarmed")
            append("  |  alerts queued: ").append(BoxRuntimeV3.pendingCount())
        }
        binding.gpsButton.text = if (BoxRuntimeV3.logging) "Stop GPS" else "Start GPS"
        binding.armButton.text = if (BoxRuntimeV3.armed) "Disarm sensors" else "Arm sensors"
        val lat = BoxRuntimeV3.lastLat
        val lon = BoxRuntimeV3.lastLon
        binding.fixText.text = if (lat != null && lon != null) {
            "Last fix: %.6f, %.6f".format(lat, lon)
        } else {
            "Last fix: none yet"
        }
        binding.alertLog.text = BoxRuntimeV3.recentLines()
            .joinToString("\n")
            .ifEmpty { "No tamper alerts on this phone yet." }
    }

    companion object {
        const val EXTRA_BATCH_ID = "batchId"
        const val EXTRA_AUTO_START = "autoStart"
    }
}
