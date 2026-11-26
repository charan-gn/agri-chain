package com.agrichain.mesh

import com.agrichain.core.Event

/**
 * One envelope format, many links. Nearby, SMS, Meshtastic LoRa and HTTP all implement this.
 */
interface Transport {
    val id: String

    fun available(): Boolean

    /** Largest envelope this link can carry in one send; SMS is the smallest. */
    val maxPayload: Int

    fun start(onReceive: (MeshEnvelope, Transport) -> Unit, onLog: (String) -> Unit)

    /**
     * Same as [start], but also reports the link peer that handed over the
     * envelope. Relayed envelopes keep the origin's sender id, so ACK scoping
     * must use the link peer, not `envelope.senderId`.
     */
    fun startWithPeer(
        onReceive: (MeshEnvelope, Transport, String?) -> Unit,
        onLog: (String) -> Unit,
    ) {
        start({ envelope, transport -> onReceive(envelope, transport, null) }, onLog)
    }

    fun stop()

    /** Returns the number of peers/chunks the envelope was handed to; 0 when unavailable. */
    fun send(envelope: MeshEnvelope): Int

    /**
     * Peer ids this send reaches. The relay records `sentTo(peer, eventIds)` so an
     * ACK can clear only what the peer really received.
     */
    fun peers(envelope: MeshEnvelope): Set<String> = emptySet()

    /** Peer ids currently known to this link, for the contact picker. */
    fun knownPeers(): Set<String> = emptySet()

    /** Event ids that [send] will actually hand over (SMS drops GPS and batches). */
    fun carriedEventIds(envelope: MeshEnvelope): Set<String> =
        envelope.events.mapTo(mutableSetOf()) { it.eventId }
}

/** SMS addresses are normalized so `+91 90000-00001` and `+919000000001` match. */
fun normalizePeerId(raw: String): String =
    raw.trim().filter { it.isDigit() || it == '+' || it.isLetter() || it in "-._" }

interface RelayStore {
    fun hasSeen(eventId: String): Boolean
    fun markSeen(eventIds: Collection<String>)
    fun enqueue(event: Event): Boolean
    fun pending(): List<Event>
    fun markSynced(eventIds: Collection<String>)
    fun pendingCount(): Int

    fun hasSeenEnvelope(envelopeId: String): Boolean = false
    fun markSeenEnvelope(envelopeId: String) {}

    /** Peer the event was handed to; used to scope ACKs. */
    fun markSentTo(peerId: String, eventIds: Collection<String>) {}
    fun sentTo(peerId: String): Set<String> = emptySet()

    /** Records a verified ACK from [peerId] for an event. */
    fun markAcked(peerId: String, eventIds: Collection<String>) {}
    fun acked(peerId: String): Set<String> = emptySet()

    /** Trust-on-first-use key for an ACK signer id. */
    fun ackKey(signerId: String): String? = null
    fun rememberAckKey(signerId: String, publicKeySpkiBase64: String) {}
}

data class ReceiveResult(
    val freshEvents: Int,
    val duplicates: Int,
    val forwarded: Boolean,
    val uploaded: Int,
    val acked: Int = 0,
)

data class PumpResult(val uploaded: Int, val pending: Int, val error: String? = null)

data class ChatSendResult(val envelope: MeshEnvelope, val links: Int)

interface LedgerGateway {
    fun available(): Boolean
    fun upload(events: List<Event>): UploadResult
}

data class UploadResult(
    val accepted: List<String>,
    val rejected: List<String>,
    val error: String? = null,
)
