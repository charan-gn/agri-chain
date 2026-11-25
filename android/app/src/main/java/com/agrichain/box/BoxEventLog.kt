package com.agrichain.box

import android.content.Context
import com.agrichain.batch.BatchStore
import com.agrichain.core.Event
import com.agrichain.core.Signer

class BoxEventLog(context: Context, private val signer: Signer) {

    private val store = BatchStore(context)

    fun events(batchId: String): List<Event> = store.all().filter { it.batchId == batchId }

    fun head(batchId: String, fallback: String = ""): String =
        events(batchId).lastOrNull()?.hash ?: fallback

    fun traceHead(batchId: String): String? =
        events(batchId).lastOrNull { it.type == "GPS" }?.hash

    fun lastFixCoords(batchId: String): Pair<String?, String?> {
        val lastGps = events(batchId).lastOrNull { it.type == "GPS" }
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
            prevHash = head(batchId, baseHead),
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
            prevHash = head(batchId, baseHead),
            traceHead = traceHead(batchId),
            lastLat = lastLat,
            lastLon = lastLon,
        )
        store.save(event)
        return event
    }
}
