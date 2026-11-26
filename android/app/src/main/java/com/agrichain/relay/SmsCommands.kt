package com.agrichain.relay

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Read-only SMS commands for feature phones:
 *
 * `PRICE <crop> [region]` and `LISTINGS <crop> [region]`.
 *
 * The reply is plain text and is always trimmed to one 160-character SMS.
 */
class SmsCommandHandler(
    private val client: V3LedgerClient,
    private val defaultRegion: Int = 1,
) {

    data class Command(val crop: Int, val region: Int)

    fun parse(body: String): Command? {
        val words = body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        if (!words[0].equals("PRICE", true) && !words[0].equals("LISTINGS", true)) return null
        val crop = cropId(words[1]) ?: return null
        val region = words.getOrNull(2)?.let { regionId(it) } ?: defaultRegion
        return Command(crop, region)
    }

    suspend fun handle(body: String): String {
        val command = parse(body) ?: return "Usage: PRICE <crop> [region] or LISTINGS <crop> [region]"
        return if (body.trim().startsWith("PRICE", ignoreCase = true)) {
            priceReply(command)
        } else {
            listingsReply(command)
        }
    }

    private suspend fun priceReply(command: Command): String {
        val cropName = V3Dictionaries.cropName(command.crop).uppercase()
        val regionName = V3Dictionaries.regionName(command.region)
        val feed = runCatching { client.prices(0) }.getOrElse {
            return "$cropName: ledger unreachable, try later"
        }
        val tables = feed.prices.filter { it.region == command.region }
        val latest = tables.maxByOrNull { it.version } ?: return "No price for $cropName in $regionName yet"
        val rows = tables.filter { it.version == latest.version }.flatMap { item ->
            runCatching {
                val body = V3Codec.decode(item.message).body
                (V3Bodies.parse(V3Type.PRICE_TABLE, body) as V3Body.PriceTable).rows
            }.getOrDefault(emptyList())
        }
        val row = rows.firstOrNull { it.crop == command.crop }
            ?: return "No price for $cropName in $regionName yet"
        val reply = "$cropName ${row.pricePaise} paise/kg (${money(row.pricePaise.toLong())}) " +
            "$regionName v${latest.version} ${row.trades} trades"
        return reply.take(160)
    }

    private suspend fun listingsReply(command: Command): String {
        val cropName = V3Dictionaries.cropName(command.crop).uppercase()
        val regionName = V3Dictionaries.regionName(command.region)
        val feed = runCatching { client.listings(0) }.getOrElse {
            return "$cropName: ledger unreachable, try later"
        }
        val open = feed.listings.filter {
            it.crop == command.crop && it.region == command.region && it.remainingKg > 0
        }.sortedBy { it.askPaise }
        if (open.isEmpty()) return "No open $cropName listings in $regionName"
        val text = open.take(2).joinToString("; ") { item ->
            "$cropName ${item.remainingKg}kg @${money(item.askPaise.toLong())} " +
                "seller ${item.sellerId.take(6)}"
        }
        return text.take(160)
    }

    private fun cropId(value: String): Int? =
        value.toIntOrNull()?.takeIf { id -> V3Dictionaries.crops.any { it.first == id } }
            ?: V3Dictionaries.cropId(value)

    private fun regionId(value: String): Int? =
        value.toIntOrNull()?.takeIf { id -> V3Dictionaries.regions.any { it.first == id } }
            ?: V3Dictionaries.regionId(value)

    private fun money(paise: Long): String = "%.2f/kg".format(paise / 100.0)
}

/** Small direct SMS sender for feature-phone replies (no relay required). */
object SmsSender {

    fun sendText(context: Context, number: String, text: String): Boolean {
        if (number.isBlank()) return false
        return runCatching {
            val subId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
                .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            val manager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            manager.sendTextMessage(number, null, text.take(160), null, null)
            true
        }.getOrDefault(false)
    }
}
