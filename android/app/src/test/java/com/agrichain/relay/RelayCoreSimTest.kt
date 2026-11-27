package com.agrichain.relay

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPrivateKey

/** P4 simulator scenarios: flooding, dedupe, dense meshes, drain and fallback. */
class RelayCoreSimTest {

    private val ledgerKey: ECPrivateKey = V3Bodies.scalarKey(999)
    private val ledgerPubkey: ByteArray = V3Codec.compressedPublicKey(ledgerKey)
    private val ledgerId: ByteArray = V3Codec.actorId(ledgerPubkey)
    private val ts: Long = (System.currentTimeMillis() / 1000)
        .coerceIn(0, 0xFFFFFFFFL - 86_400L)

    private fun config(): RelayConfig = RelayConfig(
        nick = "sim",
        relayJitterMs = 0..0,
        slowLinkPacingMs = 0,
        loraMinGapMs = 0,
    )

    private fun node(network: SimNetwork, index: Int, config: RelayConfig = config()): SimNode =
        SimNode("n$index", index + 1, ledgerPubkey, network, config)

    private fun unsigned(
        type: V3Type,
        body: V3Body,
        fromId: ByteArray,
        toId: ByteArray? = null,
        flags: Int = 0,
    ): ByteArray = V3Codec.encodeUnsigned(
        type = type,
        fromId = fromId,
        body = V3Bodies.encode(body),
        ts = ts,
        toId = toId,
        flags = flags,
    )

    private fun ledgerRaw(
        type: V3Type,
        body: V3Body,
        toId: ByteArray? = null,
        key: ECPrivateKey = ledgerKey,
        fromId: ByteArray = ledgerId,
    ): ByteArray {
        val bytes = unsigned(type, body, fromId, toId, V3.FLAG_LEDGER_SIGNED)
        return bytes + V3Codec.signRaw(bytes, key)
    }

    private fun listing() = V3Body.Listing(
        crop = 1,
        qtyKg = 100,
        askPaise = 2_000,
        region = 1,
        validUntil = ts + 3_600,
        grade = 1,
        batchId = ByteArray(12),
    )

    private fun order() = V3Body.Order(ByteArray(12) { it.toByte() }, 10, 1_800, ts + 3_600)

    @Test
    fun `five node chain floods one listing and queues it everywhere`() = runBlocking {
        val network = SimNetwork()
        val nodes = (0..4).map { node(network, it) }
        for (i in 0..3) network.connect(nodes[i], nodes[i + 1])
        nodes.forEach { it.resetCounters() }

        val originated = nodes[0].core.originate(V3Type.LISTING, listing(), ttl = 7)

        assertTrue(nodes[0].core.pendingSnapshot().any { it.msgId == originated.msgId && it.own })
        for (index in 1..4) {
            assertTrue("n$index missed the listing", nodes[index].core.pendingSnapshot().any { it.msgId == originated.msgId })
            assertEquals("n$index processed twice", 1L, nodes[index].core.counters().processed)
        }
        assertEquals(0L, nodes[0].core.counters().relayed)
        assertEquals(1L, nodes[1].core.counters().relayed)
        assertEquals(1L, nodes[2].core.counters().relayed)
        assertEquals(1L, nodes[3].core.counters().relayed)
        assertEquals(0L, nodes[4].core.counters().relayed)
    }

    @Test
    fun `six node full mesh uses at most two sends per node`() = runBlocking {
        val network = SimNetwork()
        val nodes = (0..5).map { node(network, it) }
        for (i in 0..5) {
            for (j in i + 1..5) network.connect(nodes[i], nodes[j])
        }
        nodes.forEach { it.resetCounters() }

        nodes[0].core.originate(V3Type.ORDER, order(), ttl = 7)

        nodes.forEach { n ->
            assertTrue("${n.id} sent ${n.transport.sendCount} times", n.transport.sendCount <= 2)
        }
        assertEquals(1, nodes[0].transport.sendCount)
        for (index in 1..5) {
            assertEquals("n$index relayed more than once", 1, nodes[index].transport.sendCount)
            assertEquals(1L, nodes[index].core.counters().processed)
        }
    }

    @Test
    fun `ledger price table is cached once and replays are duplicates`() = runBlocking {
        val network = SimNetwork()
        val nodes = (0..2).map { node(network, it) }
        network.connect(nodes[0], nodes[1])
        network.connect(nodes[1], nodes[2])
        nodes.forEach { it.resetCounters() }

        val body = V3Body.PriceTable(1, 4, ts, 1, 2, listOf(V3Body.PriceRow(1, 2_350, 7)))
        val raw = ledgerRaw(V3Type.PRICE_TABLE, body)
        assertTrue(nodes[0].core.injectLedgerMessage(raw, ttl = 3).fresh)

        nodes.forEach { n ->
            assertEquals(1L, n.core.counters().processed)
            assertEquals(4L, n.core.priceVersions()[1])
            assertEquals(2_350, n.core.priceCaches().single().rows.single().pricePaise)
        }
        val replay = nodes[0].core.injectLedgerMessage(raw, ttl = 3)
        assertTrue(replay.duplicate)
        nodes.forEach { assertEquals(1L, it.core.counters().processed) }
    }

    @Test
    fun `a duplicate arrival on the same link is not relayed`() = runBlocking {
        val network = SimNetwork()
        val nodes = (0..2).map { node(network, it) }
        network.connect(nodes[0], nodes[1])
        network.connect(nodes[1], nodes[2])
        nodes.forEach { it.resetCounters() }

        val originated = nodes[0].core.originate(V3Type.LISTING, listing(), ttl = 7)
        val before = nodes[1].core.counters().relayed
        val envelope = V3Codec.encodeEnvelope(originated.message, 7)

        val duplicate = network.deliver(nodes[0], nodes[1], "n0-n1", envelope)
        assertTrue(duplicate.duplicate)
        assertEquals(before, nodes[1].core.counters().relayed)
    }

    @Test
    fun `gateway death keeps own pending and the link re-offers it on return`() = runBlocking {
        val network = SimNetwork()
        val phone = node(network, 0)
        val gateway = node(network, 1)
        val linkId = network.connect(phone, gateway)
        phone.resetCounters()
        gateway.resetCounters()

        val originated = phone.core.originate(V3Type.ORDER, order(), ttl = 7)
        assertTrue(gateway.core.pendingSnapshot().any { it.msgId == originated.msgId })
        assertEquals(0, phone.core.tick(System.currentTimeMillis() + 73L * 3_600_000).expiredPending)
        assertEquals(1, phone.core.pendingOwnCount())

        network.disconnect(linkId)
        assertFalse(phone.core.gatewayReachable(System.currentTimeMillis() + 6 * 60_000L))

        network.connect(phone, gateway, linkId)
        yield()
        assertTrue(phone.transport.sent.isNotEmpty())

        val msgIdBytes = V3Codec.msgId(V3Codec.decode(originated.message))
        val receipt = ledgerRaw(
            V3Type.RECEIPT,
            V3Body.Receipt(msgIdBytes, 0, 0, 1, ByteArray(8)),
            toId = phone.actorId,
        )
        gateway.core.injectLedgerMessage(receipt, ttl = 7)

        assertFalse(phone.core.pendingSnapshot().any { it.msgId == originated.msgId })
        assertNotNull(phone.core.receiptFor(originated.msgId))
        assertEquals(OrderState.ON_LEDGER, phone.core.orderState(originated.msgId))
    }

    @Test
    fun `own pending falls back to SMS when no gateway is reachable`() = runBlocking {
        val network = SimNetwork()
        val config = config().copy(
            smsEnabled = true,
            smsGatewayNumber = "+919000000001",
            smsFallbackAfterMs = 1_000,
        )
        val phone = node(network, 0, config)
        val sent = mutableListOf<Pair<String, ByteArray>>()
        phone.core.smsFallback = { number, envelope ->
            sent += number to envelope
            true
        }

        phone.core.originate(V3Type.LISTING, listing(), ttl = 7)
        val tick = phone.core.tick(System.currentTimeMillis() + 2_000L)

        assertEquals(1, tick.smsCandidates)
        assertEquals(1, tick.smsSent)
        assertEquals("+919000000001", sent.single().first)
        assertEquals(7, V3Codec.decodeEnvelope(sent.single().second).first)
        assertTrue(phone.core.pendingSnapshot().single().smsAttempted)
        assertEquals(1, phone.core.pendingOwnCount())
    }

    @Test
    fun `bad ledger signature and false client ledger claim are dropped`() = runBlocking {
        val network = SimNetwork()
        val node = node(network, 0)

        val receiptBody = V3Body.Receipt(ByteArray(12) { it.toByte() }, 0, 0, 1, ByteArray(8))
        val forgedLedger = ledgerRaw(V3Type.RECEIPT, receiptBody, key = V3Bodies.scalarKey(1234))
        val bad = node.core.injectLedgerMessage(forgedLedger, ttl = 7)
        assertEquals("bad_ledger_signature", bad.droppedReason)
        assertFalse(bad.fresh)

        val claimBytes = unsigned(V3Type.RECEIPT, receiptBody, node.actorId)
        val clientClaim = claimBytes + V3Codec.signRaw(claimBytes, node.key)
        val claimed = node.core.injectLedgerMessage(clientClaim, ttl = 7)
        assertEquals("client_claimed_ledger_type", claimed.droppedReason)
        assertFalse(claimed.fresh)
        assertEquals(0L, node.core.counters().processed)
        assertEquals(0, node.core.pendingCount())
    }
}
