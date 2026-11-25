package com.agrichain.prices

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PriceRepository(private val cache: PriceCache) {

    fun cached(crop: String): CachedPrices? = cache.load(crop)

    fun fetch(baseUrl: String, crop: String): TradePriceSnapshot {
        return TradePriceJson.parse(request(PriceUrls.tradesUrl(baseUrl, crop)))
    }

    private fun request(urlText: String): String {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException(errorText(text, status))
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun errorText(body: String, status: Int): String {
        val parsed = runCatching {
            val json = JSONObject(body)
            listOf(json.optString("error"), json.optString("detail"))
                .filter { it.isNotBlank() }
                .joinToString(": ")
        }.getOrNull()
        return parsed?.takeIf { it.isNotBlank() } ?: "HTTP $status"
    }
}
