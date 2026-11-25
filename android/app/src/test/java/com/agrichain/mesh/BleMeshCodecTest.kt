package com.agrichain.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMeshCodecTest {

    @Test
    fun `message id is stable for one envelope and differs between envelopes`() {
        val first = BleMeshCodec.messageId("abcd1234")
        val second = BleMeshCodec.messageId("abcd1234")
        val other = BleMeshCodec.messageId("abcd1235")

        assertEquals(first, second)
        assertNotEquals(first, other)
    }

    @Test
    fun `peer hash is four bytes and stable`() {
        val hello = BleMeshCodec.hello(BleMeshCodec.peerHash("phone-a"))
        val frame = BleMeshCodec.parse(hello)

        assertEquals(BleMeshCodec.TYPE_HELLO, frame?.type)
        assertEquals(BleMeshCodec.HELLO_SIZE, frame?.payload?.size)
        assertEquals(BleMeshCodec.peerHash("phone-a"), frame?.let(BleMeshCodec::helloHash))
        assertNull(BleReassembler().accept("link", frame!!, now = 0))
    }

    @Test
    fun `single frame round trips through the reassembler`() {
        val body = "hello mesh".toByteArray()
        val frames = BleMeshCodec.chunks(messageId = 42, body = body, maxFrame = 64)

        assertEquals(1, frames.size)
        val frame = BleMeshCodec.parse(frames[0])!!
        assertEquals(42, frame.messageId)
        assertEquals(1, frame.index)
        assertEquals(1, frame.total)
        assertArrayEquals(body, BleReassembler().accept("link", frame, now = 0))
    }

    @Test
    fun `multi frame bodies reassemble out of order with duplicates`() {
        val body = ByteArray(2_000) { (it % 251).toByte() }
        val frames = BleMeshCodec.chunks(messageId = 7, body = body, maxFrame = 64)
        val reassembler = BleReassembler()
        val parsed = frames.map { BleMeshCodec.parse(it)!! }

        assertTrue(parsed.size > 1)
        assertNull(reassembler.accept("link", parsed[1], now = 0))
        assertNull(reassembler.accept("link", parsed[1], now = 1))
        var result: ByteArray? = null
        parsed.asReversed().forEach { frame ->
            result = reassembler.accept("link", frame, now = 2) ?: result
        }

        assertArrayEquals(body, result)
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `conflicting duplicate drops the assembly`() {
        val first = BleMeshCodec.parse(
            BleMeshCodec.chunks(messageId = 9, body = "a".repeat(30).toByteArray(), maxFrame = 20)[0]
        )!!
        val conflicting = BleMeshCodec.parse(
            BleMeshCodec.chunks(messageId = 9, body = "b".repeat(30).toByteArray(), maxFrame = 20)[0]
        )!!
        val reassembler = BleReassembler()

        assertNull(reassembler.accept("link", first, now = 0))
        assertNull(reassembler.accept("link", conflicting, now = 1))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `stale assemblies expire`() {
        val frame = BleMeshCodec.parse(
            BleMeshCodec.chunks(messageId = 3, body = ByteArray(100), maxFrame = 20)[0]
        )!!
        val reassembler = BleReassembler(timeoutMs = 1_000)

        assertNull(reassembler.accept("link", frame, now = 0))
        assertEquals(1, reassembler.pending())
        assertEquals(1, reassembler.expire(now = 2_000))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun `malformed frames are rejected`() {
        val good = BleMeshCodec.chunks(messageId = 1, body = "payload".toByteArray(), maxFrame = 64)[0]

        assertNull(BleMeshCodec.parse(good.copyOf(0)))
        assertNull(BleMeshCodec.parse(good.copyOf().also { it[0] = 0 }))
        assertNull(BleMeshCodec.parse(good.copyOf().also { it[2] = 9 }))
        assertNull(BleMeshCodec.parse(good.copyOf().also { it[12] = 0; it[13] = 0 }))
        assertNull(BleMeshCodec.parse(good.copyOf(good.size - 1)))
    }

    @Test
    fun `oversized bodies and frame counts are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleMeshCodec.chunks(
                messageId = 1,
                body = ByteArray(BleMeshCodec.MAX_BODY + 1),
                maxFrame = 128,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleMeshCodec.chunks(
                messageId = 1,
                body = ByteArray(1_600),
                maxFrame = BleMeshCodec.MIN_FRAME,
            )
        }
    }
}
