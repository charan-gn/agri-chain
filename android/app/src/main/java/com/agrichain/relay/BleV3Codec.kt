package com.agrichain.relay

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * BLE GATT framing for v3 envelopes.
 *
 * `magic 'A','B' | version 1 | type 1 | messageId(4) | index(2) | total(2) | length(2) | payload`
 *
 * A frame is at most the negotiated ATT MTU minus 3. The relay (not the link)
 * owns TTL and store-and-forward, so the frame only carries fragmentation.
 */
data class BleV3Frame(
    val type: Int,
    val messageId: Int,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
)

object BleV3Codec {

    const val TYPE_DATA = 0
    const val HEADER_SIZE = 14
    const val MIN_FRAME = 20
    const val MAX_FRAME = 512
    const val MAX_TOTAL = 255
    const val MAX_BODY = 128 * 1024
    private const val MAGIC_0 = 'A'.code.toByte()
    private const val MAGIC_1 = 'B'.code.toByte()
    private const val VERSION = 1

    fun messageId(envelope: ByteArray): Int =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(envelope)).int

    fun chunks(envelope: ByteArray, maxFrame: Int = 180): List<ByteArray> {
        require(envelope.size in 2..BleV3Codec.MAX_BODY) { "BLE envelope too large" }
        val frameSize = maxFrame.coerceIn(MIN_FRAME, MAX_FRAME)
        val payloadSize = frameSize - HEADER_SIZE
        require(payloadSize > 0) { "frame too small" }
        val total = (envelope.size + payloadSize - 1) / payloadSize
        require(total <= MAX_TOTAL) { "BLE envelope needs $total frames" }
        val id = messageId(envelope)
        return (0 until total).map { index ->
            val start = index * payloadSize
            val end = minOf(start + payloadSize, envelope.size)
            val payload = envelope.copyOfRange(start, end)
            val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            buffer.put(MAGIC_0).put(MAGIC_1).put(VERSION.toByte()).put(TYPE_DATA.toByte())
            buffer.putInt(id).putShort((index + 1).toShort()).putShort(total.toShort())
            buffer.putShort(payload.size.toShort()).put(payload)
            buffer.array()
        }
    }

    fun parse(frame: ByteArray): BleV3Frame? {
        if (frame.size < HEADER_SIZE) return null
        if (frame[0] != MAGIC_0 || frame[1] != MAGIC_1) return null
        if (frame[2].toInt() and 0xFF != VERSION) return null
        val type = frame[3].toInt() and 0xFF
        if (type != TYPE_DATA) return null
        val buffer = ByteBuffer.wrap(frame)
        buffer.position(4)
        val id = buffer.int
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        val length = buffer.short.toInt() and 0xFFFF
        if (total !in 1..MAX_TOTAL || index !in 1..total) return null
        if (length != frame.size - HEADER_SIZE) return null
        val payload = frame.copyOfRange(HEADER_SIZE, frame.size)
        return BleV3Frame(type, id, index, total, payload)
    }
}

/** Reassembles BLE frames per link and message id. */
class BleV3Reassembler(
    private val timeoutMs: Long = 60_000L,
    private val maxPending: Int = 64,
) {

    private data class Key(val link: String, val messageId: Int)

    private data class Assembly(
        val total: Int,
        val parts: MutableMap<Int, ByteArray>,
        var updatedAt: Long,
    )

    private val assemblies = LinkedHashMap<Key, Assembly>()

    @Synchronized
    fun accept(link: String, frame: BleV3Frame, now: Long): ByteArray? {
        expire(now)
        val key = Key(link, frame.messageId)
        val existing = assemblies[key]
        if (existing != null && existing.total != frame.total) {
            assemblies.remove(key)
            return null
        }
        val current = existing ?: Assembly(frame.total, mutableMapOf(), now).also {
            if (assemblies.size >= maxPending) {
                assemblies.remove(assemblies.keys.first())
            }
            assemblies[key] = it
        }
        current.parts[frame.index] = frame.payload
        current.updatedAt = now
        if (current.parts.size < current.total) return null

        var size = 0
        for (index in 1..current.total) {
            size += current.parts[index]?.size ?: return null
        }
        if (size > BleV3Codec.MAX_BODY) {
            assemblies.remove(key)
            return null
        }
        val body = ByteArray(size)
        var offset = 0
        for (index in 1..current.total) {
            val part = current.parts[index] ?: return null
            part.copyInto(body, offset)
            offset += part.size
        }
        assemblies.remove(key)
        return runCatching {
            V3Codec.decodeEnvelope(body)
            body
        }.getOrNull()
    }

    @Synchronized
    fun expire(now: Long): Int {
        val before = assemblies.size
        assemblies.entries.removeAll { now - it.value.updatedAt > timeoutMs }
        return before - assemblies.size
    }

    @Synchronized
    fun pending(): Int = assemblies.size
}
