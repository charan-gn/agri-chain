package com.agrichain

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityMainBinding
import com.agrichain.box.BoxActivity
import com.agrichain.data.SyncActivity
import com.agrichain.mesh.MeshActivity
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.prices.PricesActivity
import com.agrichain.ui.BuyerActivity
import com.agrichain.ui.FarmerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var discovering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // tools/phone.sh passes the active USB port so custom PORT values still work.
        intent?.getStringExtra("ledgerUrl")?.let { url ->
            runCatching { LedgerSettings.setBaseUrl(this, url) }
        }
        binding.ledgerStatus.text = "Ledger: ${LedgerSettings.baseUrl(this)}"
        binding.findLedgerButton.setOnClickListener { autoDetect(announce = true) }

        binding.farmerButton.setOnClickListener {
            startActivity(Intent(this, FarmerActivity::class.java))
        }
        binding.buyerButton.setOnClickListener {
            startActivity(Intent(this, BuyerActivity::class.java))
        }
        binding.boxButton.setOnClickListener {
            startActivity(Intent(this, BoxActivity::class.java))
        }
        binding.pricesButton.setOnClickListener {
            startActivity(Intent(this, PricesActivity::class.java))
        }
        binding.syncButton.setOnClickListener {
            startActivity(Intent(this, SyncActivity::class.java))
        }
        binding.meshButton.setOnClickListener {
            startActivity(Intent(this, MeshActivity::class.java))
        }
        autoDetect(announce = false)
    }

    private fun autoDetect(announce: Boolean) {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        if (announce) {
            binding.ledgerStatus.text = "Searching for a ledger on USB and Wi-Fi…"
            binding.ledgerStatus.setTextColor(getColor(R.color.status_neutral))
        }
        LedgerDiscovery.discoverAsync(
            this,
            onLog = { line ->
                runOnUiThread {
                    if (discovering && !isFinishing && !isDestroyed) {
                        binding.ledgerStatus.text = line
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
                        binding.ledgerStatus.text = "Ledger: $found"
                        binding.ledgerStatus.setTextColor(getColor(R.color.status_ok))
                    }
                    announce -> {
                        binding.ledgerStatus.text =
                            "No ledger found. Start ./tools/ledger.sh or ./tools/dev.sh."
                        binding.ledgerStatus.setTextColor(getColor(R.color.status_alert))
                    }
                }
            }
        }
    }
}
