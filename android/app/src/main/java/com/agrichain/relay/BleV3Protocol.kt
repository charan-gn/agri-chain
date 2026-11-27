package com.agrichain.relay

import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID

/**
 * Pure BLE link rules for the v3 transport. Everything here runs on the JVM so
 * the frame format, scan duty cycle, link election and retry policy are proven
 * without a radio.
 *
 * An envelope is at most 199 bytes (`ttl || message`), so once the MTU is 247
 * one ATT write carries a whole envelope. Before the MTU is raised the frame
 * header lets the same envelope travel in 18-byte payloads.
 */
object BleFrameCodec {

    /** seq(1) || total(1). */
    const val HEADER = 2

    /** 199 bytes / 18-byte payload = 12 frames; 16 leaves headroom. */
    const val MAX_TOTAL = 16

    /** ATT overhead is 3 bytes for a write. */
    const val ATT_OVERHEAD = 3

    const val REQUESTED_MTU = 247

    /** 23-byte default ATT MTU. */
    const val MIN_FRAME = 20

    data class Frame(val seq: Int, val total: Int, val payload: ByteArray)

    fun frameSize(mtu: Int): Int =
        (mtu - ATT_OVERHEAD).coerceIn(MIN_FRAME, REQUESTED_MTU - ATT_OVERHEAD)

    fun chunks(envelope: ByteArray, frameSize: Int = MIN_FRAME): List<ByteArray> {
        require(envelope.isNotEmpty()) { "envelope is empty" }
        val payload = frameSize - HEADER
        require(payload > 0) { "frame size $frameSize leaves no payload" }
        val total = (envelope.size + payload - 1) / payload
        require(total <= MAX_TOTAL) {
            "${envelope.size} bytes needs $total frames at $frameSize; max $MAX_TOTAL"
        }
        val out = ArrayList<ByteArray>(total)
        var offset = 0
        for (seq in 0 until total) {
            val end = minOf(offset + payload, envelope.size)
            val frame = ByteArray(HEADER + (end - offset))
            frame[0] = seq.toByte()
            frame[1] = total.toByte()
            envelope.copyInto(frame, HEADER, offset, end)
            out += frame
            offset = end
        }
        return out
    }

    fun parse(frame: ByteArray): Frame? {
        if (frame.size <= HEADER) return null
        val seq = frame[0].toInt() and 0xFF
        val total = frame[1].toInt() and 0xFF
        if (total !in 1..MAX_TOTAL) return null
        if (seq >= total) return null
        return Frame(seq, total, frame.copyOfRange(HEADER, frame.size))
    }
}

/**
 * Reassembles frames per link. The transport sends one envelope at a time per
 * link, so a single assembly slot is enough; a new `seq = 0` frame restarts it.
 */
class BleReassembler(
    private val maxTotal: Int = BleFrameCodec.MAX_TOTAL,
    private val timeoutMs: Long = 30_000,
) {

    private class Assembly(val total: Int, val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        var bytes = 0
    }

    private val assemblies = HashMap<String, Assembly>()

    fun accept(key: String, frame: BleFrameCodec.Frame, now: Long): ByteArray? {
        if (frame.total > maxTotal) return null
        if (frame.seq == 0) {
            assemblies[key] = Assembly(frame.total, now)
        }
        val assembly = assemblies[key] ?: return null
        if (assembly.total != frame.total) {
            assemblies.remove(key)
            return null
        }
        if (assembly.parts[frame.seq] == null) {
            assembly.parts[frame.seq] = frame.payload
            assembly.received += 1
            assembly.bytes += frame.payload.size
        }
        if (assembly.received < assembly.total) return null
        assemblies.remove(key)
        val out = ByteArray(assembly.bytes)
        var offset = 0
        for (part in assembly.parts) {
            val value = part ?: return null
            value.copyInto(out, offset)
            offset += value.size
        }
        return out
    }

    fun expire(now: Long): Int {
        val stale = assemblies.filterValues { now - it.startedAt > timeoutMs }.keys
        for (key in stale) assemblies.remove(key)
        return stale.size
    }

    fun pending(): Int = assemblies.size

    fun clear() = assemblies.clear()
}

/**
 * Scan duty cycle. With fewer than [minLinks] peers the radio scans
 * continuously; once two links exist it scans [windowMs] on, [idleMs] off.
 * Start attempts are capped at [maxStarts] per [rateWindowMs] regardless.
 */
class BleScanGate(
    private val windowMs: Long = 5_000,
    private val idleMs: Long = 10_000,
    private val minLinks: Int = 2,
    private val rateWindowMs: Long = 30_000,
    private val maxStarts: Int = 5,
) {

    private val starts = ArrayDeque<Long>()
    private var scanningSince: Long? = null
    private var lastStopAt: Long? = null

    fun allowStart(now: Long, links: Int): Boolean {
        prune(now)
        if (starts.size >= maxStarts) return false
        if (links >= minLinks) {
            val stopped = lastStopAt
            if (stopped != null && now - stopped < idleMs) return false
        }
        return true
    }

    fun onStart(now: Long) {
        prune(now)
        starts.addLast(now)
        scanningSince = now
    }

    fun onStop(now: Long) {
        scanningSince = null
        lastStopAt = now
    }

    fun shouldStop(now: Long, links: Int): Boolean {
        if (links < minLinks) return false
        val since = scanningSince ?: return false
        return now - since >= windowMs
    }

    fun startsInWindow(now: Long): Int {
        prune(now)
        return starts.size
    }

    /** Earliest moment [allowStart] can return true, or null if already open. */
    fun nextAllowedAt(now: Long, links: Int): Long? {
        prune(now)
        var at = now
        if (starts.size >= maxStarts) {
            val oldest = starts.peekFirst() ?: return at
            at = maxOf(at, oldest + rateWindowMs)
        }
        if (links >= minLinks) {
            val stopped = lastStopAt
            if (stopped != null) at = maxOf(at, stopped + idleMs)
        }
        return at.takeIf { it > now }
    }

    private fun prune(now: Long) {
        while (starts.isNotEmpty() && now - starts.peekFirst() >= rateWindowMs) {
            starts.pollFirst()
        }
    }
}

/**
 * One link per peer actor. The link in which the lower actorId is the central
 * is canonical, so a simultaneous connect collapses to one link.
 */
object BleLinkElection {

    fun canonicalCentral(localActor: String, peerActor: String): Boolean =
        localActor < peerActor

    /** True when the candidate link should replace the existing one. */
    fun preferCandidate(
        localActor: String,
        peerActor: String,
        candidateIsCentral: Boolean,
    ): Boolean = candidateIsCentral == canonicalCentral(localActor, peerActor)
}

/**
 * Reconnect backoff for the Android GATT failures that mean "try later":
 * 133 GATT_ERROR, 8 insufficient authorization, 19 peer terminated.
 */
class BleBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val retryable: Set<Int> = setOf(133, 8, 19),
) {

    fun isRetryable(status: Int): Boolean = status in retryable

    fun nextDelay(attempt: Int): Long {
        if (attempt <= 0) return baseMs
        var delay = baseMs
        repeat(attempt.coerceAtMost(6)) {
            delay = (delay * 2).coerceAtMost(maxMs)
        }
        return delay.coerceAtMost(maxMs)
    }
}

/**
 * Fresh 128-bit UUIDs for the service and its frame characteristic. Generated
 * once per install with [SecureRandom] and persisted by the transport; a fixed
 * UUID would make every AgriMesh phone discoverable to any BLE scanner.
 */
object BleUuids {

    fun random(random: SecureRandom = SecureRandom()): Pair<UUID, UUID> {
        val service = uuid(random)
        var characteristic = uuid(random)
        while (characteristic == service) characteristic = uuid(random)
        return service to characteristic
    }

    private fun uuid(random: SecureRandom): UUID {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        // RFC 4122 version 4 layout, then clear the multicast bit so the value
        // can never collide with a standard SIG-assigned UUID.
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val msb = (bytes[0].toLong() and 0xFF shl 56) or
            (bytes[1].toLong() and 0xFF shl 48) or
            (bytes[2].toLong() and 0xFF shl 40) or
            (bytes[3].toLong() and 0xFF shl 32) or
            (bytes[4].toLong() and 0xFF shl 24) or
            (bytes[5].toLong() and 0xFF shl 16) or
            (bytes[6].toLong() and 0xFF shl 8) or
            (bytes[7].toLong() and 0xFF)
        val lsb = (bytes[8].toLong() and 0xFF shl 56) or
            (bytes[9].toLong() and 0xFF shl 48) or
            (bytes[10].toLong() and 0xFF shl 40) or
            (bytes[11].toLong() and 0xFF shl 32) or
            (bytes[12].toLong() and 0xFF shl 24) or
            (bytes[13].toLong() and 0xFF shl 16) or
            (bytes[14].toLong() and 0xFF shl 8) or
            (bytes[15].toLong() and 0xFF)
        return UUID(msb, lsb)
    }
}
