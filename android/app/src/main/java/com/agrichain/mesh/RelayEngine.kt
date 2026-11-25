package com.agrichain.mesh

import com.agrichain.core.Event
import com.agrichain.core.PublicKeySigner

/**
 * Transport-agnostic relay: dedupe by envelopeId and eventId, forward with TTL,
 * upload when a ledger is reachable, and relay signed ACKs.
 *
 * An ACK clears an event only when:
 *  - the signature verifies against the key in the envelope (H6),
 *  - the signer key does not change for an id already seen (trust on first use),
 *  - and the event was actually handed to the peer that delivered the ACK.
 */
class RelayEngine(
    private val selfId: String,
    private val store: RelayStore,
    private val ledger: LedgerGateway?,
    private val transports: MutableList<Transport> = mutableListOf(),
    private val log: (String) -> Unit = {},
    private val signer: PublicKeySigner? = null,
    private val chatListener: ((MeshEnvelope) -> Unit)? = null,
) {

    private val seenEnvelopes = LinkedHashSet<String>()

    fun addTransport(transport: Transport) {
        synchronized(transports) {
            if (transports.none { it.id == transport.id }) transports += transport
        }
    }

    fun removeTransport(transport: Transport) {
        synchronized(transports) { transports.removeAll { it.id == transport.id } }
    }

    @Synchronized
    fun receive(
        envelope: MeshEnvelope,
        fromTransportId: String? = null,
        fromPeerId: String? = null,
    ): ReceiveResult {
        if (envelope.senderId == selfId) return ReceiveResult(0, envelope.events.size, false, 0)
        // Validate signed kinds before remembering the id. Otherwise a forged
        // copy could burn the id and make the genuine message look like a dup.
        when (envelope.kind) {
            MeshKind.CHAT -> {
                if (fromTransportId != null && fromTransportId !in CHAT_TRANSPORTS) {
                    log("ignored chat from $fromTransportId: not a local link")
                    return ReceiveResult(0, 0, false, 0)
                }
                if (!envelope.verifyChat()) {
                    log("ignored chat from ${envelope.senderId.take(12)}: bad or missing signature")
                    return ReceiveResult(0, 0, false, 0)
                }
            }
            MeshKind.ACK -> if (!envelope.verifyAck()) {
                log("ignored ACK from ${envelope.senderId.take(12)}: bad or missing signature")
                return ReceiveResult(0, 0, false, 0)
            }
            MeshKind.EVENTS -> Unit
        }
        if (envelope.envelopeId in seenEnvelopes || store.hasSeenEnvelope(envelope.envelopeId)) {
            return ReceiveResult(0, envelope.events.size, false, 0)
        }
        rememberEnvelope(envelope.envelopeId)

        return when (envelope.kind) {
            MeshKind.EVENTS -> receiveEvents(envelope, fromTransportId, fromPeerId)
            MeshKind.ACK -> receiveAck(envelope, fromTransportId, fromPeerId)
            MeshKind.CHAT -> receiveChat(envelope, fromTransportId)
        }
    }

    private fun receiveEvents(
        envelope: MeshEnvelope,
        fromTransportId: String?,
        fromPeerId: String?,
    ): ReceiveResult {
        var fresh = 0
        var duplicates = 0
        for (event in envelope.events) {
            if (store.hasSeen(event.eventId)) {
                duplicates++
                continue
            }
            if (store.enqueue(event)) fresh++ else duplicates++
            store.markSeen(listOf(event.eventId))
        }

        var forwarded = false
        if (fresh > 0 && envelope.ttl > 1) {
            val next = envelope.withTtl(envelope.ttl - 1)
            val skip = if (fromTransportId == "sms") "sms" else null
            forwarded = forward(next, skipTransportId = skip) > 0
            log("relay $fresh event(s), ttl ${next.ttl}")
        }

        val uploaded = if (fresh > 0 && ledger?.available() == true) {
            pump().uploaded
        } else {
            0
        }
        return ReceiveResult(fresh, duplicates, forwarded, uploaded)
    }

    private fun receiveAck(
        envelope: MeshEnvelope,
        fromTransportId: String?,
        fromPeerId: String?,
    ): ReceiveResult {
        val publicKey = envelope.pubKey.orEmpty()
        val knownKey = store.ackKey(envelope.senderId)
        if (knownKey != null && knownKey != publicKey) {
            log("ignored ACK from ${envelope.senderId.take(12)}: signer key changed")
            return ReceiveResult(0, 0, false, 0)
        }
        if (knownKey == null) store.rememberAckKey(envelope.senderId, publicKey)

        // The peer that handed us the ACK is the one we actually sent events to.
        val peer = normalizePeerId(fromPeerId ?: fromTransportId ?: envelope.senderId)
        val sent = store.sentTo(peer)
        val verified = envelope.ackedEventIds.filter { it in sent }
        if (verified.isNotEmpty()) {
            store.markSynced(verified)
            store.markAcked(peer, verified)
        }
        if (verified.size < envelope.ackedEventIds.size) {
            log(
                "ack from $peer: ${verified.size}/${envelope.ackedEventIds.size} " +
                    "matched events sent to that peer"
            )
        }

        var forwarded = false
        if (envelope.ttl > 1) {
            val skip = if (fromTransportId == "sms") "sms" else null
            forwarded = forward(envelope.withTtl(envelope.ttl - 1), skipTransportId = skip) > 0
        }
        log("ack ${verified.size} event(s) from $peer")
        return ReceiveResult(0, 0, forwarded, 0, verified.size)
    }

    /**
     * Chat is mesh-local: signature and TOFU key are checked, the message is
     * delivered to the UI, and the relay forwards it. It never enters the
     * ledger outbox and it is never sent over SMS.
     */
    private fun receiveChat(envelope: MeshEnvelope, fromTransportId: String?): ReceiveResult {
        val publicKey = envelope.pubKey.orEmpty()
        val knownKey = store.ackKey(envelope.senderId)
        if (knownKey != null && knownKey != publicKey) {
            log("ignored chat from ${envelope.senderId.take(12)}: signer key changed")
            return ReceiveResult(0, 0, false, 0)
        }
        if (knownKey == null) store.rememberAckKey(envelope.senderId, publicKey)

        chatListener?.invoke(envelope)
        var forwarded = false
        if (envelope.ttl > 1) {
            forwarded = forward(
                envelope.withTtl(envelope.ttl - 1),
                allowedTransportIds = CHAT_TRANSPORTS,
            ) > 0
        }
        log("chat from ${envelope.senderId.take(12)}: ${envelope.chatText?.take(40)}")
        return ReceiveResult(0, 0, forwarded, 0)
    }

    /** Signs and broadcasts a local chat message. Returns null when no signer is configured. */
    @Synchronized
    fun sendChat(text: String): ChatSendResult? {
        val body = text.trim()
        if (body.isEmpty() || body.length > MeshEnvelope.MAX_CHAT_CHARS) return null
        val chatSigner = signer ?: return null
        val envelope = MeshEnvelope.chat(selfId, body, chatSigner)
        rememberEnvelope(envelope.envelopeId)
        val sent = forward(envelope, allowedTransportIds = CHAT_TRANSPORTS)
        log("chat sent to $sent link(s)")
        return ChatSendResult(envelope, sent)
    }

    /** Uploads everything pending if a ledger is reachable, then broadcasts a signed ACK. */
    @Synchronized
    fun pump(): PumpResult {
        val gateway = ledger
        if (gateway == null || !gateway.available()) {
            return PumpResult(0, store.pendingCount())
        }
        val pending = store.pending()
        if (pending.isEmpty()) return PumpResult(0, 0)

        val result = gateway.upload(pending)
        if (result.accepted.isNotEmpty()) {
            store.markSynced(result.accepted)
            store.markSeen(result.accepted)
            val ackSigner = signer
            if (ackSigner == null) {
                log("uploaded ${result.accepted.size}, ACK skipped (no signer)")
            } else {
                // Only after 201/idempotent success does the gateway ACK.
                val ack = MeshEnvelope.ack(selfId, result.accepted, ackSigner)
                rememberEnvelope(ack.envelopeId)
                forward(ack)
                log("uploaded ${result.accepted.size}, signed ack sent")
            }
        }
        if (result.error != null) log("upload error: ${result.error}")
        return PumpResult(result.accepted.size, store.pendingCount(), result.error)
    }

    /** Sends the whole outbox over every available transport. */
    @Synchronized
    fun broadcastPending(): Int {
        val pending = store.pending()
        if (pending.isEmpty()) return 0
        val envelope = MeshEnvelope.events(selfId, pending)
        rememberEnvelope(envelope.envelopeId)
        return forward(envelope)
    }

    private fun rememberEnvelope(envelopeId: String) {
        if (seenEnvelopes.size >= 512) {
            seenEnvelopes.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
        }
        seenEnvelopes += envelopeId
        store.markSeenEnvelope(envelopeId)
    }

    private fun forward(
        envelope: MeshEnvelope,
        skipTransportId: String? = null,
        allowedTransportIds: Set<String>? = null,
    ): Int {
        val peers = synchronized(transports) { transports.toList() }
        val eventIds = envelope.events.mapTo(mutableSetOf()) { it.eventId }
        var sent = 0
        for (transport in peers) {
            if (transport.id == skipTransportId) continue
            if (allowedTransportIds != null && transport.id !in allowedTransportIds) continue
            if (!transport.available()) continue
            val count = runCatching { transport.send(envelope) }
                .onFailure { log("${transport.id} send failed: ${it.message}") }
                .getOrDefault(0)
            sent += count
            if (count > 0 && eventIds.isNotEmpty()) {
                val carried = runCatching { transport.carriedEventIds(envelope) }
                    .getOrDefault(eventIds)
                val destinations = runCatching { transport.peers(envelope) }.getOrDefault(emptySet())
                destinations.forEach { peer ->
                    if (peer.isNotBlank()) store.markSentTo(normalizePeerId(peer), carried)
                }
            }
        }
        return sent
    }

    companion object {
        /** Chat stays on free local links; SMS costs money and LoRa frames are tiny. */
        private val CHAT_TRANSPORTS = setOf("ble", "nearby")
    }
}
