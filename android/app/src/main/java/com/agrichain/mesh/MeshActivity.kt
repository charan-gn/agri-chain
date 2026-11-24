package com.agrichain.mesh

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agrichain.R
import com.agrichain.data.SyncStore
import com.agrichain.databinding.ActivityMeshBinding
import java.util.UUID

class MeshActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMeshBinding
    private lateinit var transport: MeshTransport
    private lateinit var relay: MeshRelay
    private var running = false
    private val senderId = "phone-${UUID.randomUUID().toString().take(8)}"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startMesh() else log("Mesh permissions denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMeshBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transport = NearbyMeshTransport(this)
        relay = MeshRelay(SyncEventSink(this))
        binding.senderId.text = senderId
        binding.startButton.setOnClickListener { ensurePermissions() }
        binding.stopButton.setOnClickListener { stopMesh() }
        binding.broadcastButton.setOnClickListener { broadcastPending() }
        binding.copyButton.setOnClickListener { copyPending() }
        binding.relayButton.setOnClickListener { relayPasted() }
        binding.stopButton.isEnabled = false
    }

    override fun onDestroy() {
        if (running) transport.stop()
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
        transport.start(
            senderId,
            onEnvelope = { envelope -> onEnvelope(envelope) },
            onLog = { line -> ui { log(line) } },
        )
        running = true
        binding.startButton.isEnabled = false
        binding.stopButton.isEnabled = true
        binding.status.text = "Mesh running as $senderId"
        binding.status.setTextColor(getColor(R.color.status_ok))
    }

    private fun stopMesh() {
        if (!running) return
        transport.stop()
        running = false
        binding.startButton.isEnabled = true
        binding.stopButton.isEnabled = false
        binding.status.text = "Mesh stopped"
        binding.status.setTextColor(getColor(R.color.status_neutral))
    }

    private fun onEnvelope(envelope: MeshEnvelope) {
        val result = relay.accept(envelope)
        ui {
            log(
                "from ${envelope.senderId}: ${result.accepted} new, " +
                    "${result.duplicates} duplicate"
            )
        }
        if (result.accepted > 0) {
            val peers = transport.broadcast(envelope)
            if (peers > 0) ui { log("relayed to $peers peer(s)") }
        }
    }

    private fun pendingEnvelope(): MeshEnvelope? {
        val pending = SyncStore(this).pending()
        if (pending.isEmpty()) return null
        return MeshEnvelope(
            MeshEnvelope.VERSION,
            MeshEnvelope.KIND_EVENTS,
            senderId,
            System.currentTimeMillis(),
            pending,
        )
    }

    private fun broadcastPending() {
        val envelope = pendingEnvelope()
        if (envelope == null) {
            log("No queued events to send")
            return
        }
        val peers = transport.broadcast(envelope)
        log("Sent ${envelope.events.size} event(s) to $peers peer(s)")
    }

    private fun copyPending() {
        val envelope = pendingEnvelope()
        if (envelope == null) {
            log("No queued events to copy")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText("AgriChain mesh envelope", envelope.encode())
        )
        log("Copied envelope with ${envelope.events.size} event(s)")
    }

    private fun relayPasted() {
        val envelope = MeshEnvelope.parse(binding.envelopeInput.text.toString().trim())
        if (envelope == null) {
            log("Not a valid v1 EVENTS envelope")
            return
        }
        onEnvelope(envelope)
        binding.envelopeInput.text?.clear()
    }

    private fun meshPermissions(): List<String> = buildList {
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
}
