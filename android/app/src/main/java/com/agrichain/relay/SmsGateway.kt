package com.agrichain.relay

/**
 * Gateway-side SMS logic, kept free of Android types so the JVM suite can drive
 * it. Two jobs:
 *
 *  1. Remember which phone sent each uplink, then forward the ledger's RECEIPT
 *     back to that phone as a whole v3 envelope.
 *  2. Answer plain-text `PRICE <crop> [region]` and `LISTINGS <crop> [region]`
 *     commands from the relay cache, falling back to the ledger feed.
 */
class SmsGateway(
    private val priceCaches: () -> List<PriceCache>,
    private val listingCaches: () -> List<ListingCache>,
    private val priceFeed: (suspend () -> PriceFeed)? = null,
    private val listingFeed: (suspend () -> ListingFeed)? = null,
    private val sendEnvelope: (number: String, envelope: ByteArray) -> Boolean,
    private val sendText: (number: String, text: String) -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxOrigins: Int = 512,
    private val originTtlMs: Long = 72 * 60 * 60_000L,
) {

    private data class Origin(val number: String, val at: Long)

    private val lock = Any()
    private val origins = LinkedHashMap<String, Origin>()

    /** Record the sender of [envelope]; returns its msgId for client uplinks. */
    fun remember(envelope: ByteArray, number: String): String? {
        val raw = runCatching { V3Codec.decodeEnvelope(envelope).second }.getOrNull() ?: return null
        val msg = runCatching { V3Codec.decode(raw) }.getOrNull() ?: return null
        if (msg.ledgerSigned || !isUplinkType(msg.type)) return null
        val msgId = V3Codec.hex(V3Codec.msgId(msg))
        synchronized(lock) {
            val now = clock()
            origins.entries.removeIf { now - it.value.at > originTtlMs }
            while (origins.size >= maxOrigins) {
                val oldest = origins.keys.firstOrNull() ?: break
                origins.remove(oldest)
            }
            origins[msgId] = Origin(number, now)
        }
        return msgId
    }

    /** Forward a ledger RECEIPT to the phone whose message it acknowledges. */
    fun onLedgerMessage(raw: ByteArray): Boolean {
        val msg = runCatching { V3Codec.decode(raw) }.getOrNull() ?: return false
        if (msg.type != V3Type.RECEIPT) return false
        val body = runCatching { V3Bodies.parse(msg.type, msg.body) }.getOrNull()
            as? V3Body.Receipt ?: return false
        val origin = synchronized(lock) {
            origins.remove(V3Codec.hex(body.msgId))
        } ?: return false
        // TTL 1: the farmer does not need to relay a receipt any further.
        return sendEnvelope(origin.number, V3Codec.encodeEnvelope(raw, 1))
    }

    /** Parse and answer one plain-text command. Returns the reply, or null. */
    suspend fun answer(number: String, text: String): String? {
        val command = SmsCommands.parse(text) ?: return null
        val reply = when (command) {
            is SmsCommand.Price -> priceReply(command.crop, command.region)
            is SmsCommand.Listings -> listingsReply(command.crop, command.region)
        }
        sendText(number, reply)
        return reply
    }

    fun originCount(): Int = synchronized(lock) { origins.size }

    private suspend fun priceReply(crop: Int, region: Int?): String {
        val cache = priceCaches()
            .filter { region == null || it.region == region }
            .maxByOrNull { it.version }
        val row = cache?.rows?.firstOrNull { it.crop == crop }
        if (cache != null && row != null) {
            return SmsCommands.formatPrice(
                SmsPriceQuote(
                    crop = crop,
                    region = cache.region,
                    version = cache.version,
                    asOf = cache.asOf,
                    pricePaise = row.pricePaise.toLong(),
                    trades = row.trades,
                ),
                clock(),
            )
        }
        val quote = priceFromFeed(crop, region)
        return if (quote != null) SmsCommands.formatPrice(quote, clock())
        else SmsCommands.noPrice(crop, region)
    }

    private suspend fun priceFromFeed(crop: Int, region: Int?): SmsPriceQuote? {
        val feed = priceFeed?.invoke() ?: return null
        var best: SmsPriceQuote? = null
        for (item in feed.prices) {
            val table = runCatching {
                V3Bodies.parse(V3Type.PRICE_TABLE, V3Codec.decode(item.message).body)
            }.getOrNull() as? V3Body.PriceTable ?: continue
            if (region != null && table.region != region) continue
            val row = table.rows.firstOrNull { it.crop == crop } ?: continue
            val quote = SmsPriceQuote(
                crop = crop,
                region = table.region,
                version = table.version,
                asOf = table.asOf,
                pricePaise = row.pricePaise.toLong(),
                trades = row.trades,
            )
            if (best == null || quote.version > best.version) best = quote
        }
        return best
    }

    private suspend fun listingsReply(crop: Int, region: Int?): String {
        val nowSec = clock() / 1000
        val cached = listingCaches().filter {
            it.crop == crop &&
                (region == null || it.region == region) &&
                it.validUntil > nowSec
        }
        if (cached.isNotEmpty()) return listingQuote(crop, region, cached)

        val feed = listingFeed?.invoke()?.listings.orEmpty().filter {
            it.crop == crop &&
                (region == null || it.region == region) &&
                it.remainingKg > 0 &&
                it.validUntil > nowSec
        }
        if (feed.isEmpty()) return SmsCommands.noListings(crop, region)

        val chosen = feed.groupBy { it.region }.maxByOrNull { it.value.size }!!.value
        val best = chosen.minByOrNull { it.askPaise }!!
        return SmsCommands.formatListings(
            SmsListingQuote(
                crop = crop,
                region = best.region,
                count = chosen.size,
                bestPaise = best.askPaise,
                bestQtyKg = best.remainingKg,
            ),
        )
    }

    private fun listingQuote(
        crop: Int,
        region: Int?,
        listings: List<ListingCache>,
    ): String {
        val chosen = if (region != null) {
            listings
        } else {
            listings.groupBy { it.region }.maxByOrNull { it.value.size }!!.value
        }
        val best = chosen.minByOrNull { it.askPaise }!!
        return SmsCommands.formatListings(
            SmsListingQuote(
                crop = crop,
                region = best.region,
                count = chosen.size,
                bestPaise = best.askPaise,
                bestQtyKg = best.remainingKg,
            ),
        )
    }

    private fun isUplinkType(type: V3Type): Boolean = when (type) {
        V3Type.LISTING,
        V3Type.ORDER,
        V3Type.ORDER_ACCEPT,
        V3Type.ORDER_CANCEL,
        V3Type.DELIVERED,
        V3Type.BARTER_OFFER,
        V3Type.BARTER_ACCEPT,
        V3Type.TAMPER_ALERT,
        V3Type.BATCH_REGISTER,
        V3Type.BIND,
        -> true
        else -> false
    }
}
