package com.agrichain.relay

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.agrichain.databinding.ActivityRelayBinding

/** v3 relay dashboard: transports, peers, pending, receipts and navigation. */
class RelayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRelayBinding
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRelayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.startButton.setOnClickListener {
            RelayRuntime.startService(this)
            render()
        }
        binding.stopButton.setOnClickListener {
            RelayRuntime.stopService(this)
            render()
        }
        binding.marketButton.setOnClickListener {
            startActivity(Intent(this, MarketActivity::class.java))
        }
        binding.ordersButton.setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        binding.pricesButton.setOnClickListener {
            startActivity(Intent(this, PricesV3Activity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsV3Activity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    private fun render() {
        val snapshot = RelayRuntime.snapshot()
        val status = RelayRuntime.status.value
        binding.statusText.text = buildString {
            append("Status: ").append(status)
            if (snapshot.me.isNotEmpty()) append(" · me ").append(snapshot.me)
        }
        binding.gatewayText.text = buildString {
            append("Gateway: ")
            append(if (snapshot.gatewayReachable) "reachable" else "not reachable")
            if (snapshot.gatewayLastSeen > 0) {
                append(" · last sync ").append(age(snapshot.gatewayLastSeen))
            }
        }
        binding.transportText.text = if (snapshot.transports.isEmpty()) {
            "Transports: none attached"
        } else {
            snapshot.transports.joinToString(prefix = "Transports: ") {
                "${it.id}=${it.state} links=${it.links}"
            }
        }
        binding.peerText.text = if (snapshot.peers.isEmpty()) {
            "Peers: none yet"
        } else {
            snapshot.peers.take(6).joinToString(prefix = "Peers: ") {
                "${it.nick.ifBlank { it.actorId.take(8) }}${if (it.isGateway) "*" else ""}"
            }
        }
        binding.pendingText.text =
            "Pending: ${snapshot.pendingCount} (own ${snapshot.pendingOwnCount})"
        val receipt = snapshot.lastReceipt
        binding.receiptText.text = if (receipt == null) {
            "Last receipt: none"
        } else {
            "Last receipt: ${receipt.msgId.take(8)} status=${receipt.status} " +
                "reason=${receipt.reason} seq=${receipt.seq}"
        }
        binding.noticeText.text = RelayRuntime.lastNotice.value.orEmpty()
        binding.logText.text = buildString {
            append("actor: ").append(snapshot.me.ifEmpty { "not started" }).append('\n')
            append("prices cached: ").append(snapshot.prices.size).append('\n')
            append("listings cached: ").append(snapshot.listings.size)
        }
    }

    private fun age(at: Long): String {
        val seconds = ((System.currentTimeMillis() - at) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            else -> "${seconds / 3600}h ago"
        }
    }
}
