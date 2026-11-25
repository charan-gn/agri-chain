package com.agrichain.mesh

import com.agrichain.core.Event
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Text-SMS framing. One envelope becomes one or more messages:
 * `AC1|<msgId>|<index>/<total>|<base64>`. Chunks stay under the 160-character
 * GSM limit so carriers do not turn them into (blocked) data SMS.
 */
object SmsCodec {

    const val MAX_SMS_CHARS = 160
    const val MAX_CHUNK = 140
    private const val PREFIX = "AC1"

    data class Part(
        val msgId: String,
        val index: Int,
        val total: Int,
        val payload: String,
    )

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

class SmsReassembler(
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
        val part = SmsCodec.parsePart(text) ?: return null
        expire(now)
        val assembly = assemblies[part.msgId]
        if (assembly != null && assembly.total != part.total) {
            assemblies.remove(part.msgId)
            return null
        }
        val current = assembly ?: Assembly(part.total, mutableMapOf(), now).also {
            if (assemblies.size >= maxPending) {
                assemblies.remove(assemblies.keys.first())
            }
            assemblies[part.msgId] = it
        }
        current.parts[part.index] = part.payload
        current.updatedAt = now
        if (current.parts.size < current.total) return null

        val body = StringBuilder()
        for (index in 1..current.total) {
            body.append(current.parts[index] ?: return null)
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

object SmsPolicy {
    val IMPORTANT_TYPES = setOf("REGISTER", "BIND", "TRANSFER", "TAMPER", "DELIVER", "BARTER")

    fun important(events: List<Event>): List<Event> = events.filter { it.type in IMPORTANT_TYPES }

    fun filter(envelope: MeshEnvelope): MeshEnvelope =
        if (envelope.kind == MeshKind.EVENTS) envelope.onlyEvents { it.type in IMPORTANT_TYPES }
        else envelope
}
