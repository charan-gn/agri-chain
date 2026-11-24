package com.agrichain.box

import com.agrichain.core.Event
import com.agrichain.core.Signer

object TamperEvents {

    val KINDS = setOf("movement", "light", "proximity", "geofence")

    fun create(
        batchId: String,
        signer: Signer,
        kind: String,
        detail: String?,
        prevHash: String,
        ts: Long = System.currentTimeMillis(),
    ): Event {
        require(kind in KINDS) { "Unknown tamper kind: $kind" }

        val data = linkedMapOf<String, Any?>("kind" to kind)
        if (!detail.isNullOrBlank()) data["detail"] = detail

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
