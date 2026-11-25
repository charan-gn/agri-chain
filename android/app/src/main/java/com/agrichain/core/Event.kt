package com.agrichain.core

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

interface Signer {
    val actorId: String
    fun sign(bytes: ByteArray): ByteArray
}

/** A signer that can also publish its SPKI key, used for signed mesh ACKs. */
interface PublicKeySigner : Signer {
    val publicKeySpkiBase64: String
}

data class Event(
    val eventId: String,
    val batchId: String,
    val type: String,
    val actorId: String,
    val ts: Long,
    val lat: String?,
    val lon: String?,
    val data: Map<String, Any?>,
    val prevHash: String,
    val hash: String,
    val sig: String,
) {

    fun toJsonObject(): JSONObject {
        val dataJson = JSONObject()
        data.forEach { (key, value) -> dataJson.put(key, value ?: JSONObject.NULL) }
        return JSONObject()
            .put("eventId", eventId)
            .put("batchId", batchId)
            .put("type", type)
            .put("actorId", actorId)
            .put("ts", ts)
            .put("lat", lat ?: JSONObject.NULL)
            .put("lon", lon ?: JSONObject.NULL)
            .put("data", dataJson)
            .put("prevHash", prevHash)
            .put("hash", hash)
            .put("sig", sig)
    }

    companion object {
        fun create(
            batchId: String,
            type: String,
            data: Map<String, Any?>,
            signer: Signer,
            ts: Long = System.currentTimeMillis(),
            lat: String? = null,
            lon: String? = null,
            prevHash: String = "",
        ): Event {
            val fields = linkedMapOf<String, Any?>(
                "eventId" to UUID.randomUUID().toString(),
                "batchId" to batchId,
                "type" to type,
                "actorId" to signer.actorId,
                "ts" to ts,
                "lat" to lat,
                "lon" to lon,
                "data" to data,
                "prevHash" to prevHash,
            )
            val canonical = CanonicalJson.encode(fields)
            val bytes = canonical.toByteArray(StandardCharsets.UTF_8)
            return Event(
                eventId = fields.getValue("eventId") as String,
                batchId = batchId,
                type = type,
                actorId = signer.actorId,
                ts = ts,
                lat = lat,
                lon = lon,
                data = data,
                prevHash = prevHash,
                hash = EventCodec.sha256Hex(bytes),
                sig = java.util.Base64.getEncoder().encodeToString(signer.sign(bytes)),
            )
        }

        fun fromJsonObject(json: JSONObject): Event {
            val dataJson = json.getJSONObject("data")
            val data = linkedMapOf<String, Any?>()
            dataJson.keys().forEach { key ->
                val value = dataJson.get(key)
                data[key] = if (value == JSONObject.NULL) null else value
            }
            return Event(
                eventId = json.getString("eventId"),
                batchId = json.getString("batchId"),
                type = json.getString("type"),
                actorId = json.getString("actorId"),
                ts = json.getLong("ts"),
                lat = if (json.isNull("lat")) null else json.getString("lat"),
                lon = if (json.isNull("lon")) null else json.getString("lon"),
                data = data,
                prevHash = json.getString("prevHash"),
                hash = json.getString("hash"),
                sig = json.getString("sig"),
            )
        }
    }
}

object EventCodec {

    fun canonical(fields: Map<String, Any?>): String = CanonicalJson.encode(fields)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
