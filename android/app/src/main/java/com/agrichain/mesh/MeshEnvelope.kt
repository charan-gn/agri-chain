package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.EventCodec
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class MeshKind(val code: String) {
    EVENTS("E"),
    ACK("A"),
}

/**
 * Mesh envelope v2. Keys are short because SMS is a transport. The event hash is
 * omitted and recomputed by the receiver; the signature is carried unchanged.
 * v1 envelopes from T12 still parse so an old build can hand over its queue.
 */
data class MeshEnvelope(
    val kind: MeshKind,
    val envelopeId: String,
    val senderId: String,
    val sentAt: Long,
    val ttl: Int,
    val events: List<Event> = emptyList(),
    val ackedEventIds: List<String> = emptyList(),
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("v", VERSION)
            .put("k", kind.code)
            .put("id", envelopeId)
            .put("s", senderId)
            .put("t", sentAt)
            .put("ttl", ttl)
        if (events.isNotEmpty()) {
            val array = JSONArray()
            events.forEach { array.put(encodeEvent(it)) }
            json.put("ev", array)
        }
        if (ackedEventIds.isNotEmpty()) {
            val array = JSONArray()
            ackedEventIds.forEach { array.put(it) }
            json.put("ack", array)
        }
        return json
    }

    fun encode(): String = toJson().toString()

    fun byteSize(): Int = encode().toByteArray(StandardCharsets.UTF_8).size

    fun withTtl(value: Int): MeshEnvelope = copy(ttl = value)

    fun onlyEvents(predicate: (Event) -> Boolean): MeshEnvelope =
        copy(events = events.filter(predicate))

    companion object {
        const val VERSION = 2
        const val DEFAULT_TTL = 6
        private const val V1_TTL = 6

        fun events(
            senderId: String,
            events: List<Event>,
            ttl: Int = DEFAULT_TTL,
        ): MeshEnvelope = MeshEnvelope(
            kind = MeshKind.EVENTS,
            envelopeId = UUID.randomUUID().toString(),
            senderId = senderId,
            sentAt = System.currentTimeMillis(),
            ttl = ttl,
            events = events,
        )

        fun ack(
            senderId: String,
            eventIds: Collection<String>,
            ttl: Int = DEFAULT_TTL,
        ): MeshEnvelope = MeshEnvelope(
            kind = MeshKind.ACK,
            envelopeId = UUID.randomUUID().toString(),
            senderId = senderId,
            sentAt = System.currentTimeMillis(),
            ttl = ttl,
            ackedEventIds = eventIds.distinct(),
        )

        fun parse(text: String): MeshEnvelope? =
            runCatching { fromJson(JSONObject(text)) }.getOrNull()

        fun fromJson(json: JSONObject): MeshEnvelope? = when (json.optInt("v")) {
            VERSION -> fromV2(json)
            1 -> fromV1(json)
            else -> null
        }

        private fun fromV2(json: JSONObject): MeshEnvelope? {
            val kind = when (json.optString("k")) {
                MeshKind.EVENTS.code -> MeshKind.EVENTS
                MeshKind.ACK.code -> MeshKind.ACK
                else -> return null
            }
            val envelopeId = json.optString("id")
            val senderId = json.optString("s")
            val sentAt = json.optLong("t")
            val ttl = json.optInt("ttl")
            if (envelopeId.isEmpty() || senderId.isEmpty() || sentAt <= 0 || ttl < 0) return null

            val events = json.optJSONArray("ev")?.let { array ->
                (0 until array.length()).map { index ->
                    runCatching { decodeEvent(array.getJSONObject(index)) }.getOrNull()
                        ?: return null
                }
            }.orEmpty()
            val acks = json.optJSONArray("ack")?.let { array ->
                (0 until array.length()).map { index -> array.getString(index) }
            }.orEmpty()

            if (events.isEmpty() && acks.isEmpty()) return null
            if (kind == MeshKind.EVENTS && events.isEmpty()) return null
            if (kind == MeshKind.ACK && acks.isEmpty()) return null
            return MeshEnvelope(kind, envelopeId, senderId, sentAt, ttl, events, acks)
        }

        private fun fromV1(json: JSONObject): MeshEnvelope? {
            if (json.optString("kind") != "EVENTS") return null
            val senderId = json.optString("senderId")
            val sentAt = json.optLong("sentAt")
            if (senderId.isEmpty() || sentAt <= 0) return null
            val array = json.optJSONArray("events") ?: return null
            if (array.length() == 0) return null
            val events = (0 until array.length()).map { index ->
                runCatching { Event.fromJsonObject(array.getJSONObject(index)) }.getOrNull()
                    ?: return null
            }
            return MeshEnvelope(
                kind = MeshKind.EVENTS,
                envelopeId = "v1-$senderId-$sentAt",
                senderId = senderId,
                sentAt = sentAt,
                ttl = V1_TTL,
                events = events,
            )
        }

        private fun encodeEvent(event: Event): JSONObject {
            val data = JSONObject()
            event.data.forEach { (key, value) -> data.put(key, value ?: JSONObject.NULL) }
            val json = JSONObject()
                .put("i", event.eventId)
                .put("b", event.batchId)
                .put("y", event.type)
                .put("a", event.actorId)
                .put("t", event.ts)
                .put("d", data)
                .put("p", event.prevHash)
                .put("g", event.sig)
            event.lat?.let { json.put("la", it) }
            event.lon?.let { json.put("lo", it) }
            return json
        }

        private fun decodeEvent(json: JSONObject): Event {
            val dataJson = json.getJSONObject("d")
            val data = linkedMapOf<String, Any?>()
            dataJson.keys().forEach { key ->
                val value = dataJson.get(key)
                data[key] = if (value == JSONObject.NULL) null else value
            }
            val lat = if (json.has("la")) json.getString("la") else null
            val lon = if (json.has("lo")) json.getString("lo") else null
            val fields = linkedMapOf<String, Any?>(
                "eventId" to json.getString("i"),
                "batchId" to json.getString("b"),
                "type" to json.getString("y"),
                "actorId" to json.getString("a"),
                "ts" to json.getLong("t"),
                "lat" to lat,
                "lon" to lon,
                "data" to data,
                "prevHash" to json.optString("p"),
            )
            val canonical = EventCodec.canonical(fields)
            return Event(
                eventId = fields.getValue("eventId") as String,
                batchId = fields.getValue("batchId") as String,
                type = fields.getValue("type") as String,
                actorId = fields.getValue("actorId") as String,
                ts = fields.getValue("ts") as Long,
                lat = lat,
                lon = lon,
                data = data,
                prevHash = fields.getValue("prevHash") as String,
                hash = EventCodec.sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8)),
                sig = json.getString("g"),
            )
        }
    }
}
