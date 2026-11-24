package com.agrichain.prices

import android.content.Context

data class CachedPrices(
    val snapshot: PriceSnapshot,
    val savedAt: Long,
)

class PriceCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("prices", Context.MODE_PRIVATE)

    fun load(crop: String, market: String?): CachedPrices? {
        val key = PriceUrls.cacheKey(crop, market)
        val body = prefs.getString("cache:$key", null) ?: return null
        val savedAt = prefs.getLong("saved:$key", 0L)
        val snapshot = runCatching { PriceJson.parse(body) }.getOrNull() ?: return null
        return CachedPrices(snapshot = snapshot, savedAt = savedAt)
    }

    fun save(
        crop: String,
        market: String?,
        snapshot: PriceSnapshot,
        savedAt: Long = System.currentTimeMillis(),
    ) {
        val key = PriceUrls.cacheKey(crop, market)
        prefs.edit()
            .putString("cache:$key", PriceJson.encode(snapshot))
            .putLong("saved:$key", savedAt)
            .apply()
    }

    fun ledgerUrl(): String =
        prefs.getString("ledger_url", PriceUrls.DEFAULT_BASE_URL)
            .orEmpty()
            .ifEmpty { PriceUrls.DEFAULT_BASE_URL }

    fun saveLedgerUrl(url: String) {
        prefs.edit().putString("ledger_url", url).apply()
    }

    fun crop(): String = prefs.getString("crop", "wheat").orEmpty().ifEmpty { "wheat" }

    fun market(): String? = prefs.getString("market", "").orEmpty().ifEmpty { null }

    fun saveSelection(crop: String, market: String?) {
        prefs.edit()
            .putString("crop", crop)
            .putString("market", market.orEmpty())
            .apply()
    }
}
