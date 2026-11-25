package com.agrichain.data

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.core.ActorIdentity
import com.agrichain.databinding.ActivitySyncBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.ui.UiHelpers

class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding
    private var discovering = false
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        UiHelpers.wireToggle(binding.setupHeader, binding.setupBody, binding.setupChevron)

        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.findLedgerButton.setOnClickListener { autoDetect(announce = true) }
        binding.usbUrlButton.setOnClickListener {
            LedgerSettings.setBaseUrl(this, LedgerSettings.USB_BASE_URL)
            binding.ledgerUrlInput.setText(LedgerSettings.USB_BASE_URL)
            binding.status.text = "USB cable selected (127.0.0.1:8000)"
            binding.status.setTextColor(getColor(R.color.status_neutral))
        }
        binding.saveUrlButton.setOnClickListener {
            if (saveUrl() != null) {
                binding.status.text = "Server saved ✓"
                binding.status.setTextColor(getColor(R.color.status_ok))
            }
        }
        binding.registerButton.setOnClickListener { registerActors() }
        binding.syncButton.setOnClickListener { sync() }
        loadIdentities()
        refreshPending()
        autoDetect(announce = false)
    }

    private fun autoDetect(announce: Boolean) {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        if (announce) {
            binding.status.text = "Looking for server on USB and Wi-Fi…"
            binding.status.setTextColor(getColor(R.color.status_neutral))
        }
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line ->
                ui {
                    if (discovering) {
                        binding.status.text = line
                        binding.status.setTextColor(getColor(R.color.status_neutral))
                    }
                }
            },
        ) { found ->
            ui {
                discovering = false
                if (working) return@ui
                binding.findLedgerButton.isEnabled = true
                when {
                    found != null -> {
                        binding.ledgerUrlInput.setText(found)
                        binding.status.text = "Connected: $found"
                        binding.status.setTextColor(getColor(R.color.status_ok))
                    }
                    announce -> {
                        binding.status.text =
                            "No server found. Start ./tools/ledger.sh or ./tools/dev.sh."
                        binding.status.setTextColor(getColor(R.color.status_alert))
                    }
                }
            }
        }
    }

    private fun loadIdentities() {
        Thread {
            val identities = listOf("farmer", "buyer", "box").map { role ->
                role to ActorIdentity.loadOrCreate(this, role)
            }
            ui {
                binding.actors.text = identities.joinToString("\n") { (role, identity) ->
                    "$role  ${identity.actorId}"
                }
            }
        }.start()
    }

    private fun registerActors() {
        val url = saveUrl() ?: return
        setBusy(true, "Registering phone IDs…")
        Thread {
            try {
                LedgerSync(this, LedgerApi(url)).registerActors { line ->
                    ui { appendLog(line) }
                }
                ui {
                    appendLog("Actors ready on $url")
                    setBusy(false, "Phone IDs registered ✓")
                    loadIdentities()
                }
            } catch (error: Exception) {
                ui {
                    appendLog("Error: ${error.message}")
                    setBusy(false, "Registration failed")
                }
            }
        }.start()
    }

    private fun sync() {
        val url = saveUrl() ?: return
        setBusy(true, "Uploading records…")
        Thread {
            try {
                val sync = LedgerSync(this, LedgerApi(url))
                sync.registerActors { line -> ui { appendLog("actor $line") } }
                val report = sync.sync { line -> ui { appendLog(line) } }
                ui {
                    appendLog("Uploaded ${report.uploaded}, failed ${report.failed}, pending ${report.pending}")
                    setBusy(false, "Upload complete ✓")
                    refreshPending()
                }
            } catch (error: Exception) {
                ui {
                    appendLog("Error: ${error.message}")
                    setBusy(false, "Upload failed")
                }
            }
        }.start()
    }

    private fun refreshPending() {
        val pending = runCatching { SyncStore(this).pendingCount() }.getOrDefault(0)
        binding.pending.text = if (pending == 0) {
            "Everything is saved ✓"
        } else {
            "$pending item(s) waiting to upload"
        }
    }

    private fun saveUrl(): String? {
        return try {
            LedgerSettings.setBaseUrl(this, binding.ledgerUrlInput.text.toString()).also {
                binding.ledgerUrlInput.setText(it)
            }
        } catch (error: IllegalArgumentException) {
            binding.status.text = error.message
            binding.status.setTextColor(getColor(R.color.status_alert))
            null
        }
    }

    private fun setBusy(busy: Boolean, status: String) {
        working = busy
        binding.registerButton.isEnabled = !busy
        binding.syncButton.isEnabled = !busy
        binding.saveUrlButton.isEnabled = !busy
        binding.usbUrlButton.isEnabled = !busy
        binding.findLedgerButton.isEnabled = !busy && !discovering
        binding.status.text = status
        binding.status.setTextColor(getColor(if (busy) R.color.status_neutral else R.color.status_ok))
    }

    private fun appendLog(line: String) {
        binding.log.append(if (binding.log.text.isEmpty()) line else "\n$line")
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) block()
        }
    }
}
