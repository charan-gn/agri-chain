package com.agrichain.box

import com.agrichain.net.LedgerSettings
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BatchNotFoundException(message: String) : IOException(message)

data class BoxBatchInfo(
    val batchId: String,
    val mainHead: String,
    val traceHead: String,
    val boundBoxId: String?,
    val ownerId: String?,
)

object LedgerClient {

    fun normalizeBaseUrl(raw: String): String =
        raw.trim().trimEnd('/').ifBlank { LedgerSettings.DEFAULT_BASE_URL }

    /**
     * Reads this box's own chain heads and the latest BIND. The box does not need
     * the farmer's head; it only needs where its own `main`/`trace` chains stop.
     */
    @Throws(IOException::class)
    fun fetchBatchInfo(baseUrl: String, batchId: String, boxActorId: String): BoxBatchInfo {
        val connection = open("${normalizeBaseUrl(baseUrl)}/batches/$batchId")
        try {
            val code = connection.responseCode
            if (code == 404) throw BatchNotFoundException("This batch is not on the ledger yet")
            if (code !in 200..299) throw IOException("Ledger HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("batchId") != batchId) {
                throw IOException("Ledger returned a different batch")
            }

            var mainHead = ""
            var traceHead = ""
            val chains = json.optJSONArray("chains")
            if (chains != null) {
                for (index in 0 until chains.length()) {
                    val chain = chains.getJSONObject(index)
                    if (chain.optString("actorId") != boxActorId) continue
                    when (chain.optString("stream")) {
                        "main" -> mainHead = chain.optString("head")
                        "trace" -> traceHead = chain.optString("head")
                    }
                }
            }

            var boundBoxId: String? = null
            var latestBindTs = Long.MIN_VALUE
            var ownerId: String? = null
            val events = json.optJSONArray("events")
            if (events != null) {
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    when (event.optString("type")) {
                        "REGISTER" -> ownerId = event.optString("actorId")
                        "BIND" -> {
                            val ts = event.optLong("ts")
                            if (ts >= latestBindTs) {
                                latestBindTs = ts
                                val data = event.optJSONObject("data")
                                boundBoxId = if (data == null || data.isNull("boxId")) {
                                    null
                                } else {
                                    data.optString("boxId").takeIf { it.isNotEmpty() }
                                }
                            }
                        }
                    }
                }
            }

            return BoxBatchInfo(
                batchId = batchId,
                mainHead = mainHead,
                traceHead = traceHead,
                boundBoxId = boundBoxId,
                ownerId = ownerId,
            )
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    fun registerActor(
        baseUrl: String,
        actorId: String,
        name: String,
        role: String,
        pubKey: String,
        adminToken: String = "dev-admin-token",
    ) {
        val connection = open("${normalizeBaseUrl(baseUrl)}/actors").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Admin-Token", adminToken)
        }
        val payload = JSONObject()
            .put("actorId", actorId)
            .put("name", name)
            .put("role", role)
            .put("pubKey", pubKey)
            .toString()
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Ledger HTTP $code ${detail.take(160)}".trim())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
}
