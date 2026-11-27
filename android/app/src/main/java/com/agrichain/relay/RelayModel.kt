package com.agrichain.relay

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A client message that has not been acknowledged by a ledger RECEIPT yet. */
data class PendingUplink(
    val msgId: String,
    val message: ByteArray,
    val via: String,
    val firstSeen: Long,
    val lastSent: Long = 0,
    val own: Boolean = false,
    val smsAttempted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("msgId", msgId)
        .put("message", Base64.getEncoder().encodeToString(message))
        .put("via", via)
        .put("firstSeen", firstSeen)
        .put("lastSent", lastSent)
        .put("own", own)
        .put("smsAttempted", smsAttempted)

    companion object {
        fun fromJson(json: JSONObject): PendingUplink = PendingUplink(
            msgId = json.getString("msgId"),
            message = Base64.getDecoder().decode(json.getString("message")),
            via = json.optString("via", "internet"),
            firstSeen = json.optLong("firstSeen", 0),
            lastSent = json.optLong("lastSent", 0),
            own = json.optBoolean("own", false),
            smsAttempted = json.optBoolean("smsAttempted", false),
        )
    }
}

data class PeerInfo(
    val actorId: String,
    val pubkey: ByteArray?,
    val caps: Int,
    val nick: String,
    val lastSeen: Long,
    val linkId: String? = null,
    val phone: String? = null,
) {
    val isGateway: Boolean get() = caps and 0x10 != 0

    fun toJson(): JSONObject = JSONObject()
        .put("actorId", actorId)
        .put("pubkey", pubkey?.let { V3Codec.hex(it) } ?: JSONObject.NULL)
        .put("caps", caps)
        .put("nick", nick)
        .put("lastSeen", lastSeen)
        .put("linkId", linkId ?: JSONObject.NULL)
        .put("phone", phone ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): PeerInfo = PeerInfo(
            actorId = json.getString("actorId"),
            pubkey = json.optString("pubkey", "").takeIf { it.isNotEmpty() }?.let { V3Codec.hex(it) },
            caps = json.optInt("caps", 0),
            nick = json.optString("nick", ""),
            lastSeen = json.optLong("lastSeen", 0),
            linkId = json.optString("linkId", "").takeIf { it.isNotEmpty() },
            phone = json.optString("phone", "").takeIf { it.isNotEmpty() },
        )
    }
}

data class ReceiptInfo(
    val msgId: String,
    val status: Int,
    val reason: Int,
    val seq: Long,
    val head8: ByteArray,
    val at: Long,
) {
    val accepted: Boolean get() = status == 0 || status == 1
    val pending: Boolean get() = status == 3
    val rejected: Boolean get() = status == 2 && reason != 10
    val retryable: Boolean get() = status == 2 && reason == 10

    fun toJson(): JSONObject = JSONObject()
        .put("msgId", msgId)
        .put("status", status)
        .put("reason", reason)
        .put("seq", seq)
        .put("head8", V3Codec.hex(head8))
        .put("at", at)

    companion object {
        fun fromJson(json: JSONObject): ReceiptInfo = ReceiptInfo(
            msgId = json.getString("msgId"),
            status = json.getInt("status"),
            reason = json.getInt("reason"),
            seq = json.getLong("seq"),
            head8 = V3Codec.hex(json.getString("head8")),
            at = json.optLong("at", 0),
        )
    }
}

data class CachedPriceRow(
    val crop: Int,
    val pricePaise: Int,
    val trades: Int,
)

data class PriceCache(
    val region: Int,
    val version: Long,
    val asOf: Long,
    val parts: Map<Int, List<CachedPriceRow>>,
    val updatedAt: Long,
    val source: String,
    val beaconHash: String? = null,
) {
    val rows: List<CachedPriceRow> get() = parts.toSortedMap().values.flatten()
    val ageMs: Long get() = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0)
}

data class ListingCache(
    val listingId: String,
    val raw: ByteArray,
    val seq: Long,
    val sellerId: String,
    val crop: Int,
    val qtyKg: Long,
    val remainingKg: Long,
    val askPaise: Long,
    val region: Int,
    val validUntil: Long,
    val grade: Int,
    val batchId: String,
)

/** UI-facing relay state. Kept immutable so Compose/XML views can diff it. */
data class RelaySnapshot(
    val me: String,
    val peers: List<PeerInfo>,
    val pendingCount: Int,
    val pendingOwnCount: Int,
    val prices: List<PriceCache>,
    val listings: List<ListingCache>,
    val lastReceipt: ReceiptInfo?,
    val gatewayReachable: Boolean,
    val gatewayLastSeen: Long,
    val transports: List<TransportSnapshot>,
)

data class TransportSnapshot(
    val id: String,
    val state: TransportState,
    val links: Int,
    val meshLinks: Boolean,
    val maxFrame: Int,
)

sealed interface RelayEvent {
    data class Receipt(val receipt: ReceiptInfo) : RelayEvent
    data class Notice(val kind: Int, val refId: String, val seq: Long, val qtyKg: Long, val pricePaise: Long) : RelayEvent
    data class DirectedMessage(val type: V3Type, val msgId: String, val fromId: String, val raw: ByteArray) : RelayEvent
    data class Peer(val peer: PeerInfo) : RelayEvent
}

enum class OrderState {
    QUEUED,
    SENT,
    ON_LEDGER,
    REJECTED,
    PENDING,
}

/**
 * JSON snapshot for the Android service / JVM restart tests. The wire messages
 * themselves are already signed bytes, so persistence only needs base64.
 */
object RelayPersistence {
    fun encode(
        pending: Collection<PendingUplink>,
        peers: Collection<PeerInfo>,
        receipts: Collection<ReceiptInfo>,
        prices: Collection<PriceCache>,
        listings: Collection<ListingCache>,
        outboxCursor: Long,
        listingCursor: Long,
    ): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("outboxCursor", outboxCursor)
        root.put("listingCursor", listingCursor)
        root.put("pending", JSONArray().apply {
            pending.forEach { put(it.toJson()) }
        })
        root.put("peers", JSONArray().apply {
            peers.forEach { put(it.toJson()) }
        })
        root.put("receipts", JSONArray().apply {
            receipts.forEach { put(it.toJson()) }
        })
        root.put("prices", JSONArray().apply {
            prices.forEach { price ->
                put(
                    JSONObject()
                        .put("region", price.region)
                        .put("version", price.version)
                        .put("asOf", price.asOf)
                        .put("updatedAt", price.updatedAt)
                        .put("source", price.source)
                        .put("beaconHash", price.beaconHash ?: JSONObject.NULL)
                        .put("parts", JSONArray().apply {
                            price.parts.toSortedMap().forEach { (part, rows) ->
                                put(
                                    JSONObject()
                                        .put("part", part)
                                        .put("rows", JSONArray().apply {
                                            rows.forEach { row ->
                                                put(
                                                    JSONArray()
                                                        .put(row.crop)
                                                        .put(row.pricePaise)
                                                        .put(row.trades)
                                                )
                                            }
                                        })
                                )
                            }
                        })
                )
            }
        })
        root.put("listings", JSONArray().apply {
            listings.forEach { listing ->
                put(
                    JSONObject()
                        .put("listingId", listing.listingId)
                        .put("raw", Base64.getEncoder().encodeToString(listing.raw))
                        .put("seq", listing.seq)
                        .put("sellerId", listing.sellerId)
                        .put("crop", listing.crop)
                        .put("qtyKg", listing.qtyKg)
                        .put("remainingKg", listing.remainingKg)
                        .put("askPaise", listing.askPaise)
                        .put("region", listing.region)
                        .put("validUntil", listing.validUntil)
                        .put("grade", listing.grade)
                        .put("batchId", listing.batchId)
                )
            }
        })
        return root.toString()
    }

    data class Decoded(
        val pending: List<PendingUplink>,
        val peers: List<PeerInfo>,
        val receipts: List<ReceiptInfo>,
        val prices: List<PriceCache>,
        val listings: List<ListingCache>,
        val outboxCursor: Long,
        val listingCursor: Long,
    )

    fun decode(text: String): Decoded {
        val root = JSONObject(text)
        fun <T> array(name: String, map: (JSONObject) -> T): List<T> {
            val items = root.optJSONArray(name) ?: return emptyList()
            return (0 until items.length()).map { map(items.getJSONObject(it)) }
        }
        return Decoded(
            pending = array("pending", PendingUplink::fromJson),
            peers = array("peers", PeerInfo::fromJson),
            receipts = array("receipts", ReceiptInfo::fromJson),
            prices = array("prices") { price ->
                val partsJson = price.optJSONArray("parts") ?: JSONArray()
                val parts = (0 until partsJson.length()).associate { index ->
                    val part = partsJson.getJSONObject(index)
                    val rowsJson = part.optJSONArray("rows") ?: JSONArray()
                    part.getInt("part") to (0 until rowsJson.length()).map { rowIndex ->
                        val row = rowsJson.getJSONArray(rowIndex)
                        CachedPriceRow(row.getInt(0), row.getInt(1), row.getInt(2))
                    }
                }
                PriceCache(
                    region = price.getInt("region"),
                    version = price.getLong("version"),
                    asOf = price.optLong("asOf", 0),
                    parts = parts,
                    updatedAt = price.optLong("updatedAt", 0),
                    source = price.optString("source", "cache"),
                    beaconHash = price.optString("beaconHash", "").takeIf { it.isNotEmpty() },
                )
            },
            listings = array("listings") { listing ->
                ListingCache(
                    listingId = listing.getString("listingId"),
                    raw = Base64.getDecoder().decode(listing.getString("raw")),
                    seq = listing.optLong("seq", 0),
                    sellerId = listing.optString("sellerId", ""),
                    crop = listing.optInt("crop", 0),
                    qtyKg = listing.optLong("qtyKg", 0),
                    remainingKg = listing.optLong("remainingKg", 0),
                    askPaise = listing.optLong("askPaise", 0),
                    region = listing.optInt("region", 0),
                    validUntil = listing.optLong("validUntil", 0),
                    grade = listing.optInt("grade", 0),
                    batchId = listing.optString("batchId", ""),
                )
            },
            outboxCursor = root.optLong("outboxCursor", 0),
            listingCursor = root.optLong("listingCursor", 0),
        )
    }
}
