package com.agrichain.prices

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class PricePoint(
    val crop: String,
    val market: String,
    val pricePerKgPaise: Int,
    val source: String,
    val ts: Long,
)

data class PriceSnapshot(
    val updatedAt: Long,
    val prices: List<PricePoint>,
)

object PriceJson {

    fun parse(body: String): PriceSnapshot {
        val root = JSONObject(body)
        val updatedAt = root.optLong("updatedAt", 0L)
        val array = root.optJSONArray("prices") ?: JSONArray()
        val prices = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    PricePoint(
                        crop = item.getString("crop"),
                        market = item.getString("market"),
                        pricePerKgPaise = item.getInt("pricePerKgPaise"),
                        source = item.optString("source", ""),
                        ts = item.optLong("ts", updatedAt),
                    )
                )
            }
        }
        return PriceSnapshot(updatedAt = updatedAt, prices = prices)
    }

    fun encode(snapshot: PriceSnapshot): String {
        val array = JSONArray()
        snapshot.prices.forEach { point ->
            array.put(
                JSONObject()
                    .put("crop", point.crop)
                    .put("market", point.market)
                    .put("pricePerKgPaise", point.pricePerKgPaise)
                    .put("source", point.source)
                    .put("ts", point.ts)
            )
        }
        return JSONObject()
            .put("updatedAt", snapshot.updatedAt)
            .put("prices", array)
            .toString()
    }
}

object PriceFormat {

    fun rupees(paise: Int): String {
        val sign = if (paise < 0) "-" else ""
        val absolute = kotlin.math.abs(paise)
        val fraction = (absolute % 100).toString().padStart(2, '0')
        return "$sign₹${absolute / 100}.$fraction"
    }

    fun perKg(paise: Int): String = "${rupees(paise)}/kg"
}

data class PriceBar(
    val label: String,
    val pricePerKgPaise: Int,
    val fraction: Float,
    val source: String,
)

object PriceBars {

    fun build(points: List<PricePoint>): List<PriceBar> {
        val maximum = points.maxOfOrNull { it.pricePerKgPaise } ?: return emptyList()
        return points
            .sortedByDescending { it.pricePerKgPaise }
            .map { point ->
                PriceBar(
                    label = point.market,
                    pricePerKgPaise = point.pricePerKgPaise,
                    fraction = if (maximum <= 0) 0f else point.pricePerKgPaise.toFloat() / maximum,
                    source = point.source,
                )
            }
    }
}

object PriceUrls {

    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().ifEmpty { DEFAULT_BASE_URL }
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    fun listUrl(baseUrl: String, crop: String?, market: String?): String {
        val base = normalizeBaseUrl(baseUrl) + "/prices"
        val params = buildList {
            if (!crop.isNullOrBlank()) {
                add("crop=" + encode(crop.trim().lowercase()))
            }
            if (!market.isNullOrBlank()) {
                add("market=" + encode(market.trim().lowercase()))
            }
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    fun refreshUrl(baseUrl: String): String =
        normalizeBaseUrl(baseUrl) + "/prices/refresh"

    fun refreshBody(crop: String?, market: String?): String {
        val body = JSONObject()
        if (!crop.isNullOrBlank()) body.put("crop", crop.trim().lowercase())
        if (!market.isNullOrBlank()) body.put("market", market.trim().lowercase())
        return body.toString()
    }

    fun cacheKey(crop: String, market: String?): String =
        "${crop.trim().lowercase()}|${market?.trim()?.lowercase().orEmpty()}"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
