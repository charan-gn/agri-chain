package com.agrichain.mesh

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Meshtastic LoRa framing. One envelope becomes one or more 231-byte packets:
 *   `MC1|<8hexMsgId>|<index>/<total>|<base64url>`
 *
 * Prefix is "MC1" (Mesh Chunk v1) so it is distinct from SMS "AC1" chunks.
 * Base64 uses standard (not URL-safe) alphabet to match SmsCodec; the prefix
 * and indices keep it plaintext-readable even without the app.
 *
 * India-only note: physical nodes must use the **865–867 MHz** band variant
 * (IN region setting in Meshtastic). 433 MHz and 915 MHz are not permitted.
 */
object MeshtasticCodec {

    /** Meshtastic practical MTU: 237-byte payload minus 6-byte header = 231. */
    const val MAX_PACKET_BYTES = 231

    /**
     * Maximum base64 chars that fit in a single packet frame after the header
     * prefix `MC1|<8hex>|<3digits>/<3digits>|` (21 chars overhead worst-case).
     * We keep it slightly under so a 231-byte UTF-8 frame holds the whole line.
     */
    private const val HEADER_OVERHEAD = 21   // "MC1|12345678|999/999|"
    const val MAX_CHUNK = MAX_PACKET_BYTES - HEADER_OVERHEAD  // 210 chars
    private const val PREFIX = "MC1"

    data class Part(
        val msgId: String,
        val index: Int,
        val total: Int,
        val payload: String,
    )

    /**
     * Encodes [envelope] into one or more chunk strings.  Each string fits in
     * [maxChunk] base64 characters so the full frame stays within [MAX_PACKET_BYTES].
     */
    fun chunks(envelope: MeshEnvelope, maxChunk: Int = MAX_CHUNK): List<String> {
        val bytes = envelope.encode().toByteArray(StandardCharsets.UTF_8)
        val body = Base64.getEncoder().encodeToString(bytes)
        val msgId = envelope.envelopeId.replace("-", "").take(8).ifEmpty { "00000000" }
        val total = ((body.length + maxChunk - 1) / maxChunk).coerceAtLeast(1)
        return (0 until total).map { index ->
            val start = index * maxChunk
            val end = minOf(start + maxChunk, body.length)
            "$PREFIX|$msgId|${index + 1}/$total|${body.substring(start, end)}"
        }
    }

    fun parsePart(text: String): Part? {
        val pieces = text.trim().split("|", limit = 4)
        if (pieces.size != 4 || pieces[0] != PREFIX) return null
        val msgId = pieces[1]
        if (msgId.isEmpty() || msgId.length > 16) return null
        val counter = pieces[2].split("/")
        if (counter.size != 2) return null
        val index = counter[0].toIntOrNull() ?: return null
        val total = counter[1].toIntOrNull() ?: return null
        if (total !in 1..999 || index !in 1..total) return null
        if (pieces[3].isEmpty()) return null
        return Part(msgId, index, total, pieces[3])
    }

    fun isPart(text: String): Boolean = text.trim().startsWith("$PREFIX|")
}

/**
 * Reassembles Meshtastic packets by msgId.  Mirror of [SmsReassembler] but
 * keyed on the MC1 format.  Duplicate packets are ignored; a stale assembly
 * expires after [timeoutMs] milliseconds.
 */
class MeshtasticReassembler(
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
    fun accept(text: String, now: Long): MeshEnvelope? {
        val part = MeshtasticCodec.parsePart(text) ?: return null
        expire(now)
        val existing = assemblies[part.msgId]
        if (existing != null && existing.total != part.total) {
            assemblies.remove(part.msgId)
            return null
        }
        val current = existing ?: Assembly(part.total, mutableMapOf(), now).also {
            if (assemblies.size >= maxPending) {
                assemblies.remove(assemblies.keys.first())
            }
            assemblies[part.msgId] = it
        }
        current.parts[part.index] = part.payload
        current.updatedAt = now
        if (current.parts.size < current.total) return null

        val body = StringBuilder()
        for (i in 1..current.total) {
            body.append(current.parts[i] ?: return null)
        }
        assemblies.remove(part.msgId)
        return runCatching {
            val bytes = Base64.getDecoder().decode(body.toString())
            MeshEnvelope.parse(String(bytes, StandardCharsets.UTF_8))
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
