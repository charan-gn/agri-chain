package com.agrichain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SmsV3CodecTest {

    private fun envelope(size: Int = 60): ByteArray {
        val body = V3Bodies.encode(
            V3Type.ANNOUNCE,
            V3Body.Announce(ByteArray(33) { 2 }, 0x0A, "farmer"),
        )
        val unsigned = V3Codec.encodeUnsigned(
            type = V3Type.ANNOUNCE,
            fromId = ByteArray(8) { it.toByte() },
            body = body,
            ts = 1_700_000_000,
        )
        val base = V3Codec.encodeEnvelope(unsigned, 7)
        return if (base.size == size) base else ByteArray(size) { base[it % base.size] }
    }

    @Test
    fun chunkHeaderAndLengthsFitOneSms() {
        val parts = SmsV3Codec.chunks(envelope())
        assertTrue(parts.isNotEmpty())
        assertEquals(parts.size, parts.size.coerceAtMost(SmsV3Codec.MAX_PARTS))
        parts.forEach { line ->
            assertTrue(line.startsWith("AM1:"))
            assertTrue(line.length <= 160)
            val parsed = SmsV3Codec.parsePart(line)
            assertTrue(parsed != null)
            assertTrue(parsed!!.payload.length <= SmsV3Codec.MAX_CHUNK)
        }
    }

    @Test
    fun fullEnvelopeFitsThreeParts() {
        val envelope = ByteArray(199) { (it % 251).toByte() }
        val parts = SmsV3Codec.chunks(envelope)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= 160 })
    }

    @Test
    fun reassemblesOutOfOrderParts() {
        val envelope = envelope()
        val parts = SmsV3Codec.chunks(envelope, maxChunk = 40)
        assertTrue(parts.size > 1)
        val reassembler = SmsV3Reassembler()
        var result: ByteArray? = null
        parts.reversed().forEachIndexed { index, part ->
            val accepted = reassembler.accept(part, 1_000L + index)
            if (index == parts.size - 1) result = accepted else assertNull(accepted)
        }
        assertTrue(envelope.contentEquals(result))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun duplicatePartsDoNotCompleteEarly() {
        val envelope = envelope()
        val parts = SmsV3Codec.chunks(envelope, maxChunk = 40)
        val reassembler = SmsV3Reassembler()
        assertNull(reassembler.accept(parts[0], 1))
        assertNull(reassembler.accept(parts[0], 2))
        assertEquals(1, reassembler.pending())
    }

    @Test
    fun rejectsUnknownOrMalformedParts() {
        assertNull(SmsV3Codec.parsePart("hello"))
        assertNull(SmsV3Codec.parsePart("AM1:zzzzzz:1/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:abcdef:0/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:abcdef:2/1:AAAA"))
        assertNull(SmsV3Codec.parsePart("AM1:abcdef:1/1:"))
        assertNull(SmsV3Codec.parsePart("AM1:abcdef:1/1:not base64!"))
        assertFalse(SmsV3Codec.isPart("AC1:abcdef:1/1:AAAA"))
    }

    @Test
    fun rejectsTooManyParts() {
        val envelope = ByteArray(199) { 1 }
        runCatching { SmsV3Codec.chunks(envelope, maxChunk = 1) }
            .onSuccess { throw AssertionError("266 parts accepted") }
    }

    @Test
    fun expiresStaleAssemblies() {
        val envelope = envelope()
        val parts = SmsV3Codec.chunks(envelope, maxChunk = 40)
        val reassembler = SmsV3Reassembler(timeoutMs = 1_000)
        assertNull(reassembler.accept(parts[0], 0))
        assertEquals(1, reassembler.pending())
        reassembler.expire(5_000)
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun shortIdMatchesMessageIdPrefix() {
        val envelope = envelope()
        val (_, message) = V3Codec.decodeEnvelope(envelope)
        val expected = V3Codec.hex(V3Codec.msgId(V3Codec.decode(message))).take(6)
        assertEquals(expected, SmsV3Codec.shortId(envelope))
    }

    @Test
    fun base64ChunksUseUrlSafeAlphabet() {
        val envelope = ByteArray(199) { (it * 7 % 256).toByte() }
        val parts = SmsV3Codec.chunks(envelope)
        val body = parts.joinToString("") { it.substringAfterLast(":") }
        assertTrue(body.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(Base64.getUrlDecoder().decode(body).contentEquals(envelope))
    }

    @Test
    fun commandParserAcceptsCropNamesAndIds() {
        val handler = SmsCommandHandler(NoopClient)
        assertEquals(SmsCommandHandler.Command(1, 1), handler.parse("PRICE wheat"))
        assertEquals(SmsCommandHandler.Command(2, 3), handler.parse("price 2 karnataka"))
        assertEquals(SmsCommandHandler.Command(4, 2), handler.parse("LISTINGS Chilli Andhra Pradesh"))
        assertNull(handler.parse("SELL wheat 500"))
        assertNull(handler.parse("PRICE moon"))
    }

    private object NoopClient : V3LedgerClient {
        override suspend fun postMessages(via: String, messages: List<ByteArray>) = emptyList<LedgerIngestResult>()
        override suspend fun prices(sinceVersion: Long) = PriceFeed(0, emptyList(), emptyList())
        override suspend fun listings(afterSeq: Long) = ListingFeed(emptyList(), afterSeq)
        override suspend fun pullOutbox(actors: List<String>, afterId: Long) =
            OutboxPage(emptyList(), afterId)
    }
}
