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

    fun appendGps(
        batchId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Int,
        baseHead: String,
    ): Event {
        val event = GpsEvents.create(
            batchId = batchId,
            signer = signer,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            prevHash = head(batchId, baseHead),
        )
        store.save(event)
        return event
    }
}
