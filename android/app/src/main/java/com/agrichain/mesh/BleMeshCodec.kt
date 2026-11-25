package com.agrichain.mesh

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * BitChat-style BLE framing, implemented clean-room for agri-chain.
 *
 * A JSON [MeshEnvelope] is split into frames:
 *
 * `magic 'A','B' | version | type | messageId(4) | index(2) | total(2) | length(2) | payload`
 *
 * A HELLO frame carries the sender's 4-byte peer hash so both ends of a GATT
 * link agree on the peer id used for ACK scoping. The relay (not the link)
 * owns TTL and store-and-forward, so the frame only handles fragmentation.
 */
data class BleFrame(
    val type: Int,
    val messageId: Int,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
)

object BleMeshCodec {

    const val TYPE_DATA = 0
    const val TYPE_HELLO = 1
    const val HEADER_SIZE = 14
    const val MIN_FRAME = 20
    const val MAX_FRAME = 512
    const val MAX_TOTAL = 255
    const val MAX_BODY = 128 * 1024
    const val HELLO_SIZE = 4

    private const val MAGIC_0 = 0x41 // 'A'
    private const val MAGIC_1 = 0x42 // 'B'
    private const val VERSION = 1

    /** Stable 32-bit id used by every frame of one envelope. */
    fun messageId(envelopeId: String): Int =
        ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256")
                .digest(envelopeId.toByteArray(Charsets.UTF_8))
        ).int

    fun peerHash(identity: String): Int =
        ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
        ).int

    fun hello(peerHash: Int): ByteArray {
        val frame = ByteArray(HEADER_SIZE + HELLO_SIZE)
        writeHeader(frame, TYPE_HELLO, 0, 0, 0, HELLO_SIZE)
        writeInt(frame, HEADER_SIZE, peerHash)
        return frame
    }

    fun chunks(envelope: MeshEnvelope, maxFrame: Int = 180): List<ByteArray> =
        chunks(messageId(envelope.envelopeId), envelope.encode().toByteArray(Charsets.UTF_8), maxFrame)

    fun chunks(messageId: Int, body: ByteArray, maxFrame: Int = 180): List<ByteArray> {
        require(body.isNotEmpty()) { "BLE body is empty" }
        require(body.size <= MAX_BODY) { "BLE body is larger than $MAX_BODY bytes" }
        val frameSize = maxFrame.coerceIn(MIN_FRAME, MAX_FRAME)
        val payloadSize = frameSize - HEADER_SIZE
        require(payloadSize >= 1) { "BLE frame is too small" }
        val total = (body.size + payloadSize - 1) / payloadSize
        require(total <= MAX_TOTAL) { "Message needs $total BLE frames (max $MAX_TOTAL)" }
        return (0 until total).map { index ->
            val start = index * payloadSize
            val end = minOf(start + payloadSize, body.size)
            val length = end - start
            val frame = ByteArray(HEADER_SIZE + length)
            writeHeader(frame, TYPE_DATA, messageId, index + 1, total, length)
            System.arraycopy(body, start, frame, HEADER_SIZE, length)
            frame
        }
    }

    fun parse(frame: ByteArray): BleFrame? {
        if (frame.size < HEADER_SIZE) return null
        if (frame[0].toInt() != MAGIC_0 || frame[1].toInt() != MAGIC_1) return null
        if ((frame[2].toInt() and 0xFF) != VERSION) return null
        val type = frame[3].toInt() and 0xFF
        val messageId = readInt(frame, 4)
        val index = readShort(frame, 8)
        val total = readShort(frame, 10)
        val length = readShort(frame, 12)
        if (length != frame.size - HEADER_SIZE) return null
        when (type) {
            TYPE_HELLO -> if (length != HELLO_SIZE || index != 0 || total != 0) return null
            TYPE_DATA -> if (total !in 1..MAX_TOTAL || index !in 1..total || length <= 0) return null
            else -> return null
        }
        return BleFrame(
            type = type,
            messageId = messageId,
            index = index,
            total = total,
            payload = frame.copyOfRange(HEADER_SIZE, frame.size),
        )
    }

    fun helloHash(frame: BleFrame): Int? =
        if (frame.type == TYPE_HELLO && frame.payload.size == HELLO_SIZE) {
            readInt(frame.payload, 0)
        } else {
            null
        }

    private fun writeHeader(
        frame: ByteArray,
        type: Int,
        messageId: Int,
        index: Int,
        total: Int,
        length: Int,
    ) {
        frame[0] = MAGIC_0.toByte()
        frame[1] = MAGIC_1.toByte()
        frame[2] = VERSION.toByte()
        frame[3] = type.toByte()
        writeInt(frame, 4, messageId)
        writeShort(frame, 8, index)
        writeShort(frame, 10, total)
        writeShort(frame, 12, length)
    }

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}

/**
 * Reassembles one envelope per `(link, messageId)`. Duplicate parts are
 * ignored, conflicting duplicates drop the assembly, and stale or oversized
 * assemblies are discarded.
 */
class BleReassembler(
    private val timeoutMs: Long = 60_000L,
    private val maxPending: Int = 32,
) {

    private data class Assembly(
        val total: Int,
        val parts: MutableMap<Int, ByteArray>,
        var updatedAt: Long,
        var size: Int,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()

    @Synchronized
    fun accept(linkKey: String, frame: BleFrame, now: Long): ByteArray? {
        if (frame.type != BleMeshCodec.TYPE_DATA) return null
        expire(now)
        val key = "$linkKey:${frame.messageId}"
        val existing = assemblies[key]
        if (existing != null && existing.total != frame.total) {
            assemblies.remove(key)
            return null
        }
        val assembly = existing ?: Assembly(
            total = frame.total,
            parts = mutableMapOf(),
            updatedAt = now,
            size = 0,
        ).also {
            if (assemblies.size >= maxPending) {
                assemblies.remove(assemblies.keys.first())
            }
            assemblies[key] = it
        }
        val previous = assembly.parts.put(frame.index, frame.payload)
        if (previous != null) {
            if (!previous.contentEquals(frame.payload)) {
                assemblies.remove(key)
                return null
            }
        } else {
            assembly.size += frame.payload.size
            if (assembly.size > BleMeshCodec.MAX_BODY) {
                assemblies.remove(key)
                return null
            }
        }
        assembly.updatedAt = now
        if (assembly.parts.size < assembly.total) return null

        val out = ByteArrayOutputStream(assembly.size)
        for (index in 1..assembly.total) {
            val part = assembly.parts[index] ?: return null
            out.write(part)
        }
        assemblies.remove(key)
        return out.toByteArray()
    }

    @Synchronized
    fun expire(now: Long): Int {
        val before = assemblies.size
        assemblies.entries.removeAll { now - it.value.updatedAt > timeoutMs }
        return before - assemblies.size
    }

    @Synchronized
    fun pending(): Int = assemblies.size

    @Synchronized
    fun clear() = assemblies.clear()
}
