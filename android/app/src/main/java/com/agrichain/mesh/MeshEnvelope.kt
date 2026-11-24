package com.agrichain.mesh

import com.agrichain.core.Event
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mesh envelope v1. Receivers relay the envelope unchanged and dedupe by eventId.
 */
data class MeshEnvelope(
    val v: Int,
    val kind: String,
    val senderId: String,
    val sentAt: Long,
    val events: List<Event>,
) {

    fun toJson(): JSONObject {
        val array = JSONArray()
        events.forEach { array.put(it.toJsonObject()) }
        return JSONObject()
            .put("v", v)
            .put("kind", kind)
            .put("senderId", senderId)
            .put("sentAt", sentAt)
            .put("events", array)
    }

    fun encode(): String = toJson().toString()

    companion object {
        const val VERSION = 1
        const val KIND_EVENTS = "EVENTS"

        fun parse(text: String): MeshEnvelope? =
            runCatching { fromJson(JSONObject(text)) }.getOrNull()

        fun fromJson(json: JSONObject): MeshEnvelope? {
            if (json.optInt("v") != VERSION) return null
            if (json.optString("kind") != KIND_EVENTS) return null
            val senderId = json.optString("senderId")
            if (senderId.isEmpty()) return null
            val sentAt = json.optLong("sentAt")
            if (sentAt <= 0) return null
            val array = json.optJSONArray("events") ?: return null
            if (array.length() == 0) return null

            val events = (0 until array.length()).map { index ->
                runCatching { Event.fromJsonObject(array.getJSONObject(index)) }.getOrNull()
                    ?: return null
            }
            return MeshEnvelope(VERSION, KIND_EVENTS, senderId, sentAt, events)
        }
    }
}
