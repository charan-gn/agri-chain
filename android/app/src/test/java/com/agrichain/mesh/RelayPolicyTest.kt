package com.agrichain.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPolicyTest {

    @Test
    fun `ttl one and self authored frames are not relayed`() {
        val stopped = RelayPolicy.decide(1, RelayTraffic.EVENTS, degree = 3)
        assertFalse(stopped.shouldRelay)
        assertEquals(1, stopped.newTtl)
        assertEquals(0, stopped.delayMs)

        assertFalse(
            RelayPolicy.decide(6, RelayTraffic.EVENTS, degree = 3, senderIsSelf = true).shouldRelay
        )
    }

    @Test
    fun `dense meshes clamp broadcast ttl harder than thin chains`() {
        val dense = RelayPolicy.decide(7, RelayTraffic.EVENTS, degree = 8)
        val chain = RelayPolicy.decide(7, RelayTraffic.EVENTS, degree = 2)

        assertTrue(dense.shouldRelay)
        assertTrue(chain.shouldRelay)
        assertEquals(4, dense.newTtl)
        assertEquals(6, chain.newTtl)
        assertTrue(dense.delayMs in 80..180)
        assertTrue(chain.delayMs in 10..40)
    }

    @Test
    fun `ack gets directed priority and fragment floods get a dense cap`() {
        val ack = RelayPolicy.decide(6, RelayTraffic.ACK, degree = 9)
        assertTrue(ack.shouldRelay)
        assertEquals(5, ack.newTtl)
        assertTrue(ack.delayMs in 20..60)

        val sparse = RelayPolicy.decide(7, RelayTraffic.FRAGMENT, degree = 1)
        val dense = RelayPolicy.decide(7, RelayTraffic.FRAGMENT, degree = 9)
        assertEquals(6, sparse.newTtl)
        assertEquals(4, dense.newTtl)
        assertTrue(sparse.delayMs in 8..25)
    }

    @Test
    fun `link local traffic is never relayed`() {
        val decision = RelayPolicy.decide(7, RelayTraffic.LINK_LOCAL, degree = 3)
        assertFalse(decision.shouldRelay)
        assertEquals(0, decision.delayMs)
    }
}
