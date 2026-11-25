package com.agrichain.prices

import android.content.Context

data class CachedPrices(
    val snapshot: TradePriceSnapshot,
    val savedAt: Long,
)

class PriceCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("prices", Context.MODE_PRIVATE)

    fun load(crop: String): CachedPrices? {
        val key = PriceUrls.cacheKey(crop)
        val body = prefs.getString("cache:$key", null) ?: return null
        val savedAt = prefs.getLong("saved:$key", 0L)
        val snapshot = runCatching { TradePriceJson.parse(body) }.getOrNull() ?: return null
        return CachedPrices(snapshot = snapshot, savedAt = savedAt)
    }

    fun save(
        crop: String,
        snapshot: TradePriceSnapshot,
        savedAt: Long = System.currentTimeMillis(),
    ) {
        val key = PriceUrls.cacheKey(crop)
        prefs.edit()
            .putString("cache:$key", TradePriceJson.encode(snapshot))
            .putLong("saved:$key", savedAt)
            .apply()
    }

    fun crop(): String = prefs.getString("crop", "wheat").orEmpty().ifEmpty { "wheat" }

    fun saveSelection(crop: String) {
        prefs.edit().putString("crop", crop).apply()
    }
}
