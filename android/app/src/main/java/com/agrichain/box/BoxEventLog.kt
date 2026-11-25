package com.agrichain.box

import android.content.Context
import com.agrichain.batch.BatchStore
import com.agrichain.core.Chains
import com.agrichain.core.Event
import com.agrichain.core.Signer

/**
 * Box-local log with one chain per `(batchId, actorId, stream)`: GPS on `trace`,
 * TAMPER on `main`. Each chain starts at `prevHash=""`, so the box never has to
 * wait for the farmer's chain.
 */
class BoxEventLog(context: Context, private val signer: Signer) {

    private val store = BatchStore(context)

    fun events(batchId: String): List<Event> = store.all().filter { it.batchId == batchId }

    fun events(batchId: String, stream: String): List<Event> =
        Chains.eventsFor(events(batchId), signer.actorId, stream)

    fun head(batchId: String, stream: String, fallback: String = ""): String =
        Chains.head(events(batchId), signer.actorId, stream, fallback)

    fun mainHead(batchId: String): String? =
        events(batchId, Chains.MAIN).lastOrNull()?.hash

    fun traceHead(batchId: String): String? =
        events(batchId, Chains.TRACE).lastOrNull()?.hash

    fun lastFixCoords(batchId: String): Pair<String?, String?> {
        val lastGps = events(batchId, Chains.TRACE).lastOrNull()
        return lastGps?.lat to lastGps?.lon
    }

    fun appendGps(
        batchId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Int,
        baseHead: String,
        mock: Boolean = false,
    ): Event {
        val event = GpsEvents.create(
            batchId = batchId,
            signer = signer,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            prevHash = head(batchId, Chains.TRACE, baseHead),
            mock = mock,
        )
        store.save(event)
        return event
    }

    fun appendTamper(
        batchId: String,
        kind: String,
        detail: String?,
        baseHead: String,
    ): Event {
        val (lastLat, lastLon) = lastFixCoords(batchId)
        val event = TamperEvents.create(
            batchId = batchId,
            signer = signer,
            kind = kind,
            detail = detail,
            prevHash = head(batchId, Chains.MAIN, baseHead),
            traceHead = traceHead(batchId),
            lastLat = lastLat,
            lastLon = lastLon,
        )
        store.save(event)
        return event
    }
}
