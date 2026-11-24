package com.agrichain.prices

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PriceRepository(private val cache: PriceCache) {

    fun cached(crop: String, market: String?): CachedPrices? = cache.load(crop, market)

    fun fetch(
        baseUrl: String,
        crop: String?,
        market: String?,
        refresh: Boolean,
    ): PriceSnapshot {
        val url = if (refresh) {
            PriceUrls.refreshUrl(baseUrl)
        } else {
            PriceUrls.listUrl(baseUrl, crop, market)
        }
        val body = if (refresh) PriceUrls.refreshBody(crop, market) else null
        return PriceJson.parse(request(url, body))
    }

    private fun request(urlText: String, body: String?): String {
        val connection = URL(urlText).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
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
