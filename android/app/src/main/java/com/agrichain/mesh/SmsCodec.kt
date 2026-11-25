package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.EventCodec
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.roundToInt

/**
 * SMS v3 is a compact binary envelope (M4):
 *
 * `AC3 | version | kind | 8-hex msgId | ttl | sentAt | sender | count | items | crc32`
 *
 * base64url-chunked as `AC3|<msgId>|<idx>/<total>|<base64url>` with at most 140
 * payload characters, so a chunk fits one 160-character SMS. The old `AC1|`
 * text chunks still parse for an upgrade path.
 */
object SmsCodec {

    const val MAX_SMS_CHARS = 160
    const val MAX_CHUNK = 140
    private const val PREFIX = "AC3"
    private const val LEGACY_PREFIX = "AC1"
    private const val MAGIC = "AC3"
    private const val BIN_VERSION = 3

    data class Part(
        val msgId: String,
        val index: Int,
        val total: Int,
        val payload: String,
        val version: Int = BIN_VERSION,
    )

    fun chunks(envelope: MeshEnvelope, maxChunk: Int = MAX_CHUNK): List<String> {
        val bytes = encodeBinary(envelope)
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val msgId = shortId(envelope)
        val total = ((body.length + maxChunk - 1) / maxChunk).coerceAtLeast(1)
        return (0 until total).map { index ->
            val start = index * maxChunk
            val end = minOf(start + maxChunk, body.length)
            "$PREFIX|$msgId|${index + 1}/$total|${body.substring(start, end)}"
        }
    }

    fun parsePart(text: String): Part? {
        val pieces = text.trim().split("|", limit = 4)
        if (pieces.size != 4) return null
        val version = when (pieces[0]) {
            PREFIX -> BIN_VERSION
            LEGACY_PREFIX -> 1
            else -> return null
        }
        val msgId = pieces[1]
        if (msgId.isEmpty() || msgId.length > 16) return null
        if (version == BIN_VERSION && !msgId.matches(Regex("^[0-9a-f]{8}$"))) return null
        val counter = pieces[2].split("/")
        if (counter.size != 2) return null
        val index = counter[0].toIntOrNull() ?: return null
        val total = counter[1].toIntOrNull() ?: return null
        if (total !in 1..999 || index !in 1..total) return null
        if (pieces[3].isEmpty()) return null
        return Part(msgId, index, total, pieces[3], version)
    }

    fun isPart(text: String): Boolean =
        text.trim().startsWith("$PREFIX|") || text.trim().startsWith("$LEGACY_PREFIX|")

    internal fun encodeBinary(envelope: MeshEnvelope): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(StandardCharsets.US_ASCII))
        out.write(BIN_VERSION)
        out.write(if (envelope.kind == MeshKind.EVENTS) 'E'.code else 'A'.code)
        out.write(hexToBytes(shortId(envelope)))
        out.write(envelope.ttl.coerceIn(0, 255))
        writeLong(out, envelope.sentAt)
        writeString(out, envelope.senderId)

        if (envelope.kind == MeshKind.EVENTS) {
            writeShort(out, envelope.events.size)
            envelope.events.forEach { writeEvent(out, it) }
        } else {
            writeShort(out, envelope.ackedEventIds.size)
            envelope.ackedEventIds.forEach { writeUuid(out, it) }
            writeString(out, envelope.sig.orEmpty())
            writeString(out, envelope.pubKey.orEmpty())
        }

        val body = out.toByteArray()
        val crc = CRC32().apply { update(body) }.value.toInt()
        writeInt(out, crc)
        return out.toByteArray()
    }

    internal fun decodeBinary(bytes: ByteArray): MeshEnvelope? {
        if (bytes.size < 20) return null
        val bodyLength = bytes.size - 4
        val expected = readInt(bytes, bodyLength)
        val actual = CRC32().apply { update(bytes, 0, bodyLength) }.value.toInt()
        if (expected != actual) return null

        val reader = Reader(bytes, 0, bodyLength)
        val magic = runCatching { reader.ascii(3) }.getOrNull() ?: return null
        if (magic != MAGIC) return null
        if (reader.u8() != BIN_VERSION) return null
        val kind = when (reader.u8()) {
            'E'.code -> MeshKind.EVENTS
            'A'.code -> MeshKind.ACK
            else -> return null
        }
        val msgId = bytesToHex(reader.bytes(4))
        val ttl = reader.u8()
        val sentAt = reader.long()
        val sender = reader.string()

        val events = mutableListOf<Event>()
        val acks = mutableListOf<String>()
        if (kind == MeshKind.EVENTS) {
            val count = reader.u16()
            repeat(count) { events += readEvent(reader) }
        } else {
            val count = reader.u16()
            repeat(count) { acks += readUuid(reader) }
        }
        val sig = if (kind == MeshKind.ACK) reader.string().takeIf { it.isNotEmpty() } else null
        val pubKey = if (kind == MeshKind.ACK) reader.string().takeIf { it.isNotEmpty() } else null

        if (events.isEmpty() && acks.isEmpty()) return null
        if (kind == MeshKind.EVENTS && events.isEmpty()) return null
        if (kind == MeshKind.ACK && acks.isEmpty()) return null
        return MeshEnvelope(
            kind = kind,
            envelopeId = msgId,
            senderId = sender,
            sentAt = sentAt,
            ttl = ttl,
            events = events,
            ackedEventIds = acks,
            sig = sig,
            pubKey = pubKey,
        )
    }

    private fun shortId(envelope: MeshEnvelope): String {
        val raw = envelope.envelopeId.replace("-", "").lowercase()
        val hex = raw.filter { it in '0'..'9' || it in 'a'..'f' }
        if (hex.length >= 8) return hex.take(8)
        return EventCodec.sha256Hex(envelope.envelopeId.toByteArray(StandardCharsets.UTF_8)).take(8)
    }

    private val TYPE_CODES = mapOf(
        "REGISTER" to 1,
        "BIND" to 2,
        "TRANSFER" to 3,
        "GPS" to 4,
        "TAMPER" to 5,
        "DELIVER" to 6,
        "BARTER" to 7,
    )
    private val CODE_TYPES = TYPE_CODES.entries.associate { (type, code) -> code to type }

    private fun writeEvent(out: ByteArrayOutputStream, event: Event) {
        writeUuid(out, event.eventId)
        writeUuid(out, event.batchId)
        val code = TYPE_CODES[event.type] ?: 0
        out.write(code)
        if (code == 0) writeString(out, event.type)
        writeActor(out, event.actorId)
        writeLong(out, event.ts)

        val lat = event.lat
        val lon = event.lon
        val prev = event.prevHash
        var flags = 0
        if (lat != null) flags = flags or 1
        if (lon != null) flags = flags or 2
        if (prev.isNotEmpty()) flags = flags or 4
        out.write(flags)
        if (lat != null) writeInt(out, scaled(lat))
        if (lon != null) writeInt(out, scaled(lon))
        if (prev.isNotEmpty()) out.write(hexToBytes(prev))

        val data = JSONObject()
        event.data.forEach { (key, value) -> data.put(key, value ?: JSONObject.NULL) }
        writeString(out, data.toString())

        val signature = runCatching { Base64.getDecoder().decode(event.sig) }.getOrDefault(ByteArray(0))
        writeShort(out, signature.size)
        out.write(signature)
    }

    private fun readEvent(reader: Reader): Event {
        val eventId = readUuid(reader)
        val batchId = readUuid(reader)
        val code = reader.u8()
        val type = if (code == 0) reader.string() else CODE_TYPES[code] ?: "UNKNOWN"
        val actorId = readActor(reader)
        val ts = reader.long()
        val flags = reader.u8()
        val lat = if (flags and 1 != 0) unscaled(reader.int()) else null
        val lon = if (flags and 2 != 0) unscaled(reader.int()) else null
        val prevHash = if (flags and 4 != 0) bytesToHex(reader.bytes(32)) else ""
        val dataJson = JSONObject(reader.string())
        val data = linkedMapOf<String, Any?>()
        dataJson.keys().forEach { key ->
            val value = dataJson.get(key)
            data[key] = if (value == JSONObject.NULL) null else value
        }
        val signatureLength = reader.u16()
        val signature = if (signatureLength == 0) {
            ""
        } else {
            Base64.getEncoder().encodeToString(reader.bytes(signatureLength))
        }
        val fields = linkedMapOf<String, Any?>(
            "eventId" to eventId,
            "batchId" to batchId,
            "type" to type,
            "actorId" to actorId,
            "ts" to ts,
            "lat" to lat,
            "lon" to lon,
            "data" to data,
            "prevHash" to prevHash,
        )
        val canonical = EventCodec.canonical(fields)
        return Event(
            eventId = eventId,
            batchId = batchId,
            type = type,
            actorId = actorId,
            ts = ts,
            lat = lat,
            lon = lon,
            data = data,
            prevHash = prevHash,
            hash = EventCodec.sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
            sig = signature,
        )
    }

    private fun writeActor(out: ByteArrayOutputStream, actorId: String) {
        val role = actorId.substringBefore(':', "")
        val uuid = actorId.substringAfter(':', "")
        val roleCode = when (role) {
            "farmer" -> 1
            "buyer" -> 2
            "box" -> 3
            else -> 0
        }
        out.write(roleCode)
        if (roleCode == 0) writeString(out, role)
        writeUuid(out, uuid)
    }

    private fun readActor(reader: Reader): String {
        val roleCode = reader.u8()
        val role = if (roleCode == 0) reader.string() else when (roleCode) {
            1 -> "farmer"
            2 -> "buyer"
            3 -> "box"
            else -> "unknown"
        }
        return "$role:${readUuid(reader)}"
    }

    private fun scaled(value: String): Int = (value.toDouble() * 1_000_000).roundToInt()

    private fun unscaled(value: Int): String =
        if (value == 0) "0.000000" else String.format(Locale.US, "%.6f", value / 1_000_000.0)

    private fun writeUuid(out: ByteArrayOutputStream, value: String) {
        val uuid = UUID.fromString(value)
        writeLong(out, uuid.mostSignificantBits)
        writeLong(out, uuid.leastSignificantBits)
    }

    private fun readUuid(reader: Reader): String =
        UUID(reader.long(), reader.long()).toString()

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "SMS field is too long" }
        writeShort(out, bytes.size)
        out.write(bytes)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeLong(out: ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value ushr shift) and 0xFF).toInt())
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int,
        private val limit: Int,
    ) {
        fun u8(): Int {
            require(position < limit) { "SMS envelope ended early" }
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun int(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

        fun long(): Long {
            var value = 0L
            repeat(8) { value = (value shl 8) or u8().toLong() }
            return value
        }

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && position + count <= limit) { "SMS envelope ended early" }
            val result = bytes.copyOfRange(position, position + count)
            position += count
            return result
        }

        fun ascii(count: Int): String =
            String(bytes(count), StandardCharsets.US_ASCII)

        fun string(): String {
            val length = u16()
            return String(bytes(length), StandardCharsets.UTF_8)
        }
    }
}

class SmsReassembler(
    private val timeoutMs: Long = 10 * 60_000L,
    private val maxPending: Int = 32,
) {

    private data class Assembly(
        val total: Int,
        val version: Int,
        val parts: MutableMap<Int, String>,
        var updatedAt: Long,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()

    @Synchronized
    fun accept(text: String, now: Long): MeshEnvelope? {
        val part = SmsCodec.parsePart(text) ?: return null
        expire(now)
        val assembly = assemblies[part.msgId]
        if (assembly != null && (assembly.total != part.total || assembly.version != part.version)) {
            assemblies.remove(part.msgId)
            return null
        }
        val current = assembly ?: Assembly(
            part.total,
            part.version,
            mutableMapOf(),
            now,
        ).also {
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
            if (current.version == 1) {
                val bytes = Base64.getDecoder().decode(body.toString())
                MeshEnvelope.parse(String(bytes, StandardCharsets.UTF_8))
            } else {
                val bytes = Base64.getUrlDecoder().decode(body.toString())
                SmsCodec.decodeBinary(bytes)
            }
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
