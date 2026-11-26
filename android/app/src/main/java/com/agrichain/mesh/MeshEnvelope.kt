package com.agrichain.mesh

import com.agrichain.core.CanonicalJson
import com.agrichain.core.Event
import com.agrichain.core.EventCodec
import com.agrichain.core.EventVerifier
import com.agrichain.core.PublicKeySigner
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

enum class MeshKind(val code: String) {
    EVENTS("E"),
    ACK("A"),
    CHAT("C"),
    /** Self-reported phone identity so peers can be shown as named contacts. */
    USER("U"),
}

/**
 * Mesh envelope v3. Keys are short because SMS is a transport. The event hash is
 * omitted and recomputed by the receiver; the signature is carried unchanged.
 * v1/v2 envelopes still parse so older builds can hand over their queue.
 *
 * ACKs and chat messages are signed by the origin node. The signed bytes cover
 * `{v,k,id,s,t,ack}` or `{v,k,id,s,t,c}` but not `ttl`, so a relay can decrement
 * the TTL without re-signing; envelope-id dedupe stops replay.
 */
data class MeshEnvelope(
    val kind: MeshKind,
    val envelopeId: String,
    val senderId: String,
    val sentAt: Long,
    val ttl: Int,
    val events: List<Event> = emptyList(),
    val ackedEventIds: List<String> = emptyList(),
    val chatText: String? = null,
    /** Null means broadcast; otherwise only this phone should display the chat. */
    val recipientId: String? = null,
    val senderName: String? = null,
    val sig: String? = null,
    val pubKey: String? = null,
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
        if (kind == MeshKind.CHAT) {
            chatText?.let { json.put("c", it) }
            recipientId?.let { json.put("r", it) }
        }
        if (kind == MeshKind.USER) {
            senderName?.let { json.put("n", it) }
        }
        if (kind == MeshKind.ACK || kind == MeshKind.CHAT) {
            sig?.let { json.put("sig", it) }
            pubKey?.let { json.put("pk", it) }
        }
        return json
    }

    fun encode(): String = toJson().toString()

    fun byteSize(): Int = encode().toByteArray(StandardCharsets.UTF_8).size

    fun withTtl(value: Int): MeshEnvelope = copy(ttl = value)

    fun onlyEvents(predicate: (Event) -> Boolean): MeshEnvelope =
        copy(events = events.filter(predicate))

    /** Bytes signed by the ACK sender; `ttl` is intentionally excluded. */
    fun ackBytes(): ByteArray = CanonicalJson.encode(
        linkedMapOf<String, Any?>(
            "v" to VERSION,
            "k" to MeshKind.ACK.code,
            "id" to envelopeId,
            "s" to senderId,
            "t" to sentAt,
            "ack" to ackedEventIds,
        )
    ).toByteArray(StandardCharsets.UTF_8)

    fun verifyAck(): Boolean {
        if (kind != MeshKind.ACK) return false
        val signature = sig ?: return false
        val key = pubKey ?: return false
        return EventVerifier.verifyEcdsa(ackBytes(), signature, key)
    }

    /**
     * Bytes signed by the chat sender; `ttl` is intentionally excluded. The
     * recipient is signed only when present, so pre-addressing peers keep their
     * original broadcast signature bytes.
     */
    fun chatBytes(): ByteArray = CanonicalJson.encode(
        linkedMapOf<String, Any?>(
            "v" to VERSION,
            "k" to MeshKind.CHAT.code,
            "id" to envelopeId,
            "s" to senderId,
            "t" to sentAt,
            "c" to (chatText ?: ""),
        ).also { if (recipientId != null) it["r"] = recipientId }
    ).toByteArray(StandardCharsets.UTF_8)

    fun verifyChat(): Boolean {
        if (kind != MeshKind.CHAT) return false
        if (chatText.isNullOrBlank()) return false
        val signature = sig ?: return false
        val key = pubKey ?: return false
        return EventVerifier.verifyEcdsa(chatBytes(), signature, key)
    }

    companion object {
        const val VERSION = 3
        const val DEFAULT_TTL = 6
        const val MAX_CHAT_CHARS = 500
        private const val V1_TTL = 6

        fun shortId(): String =
            UUID.randomUUID().toString().replace("-", "").take(8).lowercase()

        fun events(
            senderId: String,
            events: List<Event>,
            ttl: Int = DEFAULT_TTL,
        ): MeshEnvelope = MeshEnvelope(
            kind = MeshKind.EVENTS,
            envelopeId = shortId(),
            senderId = senderId,
            sentAt = System.currentTimeMillis(),
            ttl = ttl,
            events = events,
        )

        /** Unsigned ACK for codec tests and pre-T21 peers; the relay ignores it. */
        fun ack(
            senderId: String,
            eventIds: Collection<String>,
            ttl: Int = DEFAULT_TTL,
        ): MeshEnvelope = MeshEnvelope(
            kind = MeshKind.ACK,
            envelopeId = shortId(),
            senderId = senderId,
            sentAt = System.currentTimeMillis(),
            ttl = ttl,
            ackedEventIds = eventIds.distinct(),
        )

        /** A signed ACK. The gateway only calls this after a 201/idempotent success. */
        fun ack(
            senderId: String,
            eventIds: Collection<String>,
            signer: PublicKeySigner,
            ttl: Int = DEFAULT_TTL,
        ): MeshEnvelope {
            val unsigned = MeshEnvelope(
                kind = MeshKind.ACK,
                envelopeId = shortId(),
                senderId = senderId,
                sentAt = System.currentTimeMillis(),
                ttl = ttl,
                ackedEventIds = eventIds.distinct(),
            )
            val signature = Base64.getEncoder().encodeToString(signer.sign(unsigned.ackBytes()))
            return unsigned.copy(sig = signature, pubKey = signer.publicKeySpkiBase64)
        }

        /** Signed local chat. Relays forward it; the ledger only mirrors a monitor row. */
        fun chat(
            senderId: String,
            text: String,
            signer: PublicKeySigner,
            ttl: Int = DEFAULT_TTL,
            recipientId: String? = null,
        ): MeshEnvelope {
            val body = text.trim().take(MAX_CHAT_CHARS)
            require(body.isNotEmpty()) { "Chat message is empty" }
            val unsigned = MeshEnvelope(
                kind = MeshKind.CHAT,
                envelopeId = shortId(),
                senderId = senderId,
                sentAt = System.currentTimeMillis(),
                ttl = ttl,
                chatText = body,
                recipientId = recipientId?.takeIf { it.isNotBlank() },
            )
            val signature = Base64.getEncoder().encodeToString(signer.sign(unsigned.chatBytes()))
            return unsigned.copy(sig = signature, pubKey = signer.publicKeySpkiBase64)
        }

        /** Self-reported display name; no signature, treated as a UI label only. */
        fun user(senderId: String, name: String, ttl: Int = DEFAULT_TTL): MeshEnvelope {
            val label = name.trim().take(40)
            require(label.isNotEmpty()) { "User name is empty" }
            return MeshEnvelope(
                kind = MeshKind.USER,
                envelopeId = shortId(),
                senderId = senderId,
                sentAt = System.currentTimeMillis(),
                ttl = ttl,
                senderName = label,
            )
        }

        fun parse(text: String): MeshEnvelope? =
            runCatching { fromJson(JSONObject(text)) }.getOrNull()

        fun fromJson(json: JSONObject): MeshEnvelope? = when (json.optInt("v")) {
            VERSION -> fromV3(json)
            2 -> fromV2(json)
            1 -> fromV1(json)
            else -> null
        }

        private fun fromV3(json: JSONObject): MeshEnvelope? {
            val kind = when (json.optString("k")) {
                MeshKind.EVENTS.code -> MeshKind.EVENTS
                MeshKind.ACK.code -> MeshKind.ACK
                MeshKind.CHAT.code -> MeshKind.CHAT
                MeshKind.USER.code -> MeshKind.USER
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
            val chat = if (kind == MeshKind.CHAT) {
                json.optString("c").takeIf { it.isNotEmpty() && it.length <= MAX_CHAT_CHARS }
                    ?: return null
            } else {
                null
            }

            val senderName = if (kind == MeshKind.USER) {
                json.optString("n").takeIf { it.isNotBlank() } ?: return null
            } else {
                null
            }
            if (events.isEmpty() && acks.isEmpty() && chat == null && senderName == null) return null
            if (kind == MeshKind.EVENTS && events.isEmpty()) return null
            if (kind == MeshKind.ACK && acks.isEmpty()) return null
            if (kind == MeshKind.CHAT && chat == null) return null
            if (kind == MeshKind.USER && senderName == null) return null
            return MeshEnvelope(
                kind = kind,
                envelopeId = envelopeId,
                senderId = senderId,
                sentAt = sentAt,
                ttl = ttl,
                events = events,
                ackedEventIds = acks,
                chatText = chat,
                recipientId = if (kind == MeshKind.CHAT) {
                    json.optString("r").takeIf { it.isNotBlank() }
                } else {
                    null
                },
                senderName = senderName,
                sig = json.optString("sig").takeIf { it.isNotEmpty() },
                pubKey = json.optString("pk").takeIf { it.isNotEmpty() },
            )
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
