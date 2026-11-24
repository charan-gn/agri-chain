package com.agrichain.box

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BatchNotFoundException(message: String) : IOException(message)

object LedgerClient {

    fun normalizeBaseUrl(raw: String): String =
        raw.trim().trimEnd('/').ifBlank { BoxSession.DEFAULT_LEDGER_URL }

    @Throws(IOException::class)
    fun fetchBatchHead(baseUrl: String, batchId: String): String {
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
            return if (json.isNull("lastHash")) "" else json.getString("lastHash")
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
    ) {
        val connection = open("${normalizeBaseUrl(baseUrl)}/actors").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
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
