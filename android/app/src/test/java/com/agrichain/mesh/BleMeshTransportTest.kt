package com.agrichain.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMeshTransportTest {

    private fun transport(available: Boolean = true): BleMeshTransport =
        BleMeshTransport(context = null, identity = "phone-a", stubMode = true).apply {
            stubAvailable = available
        }

    @Test
    fun `stub transport fragments and delivers an envelope`() {
        val ble = transport()
        var received: MeshEnvelope? = null
        var receivedPeer: String? = null
        ble.startWithPeer({ envelope, _, peer ->
            received = envelope
            receivedPeer = peer
        }, {})

        val chat = MeshEnvelope.chat("phone-a", "hello mesh", TestSigners.create("mesh:A"))
        assertTrue(ble.available())
        assertEquals(1, ble.send(chat))
        assertTrue("expected fragmentation", ble.sentFrames.size > 1)

        ble.sentFrames.forEach { frame -> ble.simulateReceive(frame, peerId = "ble-peer1") }

        assertNotNull(received)
        assertEquals(chat.envelopeId, received?.envelopeId)
        assertEquals("hello mesh", received?.chatText)
        assertEquals("ble-peer1", receivedPeer)
    }

    @Test
    fun `stub transport dedupes an envelope received twice`() {
        val ble = transport()
        var messages = 0
        ble.startWithPeer({ _, _, _ -> messages++ }, {})

        val chat = MeshEnvelope.chat("phone-a", "once", TestSigners.create("mesh:A"))
        ble.send(chat)
        ble.sentFrames.forEach { frame -> ble.simulateReceive(frame, peerId = "ble-peer1") }
        ble.sentFrames.forEach { frame -> ble.simulateReceive(frame, peerId = "ble-peer1") }

        assertEquals(1, messages)
    }

    @Test
    fun `stub transport is silent when unavailable`() {
        val ble = transport(available = false)
        ble.startWithPeer({ _, _, _ -> }, {})

        val chat = MeshEnvelope.chat("phone-a", "nobody", TestSigners.create("mesh:A"))

        assertEquals(0, ble.send(chat))
        assertTrue(ble.sentFrames.isEmpty())
        assertNull(ble.simulateReceive(BleMeshCodec.hello(1)))
    }

    @Test
    fun `stub peer count follows availability`() {
        assertEquals(1, transport().connectedPeerCount())
        assertEquals(0, transport(available = false).connectedPeerCount())
    }
}
