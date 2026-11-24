package com.agrichain.barter

import android.content.Context
import com.agrichain.core.Event
import com.agrichain.net.LedgerSettings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BatchInfo(
    val batchId: String,
    val crop: String,
    val grade: String,
    val kg: Int,
) {
    val label: String get() = "$crop · $kg kg · ${batchId.take(8)}"
}

data class BarterOffer(
    val offerId: String,
    val status: String,
    val batchId: String,
    val actorId: String,
    val withActorId: String,
    val giveKg: Int,
    val wantBatchId: String,
    val wantKg: Int,
    val ts: Long,
) {
    companion object {
        fun parse(json: JSONObject): BarterOffer = BarterOffer(
            offerId = json.getString("offerId"),
            status = json.getString("status"),
            batchId = json.getString("batchId"),
            actorId = json.getString("actorId"),
            withActorId = json.getString("withActorId"),
            giveKg = json.getInt("giveKg"),
            wantBatchId = json.getString("wantBatchId"),
            wantKg = json.getInt("wantKg"),
            ts = json.getLong("ts"),
        )
    }
}

class BarterClient(context: Context) {

    private val baseUrl = LedgerSettings.baseUrl(context)

    fun myBatches(actorId: String): List<BatchInfo> {
        val json = get("/batches?owner=${encode(actorId)}")
        val array = json.getJSONArray("batches")
        return (0 until array.length()).mapNotNull { index ->
            val batch = array.getJSONObject(index)
            val kg = batch.optJSONObject("balances")?.optInt(actorId) ?: 0
            if (kg <= 0) {
                null
            } else {
                BatchInfo(
                    batchId = batch.getString("batchId"),
                    crop = batch.getString("crop"),
                    grade = batch.getString("grade"),
                    kg = kg,
                )
            }
        }
    }

    fun offers(actorId: String): List<BarterOffer> {
        val json = get("/barter/offers?actorId=${encode(actorId)}")
        val array = json.getJSONArray("offers")
        return (0 until array.length()).map { index ->
            BarterOffer.parse(array.getJSONObject(index))
        }
    }

    fun head(batchId: String): String =
        get("/events?batchId=${encode(batchId)}").getString("head")

    fun post(event: Event): JSONObject = postJson("/events", event.toJsonObject())

    private fun get(path: String): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        return read(connection)
    }

    private fun postJson(path: String, body: JSONObject): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return read(connection)
    }

    private fun read(connection: HttpURLConnection): JSONObject {
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    val json = JSONObject(text)
                    val code = json.optString("error")
                    val message = json.optString("detail")
                    if (code.isEmpty()) "HTTP $status" else "$code: $message"
                }.getOrDefault("HTTP $status")
                throw IOException(detail)
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
