package com.agrichain.relay

import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale

/**
 * SMS chunking for whole v3 envelopes.
 *
 * Text form from the P6 guide: `AM1:<id6>:<i>/<n>:<base64url chunk>`.
 * `:` is used instead of `|` because a pipe is two septets in GSM 7-bit.
 * A chunk is at most 130 base64url characters and an envelope is at most six
 * parts, so a maximum 199-byte envelope (three parts) always fits one SMS each.
 */
object SmsV3Codec {

    const val PREFIX = "AM1"
    const val MAX_CHUNK = 130
    const val MAX_PARTS = 6
    const val MAX_TEXT = 160
    const val MAX_ENVELOPE = V3.MAX_MESSAGE + 1

    data class Part(
        val id: String,
        val index: Int,
        val total: Int,
        val payload: String,
    )

    /** First six hex characters of the message id; stable for every chunk. */
    fun id6(envelope: ByteArray): String {
        val raw = V3Codec.decodeEnvelope(envelope).second
        val message = V3Codec.decode(raw)
        return V3Codec.hex(V3Codec.msgId(message)).take(6)
    }

    fun chunks(envelope: ByteArray, maxChunk: Int = MAX_CHUNK): List<String> {
        require(maxChunk in 1..MAX_CHUNK) { "chunk size must be 1..$MAX_CHUNK" }
        require(envelope.size in 2..MAX_ENVELOPE) { "envelope must be 2..$MAX_ENVELOPE bytes" }
        val id = id6(envelope)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(envelope)
        val total = (body.length + maxChunk - 1) / maxChunk
        require(total <= MAX_PARTS) { "envelope needs $total SMS parts (max $MAX_PARTS)" }
        return (0 until total).map { index ->
            val start = index * maxChunk
            val end = minOf(start + maxChunk, body.length)
            "$PREFIX:$id:${index + 1}/$total:${body.substring(start, end)}"
        }
    }

    fun isPart(text: String): Boolean = text.trim().startsWith("$PREFIX:")

    fun parsePart(text: String): Part? {
        val pieces = text.trim().split(":", limit = 4)
        if (pieces.size != 4 || pieces[0] != PREFIX) return null
        val id = pieces[1]
        if (id.length != 6 || !id.all { it.isDigit() || it in 'a'..'f' }) return null
        val counter = pieces[2].split("/")
        if (counter.size != 2) return null
        val index = counter[0].toIntOrNull() ?: return null
        val total = counter[1].toIntOrNull() ?: return null
        if (total !in 1..MAX_PARTS || index !in 1..total) return null
        val payload = pieces[3]
        if (payload.isEmpty()) return null
        if (!payload.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
        return Part(id, index, total, payload)
    }

    /**
     * GSM 7-bit septet count. The SMS text is ASCII base64url, so this is just
     * the header + payload length; the helper is kept public for the tests and
     * for the `:`-vs-`|` decision.
     */
    fun gsm7Septets(text: String): Int {
        var count = 0
        for (char in text) {
            count += if (char in GSM7_EXTENDED) 2 else 1
        }
        return count
    }

    private val GSM7_EXTENDED = setOf('^', '{', '}', '\\', '[', ']', '~', '|', '€')
}

/** Reassembles `AM1` chunks from one or more SMS parts. */
class SmsV3Reassembler(
    private val timeoutMs: Long = 10 * 60_000L,
    private val maxPending: Int = 16,
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
        val assembly = existing ?: Assembly(part.total, mutableMapOf(), now).also {
            if (assemblies.size >= maxPending) assemblies.remove(assemblies.keys.first())
            assemblies[part.id] = it
        }
        assembly.parts[part.index] = part.payload
        assembly.updatedAt = now
        if (assembly.parts.size < assembly.total) return null

        val body = StringBuilder()
        for (index in 1..assembly.total) {
            body.append(assembly.parts[index] ?: return null)
        }
        assemblies.remove(part.id)
        val envelope = runCatching {
            Base64.getUrlDecoder().decode(body.toString())
        }.getOrNull() ?: return null
        if (envelope.size !in 2..SmsV3Codec.MAX_ENVELOPE) return null
        val actual = runCatching { SmsV3Codec.id6(envelope) }.getOrNull() ?: return null
        return envelope.takeIf { actual == part.id }
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

/**
 * Parts-per-minute and inter-part pacing. Pure so the JVM tests can drive the
 * clock instead of sleeping for two seconds per chunk.
 */
class SmsPacing(
    private val minGapMs: Long = 2_000,
    private val windowMs: Long = 60_000,
    private val maxPerWindow: Int = 20,
) {
    private val sent = ArrayDeque<Long>()
    private var lastAt = Long.MIN_VALUE

    fun allow(now: Long): Boolean {
        prune(now)
        if (sent.size >= maxPerWindow) return false
        if (lastAt != Long.MIN_VALUE && now - lastAt < minGapMs) return false
        return true
    }

    fun record(now: Long) {
        sent.addLast(now)
        lastAt = now
    }

    fun sentInWindow(now: Long): Int {
        prune(now)
        return sent.size
    }

    fun nextAllowedAt(now: Long): Long {
        prune(now)
        if (sent.size >= maxPerWindow) {
            return sent.first() + windowMs
        }
        if (lastAt == Long.MIN_VALUE) return now
        return maxOf(now, lastAt + minGapMs)
    }

    private fun prune(now: Long) {
        while (sent.isNotEmpty() && now - sent.first() >= windowMs) sent.removeFirst()
    }
}

/** Queue of SMS parts drained at a human pace. */
class SmsOutbox(
    private val sender: (number: String, text: String) -> Boolean,
    private val pacing: SmsPacing = SmsPacing(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Item(val number: String, val text: String)

    private val queue = ArrayDeque<Item>()

    @Volatile
    var sentParts: Int = 0
        private set

    @Volatile
    var failedParts: Int = 0
        private set

    @Synchronized
    fun enqueue(number: String, texts: List<String>): Int {
        var added = 0
        for (text in texts) {
            if (text.isEmpty()) continue
            queue.addLast(Item(number, text))
            added += 1
        }
        return added
    }

    @Synchronized
    fun drain(now: Long = clock()): Int {
        var attempted = 0
        while (queue.isNotEmpty() && pacing.allow(now)) {
            val item = queue.removeFirst()
            val ok = runCatching { sender(item.number, item.text) }.getOrDefault(false)
            pacing.record(now)
            if (ok) sentParts += 1 else failedParts += 1
            attempted += 1
        }
        return attempted
    }

    @Synchronized
    fun pending(): Int = queue.size

    @Synchronized
    fun nextAllowedAt(now: Long): Long = pacing.nextAllowedAt(now)

    @Synchronized
    fun clear() = queue.clear()
}

/** Formatting helpers kept free of Android types so they can be unit-tested. */
data class SmsPriceQuote(
    val crop: Int,
    val region: Int,
    val version: Long,
    val asOf: Long,
    val pricePaise: Long,
    val trades: Int,
)

data class SmsListingQuote(
    val crop: Int,
    val region: Int,
    val count: Int,
    val bestPaise: Long,
    val bestQtyKg: Long,
)

sealed interface SmsCommand {
    val crop: Int
    val region: Int?

    data class Price(override val crop: Int, override val region: Int?) : SmsCommand
    data class Listings(override val crop: Int, override val region: Int?) : SmsCommand
}

object SmsCommands {

    const val MAX_REPLY = 160

    fun parse(text: String): SmsCommand? {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        val crop = V3Dictionaries.cropId(tokens[1]) ?: return null
        // Region names may contain spaces ("Andhra Pradesh"), so the rest of
        // the command is the region, not a single token.
        val region = if (tokens.size > 2) {
            V3Dictionaries.regionId(tokens.drop(2).joinToString(" ")) ?: return null
        } else {
            null
        }
        return when (tokens[0].uppercase(Locale.US)) {
            "PRICE" -> SmsCommand.Price(crop, region)
            "LISTINGS" -> SmsCommand.Listings(crop, region)
            else -> null
        }
    }

    fun formatPrice(quote: SmsPriceQuote, nowMs: Long = System.currentTimeMillis()): String {
        val age = ageText(nowMs - quote.asOf * 1000L)
        val text = "${V3Dictionaries.cropName(quote.crop)} " +
            "Rs${money(quote.pricePaise)}/kg " +
            "${V3Dictionaries.regionName(quote.region)} " +
            "v${quote.version} $age (${quote.trades} trades)"
        return text.take(MAX_REPLY)
    }

    fun formatListings(quote: SmsListingQuote): String {
        val text = if (quote.count == 0) {
            "${V3Dictionaries.cropName(quote.crop)}: no open listings " +
                V3Dictionaries.regionName(quote.region)
        } else {
            "${V3Dictionaries.cropName(quote.crop)} ${quote.count} open, " +
                "best Rs${money(quote.bestPaise)}/kg ${quote.bestQtyKg}kg " +
                V3Dictionaries.regionName(quote.region)
        }
        return text.take(MAX_REPLY)
    }

    fun noPrice(crop: Int, region: Int?): String =
        "${V3Dictionaries.cropName(crop)}: no price yet" +
            (region?.let { " ${V3Dictionaries.regionName(it)}" } ?: "")

    fun noListings(crop: Int, region: Int?): String =
        "${V3Dictionaries.cropName(crop)}: no open listings" +
            (region?.let { " ${V3Dictionaries.regionName(it)}" } ?: "")

    fun offline(): String = "AgriMesh gateway offline; retry later"

    fun money(paise: Long): String =
        String.format(Locale.US, "%,.2f", paise / 100.0)

    private fun ageText(ageMs: Long): String {
        val seconds = (ageMs / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            else -> "${seconds / 86_400}d"
        }
    }
}
