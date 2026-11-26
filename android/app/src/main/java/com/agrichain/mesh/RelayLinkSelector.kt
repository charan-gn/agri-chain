package com.agrichain.mesh

/** How the phone-to-phone relay picks its radio link. */
enum class RelayLinkMode {
    /** Discover on Wi-Fi and Bluetooth; attach whichever link sees a peer. */
    AUTO,
    /** Always use the Wi-Fi NSD link. */
    WIFI,
    /** Always use the Bluetooth LE link. */
    BLE,
}

/** The two local links that can carry signed chat and ledger envelopes. */
enum class RelayLinkId(val transportId: String) {
    WIFI("wifi"),
    BLE("ble"),
}

data class RelayLinkSnapshot(
    val wifiPeers: Int,
    val blePeers: Int,
)

data class RelayLinkStatus(
    val mode: RelayLinkMode,
    val wifiPeers: Int,
    val blePeers: Int,
    val active: RelayLinkId?,
    val running: Boolean,
)

/**
 * Pure selection policy. Keeps the current link while it still has peers so a
 * flapping scan result cannot bounce traffic between radios; only loses it when
 * that link has no peers left. In auto mode the link with peers wins, with the
 * current link winning ties and Wi-Fi winning a first-seen tie.
 */
object RelayLinkSelector {
    fun choose(
        mode: RelayLinkMode,
        snapshot: RelayLinkSnapshot,
        current: RelayLinkId?,
    ): RelayLinkId? = when (mode) {
        RelayLinkMode.WIFI -> RelayLinkId.WIFI
        RelayLinkMode.BLE -> RelayLinkId.BLE
        RelayLinkMode.AUTO -> {
            val wifi = snapshot.wifiPeers > 0
            val ble = snapshot.blePeers > 0
            when {
                !wifi && !ble -> null
                wifi && ble -> when {
                    current == RelayLinkId.WIFI && snapshot.wifiPeers >= snapshot.blePeers ->
                        RelayLinkId.WIFI
                    current == RelayLinkId.BLE && snapshot.blePeers >= snapshot.wifiPeers ->
                        RelayLinkId.BLE
                    snapshot.wifiPeers > snapshot.blePeers -> RelayLinkId.WIFI
                    else -> RelayLinkId.BLE
                }
                wifi -> RelayLinkId.WIFI
                else -> RelayLinkId.BLE
            }
        }
    }
}
