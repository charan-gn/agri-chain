package com.agrichain.relay

import kotlinx.coroutines.delay

/** Result of one message in `POST /v3/messages`. */
data class LedgerIngestResult(
    val msgId: String,
    val status: String,
    val reasonCode: Int,
    val reason: String,
    val seq: Long,
    val receipt: ByteArray?,
    val retry: Boolean = false,
)

data class PriceTableItem(
    val region: Int,
    val version: Long,
    val part: Int,
    val parts: Int,
    val asOf: Long,
    val message: ByteArray,
)

data class PriceBeaconItem(
    val region: Int,
    val version: Long,
    val hash8: String,
    val message: ByteArray,
)

data class PriceFeed(
    val version: Long,
    val prices: List<PriceTableItem>,
    val beacons: List<PriceBeaconItem>,
)

data class ListingItem(
    val listingId: String,
    val sellerId: String,
    val crop: Int,
    val qtyKg: Long,
    val remainingKg: Long,
    val askPaise: Long,
    val region: Int,
    val validUntil: Long,
    val grade: Int,
    val batchId: String,
    val createdSeq: Long,
    val message: ByteArray?,
)

data class ListingFeed(
    val listings: List<ListingItem>,
    val nextAfterSeq: Long,
)

data class OutboxItem(
    val id: Long,
    val toId: String,
    val msgId: String,
    val kind: String,
    val createdSeq: Long,
    val createdAt: Long,
    val message: ByteArray,
)

data class OutboxPage(
    val items: List<OutboxItem>,
    val nextAfterId: Long,
)

interface V3LedgerClient {
    suspend fun postMessages(via: String, messages: List<ByteArray>): List<LedgerIngestResult>
    suspend fun prices(sinceVersion: Long): PriceFeed
    suspend fun listings(afterSeq: Long): ListingFeed
    suspend fun pullOutbox(actors: List<String>, afterId: Long): OutboxPage
}

data class GatewayConfig(
    val batchSize: Int = 50,
    val receiptTtl: Int = 7,
    val priceTtl: Int = 5,
    val beaconTtl: Int = 5,
    val listingTtl: Int = 3,
    val uplinkEveryMs: Long = 15_000,
    val pricesEveryMs: Long = 30_000,
    val beaconEveryMs: Long = 5 * 60_000,
    val listingsEveryMs: Long = 60_000,
    val outboxEveryMs: Long = 15_000,
    val actorWindowMs: Long = 24 * 60 * 60_000L,
)

data class GatewayTick(
    val uplinked: Int,
    val receipts: Int,
    val prices: Int,
    val listings: Int,
    val outbox: Int,
)

/**
 * Downlink/uplink loop from the guide. It is deliberately callable one step at
 * a time so the JVM simulator and service both use the same code.
 */
class GatewayLoop(
    private val core: RelayCore,
    private val client: V3LedgerClient,
    private val config: GatewayConfig = GatewayConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private var lastBeaconAt = 0L
    private var lastPricesAt = 0L
    private var lastListingsAt = 0L
    private var lastOutboxAt = 0L

    suspend fun uplinkOnce(): Int {
        val pending = core.pendingForUplink(config.batchSize)
        if (pending.isEmpty()) return 0
        var uploaded = 0
        for ((via, items) in pending.groupBy { normalizeVia(it.via) }) {
            val results = try {
                client.postMessages(via, items.map { it.message })
            } catch (exc: Exception) {
                continue
            }
            core.noteGatewaySeen()
            for (result in results) {
                core.applyIngestResult(result.msgId, result.status, result.reasonCode)
                if (result.status != "pending") uploaded += 1
                result.receipt?.let { receipt ->
                    core.injectLedgerMessage(receipt, config.receiptTtl)
                }
            }
        }
        return uploaded
    }

    suspend fun pricesOnce(forceBeacon: Boolean = false): Int {
        val since = core.priceVersions().values.minOrNull()?.let { maxOf(0, it - 1) } ?: 0
        val feed = try {
            client.prices(since)
        } catch (exc: Exception) {
            return 0
        }
        var injected = 0
        for (item in feed.prices) {
            core.injectLedgerMessage(item.message, config.priceTtl)
            injected += 1
        }
        val now = clock()
        if (forceBeacon || now - lastBeaconAt >= config.beaconEveryMs) {
            for (beacon in feed.beacons) {
                core.injectLedgerMessage(beacon.message, config.beaconTtl)
                injected += 1
            }
            if (feed.beacons.isNotEmpty()) lastBeaconAt = now
        }
        lastPricesAt = now
        return injected
    }

    suspend fun listingsOnce(): Int {
        val feed = try {
            client.listings(core.listingCursor())
        } catch (exc: Exception) {
            return 0
        }
        var injected = 0
        for (item in feed.listings) {
            val message = item.message ?: continue
            core.injectLedgerMessage(message, config.listingTtl)
            core.cacheListing(message, item.createdSeq)
            injected += 1
        }
        core.setListingCursor(feed.nextAfterSeq)
        lastListingsAt = clock()
        return injected
    }

    suspend fun outboxOnce(): Int {
        val now = clock()
        val actors = (core.peersSnapshot()
            .filter { now - it.lastSeen <= config.actorWindowMs }
            .map { it.actorId } + core.me)
            .distinct()
            .take(200)
        if (actors.isEmpty()) return 0
        val page = try {
            client.pullOutbox(actors, core.outboxCursor())
        } catch (exc: Exception) {
            return 0
        }
        var injected = 0
        for (item in page.items) {
            core.injectLedgerMessage(item.message, config.receiptTtl)
            injected += 1
        }
        core.setOutboxCursor(page.nextAfterId)
        lastOutboxAt = now
        return injected
    }

    suspend fun pollOnce(forceBeacon: Boolean = false): GatewayTick {
        val uplinked = uplinkOnce()
        val prices = pricesOnce(forceBeacon)
        val listings = listingsOnce()
        val outbox = outboxOnce()
        return GatewayTick(
            uplinked = uplinked,
            receipts = 0,
            prices = prices,
            listings = listings,
            outbox = outbox,
        )
    }

    /** Long-running gateway loop used by RelayService. */
    suspend fun run() {
        while (true) {
            val now = clock()
            uplinkOnce()
            if (now - lastPricesAt >= config.pricesEveryMs) pricesOnce()
            if (now - lastListingsAt >= config.listingsEveryMs) listingsOnce()
            if (now - lastOutboxAt >= config.outboxEveryMs) outboxOnce()
            sleep(5_000)
        }
    }

    private fun normalizeVia(via: String): String = when (via.lowercase()) {
        "wifi", "ble", "lora", "sms", "internet" -> via.lowercase()
        else -> "internet"
    }
}
