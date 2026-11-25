package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmsCodecTest {

    private val batchId = "22222222-2222-4222-8222-222222222222"

    private object TestSigner : Signer {
        override val actorId = "farmer:11111111-1111-4111-8111-111111111111"
        override fun sign(bytes: ByteArray): ByteArray = byteArrayOf(9, 8, 7)
    }

    private fun envelope(): MeshEnvelope {
        val register = Event.create(
            batchId = batchId,
            type = "REGISTER",
            data = mapOf("crop" to "wheat", "grade" to "A", "weightKg" to 100),
            signer = TestSigner,
            ts = 1764000000000,
            prevHash = "",
        )
        val tamper = Event.create(
            batchId = batchId,
            type = "TAMPER",
            data = mapOf("kind" to "light", "detail" to "lid opened"),
            signer = TestSigner,
            ts = 1764000001000,
            prevHash = register.hash,
        )
        return MeshEnvelope.events("phone-a", listOf(register, tamper))
    }

    @Test
    fun `chunks fit in one text SMS`() {
        val chunks = SmsCodec.chunks(envelope())

        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue("${chunk.length} chars", chunk.length <= SmsCodec.MAX_SMS_CHARS)
            val part = SmsCodec.parsePart(chunk)
            assertTrue(part != null)
            assertTrue(part!!.payload.length <= SmsCodec.MAX_CHUNK)
        }
    }

    @Test
    fun `reassembles out of order with duplicates`() {
        val original = envelope()
        val chunks = SmsCodec.chunks(original)
        val reassembler = SmsReassembler()
        var result: MeshEnvelope? = null

        val shuffled = (chunks + chunks.first()).shuffled(Random(7))
        shuffled.forEach { chunk ->
            result = reassembler.accept(chunk, now = 1_000) ?: result
        }

        assertEquals(original, result)
    }

    @Test
    fun `a lost chunk keeps the envelope pending until retry`() {
        val original = envelope()
        val chunks = SmsCodec.chunks(original)
        val reassembler = SmsReassembler()

        chunks.drop(1).forEach { assertNull(reassembler.accept(it, now = 1_000)) }
        assertEquals(1, reassembler.pending())

        val rebuilt = reassembler.accept(chunks.first(), now = 2_000)

        assertEquals(original, rebuilt)
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `expired assemblies are dropped`() {
        val chunks = SmsCodec.chunks(envelope())
        val reassembler = SmsReassembler(timeoutMs = 60_000)

        assertNull(reassembler.accept(chunks.first(), now = 0))
        assertEquals(1, reassembler.expire(now = 61_000))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `rejects malformed parts`() {
        assertNull(SmsCodec.parsePart("hello"))
        assertNull(SmsCodec.parsePart("AC1|abcd|1/1|"))
        assertNull(SmsCodec.parsePart("AC1|abcd|2/1|AAAA"))
        assertNull(SmsCodec.parsePart("AC1|abcd|0/2|AAAA"))
        assertNull(SmsCodec.parsePart("AC2|abcd|1/1|AAAA"))
        assertNull(SmsCodec.parsePart("AC3|nothex!!|1/1|AAAA"))
        assertTrue(SmsCodec.isPart("AC1|abcd|1/1|AAAA"))
        assertTrue(SmsCodec.isPart("AC3|aabbccdd|1/1|AAAA"))
    }

    @Test
    fun `v3 chunks are binary url-safe and CRC checked`() {
        val original = envelope()
        val chunks = SmsCodec.chunks(original)
        val joined = chunks
            .map { SmsCodec.parsePart(it)!! }
            .sortedBy { it.index }
            .joinToString("") { it.payload }
        val bytes = java.util.Base64.getUrlDecoder().decode(joined)

        assertEquals(original, SmsCodec.decodeBinary(bytes))
        assertTrue(chunks.first().startsWith("AC3|"))
        assertTrue(chunks.first().split("|")[3].none { it == '+' || it == '/' })
    }

    @Test
    fun `a corrupted v3 body fails the CRC`() {
        val bytes = SmsCodec.encodeBinary(envelope())
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()

        assertNull(SmsCodec.decodeBinary(bytes))
    }

    @Test
    fun `signed ack survives the binary codec`() {
        val event = envelope().events.first()
        val ack = MeshEnvelope.ack(
            "phone-b",
            listOf(event.eventId),
            TestSigners.create("mesh:B"),
        )

        val decoded = SmsCodec.decodeBinary(SmsCodec.encodeBinary(ack))

        assertEquals(MeshKind.ACK, decoded?.kind)
        assertEquals(ack.ackedEventIds, decoded?.ackedEventIds)
        assertTrue(decoded?.verifyAck() == true)
    }

    @Test
    fun `sms policy never carries gps`() {
        val register = Event.create(
            batchId = batchId,
            type = "REGISTER",
            data = mapOf("crop" to "wheat", "grade" to "A", "weightKg" to 100),
            signer = TestSigner,
            prevHash = "",
        )
        val gps = Event.create(
            batchId = batchId,
            type = "GPS",
            data = mapOf("accuracyM" to 5),
            signer = TestSigner,
            lat = "17.385044",
            lon = "78.486671",
            prevHash = register.hash,
        )
        val envelope = MeshEnvelope.events("box", listOf(register, gps))

        val filtered = SmsPolicy.filter(envelope)

        assertEquals(listOf("REGISTER"), filtered.events.map { it.type })
    }
}
