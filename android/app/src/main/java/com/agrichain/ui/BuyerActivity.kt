package com.agrichain.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.buyer.BuyerHistoryActivity
import com.agrichain.databinding.ActivityBuyerBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.qr.ScanActivity

class BuyerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuyerBinding
    private var discovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuyerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.ledgerUrlInput.setText(LedgerSettings.baseUrl(this))
        binding.findLedgerButton.setOnClickListener { autoDetect(announce = true) }
        UiHelpers.wireToggle(binding.serverHeader, binding.serverBody, binding.serverChevron)

        binding.scanButton.setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        binding.openHistoryButton.setOnClickListener {
            val batchId = binding.batchIdInput.text.toString().trim()
            if (batchId.isEmpty()) {
                binding.batchIdInput.error = "Paste a batch code first"
            } else {
                startActivity(BuyerHistoryActivity.intent(this, batchId))
            }
        }
        binding.saveUrlButton.setOnClickListener {
            runCatching { LedgerSettings.setBaseUrl(this, binding.ledgerUrlInput.text.toString()) }
                .onSuccess {
                    binding.ledgerUrlInput.setText(it)
                    binding.urlStatus.text = "Saved server: $it"
                }
                .onFailure { binding.urlStatus.text = it.message }
        }
        autoDetect(announce = false)
    }

    private fun autoDetect(announce: Boolean) {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        if (announce) binding.urlStatus.text = "Looking for server on USB and Wi-Fi…"
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line ->
                runOnUiThread {
                    if (discovering && !isFinishing && !isDestroyed) {
                        binding.urlStatus.text = line
                    }
                }
            },
        ) { found ->
            runOnUiThread {
                discovering = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.findLedgerButton.isEnabled = true
                when {
                    found != null -> {
                        binding.ledgerUrlInput.setText(found)
                        binding.urlStatus.text = "Connected: $found"
                    }
                    announce -> {
                        binding.urlStatus.text =
                            "No server found. Start ./tools/ledger.sh or ./tools/dev.sh."
                    }
                }
            }
        }
    }
}
