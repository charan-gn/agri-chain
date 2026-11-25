package com.agrichain.data

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.R
import com.agrichain.core.ActorIdentity
import com.agrichain.databinding.ActivitySyncBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings

class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding
    private var discovering = false
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.findLedgerButton.setOnClickListener { autoDetect(announce = true) }
        binding.usbUrlButton.setOnClickListener {
            LedgerSettings.setBaseUrl(this, LedgerSettings.USB_BASE_URL)
            binding.ledgerUrlInput.setText(LedgerSettings.USB_BASE_URL)
            binding.status.text = "USB ledger selected (adb reverse tcp:8000)"
            binding.status.setTextColor(getColor(R.color.status_neutral))
        }
        binding.saveUrlButton.setOnClickListener {
            if (saveUrl() != null) {
                binding.status.text = "Ledger server saved"
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
            binding.status.text = "Searching for a ledger on USB and Wi-Fi…"
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
                        binding.status.text = "Ledger: $found"
                        binding.status.setTextColor(getColor(R.color.status_ok))
                    }
                    announce -> {
                        binding.status.text =
                            "No ledger found. Start ./tools/ledger.sh or ./tools/dev.sh."
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
        setBusy(true, "Registering actors…")
        Thread {
            try {
                LedgerSync(this, LedgerApi(url)).registerActors { line ->
                    ui { appendLog(line) }
                }
                ui {
                    appendLog("Actors ready on $url")
                    setBusy(false, "Actors registered")
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
        setBusy(true, "Syncing events…")
        Thread {
            try {
                val sync = LedgerSync(this, LedgerApi(url))
                sync.registerActors { line -> ui { appendLog("actor $line") } }
                val report = sync.sync { line -> ui { appendLog(line) } }
                ui {
                    appendLog("Uploaded ${report.uploaded}, failed ${report.failed}, pending ${report.pending}")
                    setBusy(false, "Sync finished")
                    refreshPending()
                }
            } catch (error: Exception) {
                ui {
                    appendLog("Error: ${error.message}")
                    setBusy(false, "Sync failed")
                }
            }
        }.start()
    }

    private fun refreshPending() {
        val pending = runCatching { SyncStore(this).pendingCount() }.getOrDefault(0)
        binding.pending.text = "$pending event(s) waiting to sync"
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
