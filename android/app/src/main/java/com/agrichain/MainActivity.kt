package com.agrichain

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.core.Permissions
import com.agrichain.databinding.ActivityMainBinding
import com.agrichain.net.LedgerDiscovery
import com.agrichain.net.LedgerSettings
import com.agrichain.relay.BarterV3Activity
import com.agrichain.relay.BatchV3Activity
import com.agrichain.relay.BoxV3Activity
import com.agrichain.relay.MarketActivity
import com.agrichain.relay.OrdersActivity
import com.agrichain.relay.PricesV3Activity
import com.agrichain.relay.RelayActivity
import com.agrichain.relay.RelayRuntime
import com.agrichain.ui.UiHelpers

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var discovering = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        reportPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // tools/phone.sh passes the active USB port so custom PORT values still work.
        intent?.getStringExtra("ledgerUrl")?.let { url ->
            runCatching { LedgerSettings.setBaseUrl(this, url) }
        }
        binding.ledgerStatus.text = "Server: ${LedgerSettings.baseUrl(this)}"
        binding.findLedgerButton.setOnClickListener { autoDetect(announce = true) }
        binding.permissionsButton.setOnClickListener { requestPermissions() }

        val startSetupOpen = Permissions.missing(this).isNotEmpty()
        UiHelpers.wireToggle(
            binding.setupHeader,
            binding.setupBody,
            binding.setupChevron,
            startOpen = startSetupOpen,
        )
        binding.setupPill.setOnClickListener { binding.setupHeader.performClick() }

        binding.farmerButton.setOnClickListener {
            startActivity(Intent(this, BatchV3Activity::class.java))
        }
        binding.buyerButton.setOnClickListener {
            startActivity(Intent(this, MarketActivity::class.java))
        }
        binding.boxButton.setOnClickListener {
            startActivity(Intent(this, BoxV3Activity::class.java))
        }
        binding.pricesButton.setOnClickListener {
            startActivity(Intent(this, PricesV3Activity::class.java))
        }
        binding.syncButton.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        binding.meshButton.setOnClickListener {
            startActivity(Intent(this, BarterV3Activity::class.java))
        }
        binding.relayButton.setOnClickListener {
            startActivity(Intent(this, RelayActivity::class.java))
        }

        Permissions.logState(this)
        showPermissionState()
        if (intent?.getBooleanExtra("checkPermissions", false) == true) {
            binding.root.post { requestPermissions() }
        }
        autoDetect(announce = false)
        handleDevIntent()
    }

    override fun onResume() {
        super.onResume()
        refreshOutboxBadge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDevIntent()
    }

    /** adb hooks: --es openBox <batchId> or --ez openMesh true, both with --ez autoStart. */
    private fun handleDevIntent() {
        val autoStart = intent?.getBooleanExtra("autoStart", false) == true
        intent?.getStringExtra("openBox")?.let { batchId ->
            Log.i(Permissions.TAG, "dev: opening box $batchId autoStart=$autoStart")
            startActivity(
                Intent(this, BoxV3Activity::class.java)
                    .putExtra(BoxV3Activity.EXTRA_BATCH_ID, batchId)
                    .putExtra(BoxV3Activity.EXTRA_AUTO_START, autoStart)
            )
        }
        if (intent?.getBooleanExtra("openMesh", false) == true) {
            Log.i(Permissions.TAG, "dev: opening relay autoStart=$autoStart")
            startActivity(Intent(this, RelayActivity::class.java))
        }
    }

    private fun refreshOutboxBadge() {
        val pending = RelayRuntime.snapshot().pendingOwnCount
        if (pending > 0) {
            binding.syncBadge.visibility = View.VISIBLE
            binding.syncBadge.text = "$pending waiting"
            binding.syncSubtitle.text = "Signed messages waiting for the ledger"
        } else {
            binding.syncBadge.visibility = View.GONE
            binding.syncSubtitle.text = "Outbox is empty"
        }
    }

    private fun requestPermissions() {
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) {
            reportPermissions()
            return
        }
        Log.i(
            Permissions.TAG,
            "permissions: requesting ${missing.joinToString { it.permission }}",
        )
        permissionLauncher.launch(missing.map { it.permission }.toTypedArray())
    }

    private fun reportPermissions() {
        val missing = Permissions.missing(this)
        Permissions.logState(this)
        showPermissionState()
        val message = if (missing.isEmpty()) {
            "All ${Permissions.required().size} permissions are allowed."
        } else {
            "Denied (enable in App info > Permissions):\n\n" +
                Permissions.describe(missing) +
                "\n\nSMS toggles greyed out? Open App info, tap the menu, then " +
                "\"Allow restricted settings\", and grant again."
        }
        AlertDialog.Builder(this)
            .setTitle("Permissions")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showPermissionState() {
        val missing = Permissions.missing(this)
        binding.permissionStatus.text = Permissions.summary(this)
        val color = getColor(if (missing.isEmpty()) R.color.status_ok else R.color.status_alert)
        binding.permissionStatus.setTextColor(color)
        binding.setupPill.text = if (missing.isEmpty()) "Ready" else "Setup (${missing.size})"
        binding.setupPill.setTextColor(color)
    }

    private fun autoDetect(announce: Boolean) {
        if (discovering) return
        discovering = true
        binding.findLedgerButton.isEnabled = false
        if (announce) {
            binding.ledgerStatus.text = "Looking for server on USB and Wi-Fi..."
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
                        binding.ledgerStatus.text = "Connected: $found"
                        binding.ledgerStatus.setTextColor(getColor(R.color.status_ok))
                    }
                    announce -> {
                        binding.ledgerStatus.text =
                            "No server found. Start ./tools/ledger.sh or ./tools/dev.sh."
                        binding.ledgerStatus.setTextColor(getColor(R.color.status_alert))
                    }
                }
            }
        }
    }
}
