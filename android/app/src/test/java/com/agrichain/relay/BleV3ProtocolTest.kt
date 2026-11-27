package com.agrichain.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

/** P8 BLE framing, scan duty cycle, link election and retry rules. */
class BleV3ProtocolTest {

    private fun envelope(size: Int): ByteArray = ByteArray(size) { (it and 0xFF).toByte() }

    @Test
    fun oneEnvelopeFitsOneFrameAtMtu247() {
        val payload = envelope(199)
        val frameSize = BleFrameCodec.frameSize(BleFrameCodec.REQUESTED_MTU)
        assertEquals(244, frameSize)
        val frames = BleFrameCodec.chunks(payload, frameSize)
        assertEquals(1, frames.size)
        val frame = BleFrameCodec.parse(frames.single())
        assertNotNull(frame)
        assertEquals(0, frame!!.seq)
        assertEquals(1, frame.total)
        assertTrue(frame.payload.contentEquals(payload))
    }

    @Test
    fun defaultMtuUsesTwelveFramesAndReassembles() {
        val payload = envelope(199)
        val frames = BleFrameCodec.chunks(payload, BleFrameCodec.MIN_FRAME)
        assertEquals(12, frames.size)
        val reassembler = BleReassembler()
        var rebuilt: ByteArray? = null
        for (frame in frames) {
            val parsed = BleFrameCodec.parse(frame) ?: error("bad frame")
            rebuilt = reassembler.accept("aa:bb", parsed, 1_000L)
        }
        assertTrue(rebuilt!!.contentEquals(payload))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun outOfOrderAndDuplicateFramesAreTolerated() {
        val payload = envelope(199)
        val frames = BleFrameCodec.chunks(payload, BleFrameCodec.MIN_FRAME)
        val reassembler = BleReassembler()
        val parsed = frames.map { BleFrameCodec.parse(it)!! }
        var rebuilt: ByteArray? = null
        // First part twice, then the rest in reverse.
        rebuilt = reassembler.accept("k", parsed.first(), 1_000L)
        assertNull(rebuilt)
        rebuilt = reassembler.accept("k", parsed.first(), 1_001L)
        assertNull(rebuilt)
        for (frame in parsed.drop(1).reversed()) {
            rebuilt = reassembler.accept("k", frame, 1_002L)
        }
        assertTrue(rebuilt!!.contentEquals(payload))
    }

    @Test
    fun restartingAtSeqZeroDiscardsTheOldAssembly() {
        val first = BleFrameCodec.chunks(envelope(100), BleFrameCodec.MIN_FRAME)
        val second = BleFrameCodec.chunks(envelope(50), BleFrameCodec.MIN_FRAME)
        val reassembler = BleReassembler()
        assertNull(reassembler.accept("k", BleFrameCodec.parse(first[0])!!, 1_000L))
        assertEquals(1, reassembler.pending())
        // A new seq=0 frame replaces the stale assembly instead of mixing bytes.
        val secondParsed = second.map { BleFrameCodec.parse(it)!! }
        var rebuilt: ByteArray? = null
        for (frame in secondParsed) {
            rebuilt = reassembler.accept("k", frame, 1_100L)
        }
        assertTrue(rebuilt!!.contentEquals(envelope(50)))
    }

    @Test
    fun reassemblerExpiresStaleAssemblies() {
        val frames = BleFrameCodec.chunks(envelope(199), BleFrameCodec.MIN_FRAME)
        val reassembler = BleReassembler(timeoutMs = 5_000L)
        assertNull(reassembler.accept("k", BleFrameCodec.parse(frames[0])!!, 1_000L))
        assertEquals(1, reassembler.pending())
        assertEquals(0, reassembler.expire(5_500L))
        assertEquals(1, reassembler.expire(7_000L))
        assertEquals(0, reassembler.pending())
    }

    @Test
    fun frameCodecRejectsGarbage() {
        assertNull(BleFrameCodec.parse(ByteArray(0)))
        assertNull(BleFrameCodec.parse(byteArrayOf(0, 1)))
        // seq beyond total
        assertNull(BleFrameCodec.parse(byteArrayOf(3, 3, 9)))
        // total zero
        assertNull(BleFrameCodec.parse(byteArrayOf(0, 0, 9)))
        // total above the cap
        assertNull(BleFrameCodec.parse(byteArrayOf(0, 40, 9)))
    }

    @Test
    fun frameSizeClampsToAttLimits() {
        assertEquals(BleFrameCodec.MIN_FRAME, BleFrameCodec.frameSize(23))
        assertEquals(BleFrameCodec.MIN_FRAME, BleFrameCodec.frameSize(10))
        assertEquals(244, BleFrameCodec.frameSize(247))
        assertEquals(244, BleFrameCodec.frameSize(517))
    }

    @Test
    fun oversizedEnvelopeIsRefused() {
        val error = runCatching {
            BleFrameCodec.chunks(ByteArray(400), BleFrameCodec.MIN_FRAME)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun scanRunsContinuouslyBelowTwoLinks() {
        val gate = BleScanGate()
        assertTrue(gate.allowStart(0, links = 0))
        gate.onStart(0)
        assertFalse(gate.shouldStop(6_000, links = 1))
        assertFalse(gate.shouldStop(60_000, links = 1))
    }

    @Test
    fun scanDutyCyclesAtTwoLinks() {
        val gate = BleScanGate(windowMs = 5_000, idleMs = 10_000)
        assertTrue(gate.allowStart(0, links = 2))
        gate.onStart(0)
        assertFalse(gate.shouldStop(4_999, links = 2))
        assertTrue(gate.shouldStop(5_000, links = 2))
        gate.onStop(5_000)
        assertFalse(gate.allowStart(5_001, links = 2))
        assertFalse(gate.allowStart(14_999, links = 2))
        assertTrue(gate.allowStart(15_000, links = 2))
    }

    @Test
    fun scanStartsAreCappedPerWindow() {
        val gate = BleScanGate(rateWindowMs = 30_000, maxStarts = 5)
        for (step in 0 until 5) {
            val at = step * 1_000L
            assertTrue(gate.allowStart(at, links = 0))
            gate.onStart(at)
            gate.onStop(at + 100)
        }
        assertEquals(5, gate.startsInWindow(5_000))
        assertFalse(gate.allowStart(5_000, links = 0))
        assertEquals(30_000L, gate.nextAllowedAt(5_000, links = 0))
        // The oldest start ages out after thirty seconds.
        assertTrue(gate.allowStart(30_000, links = 0))
    }

    @Test
    fun nextAllowedAtHonoursIdleWindow() {
        val gate = BleScanGate(windowMs = 5_000, idleMs = 10_000)
        gate.onStart(0)
        gate.onStop(5_000)
        assertEquals(15_000L, gate.nextAllowedAt(6_000, links = 3))
        assertNull(gate.nextAllowedAt(15_000, links = 3))
    }

    @Test
    fun lowerActorIsTheCanonicalCentral() {
        val low = "0000000000000001"
        val high = "ffffffffffffffff"
        assertTrue(BleLinkElection.canonicalCentral(low, high))
        assertFalse(BleLinkElection.canonicalCentral(high, low))

        // We are the lower actor: keep the link we opened as central.
        assertTrue(BleLinkElection.preferCandidate(low, high, candidateIsCentral = true))
        assertFalse(BleLinkElection.preferCandidate(low, high, candidateIsCentral = false))
        // We are the higher actor: keep the link the peer opened.
        assertTrue(BleLinkElection.preferCandidate(high, low, candidateIsCentral = false))
        assertFalse(BleLinkElection.preferCandidate(high, low, candidateIsCentral = true))
    }

    @Test
    fun backoffDoublesAndCaps() {
        val backoff = BleBackoff(baseMs = 1_000, maxMs = 60_000)
        assertEquals(1_000, backoff.nextDelay(0))
        assertEquals(2_000, backoff.nextDelay(1))
        assertEquals(4_000, backoff.nextDelay(2))
        assertEquals(8_000, backoff.nextDelay(3))
        assertEquals(60_000, backoff.nextDelay(20))
        assertTrue(backoff.isRetryable(133))
        assertTrue(backoff.isRetryable(8))
        assertTrue(backoff.isRetryable(19))
        assertFalse(backoff.isRetryable(0))
        assertFalse(backoff.isRetryable(22))
    }

    @Test
    fun uuidsAreRandomV4AndDistinct() {
        val random = SecureRandom()
        val seen = mutableSetOf<UUID>()
        repeat(32) {
            val (service, frame) = BleUuids.random(random)
            assertTrue(service != frame)
            assertEquals(4, service.version())
            assertEquals(4, frame.version())
            assertEquals(2, service.variant())
            assertEquals(2, frame.variant())
            assertTrue(seen.add(service))
            assertTrue(seen.add(frame))
        }
    }
}
