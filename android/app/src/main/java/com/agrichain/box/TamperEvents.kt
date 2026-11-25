package com.agrichain.box

import com.agrichain.core.Event
import com.agrichain.core.Signer

object TamperEvents {

    val KINDS = setOf(
        "movement",
        "light",
        "proximity",
        "geofence",
        "gps_off",
        "mock_location",
        "power",
    )

    fun create(
        batchId: String,
        signer: Signer,
        kind: String,
        detail: String?,
        prevHash: String,
        ts: Long = System.currentTimeMillis(),
        traceHead: String? = null,
        lastLat: String? = null,
        lastLon: String? = null,
    ): Event {
        require(kind in KINDS) { "Unknown tamper kind: $kind" }

        val data = linkedMapOf<String, Any?>("kind" to kind)
        if (!detail.isNullOrBlank()) data["detail"] = detail
        if (!traceHead.isNullOrBlank()) data["traceHead"] = traceHead
        if (!lastLat.isNullOrBlank()) data["lastLat"] = lastLat
        if (!lastLon.isNullOrBlank()) data["lastLon"] = lastLon

        return Event.create(
            batchId = batchId,
            type = "TAMPER",
            data = data,
            signer = signer,
            ts = ts,
            prevHash = prevHash,
        )
    }
}
