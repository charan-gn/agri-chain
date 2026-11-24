package com.agrichain.data

import com.agrichain.core.Event
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LedgerException(
    val status: Int,
    val code: String,
    val detail: String,
    val head: String?,
) : IOException(if (code.isEmpty()) detail else "$code: $detail")

data class ServerBatch(val head: String, val eventIds: Set<String>)

class LedgerApi(private val baseUrl: String) {

    fun registerActor(actorId: String, name: String, role: String, pubKey: String) {
        val body = JSONObject()
            .put("actorId", actorId)
            .put("name", name)
            .put("role", role)
            .put("pubKey", pubKey)
        request("POST", "/actors", body)
    }

    fun fetchBatch(batchId: String): ServerBatch? {
        val json = request("GET", "/events?batchId=${encode(batchId)}", null, allowNotFound = true)
            ?: return null
        val events = json.optJSONArray("events") ?: return ServerBatch(json.getString("head"), emptySet())
        val ids = (0 until events.length()).mapTo(mutableSetOf()) { index ->
            events.getJSONObject(index).getString("eventId")
        }
        return ServerBatch(json.getString("head"), ids)
    }

    fun postEvent(event: Event): String {
        val json = request("POST", "/events", event.toJsonObject())
            ?: throw LedgerException(500, "empty_response", "ledger returned no body", null)
        return json.getString("head")
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        allowNotFound: Boolean = false,
    ): JSONObject? {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 6_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (allowNotFound && status == 404) return null
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text) }.getOrNull()
                throw LedgerException(
                    status,
                    error?.optString("error").orEmpty(),
                    error?.optString("detail").orEmpty(),
                    error?.optString("head")?.takeIf { it.isNotEmpty() },
                )
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
