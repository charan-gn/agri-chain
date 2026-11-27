package com.agrichain.relay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Farmer-side memory of barter offers this phone created. */
data class LocalBarterOffer(
    val offerId: String,
    val giveCrop: Int,
    val giveKg: Long,
    val wantCrop: Int,
    val wantKg: Long,
    val region: Int,
    val validUntil: Long,
    val createdAt: Long,
    val status: String = "open",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("offerId", offerId)
        .put("giveCrop", giveCrop)
        .put("giveKg", giveKg)
        .put("wantCrop", wantCrop)
        .put("wantKg", wantKg)
        .put("region", region)
        .put("validUntil", validUntil)
        .put("createdAt", createdAt)
        .put("status", status)

    companion object {
        fun fromJson(json: JSONObject): LocalBarterOffer = LocalBarterOffer(
            offerId = json.getString("offerId"),
            giveCrop = json.optInt("giveCrop", 0),
            giveKg = json.optLong("giveKg", 0),
            wantCrop = json.optInt("wantCrop", 0),
            wantKg = json.optLong("wantKg", 0),
            region = json.optInt("region", 0),
            validUntil = json.optLong("validUntil", 0),
            createdAt = json.optLong("createdAt", 0),
            status = json.optString("status", "open"),
        )
    }
}

class BarterStoreV3(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<LocalBarterOffer> {
        val raw = prefs.getString(KEY_OFFERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { LocalBarterOffer.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(offer: LocalBarterOffer) {
        val offers = all().toMutableList()
        val index = offers.indexOfFirst { it.offerId == offer.offerId }
        if (index >= 0) offers[index] = offer else offers.add(0, offer)
        persist(offers.take(50))
    }

    fun markAccepted(offerId: String) {
        val offers = all().toMutableList()
        val index = offers.indexOfFirst { it.offerId == offerId }
        if (index >= 0) {
            offers[index] = offers[index].copy(status = "accepted")
            persist(offers)
        }
    }

    private fun persist(offers: List<LocalBarterOffer>) {
        val array = JSONArray()
        offers.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_OFFERS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "agrichain_v3_barter"
        private const val KEY_OFFERS = "offers"
    }
}
