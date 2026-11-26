package com.agrichain.mesh

import android.os.Handler
import android.os.Looper

/**
 * Owns the two local relay links. Both keep discovering while the relay is on,
 * but only the selected link is attached to [RelayEngine], so an envelope is
 * never sent twice over two radios. Selection follows [RelayLinkSelector].
 */
class RelayLinkManager(
    private val wifi: WifiMeshTransport,
    private val ble: BleMeshTransport,
    private val attach: (Transport) -> Unit,
    private val detach: (Transport) -> Unit,
    private val deliver: (MeshEnvelope, String, String?) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile var mode: RelayLinkMode = RelayLinkMode.AUTO
        private set

    @Volatile var running: Boolean = false
        private set

    @Volatile private var active: RelayLinkId? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            reevaluate()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start(initialMode: RelayLinkMode = RelayLinkMode.AUTO) {
        if (running) {
            setMode(initialMode)
            return
        }
        mode = initialMode
        running = true
        wifi.startWithPeer({ envelope, via, peer ->
            if (active == RelayLinkId.WIFI) deliver(envelope, via.id, peer)
        }, { line -> log("wifi: $line") })
        ble.startWithPeer({ envelope, via, peer ->
            if (active == RelayLinkId.BLE) deliver(envelope, via.id, peer)
        }, { line -> log("ble: $line") })
        handler.removeCallbacks(tick)
        handler.post(tick)
        log("relay started in ${mode.name.lowercase()} mode")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        detachActive()
        wifi.stop()
        ble.stop()
        log("relay stopped")
    }

    fun setMode(next: RelayLinkMode) {
        mode = next
        if (!running) return
        reevaluate()
        log("relay mode: ${next.name.lowercase()}")
    }

    fun status(): RelayLinkStatus = RelayLinkStatus(
        mode = mode,
        wifiPeers = wifi.peerCount(),
        blePeers = ble.connectedPeerCount(),
        active = active,
        running = running,
    )

    private fun reevaluate() {
        if (!running) return
        val desired = RelayLinkSelector.choose(
            mode,
            RelayLinkSnapshot(wifi.peerCount(), ble.connectedPeerCount()),
            active,
        )
        if (desired == active) return
        detachActive()
        active = desired
        if (desired != null) {
            val transport = transport(desired)
            attach(transport)
            log("active link: ${desired.name.lowercase()}")
        } else {
            log("no relay peer found; waiting on Wi-Fi and Bluetooth")
        }
    }

    private fun detachActive() {
        active?.let { detach(transport(it)) }
        active = null
    }

    private fun transport(id: RelayLinkId): Transport = when (id) {
        RelayLinkId.WIFI -> wifi
        RelayLinkId.BLE -> ble
    }

    companion object {
        private const val SCAN_INTERVAL_MS = 2_000L
    }
}
