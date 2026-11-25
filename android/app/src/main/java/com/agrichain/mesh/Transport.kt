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

    fun stop()

    /** Returns the number of peers/chunks the envelope was handed to; 0 when unavailable. */
    fun send(envelope: MeshEnvelope): Int
}

interface RelayStore {
    fun hasSeen(eventId: String): Boolean
    fun markSeen(eventIds: Collection<String>)
    fun enqueue(event: Event): Boolean
    fun pending(): List<Event>
    fun markSynced(eventIds: Collection<String>)
    fun pendingCount(): Int

    fun hasSeenEnvelope(envelopeId: String): Boolean = false
    fun markSeenEnvelope(envelopeId: String) {}
}

data class ReceiveResult(
    val freshEvents: Int,
    val duplicates: Int,
    val forwarded: Boolean,
    val uploaded: Int,
)

data class PumpResult(val uploaded: Int, val pending: Int, val error: String? = null)

interface LedgerGateway {
    fun available(): Boolean
    fun upload(events: List<Event>): UploadResult
}

data class UploadResult(
    val accepted: List<String>,
    val rejected: List<String>,
    val error: String? = null,
)
