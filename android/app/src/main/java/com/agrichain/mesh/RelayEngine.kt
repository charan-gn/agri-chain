package com.agrichain.mesh

import com.agrichain.core.Event

/**
 * Transport-agnostic relay: dedupe by eventId, forward with TTL, upload when a
 * ledger is reachable, and relay ACKs back so senders can clear their outbox.
 */
class RelayEngine(
    private val selfId: String,
    private val store: RelayStore,
    private val ledger: LedgerGateway?,
    private val transports: MutableList<Transport> = mutableListOf(),
    private val log: (String) -> Unit = {},
) {

    fun addTransport(transport: Transport) {
        synchronized(transports) {
            if (transports.none { it.id == transport.id }) transports += transport
        }
    }

    fun removeTransport(transport: Transport) {
        synchronized(transports) { transports.removeAll { it.id == transport.id } }
    }

    @Synchronized
    fun receive(envelope: MeshEnvelope): ReceiveResult {
        if (envelope.senderId == selfId) return ReceiveResult(0, envelope.events.size, false, 0)

        return when (envelope.kind) {
            MeshKind.EVENTS -> receiveEvents(envelope)
            MeshKind.ACK -> receiveAck(envelope)
        }
    }

    private fun receiveEvents(envelope: MeshEnvelope): ReceiveResult {
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
            forward(next)
            forwarded = true
            log("relay ${fresh} event(s), ttl ${next.ttl}")
        }

        val uploaded = if (fresh > 0 && ledger?.available() == true) {
            pump().uploaded
        } else {
            0
        }
        return ReceiveResult(fresh, duplicates, forwarded, uploaded)
    }

    private fun receiveAck(envelope: MeshEnvelope): ReceiveResult {
        val ids = envelope.ackedEventIds
        store.markSynced(ids)
        var forwarded = false
        if (envelope.ttl > 1) {
            forward(envelope.withTtl(envelope.ttl - 1))
            forwarded = true
        }
        log("ack ${ids.size} event(s)")
        return ReceiveResult(0, 0, forwarded, 0)
    }

    /** Uploads everything pending if a ledger is reachable, then broadcasts an ACK. */
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
            val ack = MeshEnvelope.ack(selfId, result.accepted)
            forward(ack)
            log("uploaded ${result.accepted.size}, ack sent")
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
        return forward(envelope)
    }

    private fun forward(envelope: MeshEnvelope): Int {
        val peers = synchronized(transports) { transports.toList() }
        var sent = 0
        for (transport in peers) {
            if (!transport.available()) continue
            sent += runCatching { transport.send(envelope) }
                .onFailure { log("${transport.id} send failed: ${it.message}") }
                .getOrDefault(0)
        }
        return sent
    }
}
