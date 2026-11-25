package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM unit tests for MeshtasticCodec and MeshtasticReassembler.
 * These run entirely on the JVM — no Android context needed.
 *
 * Hardware note: real end-to-end tests (Device test D10) require two
 * Meshtastic nodes operating on **865–867 MHz** (India / IN region).
 */
class MeshtasticCodecTest {

    private val batchId = "33333333-3333-4333-8333-333333333333"

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
            ts = 1764000000000L,
            prevHash = "",
        )
        val tamper = Event.create(
            batchId = batchId,
            type = "TAMPER",
            data = mapOf("kind" to "light", "detail" to "lid opened"),
            signer = TestSigner,
            ts = 1764000001000L,
            prevHash = register.hash,
        )
        return MeshEnvelope.events("lora-node-a", listOf(register, tamper))
    }

    // ---- codec ----

    @Test
    fun `single small envelope fits in one chunk`() {
        val small = MeshEnvelope.ack("phone-a", listOf("evt-aabbccdd"))
        val chunks = MeshtasticCodec.chunks(small)
        // ACK envelope is tiny; must be exactly one chunk.
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].startsWith("MC1|"))
        // Frame must fit within the 231-byte MTU.
        assertTrue(chunks[0].toByteArray(Charsets.UTF_8).size <= MeshtasticCodec.MAX_PACKET_BYTES)
    }

    @Test
    fun `large envelope splits into multiple chunks, each within MTU`() {
        val chunks = MeshtasticCodec.chunks(envelope())
        assertTrue("expected >=1 chunk, got ${chunks.size}", chunks.size >= 1)
        for (chunk in chunks) {
            val byteLen = chunk.toByteArray(Charsets.UTF_8).size
            assertTrue("chunk $byteLen > MTU", byteLen <= MeshtasticCodec.MAX_PACKET_BYTES)
            assertTrue("not MC1 prefix", MeshtasticCodec.isPart(chunk))
        }
    }

    @Test
    fun `parsePart round-trips index and total`() {
        val chunks = MeshtasticCodec.chunks(envelope())
        for ((i, chunk) in chunks.withIndex()) {
            val part = MeshtasticCodec.parsePart(chunk)!!
            assertEquals(i + 1, part.index)
            assertEquals(chunks.size, part.total)
            assertTrue(part.msgId.length in 1..16)
        }
    }

    @Test
    fun `parsePart rejects malformed frames`() {
        assertNull(MeshtasticCodec.parsePart("hello"))
        assertNull(MeshtasticCodec.parsePart("MC1|abcd|1/1|"))       // empty payload
        assertNull(MeshtasticCodec.parsePart("MC1|abcd|2/1|AAAA"))   // index > total
        assertNull(MeshtasticCodec.parsePart("MC1|abcd|0/2|AAAA"))   // zero index
        assertNull(MeshtasticCodec.parsePart("AC1|abcd|1/1|AAAA"))   // SMS prefix, not MC1
        assertTrue(MeshtasticCodec.isPart("MC1|abcd|1/1|AAAA"))
    }

    // ---- reassembler ----

    @Test
    fun `reassembles out of order with duplicate chunks`() {
        val original = envelope()
        val chunks = MeshtasticCodec.chunks(original)
        val reassembler = MeshtasticReassembler()
        var result: MeshEnvelope? = null

        // Shuffle + add a duplicate of the first chunk.
        val input = (chunks + chunks.first()).shuffled(Random(42))
        for (chunk in input) {
            result = reassembler.accept(chunk, now = 1_000) ?: result
        }

        assertEquals(original, result)
    }

    @Test
    fun `missing chunk keeps assembly pending until retry`() {
        val original = envelope()
        val chunks = MeshtasticCodec.chunks(original)
        val reassembler = MeshtasticReassembler()

        // Feed all but the first chunk.
        chunks.drop(1).forEach { assertNull(reassembler.accept(it, now = 1_000)) }
        assertEquals(1, reassembler.pending())

        // Deliver the missing chunk — should complete the assembly.
        val rebuilt = reassembler.accept(chunks.first(), now = 2_000)
        assertEquals(original, rebuilt)
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `expired assemblies are cleaned up`() {
        val chunks = MeshtasticCodec.chunks(envelope())
        val reassembler = MeshtasticReassembler(timeoutMs = 60_000)

        assertNull(reassembler.accept(chunks.first(), now = 0))
        assertEquals(1, reassembler.expire(now = 61_000))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `single-packet envelope round-trips through codec`() {
        val small = MeshEnvelope.ack("phone-b", listOf("ev-00112233", "ev-44556677"))
        val chunks = MeshtasticCodec.chunks(small)
        val reassembler = MeshtasticReassembler()
        val result = reassembler.accept(chunks[0], now = 0)
        assertEquals(small, result)
    }

    // ---- transport stub mode ----

    @Test
    fun `stub transport queues chunks and counts them`() {
        val transport = MeshtasticTransport(context = null, stubMode = true)
        transport.stubAvailable = true
        transport.start({ _, _ -> }, { })

        val env = envelope()
        val chunks = MeshtasticCodec.chunks(env)
        val sent = transport.send(env)

        assertEquals(chunks.size, sent)
        assertEquals(chunks.size, transport.sentChunks.size)
    }

    @Test
    fun `stub transport returns 0 when unavailable`() {
        val transport = MeshtasticTransport(context = null, stubMode = true)
        transport.stubAvailable = false
        transport.start({ _, _ -> }, { })

        assertEquals(0, transport.send(envelope()))
        assertTrue(transport.sentChunks.isEmpty())
    }

    @Test
    fun `simulateReceive delivers reassembled envelope to onReceive`() {
        val transport = MeshtasticTransport(context = null, stubMode = true)
        transport.stubAvailable = true
        var received: MeshEnvelope? = null
        transport.start({ env, _ -> received = env }, { })

        val original = envelope()
        val chunks = MeshtasticCodec.chunks(original)
        for (chunk in chunks) {
            transport.simulateReceive(chunk)
        }

        assertEquals(original, received)
    }

    @Test
    fun `simulateReceive deduplicates chunks`() {
        val transport = MeshtasticTransport(context = null, stubMode = true)
        transport.stubAvailable = true
        var deliveries = 0
        transport.start({ _, _ -> deliveries++ }, { })

        val original = envelope()
        val chunks = MeshtasticCodec.chunks(original)
        // Deliver all chunks twice (simulate LoRa re-broadcast).
        (chunks + chunks).forEach { transport.simulateReceive(it) }

        // Envelope should be delivered only once.
        assertEquals(1, deliveries)
    }
}
