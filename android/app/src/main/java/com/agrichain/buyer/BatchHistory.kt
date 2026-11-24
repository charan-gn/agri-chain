package com.agrichain.buyer

import org.json.JSONArray
import org.json.JSONObject

data class HistoryEvent(
    val type: String,
    val actorId: String,
    val ts: Long,
    val lat: String?,
    val lon: String?,
    val detail: String,
)

data class BatchHistory(
    val batchId: String,
    val crop: String,
    val grade: String,
    val initialKg: Int,
    val ownerId: String,
    val balances: List<Pair<String, Int>>,
    val verified: Boolean,
    val tamperCount: Int,
    val route: List<Pair<Double, Double>>,
    val events: List<HistoryEvent>,
) {

    companion object {

        fun parse(json: JSONObject): BatchHistory {
            val rawEvents = json.optJSONArray("events") ?: JSONArray()
            val events = (0 until rawEvents.length()).map { index ->
                val event = rawEvents.getJSONObject(index)
                val data = event.optJSONObject("data") ?: JSONObject()
                HistoryEvent(
                    type = event.getString("type"),
                    actorId = event.getString("actorId"),
                    ts = event.getLong("ts"),
                    lat = if (event.isNull("lat")) null else event.optString("lat"),
                    lon = if (event.isNull("lon")) null else event.optString("lon"),
                    detail = describe(event.getString("type"), data),
                )
            }

            val balancesJson = json.optJSONObject("balances") ?: JSONObject()
            val balances = balancesJson.keys().asSequence()
                .map { actor -> actor to balancesJson.optInt(actor) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .toList()

            val route = events.filter { it.type == "GPS" && it.lat != null && it.lon != null }
                .mapNotNull { event ->
                    val lat = event.lat?.toDoubleOrNull()
                    val lon = event.lon?.toDoubleOrNull()
                    if (lat == null || lon == null) null else lat to lon
                }

            return BatchHistory(
                batchId = json.getString("batchId"),
                crop = json.optString("crop"),
                grade = json.optString("grade"),
                initialKg = json.optInt("initialKg"),
                ownerId = json.optString("ownerId"),
                balances = balances,
                verified = json.optBoolean("verified", false),
                tamperCount = json.optInt("tamperCount"),
                route = route,
                events = events,
            )
        }

        fun shortActor(actorId: String): String {
            val role = actorId.substringBefore(':', actorId)
            val rawId = actorId.substringAfter(':', "")
            return if (rawId.length >= 8) "$role:${rawId.take(8)}" else actorId
        }

        fun rupees(paise: Int): String = "₹%.2f".format(paise / 100.0)

        private fun describe(type: String, data: JSONObject): String = when (type) {
            "REGISTER" -> "${data.optString("crop")} · grade ${data.optString("grade")} · " +
                "${data.optInt("weightKg")} kg"
            "TRANSFER" -> buildString {
                append("${data.optInt("tokens")} kg → ${shortActor(data.optString("toActorId"))}")
                if (data.has("pricePerKgPaise")) {
                    append(" @ ${rupees(data.optInt("pricePerKgPaise"))}/kg")
                }
            }
            "GPS" -> "GPS fix ±${data.optInt("accuracyM")} m"
            "TAMPER" -> buildString {
                append("TAMPER: ${data.optString("kind")}")
                if (data.has("detail")) append(" (${data.optString("detail")})")
            }
            "DELIVER" -> buildString {
                append("Delivered")
                if (data.has("toActorId")) append(" to ${shortActor(data.optString("toActorId"))}")
            }
            "BARTER" -> "${data.optString("action")} · ${data.optInt("giveKg")} kg for " +
                "${data.optInt("wantKg")} kg"
            "PRICE" -> "${data.optString("market")} · " +
                "${rupees(data.optInt("pricePerKgPaise"))}/kg"
            else -> type
        }
    }
}
