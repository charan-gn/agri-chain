package com.agrichain.relay

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/**
 * Tiny HttpURLConnection client for the v3 ledger. No OkHttp/Retrofit dependency
 * so the same code runs in the Android app and the JVM tests.
 */
class HttpV3LedgerClient(
    baseUrl: String,
    private val timeoutMs: Int = 12_000,
) : V3LedgerClient {
    @Volatile
    private var base: String = baseUrl.trim().trimEnd('/')

    /** Swap a stale ledger host without recreating the client or GatewayLoop. */
    fun setBaseUrl(value: String) {
        synchronized(this) { base = value.trim().trimEnd('/') }
    }

    fun baseUrl(): String = synchronized(this) { base }

    override suspend fun postMessages(via: String, messages: List<ByteArray>): List<LedgerIngestResult> {
        val payload = JSONObject()
            .put("via", via)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { put(Base64.getEncoder().encodeToString(it)) }
                },
            )
        val root = post("/v3/messages", payload)
        val results = root.optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).map { index ->
            val item = results.getJSONObject(index)
            LedgerIngestResult(
                msgId = item.optString("msgId", ""),
                status = item.optString("status", "rejected"),
                reasonCode = item.optInt("reasonCode", 0),
                reason = item.optString("reason", ""),
                seq = item.optLong("seq", 0),
                receipt = item.optString("receipt", "").takeIf { it.isNotEmpty() }?.let {
                    Base64.getDecoder().decode(it)
                },
                retry = item.optBoolean("retry", false),
            )
        }
    }

    override suspend fun prices(sinceVersion: Long): PriceFeed {
        val root = get("/v3/prices?sinceVersion=$sinceVersion")
        val pricesJson = root.optJSONArray("prices") ?: JSONArray()
        val prices = (0 until pricesJson.length()).mapNotNull { index ->
            val item = pricesJson.getJSONObject(index)
            val message = item.optString("message", "").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            PriceTableItem(
                region = item.getInt("region"),
                version = item.getLong("version"),
                part = item.getInt("part"),
                parts = item.getInt("parts"),
                asOf = item.optLong("asOf", 0),
                message = Base64.getDecoder().decode(message),
            )
        }
        val beaconsJson = root.optJSONArray("beacons") ?: JSONArray()
        val beacons = (0 until beaconsJson.length()).mapNotNull { index ->
            val item = beaconsJson.getJSONObject(index)
            val message = item.optString("message", "").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            PriceBeaconItem(
                region = item.getInt("region"),
                version = item.getLong("version"),
                hash8 = item.optString("hash8", ""),
                message = Base64.getDecoder().decode(message),
            )
        }
        return PriceFeed(
            version = root.optLong("version", 0),
            prices = prices,
            beacons = beacons,
        )
    }

    override suspend fun listings(afterSeq: Long): ListingFeed {
        val root = get("/v3/listings?afterSeq=$afterSeq")
        val array = root.optJSONArray("listings") ?: JSONArray()
        val listings = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ListingItem(
                listingId = item.optString("listingId", ""),
                sellerId = item.optString("sellerId", ""),
                crop = item.optInt("crop", 0),
                qtyKg = item.optLong("qtyKg", 0),
                remainingKg = item.optLong("remainingKg", 0),
                askPaise = item.optLong("askPaise", 0),
                region = item.optInt("region", 0),
                validUntil = item.optLong("validUntil", 0),
                grade = item.optInt("grade", 0),
                batchId = item.optString("batchId", ""),
                createdSeq = item.optLong("createdSeq", 0),
                message = item.optString("message", "").takeIf { it.isNotEmpty() }?.let {
                    Base64.getDecoder().decode(it)
                },
            )
        }
        return ListingFeed(listings, root.optLong("nextAfterSeq", afterSeq))
    }

    override suspend fun pullOutbox(actors: List<String>, afterId: Long): OutboxPage {
        val payload = JSONObject()
            .put("actors", JSONArray().apply { actors.forEach { put(it) } })
            .put("afterId", afterId)
        val root = post("/v3/outbox/pull", payload)
        val array = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            OutboxItem(
                id = item.getLong("id"),
                toId = item.optString("toId", ""),
                msgId = item.optString("msgId", ""),
                kind = item.optString("kind", ""),
                createdSeq = item.optLong("createdSeq", 0),
                createdAt = item.optLong("createdAt", 0),
                message = Base64.getDecoder().decode(item.getString("message")),
            )
        }
        return OutboxPage(items, root.optLong("nextAfterId", afterId))
    }

    private fun get(path: String): JSONObject = request("GET", path, null)
    private fun post(path: String, payload: JSONObject): JSONObject = request("POST", path, payload)

    private fun request(method: String, path: String, payload: JSONObject?): JSONObject {
        val connection = URI(baseUrl() + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IOException("$method $path -> $code: ${text.take(300)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
