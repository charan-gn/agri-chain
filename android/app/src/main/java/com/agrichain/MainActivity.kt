package com.agrichain

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityMainBinding
import com.agrichain.box.BoxActivity
import com.agrichain.data.SyncActivity
import com.agrichain.mesh.MeshActivity
import com.agrichain.net.LedgerSettings
import com.agrichain.prices.PricesActivity
import com.agrichain.ui.BuyerActivity
import com.agrichain.ui.FarmerActivity
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.usbButton.setOnClickListener { useUsbLink() }
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
    }

    override fun onResume() {
        super.onResume()
        showHost()
    }

    private fun showHost() {
        binding.ledgerStatus.text = "Ledger: ${LedgerSettings.baseUrl(this)}"
    }

    private fun useUsbLink() {
        val base = LedgerSettings.setBaseUrl(this, USB_HOST)
        binding.ledgerStatus.text = "USB: $base\nChecking link…"
        Thread {
            val healthy = checkHealth(base)
            mainHandler.post {
                binding.ledgerStatus.text = if (healthy) {
                    "USB link OK: $base"
                } else {
                    "USB link set, ledger unreachable: $base"
                }
            }
        }.start()
    }

    private fun checkHealth(base: String): Boolean = try {
        val connection = URL("$base/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val USB_HOST = "http://127.0.0.1:8000"
    }
}
