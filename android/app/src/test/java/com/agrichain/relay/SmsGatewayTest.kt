package com.agrichain.relay

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 gateway behaviour: origin tracking, receipt forwarding, text replies. */
class SmsGatewayTest {

    private val now = 1_700_000_000_000L
    private val ts = now / 1000

    private val sentEnvelopes = mutableListOf<Pair<String, ByteArray>>()
    private val sentTexts = mutableListOf<Pair<String, String>>()

    private fun listingEnvelope(): ByteArray {
        val body = V3Bodies.encode(
            V3Body.Listing(1, 100, 245_000, 1, ts + 3_600, 1, ByteArray(12) { 3 }),
        )
        val unsigned = V3Codec.encodeUnsigned(V3Type.LISTING, ByteArray(8) { 1 }, body, ts)
        return V3Codec.encodeEnvelope(unsigned + ByteArray(64) { 1 }, 7)
    }

    private fun receiptRaw(msgId: ByteArray): ByteArray {
        val body = V3Bodies.encode(V3Body.Receipt(msgId, 0, 0, 5, ByteArray(8) { 2 }))
        val unsigned = V3Codec.encodeUnsigned(V3Type.RECEIPT, ByteArray(8) { 9 }, body, ts)
        return unsigned + ByteArray(64) { 3 }
    }

    private fun gateway(
        prices: List<PriceCache> = emptyList(),
        listings: List<ListingCache> = emptyList(),
        priceFeed: (suspend () -> PriceFeed)? = null,
        listingFeed: (suspend () -> ListingFeed)? = null,
    ): SmsGateway = SmsGateway(
        priceCaches = { prices },
        listingCaches = { listings },
        priceFeed = priceFeed,
        listingFeed = listingFeed,
        sendEnvelope = { number, envelope ->
            sentEnvelopes += number to envelope
            true
        },
        sendText = { number, text ->
            sentTexts += number to text
            true
        },
        clock = { now },
    )

    @Test
    fun receiptGoesBackToTheOriginatingNumber() {
        val gateway = gateway()
        val envelope = listingEnvelope()
        val raw = V3Codec.decodeEnvelope(envelope).second
        val msgId = V3Codec.msgId(V3Codec.decode(raw))
        assertEquals(V3Codec.hex(msgId), gateway.remember(envelope, "+919000000001"))
        assertEquals(1, gateway.originCount())

        assertTrue(gateway.onLedgerMessage(receiptRaw(msgId)))
        assertEquals(1, sentEnvelopes.size)
        assertEquals("+919000000001", sentEnvelopes[0].first)
        val forwarded = V3Codec.decode(V3Codec.decodeEnvelope(sentEnvelopes[0].second).second)
        assertEquals(V3Type.RECEIPT, forwarded.type)
        val body = V3Bodies.parse(V3Type.RECEIPT, forwarded.body) as V3Body.Receipt
        assertTrue(body.msgId.contentEquals(msgId))
        assertEquals(0, gateway.originCount())

        // A second receipt for the same message is not forwarded twice.
        assertFalse(gateway.onLedgerMessage(receiptRaw(msgId)))
    }

    @Test
    fun nonUplinkMessagesAreNotTracked() {
        val gateway = gateway()
        val announce = V3Codec.encodeUnsigned(
            V3Type.ANNOUNCE,
            ByteArray(8) { 1 },
            ByteArray(4) { 2 },
            ts,
        )
        assertNull(gateway.remember(V3Codec.encodeEnvelope(announce + ByteArray(64) { 1 }, 7), "+91"))
        assertEquals(0, gateway.originCount())
    }

    @Test
    fun priceReplyUsesCacheAndFits160() = runBlocking {
        val gateway = gateway(
            prices = listOf(
                PriceCache(
                    region = 1,
                    version = 42,
                    asOf = ts - 120,
                    parts = mapOf(1 to listOf(CachedPriceRow(1, 245_000, 7))),
                    updatedAt = now,
                    source = "ledger",
                ),
            ),
        )
        val reply = gateway.answer("+919000000002", "PRICE Wheat")
        assertNotNull(reply)
        assertTrue(reply!!.contains("Wheat"))
        assertTrue(reply.contains("Rs2,450.00"))
        assertTrue(reply.length <= 160)
        assertEquals("+919000000002" to reply, sentTexts.single())
    }

    @Test
    fun priceReplyFallsBackToLedgerFeed() = runBlocking {
        val table = V3Body.PriceTable(
            region = 2,
            version = 9,
            asOf = ts - 60,
            part = 1,
            parts = 1,
            rows = listOf(V3Body.PriceRow(2, 310_000, 3)),
        )
        val message = V3Codec.encodeUnsigned(
            V3Type.PRICE_TABLE,
            ByteArray(8) { 9 },
            V3Bodies.encode(table),
            ts,
        ) + ByteArray(64) { 3 }
        val feed = PriceFeed(9, listOf(PriceTableItem(2, 9, 1, 1, ts - 60, message)), emptyList())
        val gateway = gateway(priceFeed = { feed })
        val reply = gateway.answer("+919000000003", "PRICE Rice Andhra Pradesh")
        assertNotNull(reply)
        assertTrue(reply!!.contains("Rice"))
        assertTrue(reply.contains("Rs3,100.00"))
        assertTrue(reply.contains("Andhra Pradesh"))
    }

    @Test
    fun listingReplyCountsOpenListingsAndBestAsk() = runBlocking {
        val gateway = gateway(
            listings = listOf(
                ListingCache(
                    listingId = "a",
                    raw = ByteArray(1),
                    seq = 1,
                    sellerId = "s1",
                    crop = 1,
                    qtyKg = 100,
                    remainingKg = 100,
                    askPaise = 240_000,
                    region = 1,
                    validUntil = ts + 3_600,
                    grade = 1,
                    batchId = "b1",
                ),
                ListingCache(
                    listingId = "b",
                    raw = ByteArray(1),
                    seq = 2,
                    sellerId = "s2",
                    crop = 1,
                    qtyKg = 50,
                    remainingKg = 50,
                    askPaise = 250_000,
                    region = 1,
                    validUntil = ts + 3_600,
                    grade = 1,
                    batchId = "b2",
                ),
                ListingCache(
                    listingId = "expired",
                    raw = ByteArray(1),
                    seq = 3,
                    sellerId = "s3",
                    crop = 1,
                    qtyKg = 10,
                    remainingKg = 10,
                    askPaise = 1_000,
                    region = 1,
                    validUntil = ts - 10,
                    grade = 1,
                    batchId = "b3",
                ),
            ),
        )
        val reply = gateway.answer("+919000000004", "LISTINGS Wheat Telangana")
        assertNotNull(reply)
        assertTrue(reply!!.contains("2 open"))
        assertTrue(reply.contains("Rs2,400.00"))
        assertTrue(reply.contains("100kg"))
        assertTrue(reply.length <= 160)
    }

    @Test
    fun noPriceReplyStillAnswers() = runBlocking {
        val gateway = gateway()
        val reply = gateway.answer("+919000000005", "PRICE Maize")
        assertNotNull(reply)
        assertTrue(reply!!.contains("no price yet"))
        assertEquals(1, sentTexts.size)
    }

    @Test
    fun unknownTextIsIgnored() = runBlocking {
        val gateway = gateway()
        assertNull(gateway.answer("+919000000006", "hello"))
        assertNull(gateway.answer("+919000000006", "SELL Wheat"))
        assertTrue(sentTexts.isEmpty())
    }
}
