package com.agrichain.prices

import com.agrichain.net.LedgerSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Trade(
    val eventId: String,
    val batchId: String,
    val ts: Long,
    val tokens: Int,
    val pricePerKgPaise: Int,
    val grade: String,
    val fromActorId: String,
    val toActorId: String,
)

data class TradePriceSnapshot(
    val crop: String,
    val grade: String?,
    val method: String,
    val sampleSize: Int,
    val pricePerKgPaise: Int?,
    val byGrade: Map<String, Int?>,
    val updatedAt: Long,
    val trades: List<Trade>,
)

object TradePriceJson {

    fun parse(body: String): TradePriceSnapshot {
        val root = JSONObject(body)
        val updatedAt = root.optLong("updatedAt", 0L)
        val array = root.optJSONArray("trades") ?: JSONArray()
        val trades = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    Trade(
                        eventId = item.optString("eventId", ""),
                        batchId = item.optString("batchId", ""),
                        ts = item.optLong("ts", 0L),
                        tokens = item.optInt("tokens", 0),
                        pricePerKgPaise = item.getInt("pricePerKgPaise"),
                        grade = item.optString("grade", ""),
                        fromActorId = item.optString("fromActorId", ""),
                        toActorId = item.optString("toActorId", ""),
                    )
                )
            }
        }
        val byGradeJson = root.optJSONObject("byGrade")
        val byGrade = buildMap {
            for (grade in listOf("A", "B", "C")) {
                if (byGradeJson != null && byGradeJson.has(grade) && !byGradeJson.isNull(grade)) {
                    put(grade, byGradeJson.getInt(grade))
                }
            }
        }
        return TradePriceSnapshot(
            crop = root.optString("crop", ""),
            grade = if (root.isNull("grade")) null else root.optString("grade").ifBlank { null },
            method = root.optString("method", "median20"),
            sampleSize = root.optInt("sampleSize", trades.size),
            pricePerKgPaise = if (root.isNull("pricePerKgPaise")) {
                null
            } else {
                root.optInt("pricePerKgPaise")
            },
            byGrade = byGrade,
            updatedAt = updatedAt,
            trades = trades,
        )
    }

    fun encode(snapshot: TradePriceSnapshot): String {
        val array = JSONArray()
        snapshot.trades.forEach { trade ->
            array.put(
                JSONObject()
                    .put("eventId", trade.eventId)
                    .put("batchId", trade.batchId)
                    .put("ts", trade.ts)
                    .put("tokens", trade.tokens)
                    .put("pricePerKgPaise", trade.pricePerKgPaise)
                    .put("grade", trade.grade)
                    .put("fromActorId", trade.fromActorId)
                    .put("toActorId", trade.toActorId)
            )
        }
        val grades = JSONObject()
        snapshot.byGrade.forEach { (grade, price) ->
            grades.put(grade, price ?: JSONObject.NULL)
        }
        return JSONObject()
            .put("crop", snapshot.crop)
            .put("grade", snapshot.grade ?: JSONObject.NULL)
            .put("method", snapshot.method)
            .put("sampleSize", snapshot.sampleSize)
            .put("pricePerKgPaise", snapshot.pricePerKgPaise ?: JSONObject.NULL)
            .put("byGrade", grades)
            .put("updatedAt", snapshot.updatedAt)
            .put("trades", array)
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

    fun build(trades: List<Trade>): List<PriceBar> {
        val window = trades.takeLast(20)
        val maximum = window.maxOfOrNull { it.pricePerKgPaise } ?: return emptyList()
        val format = SimpleDateFormat("dd MMM HH:mm", Locale.US)
        return window.map { trade ->
            PriceBar(
                label = format.format(Date(trade.ts)),
                pricePerKgPaise = trade.pricePerKgPaise,
                fraction = if (maximum <= 0) 0f else trade.pricePerKgPaise.toFloat() / maximum,
                source = "${trade.tokens} kg · grade ${trade.grade}",
            )
        }
    }
}

object PriceUrls {

    const val DEFAULT_BASE_URL = LedgerSettings.DEFAULT_BASE_URL

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

    fun tradesUrl(baseUrl: String, crop: String): String =
        normalizeBaseUrl(baseUrl) + "/trades?crop=" + encode(crop.trim().lowercase())

    fun cacheKey(crop: String): String = crop.trim().lowercase()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
