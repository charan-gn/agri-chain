package com.agrichain.relay

import java.security.MessageDigest
import java.util.Base64

/**
 * v3 SMS framing from the build guide:
 *
 * `AM1:<id6>:<i>/<n>:<base64url chunk>`
 *
 * ':' is used instead of '|' because a pipe costs two characters in GSM 7-bit
 * encoding. A chunk is at most 130 payload characters and a message is split
 * into at most six parts (one 199-byte envelope needs at most three).
 */
object SmsV3Codec {

    const val PREFIX = "AM1"
    const val MAX_CHUNK = 130
    const val MAX_PARTS = 6
    const val MAX_SMS_CHARS = 160
    const val MAX_ENVELOPE = V3.MAX_MESSAGE + 1

    data class Part(
        val id: String,
        val index: Int,
        val total: Int,
        val payload: String,
    )

    /** First six hex characters of msgId, or a hash fallback for a malformed envelope. */
    fun shortId(envelope: ByteArray): String {
        val msgId = runCatching {
            val (_, message) = V3Codec.decodeEnvelope(envelope)
            V3Codec.msgId(V3Codec.decode(message))
        }.getOrNull()
        val bytes = msgId ?: MessageDigest.getInstance("SHA-256").digest(envelope)
        return V3Codec.hex(bytes).take(6)
    }

    fun chunks(envelope: ByteArray, maxChunk: Int = MAX_CHUNK): List<String> {
        require(envelope.size in 2..MAX_ENVELOPE) { "SMS envelope must be 2..$MAX_ENVELOPE bytes" }
        val chunkSize = maxChunk.coerceIn(1, MAX_CHUNK)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
        val total = (body.length + chunkSize - 1) / chunkSize
        require(total <= MAX_PARTS) { "SMS message needs $total parts (max $MAX_PARTS)" }
        val id = shortId(envelope)
        return (0 until total).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, body.length)
            val line = "$PREFIX:$id:${index + 1}/$total:${body.substring(start, end)}"
            require(line.length <= MAX_SMS_CHARS) { "SMS part is ${line.length} characters" }
            line
        }
    }

    fun parsePart(text: String): Part? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("$PREFIX:")) return null
        val pieces = trimmed.split(":", limit = 4)
        if (pieces.size != 4) return null
        val id = pieces[1]
        if (id.length != 6 || !id.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val counter = pieces[2].split("/")
        if (counter.size != 2) return null
        val index = counter[0].toIntOrNull() ?: return null
        val total = counter[1].toIntOrNull() ?: return null
        if (total !in 1..MAX_PARTS || index !in 1..total) return null
        val payload = pieces[3]
        if (payload.isEmpty() || payload.length > MAX_CHUNK) return null
        if (!payload.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return Part(id, index, total, payload)
    }

    fun isPart(text: String): Boolean = text.trim().startsWith("$PREFIX:")
}

/** Reassembles SMS parts by their 6-hex message id. */
class SmsV3Reassembler(
    private val timeoutMs: Long = 10 * 60_000L,
    private val maxPending: Int = 32,
) {

    private data class Assembly(
        val total: Int,
        val parts: MutableMap<Int, String>,
        var updatedAt: Long,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()

    @Synchronized
    fun accept(text: String, now: Long): ByteArray? {
        val part = SmsV3Codec.parsePart(text) ?: return null
        expire(now)
        val existing = assemblies[part.id]
        if (existing != null && existing.total != part.total) {
            assemblies.remove(part.id)
            return null
        }
        val current = existing ?: Assembly(part.total, mutableMapOf(), now).also {
            if (assemblies.size >= maxPending) {
                assemblies.remove(assemblies.keys.first())
            }
            assemblies[part.id] = it
        }
        current.parts[part.index] = part.payload
        current.updatedAt = now
        if (current.parts.size < current.total) return null

        val body = StringBuilder()
        for (index in 1..current.total) {
            body.append(current.parts[index] ?: return null)
        }
        assemblies.remove(part.id)
        return runCatching {
            val envelope = Base64.getUrlDecoder().decode(body.toString())
            require(envelope.size in 2..SmsV3Codec.MAX_ENVELOPE)
            V3Codec.decodeEnvelope(envelope)
            envelope
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
