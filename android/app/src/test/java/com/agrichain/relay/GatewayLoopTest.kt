package com.agrichain.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPrivateKey

/** The gateway loop is exercised against a fake ledger, not a live server. */
class GatewayLoopTest {

    private val ledgerKey: ECPrivateKey = V3Bodies.scalarKey(999)
    private val ledgerPubkey = V3Codec.compressedPublicKey(ledgerKey)
    private val ledgerId = V3Codec.actorId(ledgerPubkey)
    private val ts = (System.currentTimeMillis() / 1000).coerceIn(0, 0xFFFFFFFFL - 86_400L)

    private class FakeClient : V3LedgerClient {
        val posts = mutableListOf<Pair<String, List<ByteArray>>>()
        val receipts = mutableMapOf<String, ByteArray>()
        var priceFeed = PriceFeed(0, emptyList(), emptyList())
        var listingFeed = ListingFeed(emptyList(), 0)
        var outbox = OutboxPage(emptyList(), 0)

        override suspend fun postMessages(via: String, messages: List<ByteArray>): List<LedgerIngestResult> {
            posts += via to messages
            return messages.map { raw ->
                val msgId = V3Codec.hex(V3Codec.msgId(V3Codec.decode(raw)))
                val receipt = receipts[msgId]
                LedgerIngestResult(
                    msgId = msgId,
                    status = "accepted",
                    reasonCode = 0,
                    reason = "",
                    seq = 1,
                    receipt = receipt,
                )
            }
        }

        override suspend fun prices(sinceVersion: Long): PriceFeed = priceFeed
        override suspend fun listings(afterSeq: Long): ListingFeed = listingFeed
        override suspend fun pullOutbox(actors: List<String>, afterId: Long): OutboxPage = outbox
    }

    private fun unsigned(
        type: V3Type,
        body: V3Body,
        fromId: ByteArray,
        toId: ByteArray? = null,
        flags: Int = 0,
    ): ByteArray = V3Codec.encodeUnsigned(
        type, fromId, V3Bodies.encode(body), ts, toId, flags,
    )

    private fun ledgerRaw(
        type: V3Type,
        body: V3Body,
        toId: ByteArray? = null,
    ): ByteArray {
        val bytes = unsigned(type, body, ledgerId, toId, V3.FLAG_LEDGER_SIGNED)
        return bytes + V3Codec.signRaw(bytes, ledgerKey)
    }

    private fun standaloneCore(): RelayCore {
        val network = SimNetwork()
        val node = SimNode("gw", 1, ledgerPubkey, network)
        return node.core
    }

    @Test
    fun `uplink batches at fifty and drains pending`() = runBlocking {
        val core = standaloneCore()
        val client = FakeClient()
        val loop = GatewayLoop(core, client)

        repeat(60) {
            core.originate(
                V3Type.LISTING,
                V3Body.Listing(1, 1, 2_000, 1, ts + 3_600, 1, ByteArray(12)),
            )
        }
        assertEquals(60, core.pendingCount())

        assertEquals(50, loop.uplinkOnce())
        assertEquals("internet", client.posts[0].first)
        assertEquals(50, client.posts[0].second.size)
        assertEquals(10, core.pendingCount())

        assertEquals(10, loop.uplinkOnce())
        assertEquals(10, client.posts[1].second.size)
        assertEquals(0, core.pendingCount())
    }

    @Test
    fun `an ingest receipt clears pending and reaches the UI state`() = runBlocking {
        val core = standaloneCore()
        val client = FakeClient()
        val loop = GatewayLoop(core, client)
        val originated = core.originate(
            V3Type.ORDER,
            V3Body.Order(ByteArray(12), 5, 1_800, ts + 3_600),
        )

        val receipt = ledgerRaw(
            V3Type.RECEIPT,
            V3Body.Receipt(V3Codec.msgId(V3Codec.decode(originated.message)), 0, 0, 7, ByteArray(8)),
        )
        client.receipts[originated.msgId] = receipt

        assertEquals(1, loop.uplinkOnce())
        assertEquals(0, core.pendingCount())
        assertNotNull(core.receiptFor(originated.msgId))
        assertEquals(OrderState.ON_LEDGER, core.orderState(originated.msgId))
    }

    @Test
    fun `price, listing and outbox feeds inject signed ledger messages`() = runBlocking {
        val core = standaloneCore()
        val client = FakeClient()
        val loop = GatewayLoop(core, client)

        val table = ledgerRaw(
            V3Type.PRICE_TABLE,
            V3Body.PriceTable(1, 2, ts, 1, 1, listOf(V3Body.PriceRow(1, 2_500, 4))),
        )
        val beacon = ledgerRaw(
            V3Type.PRICE_BEACON,
            V3Body.PriceBeacon(1, 2, ByteArray(8) { it.toByte() }),
        )
        client.priceFeed = PriceFeed(
            version = 2,
            prices = listOf(PriceTableItem(1, 2, 1, 1, ts, table)),
            beacons = listOf(PriceBeaconItem(1, 2, "0001020304050607", beacon)),
        )

        val listingBody = V3Body.Listing(1, 50, 2_400, 1, ts + 3_600, 1, ByteArray(12))
        val listingRaw = unsigned(V3Type.LISTING, listingBody, V3Codec.actorId(ledgerPubkey)) +
            V3Codec.signRaw(unsigned(V3Type.LISTING, listingBody, V3Codec.actorId(ledgerPubkey)), ledgerKey)
        client.listingFeed = ListingFeed(
            listings = listOf(
                ListingItem(
                    listingId = "listing1",
                    sellerId = "seller",
                    crop = 1,
                    qtyKg = 50,
                    remainingKg = 50,
                    askPaise = 2_400,
                    region = 1,
                    validUntil = ts + 3_600,
                    grade = 1,
                    batchId = "000102030405060708090a0b",
                    createdSeq = 11,
                    message = listingRaw,
                ),
            ),
            nextAfterSeq = 11,
        )
        client.outbox = OutboxPage(
            items = listOf(
                OutboxItem(
                    id = 1,
                    toId = core.me,
                    msgId = "receipt1",
                    kind = "RECEIPT",
                    createdSeq = 12,
                    createdAt = ts,
                    message = ledgerRaw(
                        V3Type.RECEIPT,
                        V3Body.Receipt(ByteArray(12) { it.toByte() }, 0, 0, 12, ByteArray(8)),
                        toId = V3Codec.hex(core.me),
                    ),
                ),
            ),
            nextAfterId = 1,
        )

        assertEquals(2, loop.pricesOnce(forceBeacon = true))
        assertEquals(2L, core.priceVersions()[1])
        assertEquals(1, core.priceCaches().single().rows.size)
        assertEquals(1, loop.listingsOnce())
        assertEquals(11L, core.listingCursor())
        assertEquals(1, core.listingCaches().size)
        assertEquals(1, loop.outboxOnce())
        assertEquals(1L, core.outboxCursor())
        assertTrue(core.lastReceipt() != null)
    }
}
