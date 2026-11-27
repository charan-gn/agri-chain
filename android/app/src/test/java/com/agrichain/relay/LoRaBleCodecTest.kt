package com.agrichain.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoRaBleCodecTest {

    private fun envelope(size: Int = 0): ByteArray {
        val body = V3Bodies.encode(
            V3Body.Announce(ByteArray(33) { 2 }, 0x0A, "farmer"),
        )
        val unsigned = V3Codec.encodeUnsigned(
            type = V3Type.ANNOUNCE,
            fromId = ByteArray(8) { it.toByte() },
            body = body,
            ts = 1_700_000_000,
        )
        val base = V3Codec.encodeEnvelope(unsigned, 3)
        return if (size == 0 || size == base.size) base else ByteArray(size) { base[it % base.size] }
    }

    @Test
    fun loraFrameFitsMeshtasticPacket() {
        val envelope = ByteArray(199) { (it % 251).toByte() }
        val parts = LoRaV3Codec.chunks(envelope)
        assertEquals(2, parts.size)
        parts.forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= LoRaV3Codec.MAX_PACKET_BYTES)
            assertTrue(line.startsWith("LM1:"))
        }
    }

    @Test
    fun loraReassemblesOutOfOrder() {
        val envelope = envelope()
        val parts = LoRaV3Codec.chunks(envelope, maxChunk = 40)
        val reassembler = LoRaV3Reassembler()
        var result: ByteArray? = null
        parts.reversed().forEachIndexed { index, part ->
            val accepted = reassembler.accept(part, 1_000L + index)
            if (index == parts.size - 1) result = accepted else assertNull(accepted)
        }
        assertArrayEquals(envelope, result)
    }

    @Test
    fun loraRejectsMalformedAndExpires() {
        assertNull(LoRaV3Codec.parsePart("MC1|deadbeef|1/1|AAAA"))
        assertNull(LoRaV3Codec.parsePart("LM1:zzzzzz:1/1:AAAA"))
        assertNull(LoRaV3Codec.parsePart("LM1:abcdef:3/2:AAAA"))
        val parts = LoRaV3Codec.chunks(envelope(), maxChunk = 40)
        val reassembler = LoRaV3Reassembler(timeoutMs = 500)
        assertNull(reassembler.accept(parts[0], 0))
        reassembler.expire(1_000)
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun bleChunksRoundTripAtSeveralMtuSizes() {
        val envelope = V3Codec.encodeEnvelope(
            V3Codec.encodeUnsigned(
                type = V3Type.ANNOUNCE,
                fromId = ByteArray(8) { it.toByte() },
                body = ByteArray(V3.MAX_BODY) { index -> (index * 13).toByte() },
                ts = 1_700_000_000,
            ),
            3,
        )
        for (frameSize in listOf(23, 64, 180, 512)) {
            val frames = BleV3Codec.chunks(envelope, frameSize)
            assertTrue(frames.all { it.size <= frameSize })
            val reassembler = BleV3Reassembler()
            var result: ByteArray? = null
            frames.reversed().forEachIndexed { index, bytes ->
                val frame = BleV3Codec.parse(bytes) ?: error("frame did not parse")
                val accepted = reassembler.accept("peer", frame, 1_000L + index)
                if (index == frames.size - 1) result = accepted else assertNull(accepted)
            }
            assertArrayEquals("frameSize=$frameSize", envelope, result)
        }
    }

    @Test
    fun bleRejectsBadMagicLengthAndLinkMixups() {
        val envelope = envelope()
        val frames = BleV3Codec.chunks(envelope, 64)
        val good = BleV3Codec.parse(frames[0])
        assertTrue(good != null)

        val badMagic = frames[0].copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertNull(BleV3Codec.parse(badMagic))

        val badLength = frames[0].copyOf()
        badLength[12] = 0
        badLength[13] = 0
        assertNull(BleV3Codec.parse(badLength))

        val reassembler = BleV3Reassembler()
        var result: ByteArray? = null
        frames.forEachIndexed { index, bytes ->
            val frame = BleV3Codec.parse(bytes)!!
            val link = if (index == frames.size - 1) "other" else "peer"
            result = reassembler.accept(link, frame, 1_000L + index)
        }
        assertNull(result)
        assertEquals(2, reassembler.pending())
    }

    @Test
    fun bleReassemblerRejectsOversizeBody() {
        val big = ByteArray(BleV3Codec.MAX_BODY + 1) { 1 }
        runCatching { BleV3Codec.chunks(big, 512) }
            .onSuccess { throw AssertionError("oversize BLE body accepted") }
    }
}
