package com.agrichain.relay

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPrivateKey

/**
 * v3 phones keep Wi-Fi and BLE attached at the same time. Only the radios are
 * simulated; the router, codec and dedupe rules are the production ones.
 */
class TwoTransportRelayCoreTest {

    private val ledgerKey: ECPrivateKey = V3Bodies.scalarKey(999)
    private val ledgerPubkey: ByteArray = V3Codec.compressedPublicKey(ledgerKey)
    private val ledgerId: ByteArray = V3Codec.actorId(ledgerPubkey)
    private val ts: Long = (System.currentTimeMillis() / 1000)
        .coerceIn(0, 0xFFFFFFFFL - 86_400L)

    private class TwoRadioNode(
        network: SimNetwork,
        index: Int,
        ledgerPubkey: ByteArray,
    ) {
        val sim = SimNode("n$index", index + 1, ledgerPubkey, network)
        val core: RelayCore get() = sim.core
        val wifi: SimTransport get() = sim.transport
        val ble = SimTransport("ble", sim, network)

        init {
            core.attach(ble)
        }

        fun setWifiLinks(vararg linkIds: String) {
            wifi.setLinks(linkIds.map { LinkInfo(it, "wifi", "peer-$it", "peer-$it") }.toSet())
        }

        fun setBleLinks(vararg linkIds: String) {
            ble.setLinks(linkIds.map { LinkInfo(it, "ble", "peer-$it", "peer-$it") }.toSet())
        }

        fun reset() {
            wifi.resetCounters()
            ble.resetCounters()
            core.resetCounters()
        }
    }

    private fun order() = V3Body.Order(ByteArray(12) { it.toByte() }, 10, 1_800, ts + 3_600)

    private suspend fun clientMessage(index: Int): ByteArray {
        val sender = SimNode("sender$index", 50 + index, ledgerPubkey, SimNetwork())
        return sender.core.originate(V3Type.ORDER, order(), ttl = 7).message
    }

    private fun ledgerRaw(type: V3Type, body: V3Body): ByteArray {
        val unsigned = V3Codec.encodeUnsigned(
            type = type,
            fromId = ledgerId,
            body = V3Bodies.encode(body),
            ts = ts,
            toId = null,
            flags = V3.FLAG_LEDGER_SIGNED,
        )
        return unsigned + V3Codec.signRaw(unsigned, ledgerKey)
    }

    @Test
    fun `originate fans out to both wifi and ble`() = runBlocking {
        val relay = TwoRadioNode(SimNetwork(), 0, ledgerPubkey)
        relay.setWifiLinks("w1")
        relay.setBleLinks("b1")
        yield()
        relay.reset()

        val originated = relay.core.originate(V3Type.ORDER, order(), ttl = 7)

        assertEquals(2, originated.links)
        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)
        assertEquals(7, V3Codec.decodeEnvelope(relay.wifi.sent.single()).first)
        assertEquals(7, V3Codec.decodeEnvelope(relay.ble.sent.single()).first)
        assertEquals(
            setOf("wifi", "ble"),
            relay.core.snapshot().transports.map { it.id }.toSet(),
        )
    }

    @Test
    fun `same msgId arriving on the second radio is not relayed twice`() = runBlocking {
        val relay = TwoRadioNode(SimNetwork(), 1, ledgerPubkey)
        relay.setWifiLinks("w1", "w2")
        relay.setBleLinks("b1")
        yield()
        relay.reset()
        val envelope = V3Codec.encodeEnvelope(clientMessage(1), 5)

        val first = relay.core.onEnvelope(envelope, "w1", "wifi")
        assertTrue(first.fresh)
        // One send on the other Wi-Fi link and one on BLE, from a single relay.
        assertEquals(2L, relay.core.counters().relayed)
        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)

        val second = relay.core.onEnvelope(envelope, "b1", "ble")
        assertTrue(second.duplicate)
        assertEquals(2L, relay.core.counters().relayed)
        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)
    }

    @Test
    fun `sparse relay excludes only the arrival link`() = runBlocking {
        val relay = TwoRadioNode(SimNetwork(), 2, ledgerPubkey)
        relay.setWifiLinks("w1", "w2")
        relay.setBleLinks("b1")
        yield()
        relay.reset()
        val envelope = V3Codec.encodeEnvelope(clientMessage(2), 5)

        relay.core.onEnvelope(envelope, "w1", "wifi")

        // Wi-Fi had two links but only the non-arrival link gets a send; BLE
        // still receives its one broadcast. A single TTL decrement reaches both.
        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)
        assertEquals(4, V3Codec.decodeEnvelope(relay.wifi.sent.single()).first)
        assertEquals(4, V3Codec.decodeEnvelope(relay.ble.sent.single()).first)
    }

    @Test
    fun `dense mesh caps at one broadcast per transport`() = runBlocking {
        val relay = TwoRadioNode(SimNetwork(), 3, ledgerPubkey)
        relay.setWifiLinks("w1", "w2", "w3")
        relay.setBleLinks("b1", "b2", "b3")
        yield()
        relay.reset()
        val envelope = V3Codec.encodeEnvelope(clientMessage(3), 7)

        relay.core.onEnvelope(envelope, "w1", "wifi")

        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)
        assertEquals(2L, relay.core.counters().relayed)
        // Dense meshes clamp the single TTL decrement to 5 on both radios.
        assertEquals(5, V3Codec.decodeEnvelope(relay.wifi.sent.single()).first)
        assertEquals(5, V3Codec.decodeEnvelope(relay.ble.sent.single()).first)
    }

    @Test
    fun `ledger injection reaches both transports`() = runBlocking {
        val relay = TwoRadioNode(SimNetwork(), 4, ledgerPubkey)
        relay.setWifiLinks("w1")
        relay.setBleLinks("b1")
        yield()
        relay.reset()
        val raw = ledgerRaw(
            V3Type.PRICE_TABLE,
            V3Body.PriceTable(1, 4, ts, 1, 2, listOf(V3Body.PriceRow(1, 2_350, 7))),
        )

        val outcome = relay.core.injectLedgerMessage(raw, ttl = 5)

        assertTrue(outcome.fresh)
        assertEquals(1, relay.wifi.sendCount)
        assertEquals(1, relay.ble.sendCount)
        assertEquals(4L, relay.core.priceVersions()[1])
        assertEquals(4, V3Codec.decodeEnvelope(relay.wifi.sent.single()).first)
        assertEquals(4, V3Codec.decodeEnvelope(relay.ble.sent.single()).first)
    }
}
