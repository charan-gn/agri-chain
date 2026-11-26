package com.agrichain.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WifiMeshProtocolTest {

    @Test
    fun `service name carries a safe relay peer id`() {
        val name = WifiMeshProtocol.serviceName("phone-a1")
        assertEquals("AgriChain-phone-a1", name)
        assertEquals("phone-a1", WifiMeshProtocol.peerId(name))
        assertNull(WifiMeshProtocol.peerId("OtherService-phone-a1"))
    }

    @Test
    fun `peer ids are sanitized before they reach a frame`() {
        assertEquals(
            "phone-a1badname",
            WifiMeshProtocol.sanitizePeerId("  phone-a1\tbad \"name\"  "),
        )
    }

    @Test
    fun `a framed envelope round trips with its link peer`() {
        val envelope = MeshEnvelope.chat(
            "phone-a",
            "line one\nwith a tab\tinside",
            TestSigners.create("mesh:A"),
        )
        val frame = WifiMeshProtocol.frame("phone-a", envelope)
        val parsed = WifiMeshProtocol.parse(frame)

        assertNotNull(parsed)
        assertEquals("phone-a", parsed?.first)
        assertEquals(envelope.envelopeId, parsed?.second?.envelopeId)
        assertEquals(envelope.chatText, parsed?.second?.chatText)
        assertEquals(envelope.sig, parsed?.second?.sig)
    }

    @Test
    fun `malformed frames are rejected`() {
        assertNull(WifiMeshProtocol.parse("no-separator"))
        assertNull(WifiMeshProtocol.parse("\t{\"v\":3}"))
        assertNull(WifiMeshProtocol.parse("phone-a\tnot-json"))
    }
}
