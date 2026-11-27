package com.agrichain.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.random.Random

/**
 * v3 relay router. It owns no transport details: every transport hands it whole
 * `ttl || message` envelopes and fragments internally.
 *
 * Invariants from the guide:
 *  - a message is processed at most once per node (msgId LRU, 10k / 24 h);
 *  - ledger-signed messages are verified with the pinned ledger key;
 *  - a RECEIPT removes its msgId from pending on every node that sees it;
 *  - client messages are relayed with ttl-1, except the arrival link;
 *  - own uplink messages are never dropped from pending.
 */
class RelayCore(
    private val signer: V3Signer,
    val config: RelayConfig = RelayConfig(),
    private val ledgerPubkey: ByteArray,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val relayDelay: (suspend (IntRange) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val me: String = V3Codec.hex(signer.actorId)
    val ledgerId: String = V3Codec.hex(V3Codec.actorId(ledgerPubkey))

    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()
    private val pending = LinkedHashMap<String, PendingUplink>()
    private val peers = LinkedHashMap<String, PeerInfo>()
    private val receipts = LinkedHashMap<String, ReceiptInfo>()
    private val prices = LinkedHashMap<Int, PriceCache>()
    private val listings = LinkedHashMap<String, ListingCache>()
    private val unverified = HashMap<String, ArrayDeque<Long>>()
    private val transports = LinkedHashMap<String, Transport>()
    private val jobs = mutableListOf<Job>()
    private val announcedLinks = HashMap<String, Long>()

    private val events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 128)

    private var gatewaySeenAt = 0L
    private var lastReceipt: ReceiptInfo? = null
    private var outboxCursor = 0L
    private var listingCursor = 0L
    private var nextLoraAt = 0L

    private var processedCount = 0L
    private var duplicateCount = 0L
    private var droppedCount = 0L
    private var relayedCount = 0L

    /** SMS fallback is supplied by the app/service so the router stays JVM-testable. */
    var smsFallback: (suspend (number: String, envelope: ByteArray) -> Boolean)? = null

    init {
        require(signer.actorId.size == V3.ACTOR_ID_LEN) { "actorId must be 8 bytes" }
        require(signer.publicKey.size == 33) { "public key must be compressed" }
        require(V3Codec.actorId(signer.publicKey).contentEquals(signer.actorId)) {
            "signer actorId does not match its public key"
        }
        require(ledgerPubkey.size == 33) { "ledger public key must be compressed" }
    }

    val uiEvents: SharedFlow<RelayEvent> get() = events

    fun attach(transport: Transport) {
        synchronized(lock) {
            if (transports.containsKey(transport.id)) return
            transports[transport.id] = transport
        }
        jobs += scope.launch {
            transport.incoming.collect { incoming ->
                onEnvelope(incoming.envelope, incoming.linkId, transport.id)
            }
        }
        jobs += scope.launch {
            var known = emptySet<String>()
            transport.links.collect { links ->
                val current = links.mapTo(mutableSetOf()) { it.linkId }
                for (link in links) {
                    if (link.linkId !in known) onNewLink(transport.id, link.linkId)
                }
                known = current
            }
        }
    }

    fun detach(transportId: String) {
        synchronized(lock) { transports.remove(transportId) }
    }

    suspend fun start() {
        for (transport in synchronized(lock) { transports.values.toList() }) {
            transport.start()
        }
    }

    suspend fun stop() {
        for (job in synchronized(lock) { jobs.toList() }) job.cancel()
        synchronized(lock) { jobs.clear() }
        for (transport in synchronized(lock) { transports.values.toList() }) {
            transport.stop()
        }
    }

    /** Process one envelope. Returns whether it was fresh, a duplicate or dropped. */
    suspend fun onEnvelope(
        envelope: ByteArray,
        arrivalLinkId: String? = null,
        arrivalTransportId: String? = null,
    ): RelayOutcome {
        if (envelope.size !in 2..V3.MAX_MESSAGE + 1) {
            return drop("bad_envelope_size")
        }
        val ttl: Int
        val raw: ByteArray
        try {
            val decoded = V3Codec.decodeEnvelope(envelope)
            ttl = decoded.first
            raw = decoded.second
        } catch (exc: Exception) {
            return drop("bad_envelope")
        }
        if (raw.size > V3.MAX_MESSAGE) return drop("bad_message_size")

        val msg = try {
            V3Codec.decode(raw)
        } catch (exc: Exception) {
            return drop("bad_message")
        }
        if (msg.signature == null) return drop("missing_signature")

        val msgId = V3Codec.hex(V3Codec.msgId(msg))
        val now = clock()
        synchronized(lock) {
            cleanupSeenLocked(now)
            if (seen.containsKey(msgId)) {
                duplicateCount += 1
                return RelayOutcome(
                    msgId = msgId,
                    type = msg.type,
                    fresh = false,
                    duplicate = true,
                )
            }
        }

        if (msg.ledgerSigned) {
            if (!V3Codec.verify(msg, ledgerPubkey)) {
                return drop("bad_ledger_signature", msgId, msg.type)
            }
            val ledgerActor = V3Codec.hex(msg.fromId)
            if (ledgerActor != ledgerId) return drop("wrong_ledger_actor", msgId, msg.type)
            val delivered = synchronized(lock) {
                markSeenLocked(msgId, now)
                processLedgerLocked(msg, msgId, arrivalTransportId, now)
            }
            relayIfNeeded(raw, ttl, arrivalLinkId, arrivalTransportId)
            return RelayOutcome(
                msgId = msgId,
                type = msg.type,
                fresh = true,
                delivered = delivered,
            )
        }

        if (msg.type.ledgerSigned) {
            return drop("client_claimed_ledger_type", msgId, msg.type)
        }

        val known = synchronized(lock) { peers[V3Codec.hex(msg.fromId)] }
        if (known?.pubkey != null) {
            if (!V3Codec.verify(msg, known.pubkey)) {
                return drop("bad_client_signature", msgId, msg.type)
            }
        } else if (msg.type == V3Type.ANNOUNCE) {
            val body = runCatching { V3Bodies.parse(msg.type, msg.body) }.getOrNull()
                as? V3Body.Announce ?: return drop("bad_announce", msgId, msg.type)
            if (!V3Codec.verify(msg, body.pubkey) ||
                !V3Codec.actorId(body.pubkey).contentEquals(msg.fromId)
            ) {
                return drop("bad_announce_signature", msgId, msg.type)
            }
        } else {
            val bucket = arrivalTransportId ?: "mesh"
            if (!allowUnverifiedLocked(bucket, now)) {
                return drop("unverified_rate_limited", msgId, msg.type)
            }
        }

        val delivered = synchronized(lock) {
            markSeenLocked(msgId, now)
            processClientLocked(msg, msgId, arrivalTransportId, now)
        }
        relayIfNeeded(raw, ttl, arrivalLinkId, arrivalTransportId)
        return RelayOutcome(
            msgId = msgId,
            type = msg.type,
            fresh = true,
            delivered = delivered,
        )
    }

    /** Sign, remember and flood a locally originated client message. */
    suspend fun originate(
        type: V3Type,
        body: V3Body,
        toId: ByteArray? = null,
        ttl: Int = config.meshTtl,
    ): Originated {
        require(!type.ledgerSigned) { "clients cannot originate ledger-signed types" }
        val raw = signRaw(type, body, toId)
        val msgId = V3Codec.hex(V3Codec.messageId(raw.copyOf(raw.size - V3.SIG_LEN)))
        val now = clock()
        synchronized(lock) {
            markSeenLocked(msgId, now)
            pending[msgId] = PendingUplink(
                msgId = msgId,
                message = raw,
                via = "internet",
                firstSeen = now,
                own = true,
            )
        }
        val sent = sendToAll(raw, ttl, null, null)
        if (sent > 0) markSent(msgId)
        return Originated(msgId, raw, sent)
    }

    /** Re-offer pending traffic, expire stale state and run SMS fallback. */
    suspend fun tick(now: Long = clock()): TickResult {
        val expiredPending: Int
        val expiredSeen: Int
        val fallback: List<PendingUplink>
        synchronized(lock) {
            val oldSeen = seen.size
            cleanupSeenLocked(now)
            expiredSeen = oldSeen - seen.size
            val before = pending.size
            pending.entries.removeIf { (_, item) ->
                !item.own && now - item.firstSeen > config.pendingTtlMs
            }
            expiredPending = before - pending.size
            expireListingsLocked(now)
            fallback = if (config.smsEnabled && !gatewayReachableLocked(now)) {
                pending.values.filter { item ->
                    item.own && !item.smsAttempted &&
                        now - item.firstSeen >= config.smsFallbackAfterMs
                }.take(4)
            } else {
                emptyList()
            }
        }

        var smsSent = 0
        val number = config.smsGatewayNumber
        val fallbackFn = smsFallback
        if (fallbackFn != null && !number.isNullOrBlank()) {
            for (item in fallback) {
                val envelope = V3Codec.encodeEnvelope(item.message, config.meshTtl)
                val ok = fallbackFn(number, envelope)
                synchronized(lock) {
                    val current = pending[item.msgId]
                    if (current != null) {
                        pending[item.msgId] = current.copy(smsAttempted = ok)
                    }
                }
                if (ok) smsSent += 1
            }
        }
        return TickResult(expiredPending, expiredSeen, fallback.size, smsSent)
    }

    suspend fun onNewLink(transportId: String, linkId: String) {
        val transport = synchronized(lock) { transports[transportId] } ?: return
        val now = clock()
        val announceAgain = synchronized(lock) {
            val last = announcedLinks[linkId] ?: 0L
            if (now - last < 5 * 60_000L) {
                false
            } else {
                announcedLinks[linkId] = now
                true
            }
        }
        if (announceAgain) {
            val announce = announceRaw()
            val msgId = V3Codec.hex(V3Codec.messageId(announce.copyOf(announce.size - V3.SIG_LEN)))
            synchronized(lock) { markSeenLocked(msgId, now) }
            sendOn(transport, linkId, announce, config.meshTtl)
        }

        val offers = synchronized(lock) {
            pending.values.sortedWith(
                compareByDescending<PendingUplink> { it.own }.thenBy { it.firstSeen },
            ).take(20)
        }
        val slow = transport.links.value.firstOrNull { it.linkId == linkId }?.slow == true
        for (item in offers) {
            if (slow && config.slowLinkPacingMs > 0) delay(config.slowLinkPacingMs)
            sendOn(transport, linkId, item.message, config.meshTtl)
            markSent(item.msgId)
        }
    }

    /** Inject a ledger-signed message that arrived over HTTP (receipt/outbox/prices). */
    suspend fun injectLedgerMessage(raw: ByteArray, ttl: Int = config.meshTtl): RelayOutcome =
        onEnvelope(V3Codec.encodeEnvelope(raw, ttl), null, null)

    fun noteGatewaySeen(now: Long = clock()) {
        synchronized(lock) { gatewaySeenAt = now }
    }

    fun noteListingSeq(listingId: String, seq: Long) {
        synchronized(lock) {
            val current = listings[listingId] ?: return
            listings[listingId] = current.copy(seq = seq)
            listingCursor = maxOf(listingCursor, seq)
        }
    }

    /** Cache a LISTING message returned by the ledger feed, preserving its raw bytes. */
    fun cacheListing(raw: ByteArray, seq: Long, now: Long = clock()) {
        val msg = runCatching { V3Codec.decode(raw) }.getOrNull() ?: return
        if (msg.type != V3Type.LISTING) return
        val body = runCatching { V3Bodies.parse(msg.type, msg.body) }.getOrNull()
            as? V3Body.Listing ?: return
        val listingId = V3Codec.hex(V3Codec.msgId(msg))
        synchronized(lock) {
            cacheListingLocked(
                listingId = listingId,
                body = body,
                raw = raw,
                sellerId = V3Codec.hex(msg.fromId),
                now = now,
                seq = seq,
            )
            listingCursor = maxOf(listingCursor, seq)
        }
    }

    fun pendingForUplink(limit: Int = 50): List<PendingUplink> = synchronized(lock) {
        pending.values.sortedBy { it.firstSeen }.take(limit.coerceAtLeast(1)).map { it.copy() }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun pendingOwnCount(): Int = synchronized(lock) { pending.values.count { it.own } }

    fun pendingSnapshot(): List<PendingUplink> = synchronized(lock) {
        pending.values.map { it.copy() }
    }

    fun receiptFor(msgId: String): ReceiptInfo? = synchronized(lock) { receipts[msgId] }

    fun lastReceipt(): ReceiptInfo? = synchronized(lock) { lastReceipt }

    fun priceVersions(): Map<Int, Long> = synchronized(lock) {
        prices.mapValues { it.value.version }
    }

    fun priceCaches(): List<PriceCache> = synchronized(lock) { prices.values.toList() }

    fun listingCaches(): List<ListingCache> = synchronized(lock) {
        listings.values.sortedBy { it.validUntil }.toList()
    }

    fun peersSnapshot(): List<PeerInfo> = synchronized(lock) {
        peers.values.sortedByDescending { it.lastSeen }
    }

    fun gatewayReachable(now: Long = clock()): Boolean = synchronized(lock) {
        gatewayReachableLocked(now)
    }

    fun gatewayLastSeen(): Long = synchronized(lock) { gatewaySeenAt }

    fun orderState(msgId: String): OrderState = synchronized(lock) {
        val receipt = receipts[msgId]
        if (receipt != null) {
            return when {
                receipt.accepted -> OrderState.ON_LEDGER
                receipt.pending -> OrderState.PENDING
                receipt.retryable -> OrderState.PENDING
                else -> OrderState.REJECTED
            }
        }
        val item = pending[msgId]
        if (item != null) {
            return if (item.lastSent > 0) OrderState.SENT else OrderState.QUEUED
        }
        OrderState.ON_LEDGER
    }

    fun snapshot(): RelaySnapshot = synchronized(lock) {
        RelaySnapshot(
            me = me,
            peers = peers.values.sortedByDescending { it.lastSeen }.toList(),
            pendingCount = pending.size,
            pendingOwnCount = pending.values.count { it.own },
            prices = prices.values.toList(),
            listings = listings.values.sortedBy { it.validUntil }.toList(),
            lastReceipt = lastReceipt,
            gatewayReachable = gatewayReachableLocked(clock()),
            gatewayLastSeen = gatewaySeenAt,
            transports = transports.values.map { transport ->
                TransportSnapshot(
                    id = transport.id,
                    state = transport.state.value,
                    links = transport.links.value.size,
                    meshLinks = transport.meshLinks,
                    maxFrame = transport.maxFrame,
                )
            },
        )
    }

    fun counters(): RelayCounters = synchronized(lock) {
        RelayCounters(processedCount, duplicateCount, droppedCount, relayedCount)
    }

    fun outboxCursor(): Long = synchronized(lock) { outboxCursor }

    fun listingCursor(): Long = synchronized(lock) { listingCursor }

    fun setOutboxCursor(cursor: Long) {
        synchronized(lock) { outboxCursor = maxOf(outboxCursor, cursor) }
    }

    fun setListingCursor(cursor: Long) {
        synchronized(lock) { listingCursor = maxOf(listingCursor, cursor) }
    }

    fun exportJson(): String = synchronized(lock) {
        RelayPersistence.encode(
            pending = pending.values,
            peers = peers.values,
            receipts = receipts.values,
            prices = prices.values,
            listings = listings.values,
            outboxCursor = outboxCursor,
            listingCursor = listingCursor,
        )
    }

    fun importJson(text: String) {
        val decoded = RelayPersistence.decode(text)
        synchronized(lock) {
            pending.clear()
            decoded.pending.forEach { pending[it.msgId] = it }
            peers.clear()
            decoded.peers.forEach { peers[it.actorId] = it }
            receipts.clear()
            decoded.receipts.forEach { receipts[it.msgId] = it }
            prices.clear()
            decoded.prices.forEach { prices[it.region] = it }
            listings.clear()
            decoded.listings.forEach { listings[it.listingId] = it }
            outboxCursor = decoded.outboxCursor
            listingCursor = decoded.listingCursor
        }
    }

    fun applyIngestResult(
        msgId: String,
        status: String,
        reasonCode: Int,
        now: Long = clock(),
    ) {
        synchronized(lock) {
            val item = pending[msgId] ?: return
            pending[msgId] = item.copy(lastSent = now)
            when (status.lowercase()) {
                "accepted", "duplicate" -> pending.remove(msgId)
                "rejected" -> if (reasonCode != 10) pending.remove(msgId)
                "pending" -> Unit
            }
        }
    }

    // ------------------------------------------------------------- internals

    private fun processLedgerLocked(
        msg: V3Message,
        msgId: String,
        arrivalTransportId: String?,
        now: Long,
    ): Boolean {
        gatewaySeenAt = now
        processedCount += 1
        val delivered = msg.toId?.let { V3Codec.hex(it) == me } == true
        val body = runCatching { V3Bodies.parse(msg.type, msg.body) }.getOrNull() ?: return false
        when (body) {
            is V3Body.Receipt -> {
                val receipt = ReceiptInfo(
                    msgId = V3Codec.hex(body.msgId),
                    status = body.status,
                    reason = body.reason,
                    seq = body.seq,
                    head8 = body.head8,
                    at = now,
                )
                receipts[receipt.msgId] = receipt
                lastReceipt = receipt
                if (receipt.status != 3 && !(receipt.status == 2 && receipt.reason == 10)) {
                    pending.remove(receipt.msgId)
                }
                if (delivered) events.tryEmit(RelayEvent.Receipt(receipt))
            }
            is V3Body.Notice -> {
                if (delivered) {
                    events.tryEmit(
                        RelayEvent.Notice(body.kind, V3Codec.hex(body.refId), body.seq, body.qtyKg, body.pricePaise),
                    )
                }
            }
            is V3Body.PriceTable -> {
                val source = arrivalTransportId ?: "mesh"
                val existing = prices[body.region]
                if (existing == null || body.version > existing.version) {
                    prices[body.region] = PriceCache(
                        region = body.region,
                        version = body.version,
                        asOf = body.asOf,
                        parts = mapOf(body.part to body.rows.map { CachedPriceRow(it.crop, it.pricePaise, it.trades) }),
                        updatedAt = now,
                        source = source,
                    )
                } else if (body.version == existing.version && !existing.parts.containsKey(body.part)) {
                    prices[body.region] = existing.copy(
                        parts = existing.parts + (body.part to body.rows.map {
                            CachedPriceRow(it.crop, it.pricePaise, it.trades)
                        }),
                        updatedAt = now,
                        source = source,
                    )
                }
            }
            is V3Body.PriceBeacon -> {
                val source = arrivalTransportId ?: "mesh"
                val existing = prices[body.region]
                if (existing == null || body.version > existing.version) {
                    prices[body.region] = PriceCache(
                        region = body.region,
                        version = body.version,
                        asOf = 0,
                        parts = emptyMap(),
                        updatedAt = now,
                        source = source,
                        beaconHash = V3Codec.hex(body.hash8),
                    )
                } else if (body.version == existing.version) {
                    prices[body.region] = existing.copy(
                        beaconHash = V3Codec.hex(body.hash8),
                        updatedAt = now,
                    )
                }
            }
            else -> Unit
        }
        return delivered
    }

    private fun processClientLocked(
        msg: V3Message,
        msgId: String,
        arrivalTransportId: String?,
        now: Long,
    ): Boolean {
        processedCount += 1
        val fromId = V3Codec.hex(msg.fromId)
        val delivered = msg.toId?.let { V3Codec.hex(it) == me } == true
        val body = runCatching { V3Bodies.parse(msg.type, msg.body) }.getOrNull()
        if (body is V3Body.Announce) {
            val peer = PeerInfo(
                actorId = fromId,
                pubkey = body.pubkey,
                caps = body.caps,
                nick = body.nick,
                lastSeen = now,
                linkId = arrivalTransportId,
            )
            peers[fromId] = peer
            if (peer.isGateway) gatewaySeenAt = now
            events.tryEmit(RelayEvent.Peer(peer))
        } else {
            val existing = peers[fromId]
            peers[fromId] = PeerInfo(
                actorId = fromId,
                pubkey = existing?.pubkey,
                caps = existing?.caps ?: 0,
                nick = existing?.nick ?: fromId.take(8),
                lastSeen = now,
                linkId = arrivalTransportId ?: existing?.linkId,
                phone = existing?.phone,
            )
            if (msg.type == V3Type.LISTING && body is V3Body.Listing) {
                cacheListingLocked(
                    listingId = msgId,
                    body = body,
                    raw = unsignedPlusSignature(msg),
                    sellerId = fromId,
                    now = now,
                    seq = 0,
                )
            }
            if (fromId != me && isUplinkType(msg.type)) {
                addPendingLocked(
                    PendingUplink(
                        msgId = msgId,
                        message = unsignedPlusSignature(msg),
                        via = arrivalTransportId ?: "mesh",
                        firstSeen = now,
                        own = false,
                    ),
                )
            }
        }
        if (delivered) {
            events.tryEmit(RelayEvent.DirectedMessage(msg.type, msgId, fromId, unsignedPlusSignature(msg)))
        }
        return delivered
    }

    private fun cacheListingLocked(
        listingId: String,
        body: V3Body.Listing,
        raw: ByteArray,
        sellerId: String,
        now: Long,
        seq: Long,
    ) {
        if (body.validUntil <= now / 1000) return
        listings[listingId] = ListingCache(
            listingId = listingId,
            raw = raw,
            seq = seq,
            sellerId = sellerId,
            crop = body.crop,
            qtyKg = body.qtyKg,
            remainingKg = body.qtyKg,
            askPaise = body.askPaise,
            region = body.region,
            validUntil = body.validUntil,
            grade = body.grade,
            batchId = V3Codec.hex(body.batchId),
        )
        if (listings.size > config.maxListings) {
            expireListingsLocked(now)
            val excess = listings.size - config.maxListings
            if (excess > 0) {
                listings.values
                    .sortedWith(compareBy<ListingCache> { it.validUntil }.thenBy { it.seq })
                    .take(excess)
                    .forEach { listings.remove(it.listingId) }
            }
        }
    }

    private fun expireListingsLocked(now: Long) {
        val nowSeconds = now / 1000
        listings.entries.removeIf { it.value.validUntil <= nowSeconds }
    }

    private fun addPendingLocked(item: PendingUplink) {
        if (pending.containsKey(item.msgId)) return
        if (pending.size >= config.maxPending) {
            val victim = pending.values.filter { !it.own }.minByOrNull { it.firstSeen }
            if (victim != null) pending.remove(victim.msgId)
        }
        pending[item.msgId] = item
    }

    private fun markSeenLocked(msgId: String, now: Long) {
        seen[msgId] = now
        while (seen.size > config.maxSeen) {
            val iterator = seen.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun cleanupSeenLocked(now: Long) {
        seen.entries.removeIf { now - it.value > config.seenTtlMs }
    }

    private fun allowUnverifiedLocked(bucket: String, now: Long): Boolean {
        val queue = unverified.getOrPut(bucket) { ArrayDeque() }
        while (queue.isNotEmpty() && now - queue.first() > 60_000L) queue.removeFirst()
        if (queue.size >= config.unverifiedPerMinute) return false
        queue.addLast(now)
        return true
    }

    private fun gatewayReachableLocked(now: Long): Boolean {
        if (now - gatewaySeenAt < 5 * 60_000L) return true
        return peers.values.any { it.isGateway && now - it.lastSeen < 24 * 60 * 60_000L }
    }

    private fun markSent(msgId: String) {
        synchronized(lock) {
            val item = pending[msgId] ?: return
            pending[msgId] = item.copy(lastSent = clock())
        }
    }

    private suspend fun relayIfNeeded(
        raw: ByteArray,
        ttl: Int,
        arrivalLinkId: String?,
        arrivalTransportId: String?,
    ) {
        if (ttl <= 1) return
        val degree = synchronized(lock) {
            transports.values.filter { it.meshLinks }.sumOf { it.links.value.size }
        }
        // On a dense mesh every node sees every other node. One transport-level
        // broadcast per radio is enough; per-link sends would be a storm and
        // duplicates are already handled by the seen LRU.
        val dense = degree >= 5
        val targets = synchronized(lock) {
            val list = mutableListOf<Pair<Transport, String?>>()
            for (transport in transports.values) {
                if (!transport.meshLinks) continue
                val links = transport.links.value
                if (links.isEmpty()) {
                    if (transport.id != arrivalTransportId) list += transport to null
                } else if (dense) {
                    list += transport to null
                } else {
                    for (link in links) {
                        if (transport.id == arrivalTransportId && link.linkId == arrivalLinkId) continue
                        list += transport to link.linkId
                    }
                }
            }
            list
        }
        if (targets.isEmpty()) return

        var newTtl = ttl - 1
        if (dense) newTtl = min(newTtl, 5)
        if (newTtl <= 0) return

        val delayFn: suspend (IntRange) -> Unit = relayDelay ?: { range ->
            if (range.last > 0) {
                delay(random.nextLong(range.first.toLong(), range.last.toLong() + 1))
            }
        }
        delayFn(config.relayJitterMs)
        for ((transport, linkId) in targets) {
            sendOn(transport, linkId, raw, newTtl)
            synchronized(lock) { relayedCount += 1 }
        }
    }

    private suspend fun sendToAll(
        raw: ByteArray,
        ttl: Int,
        excludeTransportId: String?,
        excludeLinkId: String?,
    ): Int {
        val degree = synchronized(lock) {
            transports.values.filter { it.meshLinks }.sumOf { it.links.value.size }
        }
        val dense = degree >= 5
        val targets = synchronized(lock) {
            val list = mutableListOf<Pair<Transport, String?>>()
            for (transport in transports.values) {
                if (!transport.meshLinks) continue
                val links = transport.links.value
                if (links.isEmpty()) {
                    if (transport.id != excludeTransportId) list += transport to null
                } else if (dense) {
                    list += transport to null
                } else {
                    for (link in links) {
                        if (transport.id == excludeTransportId && link.linkId == excludeLinkId) continue
                        list += transport to link.linkId
                    }
                }
            }
            list
        }
        for ((transport, linkId) in targets) sendOn(transport, linkId, raw, ttl)
        return targets.size
    }

    private suspend fun sendOn(
        transport: Transport,
        linkId: String?,
        raw: ByteArray,
        ttl: Int,
    ) {
        val effective = if (transport.id == "lora") min(ttl, config.loraTtl) else ttl
        if (effective <= 0) return
        if (transport.id == "lora" && config.loraMinGapMs > 0) {
            val wait = synchronized(lock) {
                val now = clock()
                val at = maxOf(now, nextLoraAt)
                nextLoraAt = at + config.loraMinGapMs
                at - now
            }
            if (wait > 0) delay(wait)
        }
        transport.send(linkId, V3Codec.encodeEnvelope(raw, effective))
    }

    private fun signRaw(type: V3Type, body: V3Body, toId: ByteArray?): ByteArray {
        val unsigned = V3Codec.encodeUnsigned(
            type = type,
            fromId = signer.actorId,
            body = V3Bodies.encode(body),
            ts = clock() / 1000,
            toId = toId,
        )
        val signature = signer.sign(unsigned)
        require(signature.size == V3.SIG_LEN) { "signer must return raw 64-byte signatures" }
        return unsigned + signature
    }

    private fun announceRaw(): ByteArray {
        val nick = truncateUtf8(config.nick.ifBlank { "phone" }, 20)
        return signRaw(
            V3Type.ANNOUNCE,
            V3Body.Announce(signer.publicKey, config.caps, nick),
            null,
        )
    }

    private fun unsignedPlusSignature(msg: V3Message): ByteArray =
        V3Codec.unsignedBytes(msg) + (msg.signature ?: ByteArray(0))

    private fun drop(reason: String, msgId: String = "", type: V3Type? = null): RelayOutcome {
        synchronized(lock) { droppedCount += 1 }
        return RelayOutcome(msgId, type, fresh = false, droppedReason = reason)
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val out = StringBuilder()
        var used = 0
        for (char in value) {
            val bytes = char.toString().toByteArray(Charsets.UTF_8).size
            if (used + bytes > maxBytes) break
            out.append(char)
            used += bytes
        }
        return out.toString().ifEmpty { "phone" }
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

data class RelayOutcome(
    val msgId: String,
    val type: V3Type?,
    val fresh: Boolean,
    val duplicate: Boolean = false,
    val delivered: Boolean = false,
    val droppedReason: String? = null,
)

data class Originated(
    val msgId: String,
    val message: ByteArray,
    val links: Int,
)

data class TickResult(
    val expiredPending: Int,
    val expiredSeen: Int,
    val smsCandidates: Int,
    val smsSent: Int,
)

data class RelayCounters(
    val processed: Long,
    val duplicates: Long,
    val dropped: Long,
    val relayed: Long,
)
