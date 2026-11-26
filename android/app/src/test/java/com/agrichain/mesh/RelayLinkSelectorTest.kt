package com.agrichain.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayLinkSelectorTest {

    @Test
    fun `auto picks the link that found a peer`() {
        assertEquals(
            RelayLinkId.WIFI,
            RelayLinkSelector.choose(
                RelayLinkMode.AUTO,
                RelayLinkSnapshot(wifiPeers = 1, blePeers = 0),
                current = null,
            ),
        )
        assertEquals(
            RelayLinkId.BLE,
            RelayLinkSelector.choose(
                RelayLinkMode.AUTO,
                RelayLinkSnapshot(wifiPeers = 0, blePeers = 2),
                current = null,
            ),
        )
    }

    @Test
    fun `auto keeps the current link on a tie and drops it when no peers remain`() {
        val both = RelayLinkSnapshot(wifiPeers = 1, blePeers = 1)
        assertEquals(
            RelayLinkId.BLE,
            RelayLinkSelector.choose(RelayLinkMode.AUTO, both, RelayLinkId.BLE),
        )
        assertEquals(
            RelayLinkId.WIFI,
            RelayLinkSelector.choose(RelayLinkMode.AUTO, both, RelayLinkId.WIFI),
        )
        assertNull(
            RelayLinkSelector.choose(
                RelayLinkMode.AUTO,
                RelayLinkSnapshot(0, 0),
                RelayLinkId.WIFI,
            ),
        )
    }

    @Test
    fun `auto switches when the other link has more peers`() {
        assertEquals(
            RelayLinkId.WIFI,
            RelayLinkSelector.choose(
                RelayLinkMode.AUTO,
                RelayLinkSnapshot(wifiPeers = 3, blePeers = 1),
                RelayLinkId.BLE,
            ),
        )
    }

    @Test
    fun `forced modes ignore discovered peers`() {
        assertEquals(
            RelayLinkId.WIFI,
            RelayLinkSelector.choose(
                RelayLinkMode.WIFI,
                RelayLinkSnapshot(0, 5),
                RelayLinkId.BLE,
            ),
        )
        assertEquals(
            RelayLinkId.BLE,
            RelayLinkSelector.choose(
                RelayLinkMode.BLE,
                RelayLinkSnapshot(5, 0),
                RelayLinkId.WIFI,
            ),
        )
    }
}
