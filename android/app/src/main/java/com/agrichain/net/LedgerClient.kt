package com.agrichain.net

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LedgerClient(context: Context) {

    private val baseUrl = LedgerSettings.baseUrl(context)

    fun getBatch(batchId: String): JSONObject = get("/batches/$batchId")

    fun getTradePrice(crop: String): JSONObject =
        get("/trades?crop=${URLEncoder.encode(crop, "UTF-8")}")

    private fun get(path: String): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("Accept", "application/json")
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("Ledger returned HTTP $status")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
