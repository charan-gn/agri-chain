package com.agrichain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P6 SMS chunking, reassembly, pacing and text-command rules. */
class SmsV3CodecTest {

    private val ts = 1_700_000_000L

    private fun listingEnvelope(ttl: Int = 7): ByteArray {
        val body = V3Bodies.encode(
            V3Body.Listing(
                crop = 1,
                qtyKg = 100,
                askPaise = 245_000,
                region = 1,
                validUntil = ts + 3_600,
                grade = 1,
                batchId = ByteArray(12) { 3 },
            ),
        )
        val unsigned = V3Codec.encodeUnsigned(
            V3Type.LISTING,
            ByteArray(8) { 1 },
            body,
            ts,
        )
        return V3Codec.encodeEnvelope(unsigned + ByteArray(64) { 7 }, ttl)
    }

    private fun maxEnvelope(): ByteArray {
        // 24-byte header with `to` + 110 body + 64 signature = 198-byte message.
        val unsigned = V3Codec.encodeUnsigned(
            V3Type.LISTING,
            ByteArray(8) { 1 },
            ByteArray(110) { 5 },
            ts,
            toId = ByteArray(8) { 2 },
        )
        return V3Codec.encodeEnvelope(unsigned + ByteArray(64) { 7 }, 7)
    }

    @Test
    fun chunkRoundTripFitsOneSms() {
        val envelope = listingEnvelope()
        val chunks = SmsV3Codec.chunks(envelope)
        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue("chunk too long: ${chunk.length}", chunk.length <= SmsV3Codec.MAX_TEXT)
            assertTrue(SmsV3Codec.isPart(chunk))
            val part = SmsV3Codec.parsePart(chunk)
            assertNotNull(part)
            assertTrue(part!!.payload.length <= SmsV3Codec.MAX_CHUNK)
        }
        val reassembler = SmsV3Reassembler()
        var rebuilt: ByteArray? = null
        for (chunk in chunks.reversed()) {
            rebuilt = reassembler.accept(chunk, 1_000L)
        }
        assertTrue(rebuilt!!.contentEquals(envelope))
    }

    @Test
    fun maxEnvelopeUsesAtMostThreeParts() {
        val envelope = maxEnvelope()
        assertEquals(199, envelope.size)
        val chunks = SmsV3Codec.chunks(envelope)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= SmsV3Codec.MAX_TEXT })
    }

    @Test
    fun chunkLimitIsEnforced() {
        val envelope = maxEnvelope()
        val error = runCatching { SmsV3Codec.chunks(envelope, maxChunk = 1) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun id6MatchesMessageId() {
        val envelope = listingEnvelope()
        val message = V3Codec.decode(V3Codec.decodeEnvelope(envelope).second)
        val expected = V3Codec.hex(V3Codec.msgId(message)).take(6)
        assertEquals(expected, SmsV3Codec.id6(envelope))
        assertTrue(SmsV3Codec.chunks(envelope).all { it.split(":")[1] == expected })
    }

    @Test
    fun parseRejectsForeignText() {
        assertNull(SmsV3Codec.parsePart("hello"))
        assertNull(SmsV3Codec.parsePart("AC3:123456:1/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:xyz123:1/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:123456:0/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:123456:1/7:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:123456:1/1:!!!!"))
        assertFalse(SmsV3Codec.isPart("AC1:123456:1/1:AAAA"))
    }

    @Test
    fun reassemblerDropsMismatchedId() {
        val first = SmsV3Codec.chunks(listingEnvelope())
        val second = SmsV3Codec.chunks(maxEnvelope())
        assertTrue(first.size >= 1)
        val reassembler = SmsV3Reassembler()
        // A chunk whose id does not match the payload must not complete an assembly.
        val realId = SmsV3Codec.id6(listingEnvelope())
        val forgedId = if (realId == "000000") "ffffff" else "000000"
        val forged = first[0].replaceFirst(Regex("AM1:[0-9a-f]{6}"), "AM1:$forgedId")
        assertNull(reassembler.accept(forged, 1_000L))
        assertNull(reassembler.accept(second[0], 1_001L))
    }

    @Test
    fun reassemblerExpiresStaleParts() {
        val chunks = SmsV3Codec.chunks(maxEnvelope())
        val reassembler = SmsV3Reassembler(timeoutMs = 5_000L)
        assertNull(reassembler.accept(chunks[0], 1_000L))
        assertEquals(1, reassembler.pending())
        assertEquals(1, reassembler.expire(7_000L))
        assertNull(reassembler.accept(chunks[1], 7_001L))
    }

    @Test
    fun pacingEnforcesGapAndWindow() {
        val pacing = SmsPacing(minGapMs = 2_000, windowMs = 60_000, maxPerWindow = 20)
        assertTrue(pacing.allow(0))
        pacing.record(0)
        assertFalse(pacing.allow(1_999))
        assertTrue(pacing.allow(2_000))
        pacing.record(2_000)
        for (step in 3..20) {
            val at = step * 2_000L
            assertTrue(pacing.allow(at))
            pacing.record(at)
        }
        assertEquals(20, pacing.sentInWindow(40_000))
        assertFalse(pacing.allow(40_000))
        assertEquals(60_000, pacing.nextAllowedAt(40_000))
        assertTrue(pacing.allow(60_000))
    }

    @Test
    fun outboxDrainsOnePartPerGap() {
        val sent = mutableListOf<Pair<String, String>>()
        val outbox = SmsOutbox(
            sender = { number, text ->
                sent += number to text
                true
            },
            pacing = SmsPacing(2_000, 60_000, 20),
            clock = { 0L },
        )
        assertEquals(3, outbox.enqueue("+919000000001", listOf("a", "b", "c")))
        assertEquals(1, outbox.drain(0))
        assertEquals(0, outbox.drain(1_000))
        assertEquals(1, outbox.drain(2_000))
        assertEquals(2, sent.size)
        assertEquals(1, outbox.pending())
    }

    @Test
    fun commandsParseCropsAndMultiWordRegions() {
        assertEquals(SmsCommand.Price(1, null), SmsCommands.parse("PRICE Wheat"))
        assertEquals(SmsCommand.Price(2, 1), SmsCommands.parse("price rice telangana"))
        assertEquals(
            SmsCommand.Listings(4, 2),
            SmsCommands.parse("LISTINGS Chilli Andhra Pradesh"),
        )
        assertNull(SmsCommands.parse("PRICE"))
        assertNull(SmsCommands.parse("PRICE Saffron"))
        assertNull(SmsCommands.parse("SELL Wheat"))
        assertNull(SmsCommands.parse("PRICE Wheat Atlantis"))
    }

    @Test
    fun repliesStayUnderOneSms() {
        val price = SmsCommands.formatPrice(
            SmsPriceQuote(
                crop = 1,
                region = 1,
                version = 42,
                asOf = (System.currentTimeMillis() / 1000) - 120,
                pricePaise = 245_000,
                trades = 7,
            ),
        )
        assertTrue(price.contains("Wheat"))
        assertTrue(price.contains("Rs2,450.00"))
        assertTrue(price.length <= SmsCommands.MAX_REPLY)

        val listings = SmsCommands.formatListings(
            SmsListingQuote(crop = 1, region = 1, count = 3, bestPaise = 240_000, bestQtyKg = 500),
        )
        assertTrue(listings.contains("3 open"))
        assertTrue(listings.contains("Rs2,400.00"))
        assertTrue(listings.length <= SmsCommands.MAX_REPLY)

        assertTrue(SmsCommands.noPrice(1, null).length <= SmsCommands.MAX_REPLY)
        assertTrue(SmsCommands.noListings(1, 2).contains("Andhra Pradesh"))
    }
}
