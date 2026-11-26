package com.agrichain.data

import com.agrichain.core.Event
import org.json.JSONArray
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

data class ServerBatch(
    val head: String,
    val eventIds: Set<String>,
    val chainHeads: Map<Pair<String, String>, String> = emptyMap(),
)

class LedgerApi(private val baseUrl: String) {

    fun registerActor(
        actorId: String,
        name: String,
        role: String,
        pubKey: String,
        adminToken: String = "dev-admin-token",
    ) {
        val body = JSONObject()
            .put("actorId", actorId)
            .put("name", name)
            .put("role", role)
            .put("pubKey", pubKey)
        request("POST", "/actors", body, adminToken = adminToken)
    }

    fun fetchBatch(batchId: String): ServerBatch? {
        val json = request("GET", "/batches/${encode(batchId)}", null, allowNotFound = true)
            ?: return null
        val events = json.optJSONArray("events")
        val ids = if (events == null) {
            emptySet()
        } else {
            (0 until events.length()).mapTo(mutableSetOf()) { index ->
                events.getJSONObject(index).getString("eventId")
            }
        }
        val chains = json.optJSONArray("chains")
        val chainHeads = linkedMapOf<Pair<String, String>, String>()
        if (chains != null) {
            for (i in 0 until chains.length()) {
                val c = chains.getJSONObject(i)
                chainHeads[c.getString("actorId") to c.getString("stream")] = c.optString("head")
            }
        }
        return ServerBatch(
            head = json.optString("lastHash"),
            eventIds = ids,
            chainHeads = chainHeads,
        )
    }

    fun postEvent(event: Event): String {
        val json = request("POST", "/events", event.toJsonObject())
            ?: throw LedgerException(500, "empty_response", "ledger returned no body", null)
        return json.getString("head")
    }

    /** Mirrors relay chat into a separate dashboard feed; never a signed event. */
    fun postRelayUsers(users: List<JSONObject>) {
        if (users.isEmpty()) return
        request("POST", "/relay/users", JSONObject().put("users", JSONArray(users)))
    }

    /** Mirrors relay chat into a separate dashboard feed; never a signed event. */
    fun postRelayMessages(messages: List<JSONObject>) {
        if (messages.isEmpty()) return
        request("POST", "/relay/messages", JSONObject().put("messages", JSONArray(messages)))
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject?,
        allowNotFound: Boolean = false,
        adminToken: String? = null,
    ): JSONObject? {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 6_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (!adminToken.isNullOrBlank()) {
                connection.setRequestProperty("X-Admin-Token", adminToken)
            }
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
